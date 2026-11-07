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

		Reference expected = new Reference(pos, vel, count, s, cx, cy, cz);

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
}
