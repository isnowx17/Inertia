package io.github.isnowx17.inertia.client.slime;

import io.github.isnowx17.inertia.Inertia;
import io.github.isnowx17.inertia.client.config.InertiaConfig;
import io.github.isnowx17.inertia.client.gpu.CudaDriver;
import io.github.isnowx17.inertia.client.gpu.GpuProbe;
import io.github.isnowx17.inertia.client.physics.PhysXRuntime;
import io.github.isnowx17.inertia.util.SlimeMesh;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.VehicleEntity;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The slime blocks: soft bodies simulated with finite elements on the GPU ({@code softbody.cu}).
 * Every body is a {@link SlimeMesh} (a cube of tetrahedra); all bodies share one set of GPU
 * buffers. Every game tick the solver runs {@link InertiaConfig#slimeSubsteps} substeps, against the
 * solid blocks around the bodies and the boxes of the player and the entities nearby, whose weight
 * presses on the slime they stand on. The player and the entities rest on the squashed surface and
 * bounce like on a slime block. Client thread, except {@link #supportEntities} (server thread).
 */
public final class SlimeWorld {
	private static final int THREADS = 128;
	/** Small cubes along each edge of a slime block. */
	private static final int SUBDIVISIONS = 4;
	/** Mass of a slime block (an entity weighs its box's volume in the same units). */
	private static final float BODY_MASS = 1f;
	private static final float GRAVITY = -9.81f;
	private static final int SIM_BYTES = 192;
	/** Most a resting entity's weight may pull on one particle (blocks per second squared). */
	private static final float MAX_PRESS = 60f;
	/** Horizontal speed kept per tick while walking on slime (sticky). */
	private static final double STICKY_WALK = 0.8;
	/** Entities this far around a body (blocks) are colliders. */
	private static final double REACH = 2.0;
	/** At most this many cells in the voxel grid of solid blocks. */
	private static final int MAX_CELLS = 1 << 21;
	/** A surface this far above or below the feet still carries an entity (blocks). */
	private static final float SUPPORT_ABOVE = 0.45f;
	private static final float SUPPORT_BELOW = 0.12f;

	public static final SlimeMesh MESH = new SlimeMesh(SUBDIVISIONS);

	private final InertiaConfig config;
	private final int perBody = MESH.particleCount();

	private CudaDriver d;
	private long context;
	private long module;
	private long fPredict, fTets, fHashClear, fHashInsert, fContacts, fApply, fVelocities;
	private long stream;
	private final Arena arena = Arena.ofShared();
	private MemorySegment simHost;
	private long simDevice;
	private MemorySegment args;
	private boolean failed;

	// GPU buffers, sized for `capacity` bodies.
	private int capacity;
	private long x, v, prev, dx, cnt, body, tets, invDm, vol, head, next;
	private long solid, boxes, press;
	private long solidBytes, boxBytes, pressBytes;
	private int hashMask;
	private MemorySegment hostPositions;
	private MemorySegment hostUpload;

	/** Bodies in order; particle i of body b is b * perBody + i. */
	private int bodies;
	private boolean topologyDirty;
	/** Origin of the local coordinates (block). */
	private int originX;
	private int originY;
	private int originZ;
	/** Positions (local) of the last two steps, 3 per particle; replaced, never changed. */
	private volatile float[] current = new float[0];
	private volatile float[] previous = new float[0];
	private volatile long stepNanos;
	private volatile int originSnapshotX;
	private volatile int originSnapshotY;
	private volatile int originSnapshotZ;
	/** Positions to upload (new bodies, removed bodies), or null. */
	private float[] pendingUpload;

	private ClientLevel level;
	private Path saveFile;

	public SlimeWorld(InertiaConfig config) {
		this.config = config;
	}

	// ------------------------------------------------------------------------------------------
	// Bodies
	// ------------------------------------------------------------------------------------------

	public int bodyCount() {
		return bodies;
	}

	/** Places a slime block at {@code pos} (world). */
	public boolean place(BlockPos pos) {
		if (!ensureGpu()) {
			return false;
		}
		if (bodies == 0) {
			originX = pos.getX() & ~15;
			originY = pos.getY() & ~15;
			originZ = pos.getZ() & ~15;
		}
		float[] all = editablePositions();
		float[] grown = java.util.Arrays.copyOf(all, (bodies + 1) * perBody * 3);
		int o = bodies * perBody * 3;
		for (int i = 0; i < perBody; i++) {
			grown[o + i * 3] = pos.getX() - originX + MESH.rest[i * 3];
			grown[o + i * 3 + 1] = pos.getY() - originY + MESH.rest[i * 3 + 1];
			grown[o + i * 3 + 2] = pos.getZ() - originZ + MESH.rest[i * 3 + 2];
		}
		bodies++;
		setPositions(grown);
		return true;
	}

	/** Removes the body whose box the ray from {@code eye} along {@code look} hits first within {@code reach}. */
	public boolean removeLookedAt(Vec3 eye, Vec3 look, double reach) {
		float[] p = current;
		int best = -1;
		double bestT = reach;
		for (int b = 0; b < bodies; b++) {
			AABB box = bodyBox(p, b).move(originX, originY, originZ);
			var hit = box.clip(eye, eye.add(look.scale(reach)));
			if (hit.isPresent()) {
				double t = hit.get().distanceTo(eye);
				if (t < bestT) {
					bestT = t;
					best = b;
				}
			}
		}
		if (best < 0) {
			return false;
		}
		float[] all = editablePositions();
		float[] kept = new float[(bodies - 1) * perBody * 3];
		int stride = perBody * 3;
		System.arraycopy(all, 0, kept, 0, best * stride);
		System.arraycopy(all, (best + 1) * stride, kept, best * stride, (bodies - 1 - best) * stride);
		bodies--;
		setPositions(kept);
		return true;
	}

	/** Removes every slime. */
	public int clear() {
		int removed = bodies;
		bodies = 0;
		setPositions(new float[0]);
		return removed;
	}

	private float[] editablePositions() {
		return pendingUpload != null ? pendingUpload : current.clone();
	}

	private void setPositions(float[] positions) {
		pendingUpload = positions;
		topologyDirty = true;
		current = positions;
		previous = positions;
		stepNanos = System.nanoTime();
		originSnapshotX = originX;
		originSnapshotY = originY;
		originSnapshotZ = originZ;
	}

	private static AABB bodyBox(float[] p, int b) {
		int count = MESH.particleCount();
		double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
		double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
		for (int i = b * count; i < (b + 1) * count && i * 3 + 2 < p.length; i++) {
			minX = Math.min(minX, p[i * 3]);
			minY = Math.min(minY, p[i * 3 + 1]);
			minZ = Math.min(minZ, p[i * 3 + 2]);
			maxX = Math.max(maxX, p[i * 3]);
			maxY = Math.max(maxY, p[i * 3 + 1]);
			maxZ = Math.max(maxZ, p[i * 3 + 2]);
		}
		return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
	}

	// ------------------------------------------------------------------------------------------
	// Drawing (render thread)
	// ------------------------------------------------------------------------------------------

	/**
	 * The particle positions (world, relative to the camera at {@code cam}) moved evenly from the
	 * previous step to the latest, into {@code out}; returns the number of bodies.
	 */
	public int interpolated(Vec3 cam, float[] out) {
		float[] a = previous;
		float[] b = current;
		int n = Math.min(Math.min(a.length, b.length), out.length);
		float t = Math.min(1f, Math.max(0f, (System.nanoTime() - stepNanos) / 50_000_000f));
		float ox = (float) (originSnapshotX - cam.x);
		float oy = (float) (originSnapshotY - cam.y);
		float oz = (float) (originSnapshotZ - cam.z);
		for (int i = 0; i + 2 < n; i += 3) {
			out[i] = a[i] + (b[i] - a[i]) * t + ox;
			out[i + 1] = a[i + 1] + (b[i + 1] - a[i + 1]) * t + oy;
			out[i + 2] = a[i + 2] + (b[i + 2] - a[i + 2]) * t + oz;
		}
		return n / (perBody * 3);
	}

	/** Centre of body {@code b} (world), for its light. */
	public BlockPos bodyCentre(int b) {
		float[] p = current;
		int i = b * perBody + perBody / 2;
		if (i * 3 + 2 >= p.length) {
			return BlockPos.ZERO;
		}
		return BlockPos.containing(p[i * 3] + originSnapshotX, p[i * 3 + 1] + originSnapshotY, p[i * 3 + 2] + originSnapshotZ);
	}

	// ------------------------------------------------------------------------------------------
	// Stepping (client thread, every game tick)
	// ------------------------------------------------------------------------------------------

	public void tick(Minecraft client) {
		ClientLevel now = client.level;
		if (now != level) {
			save();
			clear();
			level = now;
			saveFile = null;
			if (now != null) {
				load(client);
			}
		}
		if (level == null || client.player == null || client.isPaused() || bodies == 0 || failed) {
			return;
		}
		try {
			step(client.player);
			supportPlayer(client.player);
		} catch (Throwable t) {
			failed = true;
			Inertia.LOGGER.error("The slime simulation failed and was stopped", t);
		}
	}

	private void step(Player player) {
		d.pushContext(context);
		try {
			if (topologyDirty) {
				upload();
			}
			int particles = bodies * perBody;
			float[] p = current;
			// Colliders: the solid blocks around the bodies, the player's and the entities' boxes.
			AABB all = null;
			for (int b = 0; b < bodies; b++) {
				AABB box = bodyBox(p, b);
				all = all == null ? box : all.minmax(box);
			}
			int gx = (int) Math.floor(all.minX) - 2, gy = (int) Math.floor(all.minY) - 2, gz = (int) Math.floor(all.minZ) - 2;
			int sx = (int) Math.ceil(all.maxX) + 2 - gx, sy = (int) Math.ceil(all.maxY) + 2 - gy, sz = (int) Math.ceil(all.maxZ) + 2 - gz;
			if ((long) sx * sy * sz > MAX_CELLS) {
				sx = Math.min(sx, 128);
				sy = Math.min(sy, 128);
				sz = Math.min(sz, 128);
			}
			byte[] grid = new byte[sx * sy * sz];
			BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
			for (int b = 0; b < bodies; b++) {
				AABB box = bodyBox(p, b).inflate(1.5);
				for (int y = Math.max(gy, (int) Math.floor(box.minY)); y < Math.min(gy + sy, (int) Math.ceil(box.maxY)); y++) {
					for (int z = Math.max(gz, (int) Math.floor(box.minZ)); z < Math.min(gz + sz, (int) Math.ceil(box.maxZ)); z++) {
						for (int x = Math.max(gx, (int) Math.floor(box.minX)); x < Math.min(gx + sx, (int) Math.ceil(box.maxX)); x++) {
							cursor.set(x + originX, y + originY, z + originZ);
							if (!level.getBlockState(cursor).getCollisionShape(level, cursor).isEmpty()) {
								grid[((y - gy) * sz + (z - gz)) * sx + (x - gx)] = 1;
							}
						}
					}
				}
			}
			AABB near = all.move(originX, originY, originZ).inflate(REACH);
			List<Entity> colliders = new ArrayList<>();
			if (!player.isSpectator() && near.intersects(player.getBoundingBox())) {
				colliders.add(player);
			}
			colliders.addAll(level.getEntities(player, near, SlimeWorld::collides));
			float[] boxData = new float[colliders.size() * 6];
			List<float[]> pressers = new ArrayList<>();
			for (int k = 0; k < colliders.size(); k++) {
				Entity e = colliders.get(k);
				AABB box = e.getBoundingBox();
				boxData[k * 6] = (float) (box.minX - originX);
				boxData[k * 6 + 1] = (float) (box.minY - originY);
				boxData[k * 6 + 2] = (float) (box.minZ - originZ);
				boxData[k * 6 + 3] = (float) (box.maxX - originX);
				boxData[k * 6 + 4] = (float) (box.maxY - originY);
				boxData[k * 6 + 5] = (float) (box.maxZ - originZ);
				float[] presser = presser(p, e, box);
				if (presser != null) {
					pressers.add(presser);
				}
			}
			uploadColliders(grid, gx, gy, gz, sx, sy, sz, boxData, pressers);

			int substeps = Math.max(4, config.slimeSubsteps);
			float h = 0.05f / substeps;
			float e = config.slimeStiffness;
			float nu = 0.45f;
			float mu = e / (2f * (1f + nu));
			float lambda = e * nu / ((1f + nu) * (1f - 2f * nu));
			writeSim(particles, gx, gy, gz, sx, sy, sz, colliders.size(), pressers.size(), h, mu, lambda);
			d.copyToDevice(simDevice, simHost, SIM_BYTES, stream);
			int pb = CudaDriver.blocks(particles, THREADS);
			int tb = CudaDriver.blocks((long) bodies * MESH.tetCount(), THREADS);
			int hb = CudaDriver.blocks(hashMask + 1L, THREADS);
			for (int k = 0; k < substeps; k++) {
				d.launch(fPredict, pb, THREADS, stream, args);
				d.launch(fTets, tb, THREADS, stream, args);
				if (bodies > 1) {
					d.launch(fHashClear, hb, THREADS, stream, args);
					d.launch(fHashInsert, pb, THREADS, stream, args);
					d.launch(fContacts, pb, THREADS, stream, args);
				}
				d.launch(fApply, pb, THREADS, stream, args);
				d.launch(fVelocities, pb, THREADS, stream, args);
			}
			d.copyToHost(hostPositions, x, particles * 16L, stream);
			d.synchronize(stream);
			float[] next = new float[particles * 3];
			for (int i = 0; i < particles; i++) {
				next[i * 3] = hostPositions.getAtIndex(ValueLayout.JAVA_FLOAT, i * 4L);
				next[i * 3 + 1] = hostPositions.getAtIndex(ValueLayout.JAVA_FLOAT, i * 4L + 1);
				next[i * 3 + 2] = hostPositions.getAtIndex(ValueLayout.JAVA_FLOAT, i * 4L + 2);
			}
			previous = current;
			current = next;
			stepNanos = System.nanoTime();
			originSnapshotX = originX;
			originSnapshotY = originY;
			originSnapshotZ = originZ;
			// Bodies that fell out of the world go.
			for (int b = bodies - 1; b >= 0; b--) {
				if (next[(b * perBody) * 3 + 1] + originY < level.getMinY() - 64) {
					removeBody(b);
				}
			}
		} finally {
			d.popContext();
		}
	}

	private void removeBody(int b) {
		float[] all = current;
		int stride = perBody * 3;
		float[] kept = new float[(bodies - 1) * stride];
		System.arraycopy(all, 0, kept, 0, b * stride);
		System.arraycopy(all, (b + 1) * stride, kept, b * stride, (bodies - 1 - b) * stride);
		bodies--;
		setPositions(kept);
	}

	static boolean collides(Entity e) {
		return !e.isSpectator() && e.isAlive() && !e.noPhysics && !e.isPassenger()
			&& (e instanceof LivingEntity || e instanceof ItemEntity || e instanceof VehicleEntity);
	}

	/**
	 * The weight of an entity resting on slime, as a downward pull on the particles under its feet:
	 * min x, min z, max x, max z, low y, high y, acceleration (local); null when it does not rest on slime.
	 */
	private float[] presser(float[] p, Entity e, AABB box) {
		// The weight spreads over the slime around the feet, a little wider than the box.
		float minX = (float) (box.minX - originX) - 0.2f, maxX = (float) (box.maxX - originX) + 0.2f;
		float minZ = (float) (box.minZ - originZ) - 0.2f, maxZ = (float) (box.maxZ - originZ) + 0.2f;
		float feet = (float) (box.minY - originY);
		float low = feet - 0.45f, high = feet + 0.15f;
		int under = 0;
		for (int i = 0; i + 2 < p.length; i += 3) {
			if (p[i] >= minX && p[i] <= maxX && p[i + 2] >= minZ && p[i + 2] <= maxZ && p[i + 1] >= low && p[i + 1] <= high) {
				under++;
			}
		}
		if (under == 0) {
			return null;
		}
		float mass = (float) (box.getXsize() * box.getYsize() * box.getZsize()) * config.slimeEntityWeight;
		float particleMass = BODY_MASS / perBody;
		float accel = Math.min(MAX_PRESS, mass * -GRAVITY / (under * particleMass));
		return new float[] {minX, minZ, maxX, maxZ, low, high, accel, 0f};
	}

	// ------------------------------------------------------------------------------------------
	// Resting on slime
	// ------------------------------------------------------------------------------------------

	/** The local player rests on the slime under their feet, and bounces when they land on it fast. */
	private void supportPlayer(Player player) {
		if (player.isSpectator() || player.getAbilities().flying) {
			return;
		}
		support(player, current, originX, originY, originZ, player.isShiftKeyDown());
	}

	/** Server thread: the other entities rest on the slime and bounce on it. */
	public void supportEntities(net.minecraft.server.MinecraftServer server) {
		float[] p = current;
		if (p.length == 0) {
			return;
		}
		int ox = originSnapshotX, oy = originSnapshotY, oz = originSnapshotZ;
		AABB all = null;
		for (int b = 0; b < p.length / (perBody * 3); b++) {
			AABB box = bodyBox(p, b);
			all = all == null ? box : all.minmax(box);
		}
		AABB near = all.move(ox, oy, oz).inflate(1.0);
		for (var world : server.getAllLevels()) {
			if (level == null || world.dimension() != level.dimension()) {
				continue;
			}
			for (Entity e : world.getEntities((Entity) null, near, en -> collides(en) && !(en instanceof Player))) {
				support(e, p, ox, oy, oz, false);
			}
		}
	}

	private static void support(Entity e, float[] p, int ox, int oy, int oz, boolean sneaking) {
		AABB box = e.getBoundingBox();
		float minX = (float) (box.minX - ox) + 0.05f, maxX = (float) (box.maxX - ox) - 0.05f;
		float minZ = (float) (box.minZ - oz) + 0.05f, maxZ = (float) (box.maxZ - oz) - 0.05f;
		float feet = (float) (box.minY - oy);
		float top = Float.NEGATIVE_INFINITY;
		for (int i = 0; i + 2 < p.length; i += 3) {
			float y = p[i + 1];
			if (p[i] >= minX && p[i] <= maxX && p[i + 2] >= minZ && p[i + 2] <= maxZ && y <= feet + SUPPORT_ABOVE && y > top) {
				top = y;
			}
		}
		if (top == Float.NEGATIVE_INFINITY || feet > top + SUPPORT_BELOW) {
			return;
		}
		Vec3 motion = e.getDeltaMovement();
		if (motion.y > 0.05 && feet > top - 0.05f) {
			return; // jumping off
		}
		double y = top + oy;
		e.setPos(e.getX(), y, e.getZ());
		if (motion.y < -0.3 && !sneaking) {
			// Landing fast: bounce like on a slime block.
			e.setDeltaMovement(motion.x, -motion.y * (e instanceof LivingEntity ? 0.9 : 0.7), motion.z);
		} else if (motion.y < 0) {
			// Resting: sticky underfoot.
			e.setDeltaMovement(motion.x * STICKY_WALK, 0, motion.z * STICKY_WALK);
		}
		e.setOnGround(true);
		e.resetFallDistance();
	}

	// ------------------------------------------------------------------------------------------
	// GPU
	// ------------------------------------------------------------------------------------------

	private boolean ensureGpu() {
		if (d != null) {
			return !failed;
		}
		if (PhysXRuntime.state() != PhysXRuntime.State.READY) {
			return false;
		}
		try {
			d = CudaDriver.get();
			var physx = PhysXRuntime.cuda();
			physx.acquireContext();
			try {
				context = d.currentContext();
			} finally {
				physx.releaseContext();
			}
			d.pushContext(context);
			try {
				module = d.loadModule(GpuProbe.ptx("softbody"));
				fPredict = d.function(module, "predict");
				fTets = d.function(module, "tets");
				fHashClear = d.function(module, "hashClear");
				fHashInsert = d.function(module, "hashInsert");
				fContacts = d.function(module, "contacts");
				fApply = d.function(module, "apply");
				fVelocities = d.function(module, "velocities");
				stream = d.createStream();
				simHost = d.allocHost(SIM_BYTES);
				simDevice = d.alloc(SIM_BYTES);
				args = d.prepare(arena, simDevice);
			} finally {
				d.popContext();
			}
			Inertia.LOGGER.info("Slime blocks are simulated on the GPU (finite elements)");
			return true;
		} catch (Throwable t) {
			failed = true;
			Inertia.LOGGER.error("Slime blocks cannot be simulated here", t);
			return false;
		}
	}

	/** Uploads the bodies (positions from {@link #pendingUpload}, the mesh for each). */
	private void upload() {
		float[] positions = pendingUpload != null ? pendingUpload : current;
		pendingUpload = null;
		topologyDirty = false;
		if (bodies > capacity) {
			freeBodies();
			capacity = Math.max(8, Integer.highestOneBit(bodies) << 1);
			int np = capacity * perBody;
			int nt = capacity * MESH.tetCount();
			x = d.alloc(np * 16L);
			v = d.alloc(np * 16L);
			prev = d.alloc(np * 16L);
			dx = d.alloc(np * 12L);
			cnt = d.alloc(np * 4L);
			body = d.alloc(np * 4L);
			tets = d.alloc(nt * 16L);
			invDm = d.alloc(nt * 36L);
			vol = d.alloc(nt * 4L);
			next = d.alloc(np * 4L);
			hashMask = Integer.highestOneBit(Math.max(1024, np * 2)) * 2 - 1;
			head = d.alloc((hashMask + 1L) * 4);
			hostPositions = d.allocHost(np * 16L);
			hostUpload = d.allocHost(Math.max(np * 16L, nt * 36L));
		}
		int np = bodies * perBody;
		int nt = bodies * MESH.tetCount();
		float particleInvMass = perBody / BODY_MASS;
		for (int i = 0; i < np; i++) {
			hostUpload.setAtIndex(ValueLayout.JAVA_FLOAT, i * 4L, positions[i * 3]);
			hostUpload.setAtIndex(ValueLayout.JAVA_FLOAT, i * 4L + 1, positions[i * 3 + 1]);
			hostUpload.setAtIndex(ValueLayout.JAVA_FLOAT, i * 4L + 2, positions[i * 3 + 2]);
			hostUpload.setAtIndex(ValueLayout.JAVA_FLOAT, i * 4L + 3, particleInvMass);
		}
		d.copyToDevice(x, hostUpload, np * 16L, stream);
		d.fill32(v, 0, np * 4L, stream);
		for (int i = 0; i < np; i++) {
			hostUpload.setAtIndex(ValueLayout.JAVA_INT, i, i / perBody);
		}
		d.copyToDevice(body, hostUpload, np * 4L, stream);
		d.synchronize(stream);
		for (int b = 0; b < bodies; b++) {
			for (int t = 0; t < MESH.tetCount() * 4; t++) {
				hostUpload.setAtIndex(ValueLayout.JAVA_INT, (long) b * MESH.tetCount() * 4 + t, MESH.tets[t] + b * perBody);
			}
		}
		d.copyToDevice(tets, hostUpload, nt * 16L, stream);
		d.synchronize(stream);
		for (int b = 0; b < bodies; b++) {
			MemorySegment.copy(MESH.invDm, 0, hostUpload, ValueLayout.JAVA_FLOAT, (long) b * MESH.tetCount() * 36, MESH.tetCount() * 9);
		}
		d.copyToDevice(invDm, hostUpload, nt * 36L, stream);
		d.synchronize(stream);
		for (int b = 0; b < bodies; b++) {
			MemorySegment.copy(MESH.volume, 0, hostUpload, ValueLayout.JAVA_FLOAT, (long) b * MESH.tetCount() * 4, MESH.tetCount());
		}
		d.copyToDevice(vol, hostUpload, nt * 4L, stream);
		d.synchronize(stream);
	}

	private void uploadColliders(byte[] grid, int gx, int gy, int gz, int sx, int sy, int sz, float[] boxData, List<float[]> pressers) {
		solid = ensure(solid, solidBytes, grid.length, b -> solidBytes = b);
		boxes = ensure(boxes, boxBytes, Math.max(24, boxData.length * 4L), b -> boxBytes = b);
		press = ensure(press, pressBytes, Math.max(32, pressers.size() * 32L), b -> pressBytes = b);
		try (Arena temp = Arena.ofConfined()) {
			MemorySegment g = temp.allocate(Math.max(1, grid.length));
			MemorySegment.copy(grid, 0, g, ValueLayout.JAVA_BYTE, 0, grid.length);
			d.copyToDevice(solid, g, grid.length, stream);
			if (boxData.length > 0) {
				MemorySegment bseg = temp.allocate(boxData.length * 4L, 4);
				MemorySegment.copy(boxData, 0, bseg, ValueLayout.JAVA_FLOAT, 0, boxData.length);
				d.copyToDevice(boxes, bseg, boxData.length * 4L, stream);
			}
			if (!pressers.isEmpty()) {
				MemorySegment pseg = temp.allocate(pressers.size() * 32L, 4);
				for (int k = 0; k < pressers.size(); k++) {
					MemorySegment.copy(pressers.get(k), 0, pseg, ValueLayout.JAVA_FLOAT, k * 32L, 8);
				}
				d.copyToDevice(press, pseg, pressers.size() * 32L, stream);
			}
			d.synchronize(stream);
		}
	}

	private long ensure(long pointer, long have, long need, java.util.function.LongConsumer size) {
		if (pointer != 0 && have >= need) {
			return pointer;
		}
		d.synchronize(stream);
		if (pointer != 0) {
			d.free(pointer);
		}
		long bytes = Math.max(need, have * 2);
		size.accept(bytes);
		return d.alloc(bytes);
	}

	private void writeSim(int particles, int gx, int gy, int gz, int sx, int sy, int sz, int boxCount, int pressCount, float h,
		float mu, float lambda) {
		long[] pointers = {x, v, prev, dx, cnt, body, tets, invDm, vol, solid, boxes, press, head, next};
		for (int k = 0; k < pointers.length; k++) {
			simHost.set(ValueLayout.JAVA_LONG, k * 8L, pointers[k]);
		}
		int[] ints = {particles, bodies * MESH.tetCount(), gx, gy, gz, sx, sy, sz, boxCount, pressCount, hashMask};
		for (int k = 0; k < ints.length; k++) {
			simHost.set(ValueLayout.JAVA_INT, 112 + k * 4L, ints[k]);
		}
		float[] floats = {h, GRAVITY, mu, lambda, config.slimeDamping, config.slimeFriction, 0.13f, 0.3f, config.slimeStickiness};
		for (int k = 0; k < floats.length; k++) {
			simHost.set(ValueLayout.JAVA_FLOAT, 156 + k * 4L, floats[k]);
		}
	}

	private void freeBodies() {
		for (long p : new long[] {x, v, prev, dx, cnt, body, tets, invDm, vol, head, next}) {
			if (p != 0) {
				d.free(p);
			}
		}
		if (hostPositions != null) {
			d.freeHost(hostPositions);
			d.freeHost(hostUpload);
		}
	}

	// ------------------------------------------------------------------------------------------
	// Saving (with the world, like the water)
	// ------------------------------------------------------------------------------------------

	private Path file(Minecraft client) {
		var server = client.getSingleplayerServer();
		if (server == null || level == null) {
			return null;
		}
		String dimension = level.dimension().identifier().toString().replace(':', '_').replace('/', '_');
		return server.getWorldPath(LevelResource.ROOT).resolve("inertia").resolve("slime-" + dimension + ".dat");
	}

	/** Saves the slime blocks of the current dimension (positions of every particle, world). */
	public void save() {
		Path path = saveFile;
		if (path == null) {
			return;
		}
		float[] p = current;
		int ox = originSnapshotX, oy = originSnapshotY, oz = originSnapshotZ;
		try {
			if (p.length == 0) {
				Files.deleteIfExists(path);
				return;
			}
			Files.createDirectories(path.getParent());
			Path temp = path.resolveSibling(path.getFileName() + ".tmp");
			try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(temp)))) {
				out.writeInt(0x534C494D); // "SLIM"
				out.writeInt(SUBDIVISIONS);
				out.writeInt(ox);
				out.writeInt(oy);
				out.writeInt(oz);
				out.writeInt(p.length);
				for (float f : p) {
					out.writeFloat(f);
				}
			}
			Files.move(temp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		} catch (Exception e) {
			Inertia.LOGGER.error("Could not save the slime blocks", e);
		}
	}

	private void load(Minecraft client) {
		saveFile = file(client);
		if (saveFile == null || !Files.isRegularFile(saveFile)) {
			return;
		}
		try (DataInputStream in = new DataInputStream(new GZIPInputStream(Files.newInputStream(saveFile)))) {
			if (in.readInt() != 0x534C494D || in.readInt() != SUBDIVISIONS) {
				return;
			}
			int ox = in.readInt(), oy = in.readInt(), oz = in.readInt();
			int n = in.readInt();
			if (n < 0 || n % (perBody * 3) != 0 || n > 50_000_000) {
				return;
			}
			float[] p = new float[n];
			for (int i = 0; i < n; i++) {
				p[i] = in.readFloat();
			}
			pendingLoad = p;
			pendingOrigin = new int[] {ox, oy, oz};
		} catch (Exception e) {
			Inertia.LOGGER.error("Could not read the saved slime blocks", e);
		}
	}

	private float[] pendingLoad;
	private int[] pendingOrigin;

	/** Puts the saved slime blocks back once the GPU is ready and their chunks are loaded. */
	public void restoreSaved(Minecraft client) {
		if (pendingLoad == null || level == null || !ensureGpu()) {
			return;
		}
		int i = pendingLoad.length / 2;
		BlockPos at = BlockPos.containing(pendingLoad[i / 3 * 3] + pendingOrigin[0], pendingLoad[i / 3 * 3 + 1] + pendingOrigin[1],
			pendingLoad[i / 3 * 3 + 2] + pendingOrigin[2]);
		if (!level.isLoaded(at)) {
			return;
		}
		originX = pendingOrigin[0];
		originY = pendingOrigin[1];
		originZ = pendingOrigin[2];
		bodies = pendingLoad.length / (perBody * 3);
		setPositions(pendingLoad);
		pendingLoad = null;
		Inertia.LOGGER.info("Slime blocks restored: {}", bodies);
	}
}
