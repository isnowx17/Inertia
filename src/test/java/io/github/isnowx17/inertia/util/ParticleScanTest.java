package io.github.isnowx17.inertia.util;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.Arrays;
import java.util.SplittableRandom;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import org.junit.jupiter.api.Test;

class ParticleScanTest {
	private static final int STRIDE = 4;
	private static final int PER_AXIS = 16;

	/** The scan as it ran before it was split into chunks: one pass over all particles. */
	private static final class Reference {
		final IntArrayList lost = new IntArrayList();
		final LongOpenHashSet blocks = new LongOpenHashSet();
		int moving;
		float farthestSq;
		int inside;
		double sumX;
		double sumY;
		double sumZ;
		long[][] slots;
		int[] counts;
		float[] sums;

		Reference(float[] pos, float[] vel, int count, ParticleScan.Settings s, int[] cx, int[] cy, int[] cz) {
			slots = new long[cx.length][SourceSlots.words(s.perAxis)];
			counts = new int[cx.length];
			sums = new float[cx.length * 3];
			int sample = s.sampleOffset;
			long lastBlock = Long.MIN_VALUE;
			for (int i = 0; i < count; i++) {
				int o = i * STRIDE;
				float x = pos[o];
				float y = pos[o + 1];
				float z = pos[o + 2];
				float dx = x - s.playerX;
				float dy = y - s.playerY;
				float dz = z - s.playerZ;
				boolean sampled = i == sample;
				if (sampled) {
					sample += s.sampleStride;
				}
				if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z) || y < s.minY
					|| dx * dx + dy * dy + dz * dz > s.maxDistSq) {
					lost.add(i);
					continue;
				}
				farthestSq = Math.max(farthestSq, dx * dx + dy * dy + dz * dz);
				if (vel[o] * vel[o] + vel[o + 1] * vel[o + 1] + vel[o + 2] * vel[o + 2] >= s.movingSpeedSq) {
					moving++;
				}
				if (s.measurePlayer && x >= s.shellMinX && x <= s.shellMaxX && y >= s.shellMinY && y <= s.shellMaxY
					&& z >= s.shellMinZ && z <= s.shellMaxZ) {
					inside++;
					sumX += vel[o];
					sumY += vel[o + 1];
					sumZ += vel[o + 2];
				}
				for (int k = 0; k < cx.length; k++) {
					int slot = SourceSlots.slot(x - cx[k], y - cy[k], z - cz[k], s.perAxis);
					if (slot >= 0) {
						SourceSlots.mark(slots[k], slot);
						counts[k]++;
						sums[k * 3] += vel[o];
						sums[k * 3 + 1] += vel[o + 1];
						sums[k * 3 + 2] += vel[o + 2];
						break;
					}
				}
				if (sampled) {
					long block = BlockPos.asLong(Mth.floor(x) + s.originX, Mth.floor(y) + s.originY, Mth.floor(z) + s.originZ);
					if (block != lastBlock) {
						lastBlock = block;
						blocks.add(block);
					}
				}
			}
		}
	}

	@Test
	void chunkedScanMatchesOnePass() {
		SplittableRandom random = new SplittableRandom(7);
		int count = 200_003;
		float[] pos = new float[count * STRIDE];
		float[] vel = new float[count * STRIDE];
		for (int i = 0; i < count; i++) {
			// Water around the origin, some of it far away, below the world or broken.
			pos[i * STRIDE] = (float) random.nextDouble(-12, 12);
			pos[i * STRIDE + 1] = (float) random.nextDouble(-3, 9);
			pos[i * STRIDE + 2] = (float) random.nextDouble(-12, 12);
			int r = random.nextInt(1000);
			if (r == 0) {
				pos[i * STRIDE + 1] = Float.NaN;
			} else if (r == 1) {
				pos[i * STRIDE] = 500f;
			} else if (r == 2) {
				pos[i * STRIDE + 1] = -100f;
			}
			vel[i * STRIDE] = (float) random.nextDouble(-3, 3);
			vel[i * STRIDE + 1] = (float) random.nextDouble(-3, 3);
			vel[i * STRIDE + 2] = (float) random.nextDouble(-3, 3);
		}
		ParticleScan.Settings s = new ParticleScan.Settings();
		s.minY = -50f;
		s.playerX = 1.5f;
		s.playerY = 0f;
		s.playerZ = -2f;
		s.maxDistSq = 96f * 96f;
		s.measurePlayer = true;
		s.shellMinX = 0.5f;
		s.shellMinY = -0.5f;
		s.shellMinZ = -3f;
		s.shellMaxX = 2.5f;
		s.shellMaxY = 2.3f;
		s.shellMaxZ = -1f;
		s.sampleStride = 3;
		s.sampleOffset = 2;
		s.originX = 1024;
		s.originY = 64;
		s.originZ = -2048;
		s.perAxis = PER_AXIS;
		s.movingSpeedSq = 4f;
		int[] cx = {0, 3, -5};
		int[] cy = {2, 2, 0};
		int[] cz = {0, -1, 4};
		s.setSources(cx, cy, cz);
		s.measureColumns = true;

		Reference expected = new Reference(pos, vel, count, s, cx, cy, cz);
		// Water per column and the highest particle in every source block, counted directly.
		it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap columns = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();
		float[] tops = new float[cx.length];
		java.util.Arrays.fill(tops, Float.NEGATIVE_INFINITY);
		it.unimi.dsi.fastutil.ints.IntOpenHashSet lostSet = new it.unimi.dsi.fastutil.ints.IntOpenHashSet(expected.lost);
		for (int i = 0; i < count; i++) {
			if (lostSet.contains(i)) {
				continue;
			}
			float x = pos[i * STRIDE];
			float y = pos[i * STRIDE + 1];
			float z = pos[i * STRIDE + 2];
			columns.addTo(ParticleScan.column(x, z), 1);
			for (int k = 0; k < cx.length; k++) {
				if (SourceSlots.slot(x - cx[k], y - cy[k], z - cz[k], PER_AXIS) >= 0) {
					tops[k] = Math.max(tops[k], y - cy[k]);
					break;
				}
			}
		}

		for (int chunks : new int[] {1, 2, 7, 64}) {
			ParticleScan.Part total = new ParticleScan.Part();
			total.reset(cx.length, PER_AXIS);
			ParticleScan.Part part = new ParticleScan.Part();
			for (int c = 0; c < chunks; c++) {
				int from = (int) ((long) count * c / chunks);
				int to = (int) ((long) count * (c + 1) / chunks);
				part.reset(cx.length, PER_AXIS);
				ParticleScan.scan(pos, vel, STRIDE, from, to, s, part);
				ParticleScan.merge(total, part);
			}
			assertEquals(expected.lost, total.lost, chunks + " chunks: lost particles");
			assertEquals(expected.blocks, total.blocks, chunks + " chunks: blocks");
			assertEquals(expected.inside, total.inside, chunks + " chunks: player water");
			assertEquals(expected.moving, total.moving, chunks + " chunks: moving water");
			assertEquals(expected.farthestSq, total.farthestSq, chunks + " chunks: farthest water");
			assertEquals(expected.sumX, total.sumX, 1e-6 * Math.max(1, Math.abs(expected.sumX)));
			assertEquals(expected.sumY, total.sumY, 1e-6 * Math.max(1, Math.abs(expected.sumY)));
			assertEquals(expected.sumZ, total.sumZ, 1e-6 * Math.max(1, Math.abs(expected.sumZ)));
			assertTrue(expected.inside > 0 && expected.lost.size() > 0, "the test data covers both cases");
			assertEquals(columns, total.columns, chunks + " chunks: water per column");
			for (int k = 0; k < cx.length; k++) {
				assertEquals(tops[k], total.sourceTop[k], chunks + " chunks: top of source " + k);
			}
			for (int k = 0; k < cx.length; k++) {
				assertArrayEquals(expected.slots[k], total.sourceSlots[k], chunks + " chunks: source places " + k);
				assertEquals(expected.counts[k], total.sourceCount[k], chunks + " chunks: source count " + k);
				assertTrue(expected.counts[k] > 0, "source " + k + " holds water");
				for (int a = 0; a < 3; a++) {
					assertEquals(expected.sums[k * 3 + a], total.sourceSum[k * 3 + a], 1e-3f * Math.max(1f, Math.abs(expected.sums[k * 3 + a])));
				}
			}
		}
	}

	/**
	 * Removing particles moves the last particle into every hole; uploading only the ranges
	 * {@link ParticleScan#uploadRuns} returns must leave the GPU copy equal to the host copy.
	 */
	@Test
	void uploadRunsCoverEveryChangedParticle() {
		SplittableRandom random = new SplittableRandom(11);
		for (int round = 0; round < 400; round++) {
			int active = 1 + random.nextInt(20_000);
			int[] host = new int[active];
			for (int i = 0; i < active; i++) {
				host[i] = i;
			}
			int[] gpu = host.clone();
			// Lost particles: a few, many, clustered or spread out, sometimes the last ones.
			int lostCount = random.nextInt(Math.min(active, round % 4 == 0 ? 3_000 : 40) + 1);
			int[] lost = random.ints(lostCount, 0, active).distinct().sorted().toArray();
			int count = active;
			for (int k = lost.length - 1; k >= 0; k--) {
				int i = lost[k];
				if (i >= count) {
					continue;
				}
				host[i] = host[count - 1];
				count--;
			}
			int[] runs = ParticleScan.uploadRuns(lost, lost.length, count, 1 + random.nextInt(64), 1 + random.nextInt(8));
			for (int r = 0; r < runs.length; r += 2) {
				assertTrue(runs[r] < runs[r + 1] && runs[r + 1] <= count, "range inside the particles");
				System.arraycopy(host, runs[r], gpu, runs[r], runs[r + 1] - runs[r]);
			}
			assertArrayEquals(Arrays.copyOf(host, count), Arrays.copyOf(gpu, count), "round " + round);
		}
	}

	@Test
	void measuresTheWaterAroundBodies() {
		// Particles on a 0.1 lattice in [0, 4) x [0, 1) x [0, 4), all moving +x at 2 blocks/second.
		int n = 40 * 10 * 40;
		float[] pos = new float[n * 4];
		float[] vel = new float[n * 4];
		int i = 0;
		for (int x = 0; x < 40; x++) {
			for (int y = 0; y < 10; y++) {
				for (int z = 0; z < 40; z++) {
					pos[i * 4] = x * 0.1f + 0.05f;
					pos[i * 4 + 1] = y * 0.1f + 0.05f;
					pos[i * 4 + 2] = z * 0.1f + 0.05f;
					vel[i * 4] = 2f;
					i++;
				}
			}
		}
		float[] shells = {0f, 0f, 0f, 1f, 1f, 1f, 2f, 0.5f, 2f, 2.5f, 2f, 2.5f, 10f, 10f, 10f, 11f, 11f, 11f};
		for (boolean grid : new boolean[] {false, true}) {
			ParticleScan.Settings s = new ParticleScan.Settings();
			s.minY = -100f;
			s.maxDistSq = 1e9f;
			s.perAxis = 10;
			if (grid) {
				s.gridX0 = -1;
				s.gridZ0 = -1;
				s.gridSizeX = 6;
				s.gridSizeZ = 6;
			}
			s.setBodies(shells, 3);
			ParticleScan.Part total = new ParticleScan.Part();
			total.reset(0, 10, s);
			for (int c = 0; c < 4; c++) {
				ParticleScan.Part part = new ParticleScan.Part();
				part.reset(0, 10, s);
				ParticleScan.scan(pos, vel, 4, c * n / 4, (c + 1) * n / 4, s, part);
				ParticleScan.merge(total, part);
			}
			assertEquals(10 * 10 * 10, total.bodyInside[0], "grid " + grid);
			assertEquals(5 * 5 * 5, total.bodyInside[1], "grid " + grid);
			assertEquals(0, total.bodyInside[2], "grid " + grid);
			assertEquals(2f * 1000, total.bodySum[0], 1e-2f);
			assertEquals(0f, total.bodySum[1], 1e-6f);
		}
	}
}
