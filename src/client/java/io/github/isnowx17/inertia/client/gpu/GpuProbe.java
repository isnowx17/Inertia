package io.github.isnowx17.inertia.client.gpu;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.backend.opengl.GlBuffer;
import io.github.isnowx17.inertia.Inertia;
import io.github.isnowx17.inertia.client.physics.PhysXRuntime;
import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import physx.common.PxCudaContextManager;

/**
 * Checks, in the running game, everything the GPU surface needs: the CUDA driver through the
 * foreign function interface, PhysX's CUDA context shared with Inertia's own kernels (loaded from
 * PTX), and CUDA writing into an OpenGL buffer of the game. Render thread; logs what it found.
 */
public final class GpuProbe {
	private GpuProbe() {
	}

	/** Reads a kernel module's PTX from the mod's resources. */
	public static String ptx(String name) {
		try (InputStream in = GpuProbe.class.getResourceAsStream("/assets/inertia/cuda/" + name + ".ptx")) {
			if (in == null) {
				throw new IllegalStateException("No PTX " + name);
			}
			return new String(in.readAllBytes(), StandardCharsets.US_ASCII);
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	public static void run() {
		long started = System.nanoTime();
		CudaDriver d = CudaDriver.get();
		PxCudaContextManager physx = PhysXRuntime.cuda();
		physx.acquireContext();
		long context;
		try {
			context = d.currentContext();
		} finally {
			physx.releaseContext();
		}
		Inertia.LOGGER.info("GPUPROBE driver loaded, PhysX context {}", Long.toHexString(context));
		d.pushContext(context);
		try {
			long module = d.loadModule(ptx("probe"));
			long lerp = d.function(module, "lerp4");
			long stream = d.createStream();
			long begin = d.createEvent(true);
			long end = d.createEvent(true);
			int n = 1 << 20;
			long bytes = n * 16L;
			long prev = d.alloc(bytes);
			long curr = d.alloc(bytes);
			long out = d.alloc(bytes);
			try (Arena arena = Arena.ofConfined()) {
				MemorySegment host = arena.allocate(bytes, 16);
				for (int i = 0; i < n; i++) {
					host.setAtIndex(ValueLayout.JAVA_FLOAT, i * 4L, i);
				}
				d.copyToDevice(prev, host, bytes, stream);
				for (int i = 0; i < n; i++) {
					host.setAtIndex(ValueLayout.JAVA_FLOAT, i * 4L, i + 2f);
				}
				d.copyToDevice(curr, host, bytes, stream);
				d.record(begin, stream);
				d.launch(lerp, CudaDriver.blocks(n, 256), 256, stream, prev, curr, out, n, 0.25f);
				d.record(end, stream);
				d.synchronize(stream);
				float kernelMs = d.elapsed(begin, end);
				d.copyToHost(host, out, bytes, stream);
				d.synchronize(stream);
				int wrong = 0;
				for (int i = 0; i < n; i++) {
					if (Math.abs(host.getAtIndex(ValueLayout.JAVA_FLOAT, i * 4L) - (i + 0.5f)) > 1e-3f * Math.max(1, i)) {
						wrong++;
					}
				}
				Inertia.LOGGER.info("GPUPROBE kernel on 1M particles: {} ms, {} wrong", kernelMs, wrong);

				// The kernel writes straight into an OpenGL buffer of the game.
				GpuBuffer buffer = RenderSystem.getDevice().createBuffer(() -> "Inertia GPU probe",
					GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST, bytes);
				int handle = ((GlBuffer) buffer).handle();
				long resource = d.registerGlBuffer(handle, 0);
				long frameNanos = 0;
				for (int k = 0; k < 10; k++) {
					long t = System.nanoTime();
					long mapped = d.map(resource, stream);
					d.launch(lerp, CudaDriver.blocks(n, 256), 256, stream, prev, curr, mapped, n, 0.75f);
					d.unmap(resource, stream);
					frameNanos += System.nanoTime() - t;
				}
				long mapped = d.map(resource, stream);
				d.copyToHost(host, mapped, bytes, stream);
				d.synchronize(stream);
				d.unmap(resource, stream);
				wrong = 0;
				for (int i = 0; i < n; i++) {
					if (Math.abs(host.getAtIndex(ValueLayout.JAVA_FLOAT, i * 4L) - (i + 1.5f)) > 1e-3f * Math.max(1, i)) {
						wrong++;
					}
				}
				Inertia.LOGGER.info("GPUPROBE OpenGL buffer {} written by CUDA: {} wrong, map+launch+unmap {} ms on the CPU",
					handle, wrong, frameNanos / 10 / 1e6);
				d.unregister(resource);
				buffer.close();
			}
			d.free(prev);
			d.free(curr);
			d.free(out);
			d.destroyEvent(begin);
			d.destroyEvent(end);
			d.destroyStream(stream);
			d.unloadModule(module);
		} finally {
			d.popContext();
		}
		Inertia.LOGGER.info("GPUPROBE done in {} ms", (System.nanoTime() - started) / 1e6);
	}
}
