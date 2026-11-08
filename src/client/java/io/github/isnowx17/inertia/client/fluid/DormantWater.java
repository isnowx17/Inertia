package io.github.isnowx17.inertia.client.fluid;

import io.github.isnowx17.inertia.util.WaterSaveFile;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;

/**
 * Still water too far from the player to be simulated or drawn, kept per chunk in world coordinates
 * (as {@link io.github.isnowx17.inertia.util.FrozenColumns} columns: x, z, floor, layers, count and
 * four wall heights). It goes back into the simulation, asleep, once the player comes near again.
 * Saved with the world. Client thread.
 */
final class DormantWater {
	private static final int COLUMN = WaterSaveFile.COLUMN_INTS;

	private static final class Chunk {
		/** Particle spacing the counts and layers are for. */
		final float spacing;
		final IntArrayList ints = new IntArrayList();
		final FloatArrayList walls = new FloatArrayList();

		Chunk(float spacing) {
			this.spacing = spacing;
		}
	}

	private final Long2ObjectOpenHashMap<Chunk> chunks = new Long2ObjectOpenHashMap<>();
	private long version;

	boolean isEmpty() {
		return chunks.isEmpty();
	}

	/** Columns kept here. */
	int columnCount() {
		int n = 0;
		for (Chunk chunk : chunks.values()) {
			n += chunk.ints.size() / COLUMN;
		}
		return n;
	}

	/** Changes whenever water is added or taken. */
	long version() {
		return version;
	}

	void clear() {
		chunks.clear();
		version++;
	}

	/** Adds {@code count} columns (world coordinates) simulated with {@code spacing}. */
	void add(int[] ints, int offset, int count, float[] walls, int wallOffset, float spacing) {
		for (int k = 0; k < count; k++) {
			int o = offset + k * COLUMN;
			long key = ChunkPos.pack(ints[o] >> 4, ints[o + 1] >> 4);
			Chunk chunk = chunks.computeIfAbsent(key, kk -> new Chunk(spacing));
			double ratio = Math.pow(spacing / chunk.spacing, 3);
			chunk.ints.add(ints[o]);
			chunk.ints.add(ints[o + 1]);
			chunk.ints.add(ints[o + 2]);
			chunk.ints.add(Math.max(1, Math.round(ints[o + 3] * spacing / chunk.spacing)));
			chunk.ints.add((int) Math.max(1, Math.round(ints[o + 4] * ratio)));
			for (int d = 0; d < 4; d++) {
				chunk.walls.add(walls[wallOffset + k * 4 + d]);
			}
		}
		if (count > 0) {
			version++;
		}
	}

	/**
	 * Puts the water of the loaded chunks within {@code distance} blocks (horizontally) of {@code x, z}
	 * back into {@code sim}, except where {@code keepOut} (min x, z, max x, z, world; may be null)
	 * says the water is not ready yet.
	 *
	 * @return columns put back
	 */
	int wake(FluidSimulation sim, ClientLevel level, double x, double z, double distance, double[] keepOut) {
		int put = 0;
		var it = chunks.long2ObjectEntrySet().iterator();
		while (it.hasNext()) {
			var e = it.next();
			int cx = ChunkPos.getX(e.getLongKey());
			int cz = ChunkPos.getZ(e.getLongKey());
			double dx = (cx << 4) + 8 - x;
			double dz = (cz << 4) + 8 - z;
			if (dx * dx + dz * dz > distance * distance) {
				continue;
			}
			if (keepOut != null && (cx << 4) + 16 >= keepOut[0] && (cx << 4) <= keepOut[2] && (cz << 4) + 16 >= keepOut[1]
				&& (cz << 4) <= keepOut[3]) {
				continue;
			}
			Chunk chunk = e.getValue();
			int y = chunk.ints.getInt(2);
			if (!level.isLoaded(new BlockPos(cx << 4, y, cz << 4))) {
				continue;
			}
			int count = chunk.ints.size() / COLUMN;
			if (!sim.putColumns(chunk.ints.elements(), 0, count, chunk.walls.elements(), 0, chunk.spacing,
				new BlockPos(chunk.ints.getInt(0), y, chunk.ints.getInt(1)))) {
				return put; // no still water volume: it stays here
			}
			put += count;
			it.remove();
			version++;
		}
		return put;
	}

	/** Appends all of it for saving: local to {@code ox, oy, oz}, counts and layers for {@code spacing}. */
	int appendTo(IntArrayList ints, FloatArrayList walls, int ox, int oy, int oz, float spacing) {
		int n = 0;
		for (Chunk chunk : chunks.values()) {
			double ratio = Math.pow(chunk.spacing / spacing, 3);
			for (int o = 0; o < chunk.ints.size(); o += COLUMN) {
				ints.add(chunk.ints.getInt(o) - ox);
				ints.add(chunk.ints.getInt(o + 1) - oz);
				ints.add(chunk.ints.getInt(o + 2) - oy);
				ints.add(Math.max(1, Math.round(chunk.ints.getInt(o + 3) * chunk.spacing / spacing)));
				ints.add((int) Math.max(1, Math.round(chunk.ints.getInt(o + 4) * ratio)));
				int w = o / COLUMN * 4;
				for (int d = 0; d < 4; d++) {
					walls.add(chunk.walls.getFloat(w + d) - oy);
				}
				n++;
			}
		}
		return n;
	}
}
