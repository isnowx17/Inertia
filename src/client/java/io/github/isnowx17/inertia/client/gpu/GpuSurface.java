package io.github.isnowx17.inertia.client.gpu;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.backend.opengl.GlBuffer;
import io.github.isnowx17.inertia.Inertia;
import io.github.isnowx17.inertia.client.physics.PhysXRuntime;
import io.github.isnowx17.inertia.util.WaterVertexLayout;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * The water surface built on the GPU every frame from the particles moved between the two latest
 * physics steps (kernels in {@code src/cuda/surface.cu}), written straight into OpenGL buffers that
 * the water shader draws: the surface moves as smoothly as the frame rate, without the CPU building
 * it. The CPU still builds a surface once per step for the foam, the droplets and the light, which
 * the GPU's vertices take over.
 *
 * <p>Runs in PhysX's CUDA context on the client thread, on a stream of its own. Every physics step
 * hands over its particles ({@link #feed}): the newest positions are copied from PhysX's buffer
 * (before the next step starts writing it) and the previous ones from the CPU, into the next of three
 * slots. Every frame {@link #build} builds into one of two pairs of OpenGL buffers while the other one
 * is drawn; a pair is drawn once the GPU reports how many vertices and quads it got.
 */
public final class GpuSurface implements AutoCloseable {
	private static final int THREADS = 256;
	private static final int BRICK_VOLUME = 4096;
	private static final int SLOTS = 3;
	private static final int FIELD_BYTES = 296;
	/** A surface is built at least this long after the one before (240 a second). */
	private static final long MIN_BUILD_NANOS = 4_000_000L;
	/**
	 * The surface takes at most one part in this many of the GPU's time: built less often the longer
	 * a build takes (the shaders move it on in between), so the physics keeps its time.
	 */
	private static final float BUILD_BUDGET = 10f;
	private static final int COUNTERS = 5;

	/** What one physics step handed over: its particles (both steps) and where they are. */
	private static final class Slot {
		long prev;
		long curr;
		long capacity;
		int count;
		long prevNanos;
		long currNanos;
		int originX;
		int originY;
		int originZ;
		long ready;   // event: the copies are done
		// The frozen water at the same step (boxes, see FrozenColumns.boxes), and the array they came from.
		long boxes;
		long boxCapacity;
		MemorySegment boxStaging;
		int boxCount;
		float[] boxSource;
		long lastUse; // event: the latest build that read this slot is done
		boolean used;
	}

	/** A pair of OpenGL buffers the surface is built into. */
	private static final class Output {
		GpuBuffer vertices;
		GpuBuffer indices;
		long vertexResource;
		long indexResource;
		int maxVertices;
		int maxQuads;
		long built;    // event: the build is done
		long started;  // timing events around the build
		long finished;
		boolean pending;
		long buildNanos;
		/** Which foam map the foam coordinates were made for (Settings.foam). */
		Object foam;
		Slot slot;
		int vertexCount;
		int indexCount;
	}

	private final CudaDriver d;
	private final long context;
	private final long module;
	private final long stream;
	private final long copyStream;
	private final long fInsert;
	private final long fInsertBoxes;
	private final long fAssign;
	private final long fCount;
	private final long fCountBoxes;
	private final long fClassify;
	private final long fNeighbors;
	private final long fClear;
	private final long fSplat;
	private final long fSplatBoxes;
	private final long fNormalize;
	private final long fBlur;
	private final long fVertices;
	private final long fQuads;

	private final Arena arena = Arena.ofShared();
	private final MemorySegment field;
	private final MemorySegment counters;
	private final long deviceField;
	private final long deviceCounters;

	private int hashSize = 1 << 16;
	private int maxBricks = 1 << 14;
	private int maxStorage = 768;
	private long keys;
	private long slotsTable;
	private long brickKeys;
	private long brickParticles;
	private long brickStorage;
	private long storageBrick;
	private long raw;
	private long vel;
	private long smooth;
	private long tmp;
	private long cellVertex;
	private long neighborTable;
	/** Prepared kernel arguments: the field alone, and the three smoothing passes. */
	private MemorySegment argsField;
	private MemorySegment argsBlurX;
	private MemorySegment argsBlurY;
	private MemorySegment argsBlurZ;
	/** Measured GPU time of a build (milliseconds, smoothed) and when the newest build started. */
	private float buildMillis = 1f;
	private long lastBuildNanos;
	private long statNanos;
	private int statBuilds;
	private long statCpuNanos;

	private final Slot[] slots = new Slot[SLOTS];
	private int newest = -1;
	private final Output[] outputs = {new Output(), new Output()};
	private int building;
	private Output drawable;


	private long lightKeys;
	private long lightValues;
	private int lightMask = -1;
	private int lightCapacity;
	private MemorySegment lightStaging;
	private long lightSerial = Long.MIN_VALUE;

	private GpuSurface() {
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
			module = d.loadModule(GpuProbe.ptx("surface"));
			fInsert = d.function(module, "insert");
			fInsertBoxes = d.function(module, "insertBoxes");
			fAssign = d.function(module, "assign");
			fCount = d.function(module, "count");
			fCountBoxes = d.function(module, "countBoxes");
			fClassify = d.function(module, "classify");
			fNeighbors = d.function(module, "neighbors");
			fClear = d.function(module, "clearStorage");
			fSplat = d.function(module, "splat");
			fSplatBoxes = d.function(module, "splatBoxes");
			fNormalize = d.function(module, "normalize");
			fBlur = d.function(module, "blur");
			fVertices = d.function(module, "vertices");
			fQuads = d.function(module, "quads");
			stream = d.createStream();
			copyStream = d.createStream();
			field = d.allocHost(FIELD_BYTES);
			counters = d.allocHost(COUNTERS * 4L);
			deviceField = d.alloc(FIELD_BYTES);
			deviceCounters = d.alloc(COUNTERS * 4L);
			for (int k = 0; k < SLOTS; k++) {
				Slot slot = new Slot();
				slot.ready = d.createEvent(false);
				slot.lastUse = d.createEvent(false);
				slots[k] = slot;
			}
			for (Output out : outputs) {
				out.built = d.createEvent(false);
				out.started = d.createEvent(true);
				out.finished = d.createEvent(true);
			}
			allocateGrid();
		} finally {
			d.popContext();
		}
	}

	private static GpuSurface instance;
	private static boolean failed;

	/** The GPU surface, created on first use (render thread); null where it cannot work. */
	public static GpuSurface get() {
		if (instance == null && !failed) {
			try {
				instance = new GpuSurface();
				Inertia.LOGGER.info("Water surface is built on the GPU");
			} catch (Throwable t) {
				failed = true;
				Inertia.LOGGER.warn("Building the water surface on the GPU is not possible here; the CPU builds it", t);
			}
		}
		return instance;
	}

	/** The GPU surface if it was already created (the physics feeds it only then). */
	public static GpuSurface existing() {
		return instance;
	}

	/** Gives up on the GPU surface after an error (the CPU builds it from then on). */
	private static void fail(Throwable t) {
		Inertia.LOGGER.error("The GPU water surface failed; the CPU builds it from now on", t);
		failed = true;
		if (instance != null) {
			try {
				instance.close();
			} catch (Throwable ignored) {
				// already failing
			}
			instance = null;
		}
	}

	private void allocateGrid() {
		freeGrid();
		keys = d.alloc(hashSize * 8L);
		slotsTable = d.alloc(hashSize * 4L);
		brickKeys = d.alloc(maxBricks * 8L);
		brickParticles = d.alloc(maxBricks * 4L);
		brickStorage = d.alloc(maxBricks * 4L);
		storageBrick = d.alloc(maxStorage * 4L);
		long nodes = (long) maxStorage * BRICK_VOLUME;
		raw = d.alloc(nodes * 4);
		vel = d.alloc(nodes * 12);
		smooth = d.alloc(nodes * 4);
		tmp = d.alloc(nodes * 4);
		cellVertex = d.alloc(nodes * 4);
		neighborTable = d.alloc(maxStorage * 27L * 4);
		argsField = d.prepare(arena, deviceField);
		argsBlurX = d.prepare(arena, deviceField, raw, smooth, 0);
		argsBlurY = d.prepare(arena, deviceField, smooth, tmp, 1);
		argsBlurZ = d.prepare(arena, deviceField, tmp, smooth, 2);
	}

	private void freeGrid() {
		for (long p : new long[] {keys, slotsTable, brickKeys, brickParticles, brickStorage, storageBrick, raw, vel, smooth, tmp, cellVertex,
			neighborTable}) {
			d.free(p);
		}
		keys = 0;
	}

	// ------------------------------------------------------------------------------------------
	// Input
	// ------------------------------------------------------------------------------------------

	/**
	 * Client thread, after a physics step was taken over and before the next one starts: the newest
	 * particles straight from PhysX's buffer, the ones before from the CPU (they follow every particle
	 * added or removed since). Waits until the copy of PhysX's buffer is done.
	 */
	public static void feed(long physxPositions, float[] previous, int count, long prevNanos, long currNanos,
		int originX, int originY, int originZ, float[] frozenBoxes) {
		GpuSurface surface = instance;
		if (surface == null) {
			return;
		}
		try {
			surface.feedStep(physxPositions, previous, count, prevNanos, currNanos, originX, originY, originZ, frozenBoxes);
		} catch (Throwable t) {
			fail(t);
		}
	}

	private void feedStep(long physxPositions, float[] previous, int count, long prevNanos, long currNanos,
		int originX, int originY, int originZ, float[] frozenBoxes) {
		d.pushContext(context);
		try {
			int next = (newest + 1) % SLOTS;
			Slot slot = slots[next];
			if (slot.used) {
				d.waitEvent(copyStream, slot.lastUse);
			}
			long bytes = Math.max(16L, count * 16L);
			if (slot.capacity < bytes) {
				d.synchronize(copyStream);
				d.free(slot.prev);
				d.free(slot.curr);
				long capacity = Math.max(bytes, slot.capacity * 2);
				slot.prev = d.alloc(capacity);
				slot.curr = d.alloc(capacity);
				slot.capacity = capacity;
			}
			// The frozen water belongs to the same step: particles that froze are gone from the particles
			// and in the boxes at once (taken from the CPU's surface, the boxes came frames later).
			int boxCount = frozenBoxes.length / 6;
			if (frozenBoxes != slot.boxSource) {
				long boxBytes = Math.max(24L, boxCount * 24L);
				if (slot.boxCapacity < boxBytes) {
					d.synchronize(copyStream);
					d.free(slot.boxes);
					d.freeHost(slot.boxStaging);
					slot.boxCapacity = Math.max(boxBytes, slot.boxCapacity * 2);
					slot.boxes = d.alloc(slot.boxCapacity);
					slot.boxStaging = d.allocHost(slot.boxCapacity);
				}
				if (boxCount > 0) {
					MemorySegment.copy(frozenBoxes, 0, slot.boxStaging, ValueLayout.JAVA_FLOAT, 0, boxCount * 6);
					d.copyToDevice(slot.boxes, slot.boxStaging, boxBytes, copyStream);
				}
				slot.boxSource = frozenBoxes;
			}
			slot.boxCount = boxCount;
			if (count > 0) {
				d.copyOnDevice(slot.curr, physxPositions, bytes, copyStream);
				try (Arena temp = Arena.ofConfined()) {
					MemorySegment host = temp.allocate(bytes, 16);
					MemorySegment.copy(previous, 0, host, ValueLayout.JAVA_FLOAT, 0, count * 4);
					d.copyToDevice(slot.prev, host, bytes, copyStream);
				}
			}
			d.record(slot.ready, copyStream);
			// The physics step may start writing PhysX's buffer only after the copy.
			d.synchronize(copyStream);
			slot.count = count;
			slot.prevNanos = prevNanos;
			slot.currNanos = currNanos;
			slot.originX = originX;
			slot.originY = originY;
			slot.originZ = originZ;
			slot.used = false;
			newest = next;
		} finally {
			d.popContext();
		}
	}

	/**
	 * The light of the blocks the CPU's surface took its light from (keys relative to the origin, as
	 * {@code BlockPos.asLong}), when it changed.
	 */
	private void uploadLights(long[] keysRelative, int[] values, int count, long serial) {
		if (serial == lightSerial) {
			return;
		}
		lightSerial = serial;
		if (count == 0) {
			lightMask = -1;
			return;
		}
		int size = Integer.highestOneBit(Math.max(16, count * 2 - 1)) << 1;
		long[] table = new long[size];
		int[] light = new int[size];
		java.util.Arrays.fill(table, -1L);
		for (int k = 0; k < count; k++) {
			long key = keysRelative[k];
			long packed = pack(net.minecraft.core.BlockPos.getX(key), net.minecraft.core.BlockPos.getY(key), net.minecraft.core.BlockPos.getZ(key));
			int i = (int) (hash(packed) & (size - 1));
			while (table[i] != -1L && table[i] != packed) {
				i = (i + 1) & (size - 1);
			}
			table[i] = packed;
			light[i] = values[k];
		}
		if (lightCapacity < size) {
			d.synchronize(stream);
			d.free(lightKeys);
			d.free(lightValues);
			d.freeHost(lightStaging);
			lightCapacity = size;
			lightKeys = d.alloc(size * 8L);
			lightValues = d.alloc(size * 4L);
			lightStaging = d.allocHost(size * 12L);
		}
		// The previous build is done (one build at a time), so the staging memory is free again.
		MemorySegment.copy(table, 0, lightStaging, ValueLayout.JAVA_LONG, 0, size);
		MemorySegment.copy(light, 0, lightStaging, ValueLayout.JAVA_INT, size * 8L, size);
		d.copyToDevice(lightKeys, lightStaging, size * 8L, stream);
		d.copyToDevice(lightValues, lightStaging.asSlice(size * 8L), size * 4L, stream);
		lightMask = size - 1;
	}

	private static final int OFFSET = 1 << 20;

	/** Brick keys as the kernels pack them (also used for blocks in the light table). */
	static long pack(int x, int y, int z) {
		return ((long) (x + OFFSET) << 42) | ((long) (y + OFFSET) << 21) | (long) (z + OFFSET);
	}

	/** The kernels' hash of a key. */
	static long hash(long k) {
		k ^= k >>> 33;
		k *= 0xff51afd7ed558ccdL;
		k ^= k >>> 33;
		k *= 0xc4ceb9fe1a85ec53L;
		k ^= k >>> 33;
		return k & 0xFFFFFFFFL;
	}

	// ------------------------------------------------------------------------------------------
	// Building
	// ------------------------------------------------------------------------------------------

	/** How the surface is built: the CPU surface's settings and what the foam and light need. */
	public record Settings(float cell, float restDensity, float threshold, float fullCount, float perVolume,
		float[] blurKernel, float lightProbe,
		float foamTexels, float foamOriginX, float foamOriginZ, float foamWidth, float foamHeight, float foamBase,
		long foamNanos, Object foam) {
	}

	/** A built surface ready to draw: buffers, counts, where it is and when it was built for. */
	public record Built(GpuBuffer vertices, GpuBuffer indices, int vertexCount, int indexCount,
		int originX, int originY, int originZ, long buildNanos, Object foam) {
	}

	/**
	 * Render thread, every frame: takes up a finished surface, and starts building the next one for
	 * {@code now} unless one is still being built.
	 *
	 * @return the newest finished surface, or null
	 */
	public static Built frame(long now, Settings settings, long[] lightKeys, int[] lightValues, int lightCount, long lightSerial) {
		GpuSurface surface = instance;
		if (surface == null) {
			return null;
		}
		try {
			return surface.frameStep(now, settings, lightKeys, lightValues, lightCount, lightSerial);
		} catch (Throwable t) {
			fail(t);
			return null;
		}
	}

	private Built frameStep(long now, Settings settings, long[] lights, int[] lightValuesHost, int lightCount, long lightSerialNow) {
		d.pushContext(context);
		try {
			Output out = outputs[building];
			if (out.pending) {
				if (!d.done(out.built)) {
					return published();
				}
				out.pending = false;
				buildMillis += 0.2f * (d.elapsed(out.started, out.finished) - buildMillis);
				int overflow = counters.get(ValueLayout.JAVA_INT, 16);
				int usedBricks = counters.get(ValueLayout.JAVA_INT, 0);
				int usedStorage = counters.get(ValueLayout.JAVA_INT, 4);
				int usedVertices = counters.get(ValueLayout.JAVA_INT, 8);
				int usedQuads = counters.get(ValueLayout.JAVA_INT, 12);
				if (overflow != 0 && grow(overflow, out)) {
					// Parts of this surface are missing: keep showing the last whole one and build
					// again, right away, with room for everything.
					lastBuildNanos = 0L;
					return published();
				}
				out.vertexCount = Math.min(usedVertices, out.maxVertices);
				out.indexCount = Math.min(usedQuads, out.maxQuads) * WaterVertexLayout.INDICES_PER_QUAD;
				drawable = out;
				building = 1 - building;
				// Grows before it runs out, so water that keeps spreading never gets a surface with holes.
				int ahead = (usedBricks * 4 > maxBricks * 3 ? 1 : 0) | (usedStorage * 4 > maxStorage * 3 ? 4 : 0)
					| (Math.max(usedVertices, usedQuads) * 4L > nextOutputSize * 3L ? 8 : 0);
				if (ahead != 0) {
					grow(ahead, out);
				}
				out = outputs[building];
			}
			if (newest < 0 || now - lastBuildNanos < Math.max(MIN_BUILD_NANOS, (long) (buildMillis * BUILD_BUDGET * 1e6f))) {
				return published();
			}
			Slot slot = pick(now);
			if (slot == null || slot.count == 0) {
				return published();
			}
			long cpuStart = System.nanoTime();
			lastBuildNanos = now;
			uploadLights(lights, lightValuesHost, lightCount, lightSerialNow);
			ensureOutput(out);
			build(out, slot, now, settings);
			statBuilds++;
			statCpuNanos += System.nanoTime() - cpuStart;
			if (now - statNanos > 5_000_000_000L) {
				if (statNanos != 0L) {
					Inertia.LOGGER.info("GPU water surface: {} builds a second, {} ms GPU and {} ms CPU each, {} vertices",
						String.format(java.util.Locale.ROOT, "%.1f", statBuilds / ((now - statNanos) / 1e9)),
						String.format(java.util.Locale.ROOT, "%.2f", buildMillis),
						String.format(java.util.Locale.ROOT, "%.2f", statCpuNanos / 1e6 / Math.max(1, statBuilds)),
						drawable != null ? drawable.vertexCount : 0);
				}
				statNanos = now;
				statBuilds = 0;
				statCpuNanos = 0;
			}
			return published();
		} finally {
			d.popContext();
		}
	}

	/**
	 * The slot whose two steps the water is between at {@code now - DELAY}: the newest, unless the
	 * newest step came in before the water got to the step before it (then the one before, so the
	 * water keeps going at an even pace instead of jumping ahead).
	 */
	private Slot pick(long now) {
		Slot best = slots[newest];
		long shown = now - DELAY_NANOS;
		Slot older = slots[(newest + SLOTS - 1) % SLOTS];
		if (best.prevNanos != 0L && shown < best.prevNanos && older.currNanos == best.prevNanos && older.count > 0
			&& older.capacity > 0) {
			return older;
		}
		return best;
	}

	/** The water is shown this long after the moment its newest step stands for: a tick, so a step is never late. */
	private static final long DELAY_NANOS = 50_000_000L;

	private Built published() {
		Output out = drawable;
		if (out == null || out.indexCount == 0 || out.slot == null) {
			return null;
		}
		return new Built(out.vertices, out.indices, out.vertexCount, out.indexCount,
			out.slot.originX, out.slot.originY, out.slot.originZ, out.buildNanos, out.foam);
	}

	/** Grows what overflowed for the next surfaces. */
	/** Whether anything could grow (not when already as large as it gets). */
	private boolean grow(int overflow, Output out) {
		int oldBricks = maxBricks;
		int oldStorage = maxStorage;
		int oldOutput = nextOutputSize;
		if ((overflow & (1 | 2)) != 0) {
			hashSize = Math.min(1 << 22, hashSize * 2);
			maxBricks = Math.min(1 << 20, maxBricks * 2);
		}
		if ((overflow & 4) != 0) {
			maxStorage = Math.min(8192, maxStorage * 2);
		}
		if (maxBricks != oldBricks || maxStorage != oldStorage) {
			d.synchronize(stream);
			allocateGrid();
			Inertia.LOGGER.info("GPU water surface grows: {} bricks, {} with storage", maxBricks, maxStorage);
		}
		if ((overflow & (8 | 16)) != 0 && nextOutputSize < MAX_OUTPUT_SIZE) {
			for (Output o : outputs) {
				o.maxVertices = 0; // reallocated larger by ensureOutput
				o.maxQuads = Math.max(o.maxQuads, 1);
			}
			nextOutputSize = nextOutputSize * 2;
		}
		return maxBricks != oldBricks || maxStorage != oldStorage || nextOutputSize != oldOutput;
	}

	private static final int MAX_OUTPUT_SIZE = 1 << 23;

	private int nextOutputSize = 1 << 19;
	/** The moment the surface being built is for. */
	private long buildingNanos;

	/** OpenGL buffers big enough for {@link #nextOutputSize} vertices and quads, registered with CUDA. */
	private void ensureOutput(Output out) {
		if (out.maxVertices >= nextOutputSize && out.vertices != null) {
			return;
		}
		if (out.vertices != null) {
			if (out == drawable) {
				drawable = null;
			}
			d.unregister(out.vertexResource);
			d.unregister(out.indexResource);
			out.vertices.close();
			out.indices.close();
		}
		int size = nextOutputSize;
		var device = RenderSystem.getDevice();
		out.vertices = device.createBuffer(() -> "Inertia GPU water surface vertices",
			GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST, (long) size * WaterVertexLayout.STRIDE);
		out.indices = device.createBuffer(() -> "Inertia GPU water surface indices",
			GpuBuffer.USAGE_INDEX | GpuBuffer.USAGE_COPY_DST, (long) size * WaterVertexLayout.INDICES_PER_QUAD * Integer.BYTES);
		out.vertexResource = d.registerGlBuffer(((GlBuffer) out.vertices).handle(), CudaDriver.WRITE_DISCARD);
		out.indexResource = d.registerGlBuffer(((GlBuffer) out.indices).handle(), CudaDriver.WRITE_DISCARD);
		out.maxVertices = size;
		out.maxQuads = size;
	}

	private void build(Output out, Slot slot, long now, Settings s) {
		// Where the water is a tick ago: between the two steps of the slot, at an even pace.
		long interval = slot.currNanos - slot.prevNanos;
		boolean moving = slot.prevNanos != 0L && interval > 0L;
		long span = moving ? Math.max(30_000_000L, Math.min(150_000_000L, interval)) : 1L;
		long shown = now - DELAY_NANOS;
		// Data older than two steps (still water, a pause): where the newest step left it.
		if (shown - slot.currNanos > 2 * span) {
			moving = false;
		}
		float alpha = moving ? Math.max(0f, Math.min(1.25f, (float) (shown - slot.prevNanos) / span)) : 1f;
		float invSpan = moving ? 1e9f / span : 0f;

		buildingNanos = now;
		d.waitEvent(stream, slot.ready);
		d.record(out.started, stream);
		long vertexPointer = d.map(out.vertexResource, stream);
		long indexPointer = d.map(out.indexResource, stream);
		writeField(slot, s, alpha, invSpan, vertexPointer, indexPointer, out);
		d.copyToDevice(deviceField, field, FIELD_BYTES, stream);
		d.fill32(keys, -1, hashSize * 2L, stream);
		d.fill32(deviceCounters, 0, COUNTERS, stream);

		int n = slot.count;
		long nodes = (long) maxStorage * BRICK_VOLUME;
		MemorySegment a = argsField;
		d.launch(fInsert, CudaDriver.blocks(n, THREADS), THREADS, stream, a);
		int boxCount = slot.boxCount;
		if (boxCount > 0) {
			d.launch(fInsertBoxes, CudaDriver.blocks(boxCount, THREADS), THREADS, stream, a);
		}
		d.launch(fAssign, CudaDriver.blocks(hashSize, THREADS), THREADS, stream, a);
		d.launch(fCount, CudaDriver.blocks(n, THREADS), THREADS, stream, a);
		if (boxCount > 0) {
			d.launch(fCountBoxes, CudaDriver.blocks(boxCount, THREADS), THREADS, stream, a);
		}
		d.launch(fClassify, CudaDriver.blocks(maxBricks, THREADS), THREADS, stream, a);
		d.launch(fNeighbors, CudaDriver.blocks(maxStorage * 27L, THREADS), THREADS, stream, a);
		d.launch(fClear, CudaDriver.blocks(nodes, THREADS), THREADS, stream, a);
		d.launch(fSplat, CudaDriver.blocks(n, THREADS), THREADS, stream, a);
		if (boxCount > 0) {
			d.launch(fSplatBoxes, boxCount, THREADS, stream, a);
		}
		d.launch(fNormalize, CudaDriver.blocks(nodes, THREADS), THREADS, stream, a);
		if (s.blurKernel().length > 1) {
			int blocks = CudaDriver.blocks(nodes, THREADS);
			d.launch(fBlur, blocks, THREADS, stream, argsBlurX);
			d.launch(fBlur, blocks, THREADS, stream, argsBlurY);
			d.launch(fBlur, blocks, THREADS, stream, argsBlurZ);
		} else {
			d.copyOnDevice(smooth, raw, nodes * 4, stream);
		}
		d.launch(fVertices, CudaDriver.blocks(nodes, THREADS), THREADS, stream, a);
		d.launch(fQuads, CudaDriver.blocks(nodes, THREADS), THREADS, stream, a);
		d.record(out.finished, stream);
		d.unmap(out.vertexResource, stream);
		d.unmap(out.indexResource, stream);
		d.copyToHost(counters, deviceCounters, COUNTERS * 4L, stream);
		d.record(out.built, stream);
		d.record(slot.lastUse, stream);
		slot.used = true;
		out.pending = true;
		out.slot = slot;
		out.buildNanos = now;
		out.foam = s.foam();
	}

	private void writeField(Slot slot, Settings s, float alpha, float invSpan, long vertexPointer, long indexPointer, Output out) {
		long[] pointers = {keys, slotsTable, deviceCounters, brickKeys, brickParticles, brickStorage, storageBrick,
			raw, vel, smooth, tmp, cellVertex, slot.prev, slot.curr, slot.boxes, vertexPointer, indexPointer, lightKeys, lightValues,
			neighborTable};
		long o = 0;
		for (long p : pointers) {
			field.set(ValueLayout.JAVA_LONG, o, p);
			o += 8;
		}
		int[] ints = {hashSize - 1, maxBricks, maxStorage, slot.count, slot.boxCount, out.maxVertices, out.maxQuads, lightMask,
			(s.blurKernel().length - 1) / 2};
		for (int v : ints) {
			field.set(ValueLayout.JAVA_INT, o, v);
			o += 4;
		}
		float foamAge = s.foamNanos() != 0L ? Math.max(0f, Math.min(0.25f, (buildingNanos - s.foamNanos()) / 1e9f)) : 0f;
		float[] floats = {alpha, invSpan, s.cell(), 1f / s.cell(), s.restDensity(), s.threshold(), s.fullCount(), s.perVolume(),
			s.foamTexels(), s.foamOriginX(), s.foamOriginZ(), s.foamWidth(), s.foamHeight(), s.foamBase(), s.lightProbe(), foamAge};
		for (float v : floats) {
			field.set(ValueLayout.JAVA_FLOAT, o, v);
			o += 4;
		}
		float[] kernel = s.blurKernel();
		for (int k = 0; k < 9; k++) {
			field.set(ValueLayout.JAVA_FLOAT, o, k < kernel.length ? kernel[k] : 0f);
			o += 4;
		}
		if (o > FIELD_BYTES) {
			throw new IllegalStateException("Field layout too big: " + o);
		}
	}

	@Override
	public void close() {
		d.pushContext(context);
		try {
			d.synchronize(stream);
			d.synchronize(copyStream);
			for (Output out : outputs) {
				if (out.vertices != null) {
					d.unregister(out.vertexResource);
					d.unregister(out.indexResource);
					out.vertices.close();
					out.indices.close();
				}
			}
			for (Slot slot : slots) {
				d.free(slot.prev);
				d.free(slot.curr);
				d.free(slot.boxes);
				d.freeHost(slot.boxStaging);
			}
			freeGrid();
			d.free(lightKeys);
			d.free(lightValues);
			d.free(deviceField);
			d.free(deviceCounters);
			d.freeHost(field);
			d.freeHost(counters);
			d.freeHost(lightStaging);
			d.unloadModule(module);
		} finally {
			d.popContext();
		}
	}
}
