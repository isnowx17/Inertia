package io.github.isnowx17.inertia.util;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.util.Mth;

/**
 * Still water kept as a volume instead of particles: the calm water deep under the surface of a pool.
 *
 * <p>The physics only needs the water that can move. Deep under a calm surface nothing moves, yet
 * every particle there costs the GPU as much as one in a waterfall. So, per column of blocks, the calm
 * water from the floor up to a few particle layers below the surface is taken out of the simulation
 * and kept here as a height ("frozen"): a column of {@code layers} particle layers on top of a full
 * block. A box in its place carries the water above, which rests on it as it rested on the water
 * before, and the surface mesh fills the box with water of the same density, so it looks the same.
 * Only the top layers stay particles; a pool as deep as you like costs about as much as a shallow one.
 *
 * <p>As soon as the water could move again the volume turns back into particles at the places they
 * were ("thaws"), all of it or the top part: when the water above it gets thin or drains away, when
 * water next to it is lower (it would flow there), when something fast moves through the water above
 * it, when a block next to it changes, when the player comes close and near flowing sources.
 *
 * <p>Coordinates are relative to the simulation's origin, like the particles'. Not thread-safe:
 * client thread only.
 */
public final class FrozenColumns {
	/** Particle layers that stay particles on top of the frozen water. */
	static final int ACTIVE_LAYERS = 4;
	/** {@link #ACTIVE_LAYERS} while the GPU is at its limit. */
	static final int TIGHT_ACTIVE_LAYERS = 3;
	/** A column freezes when this many layers more than {@link #ACTIVE_LAYERS} can freeze at once. */
	static final int MIN_FREEZE_LAYERS = 2;
	/** Water above a frozen column thinner than this many layers thaws it. */
	static final int THAW_BELOW_LAYERS = 2;
	/**
	 * A column is calm while at most this fraction of its particles is faster than
	 * {@link #CALM_SPEED} (blocks/second): a few always jitter or roll along the surface.
	 */
	static final float CALM_FRACTION = 0.02f;
	/** At the GPU's limit water freezes while at most this fraction of it is faster than {@link #RESTLESS_SPEED}. */
	static final float TIGHT_RESTLESS_FRACTION = 0.05f;
	/** {@link #TIGHT_RESTLESS_FRACTION} while the steps take too long. */
	static final float OVERLOADED_RESTLESS_FRACTION = 0.3f;
	/** Room made by freezing is kept for at most this many ticks of the sources' water. */
	static final int FREED_ROOM_TICKS = 4;
	/** Steps before a column that could not freeze is tried again. */
	static final int RETRY_STEPS = 10;
	public static final float CALM_SPEED = 0.5f;
	/** Steps a column has to stay calm before it freezes. */
	static final int CALM_STEPS = 20;
	/**
	 * A quarter of a layer of water faster than this within half a block above a frozen column thaws it,
	 * a block per step.
	 */
	public static final float RESTLESS_SPEED = 2f;
	/** Columns within this distance (blocks, horizontally) of the player's box thaw down to below their feet. */
	static final float PLAYER_REACH = 0.5f;
	/** Blocks below another entity down to which the water around it thaws. */
	static final float BODY_DEPTH = 1f;
	/** Blocks below the player's feet down to which the water around them thaws. */
	static final float PLAYER_DEPTH = 1.5f;
	/** Columns this close to a flowing source thaw down to below it. */
	static final int SOURCE_COLUMNS = 2;
	/** Steps without freezing around a block that changed. */
	static final int CHANGE_QUIET_STEPS = 40;
	/**
	 * Dormant water (beyond the simulated distance) wakes only this much closer than where it went to
	 * sleep, so water at the edge does not wake and sleep in turn.
	 */
	public static final float WAKE_MARGIN = 8f;
	/** How far down (blocks) parked water looks for the floor it settles on. */
	private static final int PARK_FALL = 64;

	private static final int[] DX = {-1, 1, 0, 0};
	private static final int[] DZ = {0, 0, -1, 1};

	/** What the volume needs to know about the blocks (coordinates relative to the origin). */
	public interface Blocks {
		/** Whether the block is a full solid cube. */
		boolean solid(int x, int y, int z);

		/** Whether water can be in the block (nothing to collide with). */
		boolean open(int x, int y, int z);
	}

	/** Everything else an update needs, relative to the origin. */
	public static final class Context {
		/** Whether the player touches the water, and their bounding box. */
		public boolean player;
		public float playerMinX;
		public float playerMinY;
		public float playerMinZ;
		public float playerMaxX;
		public float playerMaxY;
		public float playerMaxZ;
		/** The player's position and the squared distance beyond which water is removed. */
		public float playerX;
		public float playerY;
		public float playerZ;
		public float maxDistSq = Float.POSITIVE_INFINITY;
		/** Blocks of the flowing sources. */
		public int[] sourceX = new int[0];
		public int[] sourceY = new int[0];
		public int[] sourceZ = new int[0];
		/**
		 * Other entities in the water (min x, y, z, max x, y, z each): the water around them thaws down
		 * to below them, like around the player.
		 */
		public float[] bodies = new float[0];
		/** The GPU is at its limit: freeze sooner and keep fewer layers moving. */
		public boolean tight;
		/**
		 * The steps take longer than a game tick: water that only might move again (restless water
		 * above it, thin water on it) stays frozen, and moving water freezes too.
		 */
		public boolean overloaded;
	}

	/** One frozen column: {@code layers} particle layers of water on top of block {@code floor - 1}. */
	public static final class Column {
		public final int x;
		public final int z;
		/** Height of the floor (the top of the solid block the water lies on). */
		public int floor;
		public int layers;
		/** Particles the frozen water stands for. */
		public int count;
		/**
		 * Beyond the simulated distance: kept as it is (never thaws) until the player comes back within
		 * the distance less {@link #WAKE_MARGIN}.
		 */
		public boolean dormant;
		/** Per side (-x, +x, -z, +z): up to which height the neighbouring column is solid from the floor. */
		final float[] wallTop = new float[4];

		Column(int x, int z) {
			this.x = x;
			this.z = z;
		}

		/** Height of the frozen water's top. */
		public float top(float spacing) {
			return floor + layers * spacing;
		}

		/** Per side (-x, +x, -z, +z): up to which height the neighbouring column is solid from the floor. */
		public float wallTop(int side) {
			return wallTop[side];
		}
	}

	/**
	 * Part of a column that turns back into particles: layers {@code [fromLayer, toLayer)} with
	 * {@code particles} particles, or, with {@code drop}, the whole column is removed (too far away).
	 */
	public record Thaw(int x, int z, int floor, int fromLayer, int toLayer, int particles, boolean drop) {
	}

	/** A column that freezes up to {@code layers} once the next scan listed its particles. */
	private static final class Plan {
		final int x;
		final int z;
		int floor;
		int oldLayers;
		int layers;
		/** At most this fraction of the water that freezes may be restless; negative: it must be still. */
		float restlessFraction;
		int active;
		final float[] wallTop = new float[4];

		Plan(int x, int z) {
			this.x = x;
			this.z = z;
		}
	}

	private final float spacing;
	private final int perLayer;
	/** Height of the lowest particle layer above a floor (PhysX's solid rest offset). */
	private final float firstLayer;
	/** The frozen water is drawn from this far above the floor (and its top): where the particles' share begins. */
	private final float drawOffset;
	private final Long2ObjectOpenHashMap<Column> columns = new Long2ObjectOpenHashMap<>();
	private Long2IntOpenHashMap calm = new Long2IntOpenHashMap();
	private Long2IntOpenHashMap calmNext = new Long2IntOpenHashMap();
	private final Long2ObjectOpenHashMap<Plan> plans = new Long2ObjectOpenHashMap<>();
	private final Long2IntOpenHashMap quiet = new Long2IntOpenHashMap();
	private final LongArrayList changes = new LongArrayList();
	private final LongOpenHashSet dirtyGroups = new LongOpenHashSet();
	private long frozenParticles;
	/** Particles taken out by freezing and not yet handed out as room for new water ({@link #takeFreedRoom}). */
	private long freedRoom;
	private long version;
	private float[] boxes = new float[0];
	private long boxesVersion = -1;

	/**
	 * @param spacing    distance between neighbouring particles
	 * @param perAxis    particles per block along an axis
	 * @param firstLayer height of the lowest particle layer above a floor
	 */
	public FrozenColumns(float spacing, int perAxis, float firstLayer) {
		this.spacing = spacing;
		this.perLayer = perAxis * perAxis;
		this.firstLayer = firstLayer;
		this.drawOffset = firstLayer - 0.5f * spacing;
	}

	public static long key(int x, int z) {
		return ((long) x << 32) | (z & 0xFFFFFFFFL);
	}

	/** Key of the 16x16 group of columns column {@code x, z} belongs to (one collision actor each). */
	public static long group(int x, int z) {
		return key(x >> 4, z >> 4);
	}

	public boolean isEmpty() {
		return columns.isEmpty();
	}

	public int columnCount() {
		return columns.size();
	}

	/** Particles the frozen water stands for. */
	public long frozenParticles() {
		return frozenParticles;
	}

	/**
	 * Room for new water made by freezing since the last call, at most {@code wanted}: at the GPU's limit
	 * the sources pour as much as froze.
	 */
	public int takeFreedRoom(int wanted) {
		int room = (int) Math.min(wanted, freedRoom);
		// Room not taken soon is not kept: freezing a lot at once must not let the sources pour a lot
		// at once later, when the GPU may have no time for it.
		freedRoom = Math.min(freedRoom - room, (long) wanted * FREED_ROOM_TICKS);
		return room;
	}


	/** Changes whenever a column changes. */
	public long version() {
		return version;
	}

	public Column get(int x, int z) {
		return columns.get(key(x, z));
	}

	public Iterable<Column> columns() {
		return columns.values();
	}

	/** Groups ({@link #group}) whose columns changed since the last call. */
	public long[] takeDirtyGroups() {
		long[] groups = dirtyGroups.toLongArray();
		dirtyGroups.clear();
		return groups;
	}

	public boolean hasPlans() {
		return !plans.isEmpty();
	}

	/**
	 * A block changed: frozen water next to it thaws with the next {@link #thaw}.
	 *
	 * @return whether frozen water is next to it
	 */
	public boolean blockChanged(int x, int y, int z) {
		boolean near = false;
		for (int dx = -1; dx <= 1 && !near; dx++) {
			for (int dz = -1; dz <= 1 && !near; dz++) {
				Column c = columns.get(key(x + dx, z + dz));
				near = c != null && y >= c.floor - 1 && y <= c.top(spacing) + 1;
			}
		}
		for (int dx = -2; dx <= 2; dx++) {
			for (int dz = -2; dz <= 2; dz++) {
				quiet.put(key(x + dx, z + dz), CHANGE_QUIET_STEPS);
			}
		}
		if (near) {
			changes.add(net.minecraft.core.BlockPos.asLong(x, y, z));
		}
		return near;
	}

	/** Whether a block change is waiting to thaw frozen water. */
	public boolean hasChanges() {
		return !changes.isEmpty();
	}

	/** Whether frozen water is in or right next to a box. */
	public boolean touches(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
		for (int x = Mth.floor(minX) - 1; x <= Mth.floor(maxX) + 1; x++) {
			for (int z = Mth.floor(minZ) - 1; z <= Mth.floor(maxZ) + 1; z++) {
				Column c = columns.get(key(x, z));
				if (c != null && c.top(spacing) + 1f > minY && c.floor < maxY) {
					return true;
				}
			}
		}
		return false;
	}

	/** Box around the frozen water: min x, y, z, max x, y, z; null without any. */
	public float[] box() {
		if (columns.isEmpty()) {
			return null;
		}
		float[] b = {Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY,
			Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY};
		for (Column c : columns.values()) {
			b[0] = Math.min(b[0], c.x);
			b[1] = Math.min(b[1], c.floor);
			b[2] = Math.min(b[2], c.z);
			b[3] = Math.max(b[3], c.x + 1);
			b[4] = Math.max(b[4], c.top(spacing));
			b[5] = Math.max(b[5], c.z + 1);
		}
		return b;
	}

	/** Box around the frozen columns: min x, max x (exclusive), min z, max z (exclusive); null without any. */
	public int[] bounds() {
		if (columns.isEmpty()) {
			return null;
		}
		int minX = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE;
		int minZ = Integer.MAX_VALUE;
		int maxZ = Integer.MIN_VALUE;
		for (Column c : columns.values()) {
			minX = Math.min(minX, c.x);
			maxX = Math.max(maxX, c.x + 1);
			minZ = Math.min(minZ, c.z);
			maxZ = Math.max(maxZ, c.z + 1);
		}
		return new int[] {minX, maxX, minZ, maxZ};
	}

	// ------------------------------------------------------------------------------------------
	// Thawing
	// ------------------------------------------------------------------------------------------

	/**
	 * What has to turn back into particles now, judged by the latest scan (see the class comment).
	 * Nothing changes until {@link #applyThaw}; parts that do not fit into the simulation can wait.
	 * Block changes are used up.
	 *
	 * @param stats the latest scan, measuring the columns around the water ({@link ParticleScan.Part#cell})
	 */
	public List<Thaw> thaw(ParticleScan.Part stats, Context ctx) {
		List<Thaw> thaws = new ArrayList<>();
		if (columns.isEmpty()) {
			changes.clear();
			return thaws;
		}
		Long2IntOpenHashMap target = new Long2IntOpenHashMap();
		target.defaultReturnValue(Integer.MAX_VALUE);

		for (int k = 0; k < changes.size(); k++) {
			long pos = changes.getLong(k);
			int bx = net.minecraft.core.BlockPos.getX(pos);
			int by = net.minecraft.core.BlockPos.getY(pos);
			int bz = net.minecraft.core.BlockPos.getZ(pos);
			for (int dx = -1; dx <= 1; dx++) {
				for (int dz = -1; dz <= 1; dz++) {
					Column c = columns.get(key(bx + dx, bz + dz));
					if (c != null && by >= c.floor - 1 && by <= c.top(spacing) + 1) {
						target.put(key(c.x, c.z), 0);
					}
				}
			}
		}
		changes.clear();

		for (int b = 0; b + 5 < ctx.bodies.length; b += 6) {
			for (int x = Mth.floor(ctx.bodies[b] - PLAYER_REACH); x <= Mth.floor(ctx.bodies[b + 3] + PLAYER_REACH); x++) {
				for (int z = Mth.floor(ctx.bodies[b + 2] - PLAYER_REACH); z <= Mth.floor(ctx.bodies[b + 5] + PLAYER_REACH); z++) {
					Column c = columns.get(key(x, z));
					if (c != null) {
						lower(target, c, layersBelow(c, ctx.bodies[b + 1] - BODY_DEPTH));
					}
				}
			}
		}
		if (ctx.player) {
			int x0 = Mth.floor(ctx.playerMinX - PLAYER_REACH);
			int x1 = Mth.floor(ctx.playerMaxX + PLAYER_REACH);
			int z0 = Mth.floor(ctx.playerMinZ - PLAYER_REACH);
			int z1 = Mth.floor(ctx.playerMaxZ + PLAYER_REACH);
			for (int x = x0; x <= x1; x++) {
				for (int z = z0; z <= z1; z++) {
					Column c = columns.get(key(x, z));
					if (c != null) {
						lower(target, c, layersBelow(c, ctx.playerMinY - PLAYER_DEPTH));
					}
				}
			}
		}
		for (int k = 0; k < ctx.sourceX.length; k++) {
			for (int dx = -SOURCE_COLUMNS; dx <= SOURCE_COLUMNS; dx++) {
				for (int dz = -SOURCE_COLUMNS; dz <= SOURCE_COLUMNS; dz++) {
					Column c = columns.get(key(ctx.sourceX[k] + dx, ctx.sourceZ[k] + dz));
					if (c != null && layersBelow(c, ctx.sourceY[k] - 1) < c.layers) {
						lower(target, c, layersBelow(c, ctx.sourceY[k] - 1));
					}
				}
			}
		}

		boolean measured = stats.gridSizeX > 0 && stats.gridSizeZ > 0;
		int active = ctx.tight ? TIGHT_ACTIVE_LAYERS : ACTIVE_LAYERS;
		for (Column c : columns.values()) {
			// Far away it sleeps, as it is (nothing there is simulated), until the player comes back.
			if (updateDormant(c, ctx)) {
				continue;
			}
			if (!measured) {
				continue;
			}
			float top = c.top(spacing);
			int i = stats.cell(c.x, c.z);
			if (i < 0 || stats.colCount[i] == 0 || stats.colMinY[i] > top + firstLayer + spacing) {
				// Nothing lies on the frozen water any more: it is open to the air and can flow.
				lower(target, c, 0);
				continue;
			}
			float surface = surface(stats, i);
			if (!ctx.overloaded && surface > Float.NEGATIVE_INFINITY && surface - top < THAW_BELOW_LAYERS * spacing) {
				lower(target, c, layersBelow(c, surface - active * spacing));
			}
			if (!ctx.overloaded && restless(stats, c.x, c.z, ctx.tight)) {
				lower(target, c, c.layers - active);
			}
			for (int d = 0; d < 4; d++) {
				float wall = c.wallTop[d];
				if (wall >= top - 1e-4f) {
					continue;
				}
				float[] water = neighbourWater(stats, c.x + DX[d], c.z + DZ[d]);
				if (water == null || water[0] > wall + spacing) {
					// No water beside the frozen water above the wall: it would flow out there.
					lower(target, c, layersBelow(c, wall));
				} else if (water[1] > Float.NEGATIVE_INFINITY && water[1] < top - spacing) {
					// The water beside it is lower: it would flow there, and the frozen water's side would show.
					lower(target, c, layersBelow(c, water[1] - active * spacing));
				}
			}
		}

		for (Long2IntOpenHashMap.Entry e : target.long2IntEntrySet()) {
			Column c = columns.get(e.getLongKey());
			int to = e.getIntValue();
			if (c == null || c.dormant || to >= c.layers) {
				continue;
			}
			if (to < 0) {
				thaws.add(new Thaw(c.x, c.z, c.floor, 0, c.layers, 0, true));
				continue;
			}
			to = Math.max(0, to);
			int particles = to == 0 ? c.count : (int) Math.round((double) c.count * (c.layers - to) / c.layers);
			thaws.add(new Thaw(c.x, c.z, c.floor, to, c.layers, particles, false));
		}
		return thaws;
	}

	/** Everything near enough thaws (the water above is gone entirely); dormant water stays asleep. */
	public List<Thaw> thawAll(Context ctx) {
		List<Thaw> thaws = new ArrayList<>();
		for (Column c : columns.values()) {
			if (updateDormant(c, ctx)) {
				continue;
			}
			thaws.add(new Thaw(c.x, c.z, c.floor, 0, c.layers, c.count, false));
		}
		return thaws;
	}

	/** Applies a {@link Thaw} from {@link #thaw} once its particles are back in the simulation. */
	public void applyThaw(Thaw thaw) {
		long key = key(thaw.x(), thaw.z());
		Column c = columns.get(key);
		if (c == null || c.layers != thaw.toLayer()) {
			return;
		}
		if (thaw.drop() || thaw.fromLayer() <= 0) {
			columns.remove(key);
			frozenParticles -= c.count;
		} else {
			c.layers = thaw.fromLayer();
			c.count -= thaw.particles();
			frozenParticles -= thaw.particles();
		}
		changed(c.x, c.z);
	}

	private static void lower(Long2IntOpenHashMap target, Column c, int layers) {
		long key = key(c.x, c.z);
		int current = target.get(key);
		if (current < 0) {
			return; // removed anyway
		}
		target.put(key, Math.min(current, Math.max(0, layers)));
	}

	/** Frozen layers of a column that lie below height {@code y}. */
	private int layersBelow(Column c, float y) {
		return Math.max(0, (int) Math.floor((y - c.floor) / spacing + 1e-4f));
	}

	/** Whether the water right above the frozen water of column {@code x, z} moves fast. */
	private boolean restless(ParticleScan.Part stats, int x, int z, boolean tight) {
		int i = stats.cell(x, z);
		return i >= 0 && stats.colNearRestless[i] >= Math.max(1, perLayer);
	}

	/**
	 * Height of the surface of the water in measured column {@code i}: the top of its
	 * {@link ParticleScan#TOP}-th highest particle that is not restless, so drops flying above and a
	 * stream falling through do not count. Negative infinity when all its water is restless.
	 */
	private float surface(ParticleScan.Part stats, int i) {
		return ParticleScan.surface(stats, i) + 0.5f * spacing;
	}

	/**
	 * The water in column {@code x, z}, frozen and not: its bottom and its surface (negative infinity
	 * when all of it is restless, as where a stream lands); null without water.
	 */
	private float[] neighbourWater(ParticleScan.Part stats, int x, int z) {
		Column n = columns.get(key(x, z));
		int i = stats.cell(x, z);
		boolean particles = i >= 0 && stats.colCount[i] > 0;
		if (n != null) {
			float surface = n.top(spacing);
			if (particles && stats.colMinY[i] <= surface + firstLayer + spacing) {
				surface = Math.max(surface, surface(stats, i));
			}
			return new float[] {n.floor, surface};
		}
		if (particles) {
			return new float[] {stats.colMinY[i] - firstLayer, surface(stats, i)};
		}
		return null;
	}

	// ------------------------------------------------------------------------------------------
	// Freezing
	// ------------------------------------------------------------------------------------------

	/**
	 * Picks the calm columns that can freeze some more layers, judged by the latest scan. Their
	 * particles are listed by the next scan ({@link #freezeBelow}) and taken out by {@link #commit}.
	 */
	public void plan(ParticleScan.Part stats, Blocks blocks, Context ctx) {
		plans.clear();
		// Fewer steps of quiet left around changed blocks.
		if (!quiet.isEmpty()) {
			for (it.unimi.dsi.fastutil.objects.ObjectIterator<Long2IntOpenHashMap.Entry> it = quiet.long2IntEntrySet().iterator(); it.hasNext(); ) {
				Long2IntOpenHashMap.Entry e = it.next();
				if (e.getIntValue() <= 1) {
					it.remove();
				} else {
					e.setValue(e.getIntValue() - 1);
				}
			}
		}
		calmNext.clear();
		if (stats.gridSizeX <= 0 || stats.gridSizeZ <= 0) {
			swapCalm();
			return;
		}
		int active = ctx.tight ? TIGHT_ACTIVE_LAYERS : ACTIVE_LAYERS;
		for (int gx = 0; gx < stats.gridSizeX; gx++) {
			for (int gz = 0; gz < stats.gridSizeZ; gz++) {
				int i = gx * stats.gridSizeZ + gz;
				int n = stats.colCount[i];
				if (n == 0) {
					continue;
				}
				int x = stats.gridX0 + gx;
				int z = stats.gridZ0 + gz;
				long key = key(x, z);
				if (stats.colFast[i] <= CALM_FRACTION * n) {
					calmNext.put(key, calm.get(key) + 1);
				}
				// Normally only water that has been still for a while; at the GPU's limit also water that
				// moves, deep down, if the particles that would freeze are not restless (see commit).
				if ((!ctx.tight && calmNext.get(key) < CALM_STEPS) || quiet.containsKey(key) || nearPlayer(ctx, x, z)) {
					continue;
				}
				planColumn(stats, blocks, ctx, i, x, z, active);
			}
		}
		swapCalm();
	}

	private void swapCalm() {
		Long2IntOpenHashMap old = calm;
		calm = calmNext;
		calmNext = old;
	}

	private static boolean nearPlayer(Context ctx, int x, int z) {
		if (!ctx.player) {
			return false;
		}
		float margin = PLAYER_REACH + 1f;
		return x >= Mth.floor(ctx.playerMinX - margin) && x <= Mth.floor(ctx.playerMaxX + margin)
			&& z >= Mth.floor(ctx.playerMinZ - margin) && z <= Mth.floor(ctx.playerMaxZ + margin);
	}

	private void planColumn(ParticleScan.Part stats, Blocks blocks, Context ctx, int i, int x, int z, int active) {
		float minY = stats.colMinY[i];
		Column c = columns.get(key(x, z));
		int floor;
		int oldLayers;
		float base;
		if (c != null) {
			floor = c.floor;
			oldLayers = c.layers;
			base = c.top(spacing);
			if (minY > base + firstLayer + spacing) {
				return; // not lying on the frozen water
			}
		} else {
			// The lowest particles must lie on a full block.
			// (Deep water presses its lowest particles a little into the floor.)
			floor = Math.round(minY);
			if (minY - floor > firstLayer + spacing || !blocks.solid(x, floor - 1, z)) {
				return;
			}
			oldLayers = 0;
			base = floor;
		}
		float surface = surface(stats, i);
		if (!(surface > base)) {
			return;
		}
		int depthLayers = (int) Math.floor((surface - base) / spacing);
		int add = depthLayers - active;
		if (add < MIN_FREEZE_LAYERS) {
			return;
		}
		int layers = oldLayers + add;
		// Not up to right below another entity: the water around it thaws down to there.
		for (int b = 0; b + 5 < ctx.bodies.length; b += 6) {
			if (x >= Mth.floor(ctx.bodies[b] - PLAYER_REACH) && x <= Mth.floor(ctx.bodies[b + 3] + PLAYER_REACH)
				&& z >= Mth.floor(ctx.bodies[b + 2] - PLAYER_REACH) && z <= Mth.floor(ctx.bodies[b + 5] + PLAYER_REACH)) {
				layers = Math.min(layers, (int) Math.floor((ctx.bodies[b + 1] - BODY_DEPTH - spacing - floor) / spacing));
			}
		}
		// Not up to a flowing source: the water around it has to stay free to flow.
		int margin = SOURCE_COLUMNS + 1;
		for (int k = 0; k < ctx.sourceX.length; k++) {
			if (Math.abs(ctx.sourceX[k] - x) <= margin && Math.abs(ctx.sourceZ[k] - z) <= margin) {
				layers = Math.min(layers, (int) Math.floor((ctx.sourceY[k] - 2 - floor) / spacing + 1e-4f));
			}
		}
		if (layers - oldLayers < MIN_FREEZE_LAYERS) {
			return;
		}
		float top = floor + layers * spacing;
		for (int y = Mth.floor(base); y < Mth.ceil(top); y++) {
			if (!blocks.open(x, y, z)) {
				return;
			}
		}
		Plan plan = new Plan(x, z);
		for (int d = 0; d < 4; d++) {
			int nx = x + DX[d];
			int nz = z + DZ[d];
			int wall = floor;
			while (wall < top && blocks.solid(nx, wall, nz)) {
				wall++;
			}
			plan.wallTop[d] = wall;
			if (wall >= top) {
				continue;
			}
			// Beside it there must be water resting on that wall, up to above the new top: otherwise the
			// frozen water's side would show. Lower water beside it lowers the new top.
			float[] water = neighbourWater(stats, nx, nz);
			float limit = water == null || water[0] > wall + spacing || !(water[1] > Float.NEGATIVE_INFINITY)
				? wall : Math.max(wall, water[1] - spacing);
			if (limit < top) {
				layers = Math.min(layers, (int) Math.floor((limit - floor) / spacing + 1e-4f));
				top = floor + layers * spacing;
			}
		}
		if (layers - oldLayers < MIN_FREEZE_LAYERS) {
			return;
		}
		plan.floor = floor;
		plan.oldLayers = oldLayers;
		plan.layers = layers;
		plan.active = active;
		plan.restlessFraction = ctx.overloaded ? OVERLOADED_RESTLESS_FRACTION : ctx.tight ? TIGHT_RESTLESS_FRACTION : -1f;
		plans.put(key(x, z), plan);
	}

	/**
	 * Sets up the next scan, whose grid {@code s} already has: the heights below which it lists the
	 * particles of the planned columns (plans outside the grid are dropped) and the frozen tops.
	 */
	public void prepareScan(ParticleScan.Settings s) {
		s.freezeBelow = null;
		s.frozenTop = null;
		int sx = s.gridSizeX;
		int sz = s.gridSizeZ;
		if (sx <= 0 || sz <= 0) {
			plans.clear();
			return;
		}
		if (!columns.isEmpty()) {
			float[] tops = new float[sx * sz];
			java.util.Arrays.fill(tops, Float.NEGATIVE_INFINITY);
			for (Column c : columns.values()) {
				int gx = c.x - s.gridX0;
				int gz = c.z - s.gridZ0;
				if (gx >= 0 && gx < sx && gz >= 0 && gz < sz) {
					tops[gx * sz + gz] = c.top(spacing);
				}
			}
			s.frozenTop = tops;
		}
		if (plans.isEmpty()) {
			return;
		}
		float[] below = new float[sx * sz];
		java.util.Arrays.fill(below, Float.NEGATIVE_INFINITY);
		for (it.unimi.dsi.fastutil.objects.ObjectIterator<Plan> it = plans.values().iterator(); it.hasNext(); ) {
			Plan p = it.next();
			int gx = p.x - s.gridX0;
			int gz = p.z - s.gridZ0;
			if (gx < 0 || gx >= sx || gz < 0 || gz >= sz) {
				it.remove();
				continue;
			}
			below[gx * sz + gz] = p.floor + p.layers * spacing;
		}
		if (!plans.isEmpty()) {
			s.freezeBelow = below;
		}
	}

	/**
	 * Freezes the planned columns that are still calm in the scan that listed their particles and
	 * whose listed particles fill the new layers; adds those particles to {@code remove} (ascending).
	 *
	 * @param stats    the scan that listed the particles ({@link ParticleScan.Part#freezing})
	 * @param pos      particle positions of that scan
	 * @param remove   receives the particles to take out of the simulation
	 */
	public void commit(ParticleScan.Part stats, float[] pos, int stride, IntArrayList remove) {
		if (plans.isEmpty()) {
			return;
		}
		IntArrayList listed = stats.freezing;
		Long2IntOpenHashMap found = new Long2IntOpenHashMap();
		int[] indices = listed.elements();
		for (int k = 0, size = listed.size(); k < size; k++) {
			int o = indices[k] * stride;
			found.addTo(key(Mth.floor(pos[o]), Mth.floor(pos[o + 2])), 1);
		}
		LongOpenHashSet valid = new LongOpenHashSet();
		for (Plan p : plans.values()) {
			long key = key(p.x, p.z);
			Column c = columns.get(key);
			if (c != null ? c.layers != p.oldLayers || c.floor != p.floor : p.oldLayers != 0) {
				continue;
			}
			int i = stats.cell(p.x, p.z);
			int expected = (p.layers - p.oldLayers) * perLayer;
			int n = found.get(key);
			// Full of water (no air in it), and enough water left on top of it.
			int above = i < 0 ? 0 : stats.colCount[i] - n;
			int minAbove = THAW_BELOW_LAYERS * perLayer;
			if (i < 0 || n < 0.8f * expected || n > 3f * expected || above < minAbove) {
				quiet.put(key, RETRY_STEPS);
				continue;
			}
			// The water that freezes has to be still, or, at the GPU's limit, at least not restless.
			if (p.restlessFraction >= 0f ? stats.colFreezeRestless[i] > p.restlessFraction * n : stats.colFreezeFast[i] > CALM_FRACTION * n) {
				quiet.put(key, RETRY_STEPS);
				continue;
			}
			valid.add(key);
			if (c == null) {
				c = new Column(p.x, p.z);
				columns.put(key, c);
			}
			c.floor = p.floor;
			c.layers = p.layers;
			c.count += n;
			System.arraycopy(p.wallTop, 0, c.wallTop, 0, 4);
			frozenParticles += n;
			freedRoom += n;
			changed(p.x, p.z);
		}
		plans.clear();
		if (valid.isEmpty()) {
			return;
		}
		for (int k = 0, size = listed.size(); k < size; k++) {
			int o = indices[k] * stride;
			if (valid.contains(key(Mth.floor(pos[o]), Mth.floor(pos[o + 2])))) {
				remove.add(indices[k]);
			}
		}
	}

	// ------------------------------------------------------------------------------------------
	// Drawing
	// ------------------------------------------------------------------------------------------

	/**
	 * The frozen water as boxes for the surface mesh, six floats each (min x, y, z, max x, y, z): where
	 * its particles would count towards the water's density. Towards walls they start as far from the
	 * wall as the particles would. The array is replaced, never changed.
	 */
	public float[] boxes() {
		if (boxesVersion == version) {
			return boxes;
		}
		float[] out = new float[columns.size() * 6];
		int k = 0;
		for (Column c : columns.values()) {
			float top = c.top(spacing);
			out[k++] = c.x + (c.wallTop[0] >= top ? drawOffset : 0f);
			out[k++] = c.floor + drawOffset;
			out[k++] = c.z + (c.wallTop[2] >= top ? drawOffset : 0f);
			out[k++] = c.x + 1 - (c.wallTop[1] >= top ? drawOffset : 0f);
			out[k++] = top + drawOffset;
			out[k++] = c.z + 1 - (c.wallTop[3] >= top ? drawOffset : 0f);
		}
		boxes = out;
		boxesVersion = version;
		return boxes;
	}

	/** Whether {@code c} is (or now becomes) dormant; dormant water wakes {@link #WAKE_MARGIN} inside the distance. */
	private boolean updateDormant(Column c, Context ctx) {
		float cx = c.x + 0.5f - ctx.playerX;
		float cy = c.floor + 0.5f * c.layers * spacing - ctx.playerY;
		float cz = c.z + 0.5f - ctx.playerZ;
		float distSq = cx * cx + cy * cy + cz * cz;
		if (distSq > ctx.maxDistSq) {
			c.dormant = true;
		} else if (c.dormant) {
			float wake = Math.max(0f, (float) Math.sqrt(ctx.maxDistSq) - WAKE_MARGIN);
			if (distSq < wake * wake) {
				c.dormant = false;
			}
		}
		return c.dormant;
	}

	/**
	 * Water that left the simulated distance goes to sleep where it is: {@code count} particles in
	 * column {@code x, z} settle on the floor below {@code lowestY} (on top of what already sleeps there),
	 * dormant until the player comes back.
	 */
	public void park(int x, int z, int count, float lowestY, Blocks blocks) {
		if (count <= 0) {
			return;
		}
		long key = key(x, z);
		Column c = columns.get(key);
		if (c == null) {
			int start = Mth.floor(lowestY);
			int floor = start;
			while (floor > start - PARK_FALL && !blocks.solid(x, floor - 1, z)) {
				floor--;
			}
			if (floor <= start - PARK_FALL) {
				floor = start; // nothing found below (not loaded): it stays at its height
			}
			c = new Column(x, z);
			c.floor = floor;
			columns.put(key, c);
		}
		c.count += count;
		c.layers = Math.max(c.layers, Math.max(1, (c.count + perLayer - 1) / perLayer));
		c.dormant = true;
		float top = c.top(spacing);
		for (int d = 0; d < 4; d++) {
			int wall = c.floor;
			while (wall < top && blocks.solid(x + DX[d], wall, z + DZ[d])) {
				wall++;
			}
			c.wallTop[d] = wall;
		}
		frozenParticles += count;
		changed(x, z);
	}

	/**
	 * Takes the columns further than {@code distance} (horizontally) from {@code x, z} out of the volume
	 * (water too far away to be drawn): {@link #restore}'s arguments, {@code COLUMN} ints and four wall
	 * heights each, appended to {@code ints} and {@code walls}.
	 *
	 * @return how many were taken
	 */
	public int extract(float x, float z, float distance, it.unimi.dsi.fastutil.ints.IntArrayList ints,
		it.unimi.dsi.fastutil.floats.FloatArrayList walls) {
		float limit = distance * distance;
		int taken = 0;
		var it = columns.values().iterator();
		while (it.hasNext()) {
			Column c = it.next();
			float dx = c.x + 0.5f - x;
			float dz = c.z + 0.5f - z;
			if (dx * dx + dz * dz <= limit) {
				continue;
			}
			ints.add(c.x);
			ints.add(c.z);
			ints.add(c.floor);
			ints.add(c.layers);
			ints.add(c.count);
			for (int d = 0; d < 4; d++) {
				walls.add(c.wallTop[d]);
			}
			frozenParticles -= c.count;
			dirtyGroups.add(group(c.x, c.z));
			it.remove();
			taken++;
		}
		if (taken > 0) {
			version++;
		}
		return taken;
	}

	/**
	 * Puts back a column as it was saved ({@link Column}): {@code layers} layers of frozen water on the
	 * floor at {@code floor}, standing for {@code count} particles, with the heights of its walls. It
	 * comes back dormant and wakes when the player is near enough.
	 */
	public void restore(int x, int z, int floor, int layers, int count, float[] walls, int wallsOffset) {
		if (layers <= 0 || count <= 0) {
			return;
		}
		long key = key(x, z);
		Column c = columns.get(key);
		if (c != null) {
			frozenParticles -= c.count;
		} else {
			c = new Column(x, z);
			columns.put(key, c);
		}
		c.floor = floor;
		c.layers = layers;
		c.count = count;
		c.dormant = true;
		System.arraycopy(walls, wallsOffset, c.wallTop, 0, 4);
		frozenParticles += count;
		changed(x, z);
	}

	public void clear() {
		for (Column c : columns.values()) {
			dirtyGroups.add(group(c.x, c.z));
		}
		columns.clear();
		calm.clear();
		plans.clear();
		quiet.clear();
		changes.clear();
		frozenParticles = 0;
		freedRoom = 0;
		version++;
	}

	private void changed(int x, int z) {
		dirtyGroups.add(group(x, z));
		version++;
	}
}
