package io.github.isnowx17.inertia.client.physics;

import de.fabmax.physxjni.Loader;
import io.github.isnowx17.inertia.Inertia;
import java.nio.file.Path;
import java.util.Locale;
import physx.PxTopLevelFunctions;
import physx.common.PxCudaContextManager;
import physx.common.PxCudaContextManagerDesc;
import physx.common.PxCudaTopLevelFunctions;
import physx.common.PxDefaultAllocator;
import physx.common.PxDefaultCpuDispatcher;
import physx.common.PxErrorCallbackImpl;
import physx.common.PxErrorCodeEnum;
import physx.common.PxFoundation;
import physx.common.PxTolerancesScale;
import physx.physics.PxFilterData;
import physx.physics.PxMaterial;
import physx.physics.PxPhysics;

/**
 * Process-wide PhysX state: native libraries, foundation, physics SDK and the CUDA context.
 *
 * <p>Startup runs on a background thread because the CUDA build of PhysX ships a ~250 MB GPU
 * library that is extracted and loaded on first use. If anything is missing (no NVIDIA GPU, no
 * CUDA driver, wrong platform jar, macOS) the runtime ends in {@link State#UNAVAILABLE} or
 * {@link State#FAILED} and the mod stays inactive instead of crashing the game.
 */
public final class PhysXRuntime {
	/** physx-jni release bundled with this build; native libraries are cached per version. */
	public static final String PHYSX_JNI_VERSION = "2.5.1";

	public enum State {
		NOT_STARTED,
		LOADING,
		READY,
		/** No CUDA-capable NVIDIA GPU / driver was found. */
		UNAVAILABLE,
		/** Native libraries could not be loaded or PhysX failed to initialise. */
		FAILED
	}

	/** Collision filter shared by every Inertia actor: everything collides with everything. */
	public static final int FILTER_GROUP = 1;
	public static final int FILTER_MASK = 0xFFFFFFFF;

	private static volatile State state = State.NOT_STARTED;
	private static volatile String statusDetail = "";

	private static PxFoundation foundation;
	private static PxPhysics physics;
	private static PxDefaultCpuDispatcher dispatcher;
	private static PxCudaContextManager cudaContextManager;
	private static PxMaterial staticMaterial;
	private static PxFilterData filterData;
	private static String gpuName = "";

	private PhysXRuntime() {
	}

	public static State state() {
		return state;
	}

	public static boolean isReady() {
		return state == State.READY;
	}

	/** Human readable detail for the current state (GPU name or failure reason). */
	public static String statusDetail() {
		return statusDetail;
	}

	public static String gpuName() {
		return gpuName;
	}

	public static PxPhysics physics() {
		requireReady();
		return physics;
	}

	public static PxDefaultCpuDispatcher dispatcher() {
		requireReady();
		return dispatcher;
	}

	public static PxCudaContextManager cuda() {
		requireReady();
		return cudaContextManager;
	}

	public static PxMaterial staticMaterial() {
		requireReady();
		return staticMaterial;
	}

	public static PxFilterData filterData() {
		requireReady();
		return filterData;
	}

	/**
	 * Starts loading PhysX in the background. Safe to call more than once.
	 *
	 * @param nativeCacheDir directory the native libraries are extracted to
	 */
	public static synchronized void startAsync(Path nativeCacheDir) {
		if (state != State.NOT_STARTED) {
			return;
		}

		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		if (os.contains("mac")) {
			fail(State.UNAVAILABLE, "macOS has no CUDA support");
			return;
		}

		state = State.LOADING;
		Thread thread = new Thread(() -> initialise(nativeCacheDir), "Inertia PhysX startup");
		thread.setDaemon(true);
		thread.start();
	}

	private static void initialise(Path nativeCacheDir) {
		try {
			// Keep the large native libraries in the game directory instead of the system temp dir.
			System.setProperty("physxjni.nativeLibLocation", nativeCacheDir.resolve(PHYSX_JNI_VERSION).toAbsolutePath().toString());
			Loader.load();
		} catch (Throwable t) {
			Inertia.LOGGER.error("Could not load the PhysX native libraries", t);
			fail(State.FAILED, "native libraries could not be loaded (is this the jar for your operating system?)");
			return;
		}

		try {
			int version = PxTopLevelFunctions.getPHYSICS_VERSION();
			foundation = PxTopLevelFunctions.CreateFoundation(version, new PxDefaultAllocator(), new LoggingErrorCallback());

			PxTolerancesScale scale = new PxTolerancesScale();
			physics = PxTopLevelFunctions.CreatePhysics(version, foundation, scale);
			scale.destroy();

			int threads = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 2));
			dispatcher = PxTopLevelFunctions.DefaultCpuDispatcherCreate(threads);
			staticMaterial = physics.createMaterial(0.5f, 0.5f, 0.0f);
			filterData = new PxFilterData(FILTER_GROUP, FILTER_MASK, 0, 0);
		} catch (Throwable t) {
			Inertia.LOGGER.error("Could not initialise PhysX", t);
			fail(State.FAILED, "PhysX could not be initialised: " + t.getMessage());
			return;
		}

		try {
			if (PxCudaTopLevelFunctions.GetSuggestedCudaDeviceOrdinal(foundation) < 0) {
				fail(State.UNAVAILABLE, "no CUDA-capable NVIDIA GPU found");
				return;
			}

			PxCudaContextManagerDesc desc = new PxCudaContextManagerDesc();
			PxCudaContextManager manager = PxCudaTopLevelFunctions.CreateCudaContextManager(foundation, desc);
			desc.destroy();

			if (manager == null || !manager.contextIsValid()) {
				if (manager != null) {
					manager.release();
				}
				fail(State.UNAVAILABLE, "the CUDA context could not be created (update the NVIDIA driver)");
				return;
			}

			cudaContextManager = manager;
			gpuName = manager.getDeviceName();
			statusDetail = gpuName;
			state = State.READY;
			Inertia.LOGGER.info(
				"PhysX {} ready on {} ({} MB, driver {})",
				PHYSX_JNI_VERSION,
				gpuName,
				manager.getDeviceTotalMemBytes() / (1024 * 1024),
				manager.getDriverVersion()
			);
		} catch (Throwable t) {
			Inertia.LOGGER.error("CUDA initialisation failed", t);
			fail(State.UNAVAILABLE, "CUDA initialisation failed: " + t.getMessage());
		}
	}

	private static void fail(State newState, String detail) {
		statusDetail = detail;
		state = newState;
		if (newState == State.UNAVAILABLE) {
			Inertia.LOGGER.warn("Inertia fluid physics disabled: {}", detail);
		} else {
			Inertia.LOGGER.error("Inertia fluid physics disabled: {}", detail);
		}
	}

	private static void requireReady() {
		if (state != State.READY) {
			throw new IllegalStateException("PhysX is not ready (" + state + ")");
		}
	}

	private static final class LoggingErrorCallback extends PxErrorCallbackImpl {
		@Override
		public void reportError(PxErrorCodeEnum code, String message, String file, int line) {
			switch (code) {
				case eDEBUG_INFO -> Inertia.LOGGER.debug("[PhysX] {}", message);
				case eDEBUG_WARNING, ePERF_WARNING -> Inertia.LOGGER.warn("[PhysX] {} ({}:{})", message, file, line);
				default -> Inertia.LOGGER.error("[PhysX] {}: {} ({}:{})", code, message, file, line);
			}
		}
	}
}
