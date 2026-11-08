package io.github.isnowx17.inertia.client.fluid;

import io.github.isnowx17.inertia.client.physics.PhysXRuntime;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.SectionPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryStack;
import physx.common.PxQuat;
import physx.common.PxTransform;
import physx.common.PxVec3;
import physx.extensions.PxRigidBodyExt;
import physx.geometry.PxBoxGeometry;
import physx.physics.PxMaterial;
import physx.physics.PxRigidDynamic;
import physx.physics.PxScene;
import physx.physics.PxShape;
import physx.physics.PxShapeFlagEnum;
import physx.physics.PxShapeFlags;

/**
 * PhysX rigid bodies in the water's scene, so the water and they act on each other: realistic
 * blocks (several blocks stuck together make one body, a block per box shape) and realistic boats and
 * minecarts. Positions are relative to the simulation's origin. Client thread, between physics steps.
 */
public final class RigidBodies implements AutoCloseable {
	public enum Kind { BLOCKS, BOAT, MINECART }

	/** One block of a body: its cell in the body's frame (the first block is at 0, 0, 0). */
	public record Part(int x, int y, int z, BlockState state) {
	}

	/** Half sizes of the vehicles' boxes (blocks) and their densities (kg per cubic block). */
	private static final Vector3f BOAT_HALF = new Vector3f(0.6875f, 0.22f, 0.6875f);
	private static final Vector3f MINECART_HALF = new Vector3f(0.49f, 0.35f, 0.49f);
	private static final float BOAT_DENSITY = 280f;
	private static final float MINECART_DENSITY = 1100f;

	public static final class Body {
		public final int id;
		public final Kind kind;
		/** The item it came from (for vehicles, and for what it drops). */
		public final String source;
		final List<Part> parts = new ArrayList<>();
		PxRigidDynamic actor;
		/** Pose (x, y, z, qx, qy, qz, qw) after the newest and the previous step. */
		final float[] current = new float[7];
		final float[] previous = new float[7];

		Body(int id, Kind kind, String source) {
			this.id = id;
			this.kind = kind;
			this.source = source;
		}

		public List<Part> parts() {
			return parts;
		}

		/** Pose after the newest step: x, y, z (local), qx, qy, qz, qw. */
		public float[] current() {
			return current;
		}

		/** Half size of a vehicle's box. */
		public Vector3f vehicleHalf() {
			return kind == Kind.BOAT ? BOAT_HALF : MINECART_HALF;
		}
	}

	/** What the renderer draws: a body and its two poses. Never changed after it was made. */
	public record View(int id, Kind kind, String source, List<Part> parts, float[] previous, float[] current) {
	}

	/** A ray hit: the body, the part (or -1 for a vehicle), the face it hit (in the body's frame) and where. */
	public record Hit(Body body, int part, int faceX, int faceY, int faceZ, double distance) {
	}

	private final PxScene scene;
	private final PxMaterial material;
	private final List<Body> bodies = new ArrayList<>();
	private int nextId;
	private volatile List<View> views = List.of();
	private volatile long stepNanos;

	RigidBodies(PxScene scene) {
		this.scene = scene;
		// Some friction so things do not slide forever, and a little bounce.
		this.material = PhysXRuntime.physics().createMaterial(0.6f, 0.5f, 0.1f);
	}

	public boolean isEmpty() {
		return bodies.isEmpty();
	}

	public List<Body> bodies() {
		return bodies;
	}

	/** Whether any body is moving (the scene must keep stepping). */
	boolean awake() {
		for (Body b : bodies) {
			if (!b.actor.isSleeping()) {
				return true;
			}
		}
		return false;
	}

	/** A new body of one block whose centre is at {@code centre} (local). */
	public Body addBlock(BlockState state, Vec3 centre, Vec3 velocity) {
		Body body = new Body(nextId++, Kind.BLOCKS, net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
		body.parts.add(new Part(0, 0, 0, state));
		create(body, centre, new Quaternionf(), velocity);
		return body;
	}

	/** Puts a saved body back: {@code centre} (local) and its rotation. */
	public Body restore(Kind kind, String source, List<Part> parts, Vec3 centre, Quaternionf rotation) {
		Body body = new Body(nextId++, kind, source);
		body.parts.addAll(parts);
		create(body, centre, rotation, Vec3.ZERO);
		return body;
	}

	/** A boat or a minecart at {@code centre} (local), turned by {@code yaw} degrees. */
	public Body addVehicle(Kind kind, String source, Vec3 centre, float yaw) {
		Body body = new Body(nextId++, kind, source);
		create(body, centre, new Quaternionf().rotationY((float) Math.toRadians(-yaw)), Vec3.ZERO);
		return body;
	}

	/** Sticks another block onto {@code body} at cell {@code x, y, z} of its frame. */
	public void attach(Body body, int x, int y, int z, BlockState state) {
		for (Part p : body.parts) {
			if (p.x() == x && p.y() == y && p.z() == z) {
				return;
			}
		}
		body.parts.add(new Part(x, y, z, state));
		try (MemoryStack mem = MemoryStack.stackPush()) {
			PxShape shape = blockShape(mem, x, y, z);
			body.actor.attachShape(shape);
			shape.release();
			updateMass(body);
			body.actor.wakeUp();
		}
		publish();
	}

	/**
	 * Breaks block {@code index} out of {@code body}: what is left falls apart into the groups of
	 * blocks still touching each other, each a body of its own moving on as before.
	 *
	 * @return the block that broke off
	 */
	public Part breakPart(Body body, int index) {
		Part broken = body.parts.get(index);
		List<Part> rest = new ArrayList<>(body.parts);
		rest.remove(index);
		Vec3 velocity;
		Vec3 spin;
		try (MemoryStack mem = MemoryStack.stackPush()) {
			PxVec3 v = body.actor.getLinearVelocity();
			PxVec3 w = body.actor.getAngularVelocity();
			velocity = new Vec3(v.getX(), v.getY(), v.getZ());
			spin = new Vec3(w.getX(), w.getY(), w.getZ());
		}
		float[] pose = body.current.clone();
		remove(body);
		Quaternionf rotation = new Quaternionf(pose[3], pose[4], pose[5], pose[6]);
		while (!rest.isEmpty()) {
			// One group: everything reachable from the first block through shared faces.
			List<Part> group = new ArrayList<>();
			group.add(rest.remove(0));
			for (int k = 0; k < group.size(); k++) {
				Part p = group.get(k);
				for (int j = rest.size() - 1; j >= 0; j--) {
					Part q = rest.get(j);
					if (Math.abs(p.x() - q.x()) + Math.abs(p.y() - q.y()) + Math.abs(p.z() - q.z()) == 1) {
						group.add(rest.remove(j));
					}
				}
			}
			Part first = group.get(0);
			Vector3f offset = rotation.transform(new Vector3f(first.x(), first.y(), first.z()));
			Body piece = new Body(nextId++, Kind.BLOCKS, net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(first.state().getBlock()).toString());
			for (Part p : group) {
				piece.parts.add(new Part(p.x() - first.x(), p.y() - first.y(), p.z() - first.z(), p.state()));
			}
			create(piece, new Vec3(pose[0] + offset.x, pose[1] + offset.y, pose[2] + offset.z), new Quaternionf(rotation), velocity);
			try (MemoryStack mem = MemoryStack.stackPush()) {
				piece.actor.setAngularVelocity(PxVec3.createAt(mem, MemoryStack::nmalloc, (float) spin.x, (float) spin.y, (float) spin.z));
			}
		}
		return broken;
	}

	public void remove(Body body) {
		if (bodies.remove(body)) {
			scene.removeActor(body.actor);
			body.actor.release();
			publish();
		}
	}

	public void clear() {
		shoves.clear();
		for (Body b : bodies) {
			scene.removeActor(b.actor);
			b.actor.release();
		}
		bodies.clear();
		publish();
	}

	private void create(Body body, Vec3 centre, Quaternionf rotation, Vec3 velocity) {
		try (MemoryStack mem = MemoryStack.stackPush()) {
			PxTransform pose = PxTransform.createAt(mem, MemoryStack::nmalloc,
				PxVec3.createAt(mem, MemoryStack::nmalloc, (float) centre.x, (float) centre.y, (float) centre.z),
				PxQuat.createAt(mem, MemoryStack::nmalloc, rotation.x, rotation.y, rotation.z, rotation.w));
			PxRigidDynamic actor = PhysXRuntime.physics().createRigidDynamic(pose);
			if (body.kind == Kind.BLOCKS) {
				for (Part p : body.parts) {
					PxShape shape = blockShape(mem, p.x(), p.y(), p.z());
					actor.attachShape(shape);
					shape.release();
				}
			} else {
				Vector3f half = body.vehicleHalf();
				PxShape shape = shape(mem, half.x, half.y, half.z, 0, 0, 0);
				actor.attachShape(shape);
				shape.release();
			}
			body.actor = actor;
			actor.setSolverIterationCounts(8, 2);
			// Overlaps (a player's box, a block placed into another) are pushed apart gently, never flung.
			actor.setMaxDepenetrationVelocity(2f);
			actor.setMaxLinearVelocity(40f);
			updateMass(body);
			actor.setLinearVelocity(PxVec3.createAt(mem, MemoryStack::nmalloc, (float) velocity.x, (float) velocity.y, (float) velocity.z));
			scene.addActor(actor);
		}
		float[] p = {(float) centre.x, (float) centre.y, (float) centre.z, rotation.x, rotation.y, rotation.z, rotation.w};
		System.arraycopy(p, 0, body.current, 0, 7);
		System.arraycopy(p, 0, body.previous, 0, 7);
		bodies.add(body);
		publish();
	}

	private PxShape blockShape(MemoryStack mem, int x, int y, int z) {
		return shape(mem, 0.5f, 0.5f, 0.5f, x, y, z);
	}

	private PxShape shape(MemoryStack mem, float hx, float hy, float hz, float x, float y, float z) {
		PxShapeFlags flags = PxShapeFlags.createAt(mem, MemoryStack::nmalloc, (byte) 0);
		flags.raise(PxShapeFlagEnum.eSIMULATION_SHAPE);
		PxBoxGeometry geometry = PxBoxGeometry.createAt(mem, MemoryStack::nmalloc, hx, hy, hz);
		PxShape shape = PhysXRuntime.physics().createShape(geometry, material, true, flags);
		shape.setSimulationFilterData(PhysXRuntime.filterData());
		shape.setLocalPose(PxTransform.createAt(mem, MemoryStack::nmalloc, PxVec3.createAt(mem, MemoryStack::nmalloc, x, y, z),
			PxQuat.createAt(mem, MemoryStack::nmalloc, 0f, 0f, 0f, 1f)));
		return shape;
	}

	/** Mass from the blocks' densities (wood floats, stone sinks). */
	private void updateMass(Body body) {
		float density;
		if (body.kind == Kind.BOAT) {
			density = BOAT_DENSITY;
		} else if (body.kind == Kind.MINECART) {
			density = MINECART_DENSITY;
		} else {
			float sum = 0f;
			for (Part p : body.parts) {
				sum += density(p.state());
			}
			density = sum / body.parts.size();
		}
		PxRigidBodyExt.updateMassAndInertia(body.actor, density);
	}

	/** Kilograms per cubic block (water: 1000). */
	static float density(BlockState state) {
		// Metals and gems: real densities (netherite, which does not exist, the heaviest of all).
		var block = state.getBlock();
		if (block == net.minecraft.world.level.block.Blocks.NETHERITE_BLOCK) {
			return 20000f;
		}
		if (block == net.minecraft.world.level.block.Blocks.GOLD_BLOCK) {
			return 19300f;
		}
		if (block == net.minecraft.world.level.block.Blocks.RAW_GOLD_BLOCK) {
			return 10000f;
		}
		if (block == net.minecraft.world.level.block.Blocks.IRON_BLOCK) {
			return 7870f;
		}
		if (block == net.minecraft.world.level.block.Blocks.RAW_IRON_BLOCK || block == net.minecraft.world.level.block.Blocks.RAW_COPPER_BLOCK
			|| block == net.minecraft.world.level.block.Blocks.ANCIENT_DEBRIS) {
			return 5000f;
		}
		String path = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block).getPath();
		if (path.contains("copper") && !path.contains("ore")) {
			return 8960f;
		}
		if (block == net.minecraft.world.level.block.Blocks.REDSTONE_BLOCK) {
			return 5300f;
		}
		if (block == net.minecraft.world.level.block.Blocks.DIAMOND_BLOCK) {
			return 3510f;
		}
		if (block == net.minecraft.world.level.block.Blocks.LAPIS_BLOCK) {
			return 2800f;
		}
		if (block == net.minecraft.world.level.block.Blocks.EMERALD_BLOCK) {
			return 2760f;
		}
		if (block == net.minecraft.world.level.block.Blocks.OBSIDIAN || block == net.minecraft.world.level.block.Blocks.CRYING_OBSIDIAN) {
			return 2600f;
		}
		if (block == net.minecraft.world.level.block.Blocks.COAL_BLOCK) {
			return 1400f;
		}
		if (state.is(BlockTags.LOGS) || state.is(BlockTags.PLANKS) || state.is(BlockTags.WOODEN_SLABS) || state.is(BlockTags.WOODEN_STAIRS)
			|| state.is(BlockTags.WOODEN_FENCES)) {
			return 600f;
		}
		if (state.is(BlockTags.WOOL) || state.is(BlockTags.LEAVES)) {
			return 250f;
		}
		if (state.is(BlockTags.ICE)) {
			return 900f;
		}
		if (state.is(BlockTags.SAND) || state.is(BlockTags.DIRT)) {
			return 1600f;
		}
		return 2400f;
	}

	// ------------------------------------------------------------------------------------------
	// Steps
	// ------------------------------------------------------------------------------------------

	/** After a step: the new poses (the previous ones are kept for moving smoothly in between). */
	void capturePoses() {
		if (bodies.isEmpty()) {
			return;
		}
		for (Body b : bodies) {
			System.arraycopy(b.current, 0, b.previous, 0, 7);
			PxTransform pose = b.actor.getGlobalPose();
			PxVec3 p = pose.getP();
			PxQuat q = pose.getQ();
			b.current[0] = p.getX();
			b.current[1] = p.getY();
			b.current[2] = p.getZ();
			b.current[3] = q.getX();
			b.current[4] = q.getY();
			b.current[5] = q.getZ();
			b.current[6] = q.getW();
		}
		stepNanos = System.nanoTime();
		publish();
	}

	private void publish() {
		List<View> list = new ArrayList<>(bodies.size());
		for (Body b : bodies) {
			list.add(new View(b.id, b.kind, b.source, List.copyOf(b.parts), b.previous.clone(), b.current.clone()));
		}
		views = list;
	}

	/** Render thread: what to draw and when the newest poses came. */
	public List<View> views() {
		return views;
	}

	public long stepNanos() {
		return stepNanos;
	}

	/** The chunk sections around every body (for the block collision geometry). */
	void sections(int originX, int originY, int originZ, LongSet required, LongSet urgent) {
		for (Body b : bodies) {
			float r = b.kind == Kind.BLOCKS ? radius(b) + 1.5f : 2.5f;
			int x0 = Mth.floor(b.current[0] - r) + originX, x1 = Mth.floor(b.current[0] + r) + originX;
			int y0 = Mth.floor(b.current[1] - r) + originY, y1 = Mth.floor(b.current[1] + r) + originY;
			int z0 = Mth.floor(b.current[2] - r) + originZ, z1 = Mth.floor(b.current[2] + r) + originZ;
			for (int sx = x0 >> 4; sx <= x1 >> 4; sx++) {
				for (int sy = y0 >> 4; sy <= y1 >> 4; sy++) {
					for (int sz = z0 >> 4; sz <= z1 >> 4; sz++) {
						long s = SectionPos.asLong(sx, sy, sz);
						urgent.add(s);
						required.add(s);
					}
				}
			}
		}
	}

	/** A box around every body (local: min x, y, z, max x, y, z), reaching {@code below} blocks further down. */
	float[] boxes(float below) {
		float[] out = new float[bodies.size() * 6];
		for (int k = 0; k < bodies.size(); k++) {
			Body b = bodies.get(k);
			float r = b.kind == Kind.BLOCKS ? radius(b) : 0.9f;
			out[k * 6] = b.current[0] - r;
			out[k * 6 + 1] = b.current[1] - r - below;
			out[k * 6 + 2] = b.current[2] - r;
			out[k * 6 + 3] = b.current[0] + r;
			out[k * 6 + 4] = b.current[1] + r;
			out[k * 6 + 5] = b.current[2] + r;
		}
		return out;
	}

	private static float radius(Body b) {
		float r = 0f;
		for (Part p : b.parts) {
			r = Math.max(r, (float) Math.sqrt(p.x() * p.x() + p.y() * p.y() + p.z() * p.z()));
		}
		return r + 0.9f;
	}

	// ------------------------------------------------------------------------------------------
	// Looking at bodies
	// ------------------------------------------------------------------------------------------

	/** The first body the ray (local) hits within {@code reach}. */
	public Hit raycast(Vec3 from, Vec3 dir, double reach) {
		Hit best = null;
		for (Body b : bodies) {
			Quaternionf inv = new Quaternionf(b.current[3], b.current[4], b.current[5], b.current[6]).conjugate();
			Vector3f o = new Vector3f((float) (from.x - b.current[0]), (float) (from.y - b.current[1]), (float) (from.z - b.current[2]));
			inv.transform(o);
			Vector3f d = inv.transform(new Vector3f((float) dir.x, (float) dir.y, (float) dir.z));
			if (b.kind == Kind.BLOCKS) {
				for (int k = 0; k < b.parts.size(); k++) {
					Part p = b.parts.get(k);
					Hit h = slab(b, k, o, d, p.x(), p.y(), p.z(), 0.5f, 0.5f, 0.5f, best != null ? best.distance() : reach);
					if (h != null) {
						best = h;
					}
				}
			} else {
				Vector3f half = b.vehicleHalf();
				Hit h = slab(b, -1, o, d, 0, 0, 0, half.x, half.y, half.z, best != null ? best.distance() : reach);
				if (h != null) {
					best = h;
				}
			}
		}
		return best;
	}

	private static Hit slab(Body b, int part, Vector3f o, Vector3f d, float cx, float cy, float cz, float hx, float hy, float hz, double max) {
		float[] lo = {cx - hx, cy - hy, cz - hz};
		float[] hi = {cx + hx, cy + hy, cz + hz};
		float[] oo = {o.x, o.y, o.z};
		float[] dd = {d.x, d.y, d.z};
		double enter = 0, exit = max;
		int axis = -1;
		int sign = 0;
		for (int a = 0; a < 3; a++) {
			if (Math.abs(dd[a]) < 1e-9) {
				if (oo[a] < lo[a] || oo[a] > hi[a]) {
					return null;
				}
				continue;
			}
			double t1 = (lo[a] - oo[a]) / dd[a];
			double t2 = (hi[a] - oo[a]) / dd[a];
			int s = -1;
			if (t1 > t2) {
				double t = t1;
				t1 = t2;
				t2 = t;
				s = 1;
			}
			if (t1 > enter) {
				enter = t1;
				axis = a;
				sign = s;
			}
			exit = Math.min(exit, t2);
			if (enter > exit) {
				return null;
			}
		}
		if (axis < 0) {
			return null; // starts inside
		}
		return new Hit(b, part, axis == 0 ? sign : 0, axis == 1 ? sign : 0, axis == 2 ? sign : 0, enter);
	}

	/** The world-local position of a cell of {@code body}'s frame. */
	public static Vec3 cellCentre(Body body, int x, int y, int z) {
		Quaternionf q = new Quaternionf(body.current[3], body.current[4], body.current[5], body.current[6]);
		Vector3f v = q.transform(new Vector3f(x, y, z));
		return new Vec3(body.current[0] + v.x, body.current[1] + v.y, body.current[2] + v.z);
	}

	/** Sets a body's velocity (grabbing). */
	public void push(Body body, Vec3 velocity) {
		try (MemoryStack mem = MemoryStack.stackPush()) {
			body.actor.setLinearVelocity(PxVec3.createAt(mem, MemoryStack::nmalloc, (float) velocity.x, (float) velocity.y, (float) velocity.z));
			body.actor.wakeUp();
		}
	}

	/** Driving: accelerates {@code body} (blocks per second squared) and turns it about the vertical (radians per second squared). */
	public void drive(Body body, Vec3 accel, float turn) {
		try (MemoryStack mem = MemoryStack.stackPush()) {
			body.actor.addForce(PxVec3.createAt(mem, MemoryStack::nmalloc, (float) accel.x, (float) accel.y, (float) accel.z),
				physx.physics.PxForceModeEnum.eACCELERATION);
			body.actor.addTorque(PxVec3.createAt(mem, MemoryStack::nmalloc, 0f, turn, 0f), physx.physics.PxForceModeEnum.eACCELERATION);
		}
	}

	/**
	 * On rails: the body moves along {@code direction} (unit, local) at its speed along it plus
	 * {@code push}, facing that way, without spinning; its height follows {@code height} when given.
	 */
	public void followRail(Body body, Vec3 direction, double push, double height) {
		try (MemoryStack mem = MemoryStack.stackPush()) {
			PxVec3 v = body.actor.getLinearVelocity();
			double along = v.getX() * direction.x + v.getY() * direction.y + v.getZ() * direction.z + push;
			along = Mth.clamp(along, -8.0, 8.0);
			double vy = direction.y != 0 ? direction.y * along : Math.min(v.getY(), 0f);
			body.actor.setLinearVelocity(PxVec3.createAt(mem, MemoryStack::nmalloc, (float) (direction.x * along), (float) vy,
				(float) (direction.z * along)));
			body.actor.setAngularVelocity(PxVec3.createAt(mem, MemoryStack::nmalloc, 0f, 0f, 0f));
			float yaw = (float) Math.atan2(direction.x, direction.z);
			Quaternionf q = new Quaternionf().rotationY(yaw);
			double y = Double.isNaN(height) ? body.current[1] : height;
			body.actor.setGlobalPose(PxTransform.createAt(mem, MemoryStack::nmalloc,
				PxVec3.createAt(mem, MemoryStack::nmalloc, body.current[0], (float) y, body.current[2]),
				PxQuat.createAt(mem, MemoryStack::nmalloc, q.x, q.y, q.z, q.w)));
			body.actor.wakeUp();
		}
	}

	private record Shove(Body body, Vec3 force, Vec3 at) {
	}

	private final List<Shove> shoves = new ArrayList<>();

	/**
	 * Pushes {@code body} with {@code force} (kilograms times blocks per second squared) at {@code at}
	 * (local) during the next step. Safe while a step runs: the push waits until that step is done.
	 */
	public void shove(Body body, Vec3 force, Vec3 at) {
		shoves.add(new Shove(body, force, at));
	}

	/** Before a step starts (none running): the pushes asked for since the last one. */
	void applyShoves() {
		if (shoves.isEmpty()) {
			return;
		}
		try (MemoryStack mem = MemoryStack.stackPush()) {
			for (Shove sh : shoves) {
				if (!bodies.contains(sh.body)) {
					continue; // removed or broken since
				}
				PxRigidBodyExt.addForceAtPos(sh.body.actor,
					PxVec3.createAt(mem, MemoryStack::nmalloc, (float) sh.force.x, (float) sh.force.y, (float) sh.force.z),
					PxVec3.createAt(mem, MemoryStack::nmalloc, (float) sh.at.x, (float) sh.at.y, (float) sh.at.z));
				sh.body.actor.wakeUp();
			}
		}
		shoves.clear();
	}

	/** The boxes of a body in its own frame: centre x, y, z and half x, y, z, 6 floats each. */
	public static float[] boxesOf(Body body) {
		if (body.kind != Kind.BLOCKS) {
			Vector3f h = body.vehicleHalf();
			return new float[] {0, 0, 0, h.x, h.y, h.z};
		}
		float[] out = new float[body.parts.size() * 6];
		for (int k = 0; k < body.parts.size(); k++) {
			Part p = body.parts.get(k);
			out[k * 6] = p.x();
			out[k * 6 + 1] = p.y();
			out[k * 6 + 2] = p.z();
			out[k * 6 + 3] = 0.5f;
			out[k * 6 + 4] = 0.5f;
			out[k * 6 + 5] = 0.5f;
		}
		return out;
	}

	/** World-local rotation of a body. */
	public static Quaternionf rotation(Body body) {
		return new Quaternionf(body.current[3], body.current[4], body.current[5], body.current[6]);
	}

	/** A body's velocity (blocks per second). */
	public Vec3 velocity(Body body) {
		PxVec3 v = body.actor.getLinearVelocity();
		return new Vec3(v.getX(), v.getY(), v.getZ());
	}

	/** The direction a vehicle faces (its local +z), local. */
	public static Vec3 forward(Body body) {
		Quaternionf q = new Quaternionf(body.current[3], body.current[4], body.current[5], body.current[6]);
		Vector3f f = q.transform(new Vector3f(0f, 0f, 1f));
		return new Vec3(f.x, f.y, f.z);
	}

	@Override
	public void close() {
		clear();
		material.release();
	}
}
