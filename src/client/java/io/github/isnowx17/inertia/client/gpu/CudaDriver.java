package io.github.isnowx17.inertia.client.gpu;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.Locale;

/**
 * The few functions of the CUDA driver API Inertia's own GPU work needs, called through Java's
 * foreign function interface (no extra library: the driver comes with the NVIDIA graphics driver,
 * which PhysX needs anyway). Kernels are loaded from PTX, which the driver compiles for the GPU.
 *
 * <p>Every function returns the CUDA result code; {@link #check} turns an error into an exception.
 * Handles (contexts, modules, functions, streams, events, graphics resources) and device pointers
 * are plain {@code long}s.
 */
public final class CudaDriver {
	private static final ValueLayout.OfLong PTR = ValueLayout.JAVA_LONG;
	private static final ValueLayout.OfInt INT = ValueLayout.JAVA_INT;

	private static CudaDriver instance;
	private static Throwable failure;

	private final MethodHandle cuInit;
	private final MethodHandle cuCtxGetCurrent;
	private final MethodHandle cuDeviceGet;
	private final MethodHandle cuDevicePrimaryCtxRetain;
	private final MethodHandle cuCtxPushCurrent;
	private final MethodHandle cuCtxPopCurrent;
	private final MethodHandle cuModuleLoadData;
	private final MethodHandle cuModuleGetFunction;
	private final MethodHandle cuModuleUnload;
	private final MethodHandle cuLaunchKernel;
	private final MethodHandle cuMemAlloc;
	private final MethodHandle cuMemFree;
	private final MethodHandle cuMemAllocHost;
	private final MethodHandle cuMemFreeHost;
	private final MethodHandle cuMemcpyHtoDAsync;
	private final MethodHandle cuMemcpyDtoHAsync;
	private final MethodHandle cuMemcpyDtoDAsync;
	private final MethodHandle cuMemsetD32Async;
	private final MethodHandle cuStreamCreate;
	private final MethodHandle cuStreamDestroy;
	private final MethodHandle cuStreamSynchronize;
	private final MethodHandle cuStreamWaitEvent;
	private final MethodHandle cuEventCreate;
	private final MethodHandle cuEventDestroy;
	private final MethodHandle cuEventRecord;
	private final MethodHandle cuEventSynchronize;
	private final MethodHandle cuEventQuery;
	private final MethodHandle cuEventElapsedTime;
	private final MethodHandle cuGraphicsGLRegisterBuffer;
	private final MethodHandle cuGraphicsUnregisterResource;
	private final MethodHandle cuGraphicsMapResources;
	private final MethodHandle cuGraphicsUnmapResources;
	private final MethodHandle cuGraphicsResourceGetMappedPointer;
	private final MethodHandle cuGetErrorName;

	private CudaDriver(SymbolLookup lib) {
		Linker linker = Linker.nativeLinker();
		cuInit = bind(linker, lib, "cuInit", INT);
		cuCtxGetCurrent = bind(linker, lib, "cuCtxGetCurrent", ValueLayout.ADDRESS);
		cuDeviceGet = bind(linker, lib, "cuDeviceGet", ValueLayout.ADDRESS, INT);
		cuDevicePrimaryCtxRetain = bind(linker, lib, "cuDevicePrimaryCtxRetain", ValueLayout.ADDRESS, INT);
		cuCtxPushCurrent = bind(linker, lib, "cuCtxPushCurrent_v2", PTR);
		cuCtxPopCurrent = bind(linker, lib, "cuCtxPopCurrent_v2", ValueLayout.ADDRESS);
		cuModuleLoadData = bind(linker, lib, "cuModuleLoadData", ValueLayout.ADDRESS, ValueLayout.ADDRESS);
		cuModuleGetFunction = bind(linker, lib, "cuModuleGetFunction", ValueLayout.ADDRESS, PTR, ValueLayout.ADDRESS);
		cuModuleUnload = bind(linker, lib, "cuModuleUnload", PTR);
		cuLaunchKernel = bind(linker, lib, "cuLaunchKernel", PTR, INT, INT, INT, INT, INT, INT, INT, PTR,
			ValueLayout.ADDRESS, ValueLayout.ADDRESS);
		cuMemAlloc = bind(linker, lib, "cuMemAlloc_v2", ValueLayout.ADDRESS, PTR);
		cuMemFree = bind(linker, lib, "cuMemFree_v2", PTR);
		cuMemAllocHost = bind(linker, lib, "cuMemAllocHost_v2", ValueLayout.ADDRESS, PTR);
		cuMemFreeHost = bind(linker, lib, "cuMemFreeHost", PTR);
		cuMemcpyHtoDAsync = bind(linker, lib, "cuMemcpyHtoDAsync_v2", PTR, ValueLayout.ADDRESS, PTR, PTR);
		cuMemcpyDtoHAsync = bind(linker, lib, "cuMemcpyDtoHAsync_v2", ValueLayout.ADDRESS, PTR, PTR, PTR);
		cuMemcpyDtoDAsync = bind(linker, lib, "cuMemcpyDtoDAsync_v2", PTR, PTR, PTR, PTR);
		cuMemsetD32Async = bind(linker, lib, "cuMemsetD32Async", PTR, INT, PTR, PTR);
		cuStreamCreate = bind(linker, lib, "cuStreamCreate", ValueLayout.ADDRESS, INT);
		cuStreamDestroy = bind(linker, lib, "cuStreamDestroy_v2", PTR);
		cuStreamSynchronize = bind(linker, lib, "cuStreamSynchronize", PTR);
		cuStreamWaitEvent = bind(linker, lib, "cuStreamWaitEvent", PTR, PTR, INT);
		cuEventCreate = bind(linker, lib, "cuEventCreate", ValueLayout.ADDRESS, INT);
		cuEventDestroy = bind(linker, lib, "cuEventDestroy_v2", PTR);
		cuEventRecord = bind(linker, lib, "cuEventRecord", PTR, PTR);
		cuEventSynchronize = bind(linker, lib, "cuEventSynchronize", PTR);
		cuEventQuery = bind(linker, lib, "cuEventQuery", PTR);
		cuEventElapsedTime = bind(linker, lib, "cuEventElapsedTime", ValueLayout.ADDRESS, PTR, PTR);
		cuGraphicsGLRegisterBuffer = bind(linker, lib, "cuGraphicsGLRegisterBuffer", ValueLayout.ADDRESS, INT, INT);
		cuGraphicsUnregisterResource = bind(linker, lib, "cuGraphicsUnregisterResource", PTR);
		cuGraphicsMapResources = bind(linker, lib, "cuGraphicsMapResources", INT, ValueLayout.ADDRESS, PTR);
		cuGraphicsUnmapResources = bind(linker, lib, "cuGraphicsUnmapResources", INT, ValueLayout.ADDRESS, PTR);
		cuGraphicsResourceGetMappedPointer = bind(linker, lib, "cuGraphicsResourceGetMappedPointer_v2",
			ValueLayout.ADDRESS, ValueLayout.ADDRESS, PTR);
		cuGetErrorName = bind(linker, lib, "cuGetErrorName", INT, ValueLayout.ADDRESS);
	}

	private static MethodHandle bind(Linker linker, SymbolLookup lib, String name, java.lang.foreign.MemoryLayout... args) {
		MemorySegment symbol = lib.find(name).orElseThrow(() -> new UnsatisfiedLinkError("CUDA driver has no " + name));
		return linker.downcallHandle(symbol, FunctionDescriptor.of(INT, args));
	}

	/** The driver, loaded and initialized on first use; throws if there is no usable CUDA driver. */
	public static synchronized CudaDriver get() {
		if (instance != null) {
			return instance;
		}
		if (failure != null) {
			throw new IllegalStateException("CUDA driver unavailable", failure);
		}
		try {
			String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
			String name = os.contains("win") ? "nvcuda.dll" : "libcuda.so.1";
			CudaDriver driver = new CudaDriver(SymbolLookup.libraryLookup(name, Arena.global()));
			driver.check(driver.call(driver.cuInit, 0), "cuInit");
			instance = driver;
			return driver;
		} catch (Throwable t) {
			failure = t;
			throw new IllegalStateException("CUDA driver unavailable", t);
		}
	}

	private int call(MethodHandle handle, Object... args) {
		try {
			return (int) handle.invokeWithArguments(args);
		} catch (RuntimeException | Error e) {
			throw e;
		} catch (Throwable t) {
			throw new IllegalStateException(t);
		}
	}

	/** Throws when {@code result} is an error. */
	public void check(int result, String what) {
		if (result != 0) {
			throw new IllegalStateException(what + " failed: " + errorName(result) + " (" + result + ")");
		}
	}

	public String errorName(int result) {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment out = arena.allocate(ValueLayout.ADDRESS);
			if ((int) cuGetErrorName.invokeExact(result, out) != 0) {
				return "CUDA error";
			}
			return out.get(ValueLayout.ADDRESS, 0).reinterpret(256).getString(0);
		} catch (Throwable t) {
			return "CUDA error";
		}
	}

	private long out(MethodHandle handle, String what, Object... args) {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment out = arena.allocate(ValueLayout.JAVA_LONG);
			Object[] all = new Object[args.length + 1];
			all[0] = out;
			System.arraycopy(args, 0, all, 1, args.length);
			check(call(handle, all), what);
			return out.get(ValueLayout.JAVA_LONG, 0);
		}
	}

	// ------------------------------------------------------------------------------------------
	// Contexts
	// ------------------------------------------------------------------------------------------

	/** The primary context of GPU {@code ordinal} (for tools and tests; the game shares PhysX's). */
	public long primaryContext(int ordinal) {
		int device;
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment out = arena.allocate(ValueLayout.JAVA_INT);
			check(call(cuDeviceGet, out, ordinal), "cuDeviceGet");
			device = out.get(ValueLayout.JAVA_INT, 0);
		}
		return out(cuDevicePrimaryCtxRetain, "cuDevicePrimaryCtxRetain", device);
	}

	/** The context current on this thread (0 for none). */
	public long currentContext() {
		return out(cuCtxGetCurrent, "cuCtxGetCurrent");
	}

	public void pushContext(long context) {
		check(call(cuCtxPushCurrent, context), "cuCtxPushCurrent");
	}

	public void popContext() {
		out(cuCtxPopCurrent, "cuCtxPopCurrent");
	}

	// ------------------------------------------------------------------------------------------
	// Modules and kernels
	// ------------------------------------------------------------------------------------------

	/** Loads a module from PTX text (the driver compiles it for this GPU). */
	public long loadModule(String ptx) {
		try (Arena arena = Arena.ofConfined()) {
			return out(cuModuleLoadData, "cuModuleLoadData", arena.allocateFrom(ptx));
		}
	}

	public long function(long module, String name) {
		try (Arena arena = Arena.ofConfined()) {
			return out(cuModuleGetFunction, "cuModuleGetFunction(" + name + ")", module, arena.allocateFrom(name));
		}
	}

	public void unloadModule(long module) {
		check(call(cuModuleUnload, module), "cuModuleUnload");
	}

	/**
	 * Launches {@code function} on {@code stream} with {@code blocks} blocks of {@code threads}
	 * threads. Arguments are {@code Long} (pointers, 64-bit values), {@code Integer} or {@code Float}.
	 */
	public void launch(long function, int blocks, int threads, long stream, Object... args) {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment pointers = arena.allocate(ValueLayout.ADDRESS, Math.max(1, args.length));
			for (int k = 0; k < args.length; k++) {
				MemorySegment value;
				Object a = args[k];
				if (a instanceof Long l) {
					value = arena.allocate(ValueLayout.JAVA_LONG);
					value.set(ValueLayout.JAVA_LONG, 0, l);
				} else if (a instanceof Integer i) {
					value = arena.allocate(ValueLayout.JAVA_INT);
					value.set(ValueLayout.JAVA_INT, 0, i);
				} else if (a instanceof Float f) {
					value = arena.allocate(ValueLayout.JAVA_FLOAT);
					value.set(ValueLayout.JAVA_FLOAT, 0, f);
				} else {
					throw new IllegalArgumentException("Unsupported kernel argument " + a);
				}
				pointers.setAtIndex(ValueLayout.ADDRESS, k, value);
			}
			check(call(cuLaunchKernel, function, Math.max(1, blocks), 1, 1, threads, 1, 1, 0, stream, pointers,
				MemorySegment.NULL), "cuLaunchKernel");
		}
	}

	/**
	 * Kernel arguments prepared once (for launches every frame): {@code Long} pointers, {@code Integer}
	 * and {@code Float} values, kept in {@code arena}.
	 */
	public MemorySegment prepare(Arena arena, Object... args) {
		MemorySegment pointers = arena.allocate(ValueLayout.ADDRESS, Math.max(1, args.length));
		for (int k = 0; k < args.length; k++) {
			MemorySegment value = arena.allocate(8, 8);
			Object a = args[k];
			if (a instanceof Long l) {
				value.set(ValueLayout.JAVA_LONG, 0, l);
			} else if (a instanceof Integer i) {
				value.set(ValueLayout.JAVA_INT, 0, i);
			} else if (a instanceof Float f) {
				value.set(ValueLayout.JAVA_FLOAT, 0, f);
			} else {
				throw new IllegalArgumentException("Unsupported kernel argument " + a);
			}
			pointers.setAtIndex(ValueLayout.ADDRESS, k, value);
		}
		return pointers;
	}

	/** {@link #launch} with arguments from {@link #prepare}. */
	public void launch(long function, int blocks, int threads, long stream, MemorySegment prepared) {
		int result;
		try {
			result = (int) cuLaunchKernel.invokeExact(function, Math.max(1, blocks), 1, 1, threads, 1, 1, 0, stream, prepared,
				MemorySegment.NULL);
		} catch (Throwable t) {
			throw new IllegalStateException(t);
		}
		check(result, "cuLaunchKernel");
	}

	/** Blocks needed for {@code n} threads of {@code threads} each. */
	public static int blocks(long n, int threads) {
		return (int) Math.max(1, (n + threads - 1) / threads);
	}

	// ------------------------------------------------------------------------------------------
	// Memory
	// ------------------------------------------------------------------------------------------

	public long alloc(long bytes) {
		return out(cuMemAlloc, "cuMemAlloc(" + bytes + ")", Math.max(4L, bytes));
	}

	public void free(long pointer) {
		if (pointer != 0L) {
			check(call(cuMemFree, pointer), "cuMemFree");
		}
	}

	/**
	 * Page-locked host memory: copies from and to it are truly asynchronous (from ordinary memory the
	 * driver first waits for all work on the stream). Free it with {@link #freeHost}.
	 */
	public MemorySegment allocHost(long bytes) {
		long pointer = out(cuMemAllocHost, "cuMemAllocHost(" + bytes + ")", Math.max(16L, bytes));
		return MemorySegment.ofAddress(pointer).reinterpret(Math.max(16L, bytes));
	}

	public void freeHost(MemorySegment memory) {
		if (memory != null && memory.address() != 0L) {
			check(call(cuMemFreeHost, memory.address()), "cuMemFreeHost");
		}
	}

	public void copyToDevice(long device, MemorySegment host, long bytes, long stream) {
		check(call(cuMemcpyHtoDAsync, device, host, bytes, stream), "cuMemcpyHtoDAsync");
	}

	public void copyToHost(MemorySegment host, long device, long bytes, long stream) {
		check(call(cuMemcpyDtoHAsync, host, device, bytes, stream), "cuMemcpyDtoHAsync");
	}

	public void copyOnDevice(long to, long from, long bytes, long stream) {
		check(call(cuMemcpyDtoDAsync, to, from, bytes, stream), "cuMemcpyDtoDAsync");
	}

	/** Sets {@code count} 32-bit words at {@code device} to {@code value}. */
	public void fill32(long device, int value, long count, long stream) {
		check(call(cuMemsetD32Async, device, value, count, stream), "cuMemsetD32Async");
	}

	// ------------------------------------------------------------------------------------------
	// Streams and events
	// ------------------------------------------------------------------------------------------

	/** A stream that does not wait for the default stream (flag CU_STREAM_NON_BLOCKING). */
	public long createStream() {
		return out(cuStreamCreate, "cuStreamCreate", 1);
	}

	public void destroyStream(long stream) {
		check(call(cuStreamDestroy, stream), "cuStreamDestroy");
	}

	public void synchronize(long stream) {
		check(call(cuStreamSynchronize, stream), "cuStreamSynchronize");
	}

	/** Makes {@code stream} wait until {@code event} happened. */
	public void waitEvent(long stream, long event) {
		check(call(cuStreamWaitEvent, stream, event, 0), "cuStreamWaitEvent");
	}

	/** An event; {@code timing} false makes it cheaper (flag CU_EVENT_DISABLE_TIMING). */
	public long createEvent(boolean timing) {
		return out(cuEventCreate, "cuEventCreate", timing ? 0 : 2);
	}

	public void destroyEvent(long event) {
		check(call(cuEventDestroy, event), "cuEventDestroy");
	}

	public void record(long event, long stream) {
		check(call(cuEventRecord, event, stream), "cuEventRecord");
	}

	public void synchronizeEvent(long event) {
		check(call(cuEventSynchronize, event), "cuEventSynchronize");
	}

	/** Whether all work before {@code event} is done. */
	public boolean done(long event) {
		int result = call(cuEventQuery, event);
		if (result == 600) { // CUDA_ERROR_NOT_READY
			return false;
		}
		check(result, "cuEventQuery");
		return true;
	}

	/** Milliseconds between two recorded (timing) events. */
	public float elapsed(long start, long end) {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment out = arena.allocate(ValueLayout.JAVA_FLOAT);
			check(call(cuEventElapsedTime, out, start, end), "cuEventElapsedTime");
			return out.get(ValueLayout.JAVA_FLOAT, 0);
		}
	}

	// ------------------------------------------------------------------------------------------
	// OpenGL interop (the GL context must be current on the calling thread)
	// ------------------------------------------------------------------------------------------

	/** Flags of {@link #registerGlBuffer}: CU_GRAPHICS_REGISTER_FLAGS_WRITE_DISCARD. */
	public static final int WRITE_DISCARD = 2;

	public long registerGlBuffer(int buffer, int flags) {
		return out(cuGraphicsGLRegisterBuffer, "cuGraphicsGLRegisterBuffer", buffer, flags);
	}

	public void unregister(long resource) {
		check(call(cuGraphicsUnregisterResource, resource), "cuGraphicsUnregisterResource");
	}

	/** Maps a registered resource for {@code stream}; returns its device pointer. */
	public long map(long resource, long stream) {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment resources = arena.allocate(ValueLayout.JAVA_LONG);
			resources.set(ValueLayout.JAVA_LONG, 0, resource);
			check(call(cuGraphicsMapResources, 1, resources, stream), "cuGraphicsMapResources");
			MemorySegment pointer = arena.allocate(ValueLayout.JAVA_LONG);
			MemorySegment size = arena.allocate(ValueLayout.JAVA_LONG);
			check(call(cuGraphicsResourceGetMappedPointer, pointer, size, resource), "cuGraphicsResourceGetMappedPointer");
			return pointer.get(ValueLayout.JAVA_LONG, 0);
		}
	}

	public void unmap(long resource, long stream) {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment resources = arena.allocate(ValueLayout.JAVA_LONG);
			resources.set(ValueLayout.JAVA_LONG, 0, resource);
			check(call(cuGraphicsUnmapResources, 1, resources, stream), "cuGraphicsUnmapResources");
		}
	}
}
