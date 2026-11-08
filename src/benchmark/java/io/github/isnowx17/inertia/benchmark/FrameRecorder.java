package io.github.isnowx17.inertia.benchmark;

import com.mojang.blaze3d.platform.NativeImage;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Records the game's own frames (no screen capture) into a video through an ffmpeg process: every
 * frame is read back from the main render target and placed on a fixed {@code fps} timeline by the
 * moment it was drawn (repeated when the game was slower, skipped when faster), so the video runs at
 * the speed of the game.
 */
final class FrameRecorder {
	private static final Logger LOGGER = LoggerFactory.getLogger("InertiaFilm");
	private static final int QUEUE = 6;

	private record Frame(byte[] pixels, int repeats) {
	}

	private final String ffmpeg;
	private final int fps;
	/** Game seconds per real second (a lower tick rate films in slow motion). */
	private final double speed;
	private Process process;
	private Thread writer;
	private BlockingQueue<Frame> queue;
	private final BlockingQueue<byte[]> free = new ArrayBlockingQueue<>(QUEUE + 2);
	private int width;
	private int height;
	private long startNanos;
	private long slots;
	private int captured;
	private int written;
	private Path file;

	FrameRecorder(String ffmpeg, int fps, double speed) {
		this.ffmpeg = ffmpeg;
		this.fps = fps;
		this.speed = speed;
	}

	boolean recording() {
		return file != null;
	}

	void start(Path file) {
		this.file = file;
		startNanos = 0L;
		slots = 0;
		captured = 0;
		written = 0;
	}

	/** Asks for this frame (call once per frame, after it was drawn). */
	void capture(Minecraft client) {
		if (file == null) {
			return;
		}
		long drawn = System.nanoTime();
		Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(), image -> accept(image, drawn));
	}

	private void accept(NativeImage image, long drawn) {
		try (image) {
			if (file == null) {
				return;
			}
			if (process == null) {
				open(image.getWidth(), image.getHeight());
				startNanos = drawn;
			}
			if (image.getWidth() != width || image.getHeight() != height) {
				return;
			}
			captured++;
			// Slots of the timeline up to this frame's moment get this frame.
			long due = (long) ((drawn - startNanos) * speed * fps / 1e9) + 1;
			int repeats = (int) Math.max(0, due - slots);
			if (repeats == 0) {
				return;
			}
			slots += repeats;
			byte[] pixels = free.poll();
			if (pixels == null) {
				pixels = new byte[width * height * 4];
			}
			MemoryUtil.memByteBuffer(image.getPointer(), pixels.length).get(pixels);
			queue.put(new Frame(pixels, repeats));
		} catch (Exception e) {
			LOGGER.error("Frame not recorded", e);
		}
	}

	private void open(int w, int h) throws IOException {
		width = w;
		height = h;
		queue = new ArrayBlockingQueue<>(QUEUE);
		process = new ProcessBuilder(ffmpeg, "-y", "-hide_banner", "-loglevel", "error",
			"-f", "rawvideo", "-pix_fmt", "rgba", "-s", w + "x" + h, "-r", Integer.toString(fps), "-i", "-",
			"-c:v", "libx264", "-preset", "veryfast", "-crf", "12", "-pix_fmt", "yuv420p", file.toString())
			.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.INHERIT).start();
		OutputStream out = process.getOutputStream();
		writer = new Thread(() -> {
			try {
				while (true) {
					Frame f = queue.take();
					if (f.pixels() == null) {
						break;
					}
					for (int r = 0; r < f.repeats(); r++) {
						out.write(f.pixels());
						written++;
					}
					free.offer(f.pixels());
				}
				out.close();
			} catch (Exception e) {
				LOGGER.error("Video writer stopped", e);
			}
		}, "Inertia film writer");
		writer.setDaemon(true);
		writer.start();
	}

	/** Finishes the video (waits for ffmpeg). */
	void stop() {
		if (file == null) {
			return;
		}
		Path done = file;
		file = null;
		try {
			if (process != null) {
				queue.put(new Frame(null, 0));
				writer.join();
				process.waitFor();
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		double seconds = slots / (double) fps;
		LOGGER.info("FILM {}: {} frames drawn, {} written ({} s), {} fps drawn", done.getFileName(), captured, written,
			String.format(java.util.Locale.ROOT, "%.1f", seconds), String.format(java.util.Locale.ROOT, "%.1f", captured / Math.max(0.001, seconds)));
		process = null;
		writer = null;
		free.clear();
	}
}
