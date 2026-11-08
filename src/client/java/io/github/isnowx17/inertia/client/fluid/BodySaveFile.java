package io.github.isnowx17.inertia.client.fluid;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The realistic bodies of one dimension as saved with the world: kind, source item, pose (world) and
 * blocks (block ids, cells of the body's frame).
 */
final class BodySaveFile {
	private static final int MAGIC = 0x494E4244; // "INBD"

	record Saved(RigidBodies.Kind kind, String source, double x, double y, double z, float qx, float qy, float qz, float qw,
		List<RigidBodies.Part> parts) {
	}

	private BodySaveFile() {
	}

	static void write(Path file, List<Saved> bodies) throws IOException {
		if (bodies.isEmpty()) {
			Files.deleteIfExists(file);
			return;
		}
		Files.createDirectories(file.getParent());
		Path temp = file.resolveSibling(file.getFileName() + ".tmp");
		try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(temp)))) {
			out.writeInt(MAGIC);
			out.writeInt(bodies.size());
			for (Saved b : bodies) {
				out.writeByte(b.kind().ordinal());
				out.writeUTF(b.source());
				out.writeDouble(b.x());
				out.writeDouble(b.y());
				out.writeDouble(b.z());
				out.writeFloat(b.qx());
				out.writeFloat(b.qy());
				out.writeFloat(b.qz());
				out.writeFloat(b.qw());
				out.writeInt(b.parts().size());
				for (RigidBodies.Part p : b.parts()) {
					out.writeInt(p.x());
					out.writeInt(p.y());
					out.writeInt(p.z());
					out.writeUTF(BuiltInRegistries.BLOCK.getKey(p.state().getBlock()).toString());
				}
			}
		}
		Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
	}

	static List<Saved> read(Path file) throws IOException {
		List<Saved> out = new ArrayList<>();
		if (!Files.isRegularFile(file)) {
			return out;
		}
		try (DataInputStream in = new DataInputStream(new GZIPInputStream(Files.newInputStream(file)))) {
			if (in.readInt() != MAGIC) {
				return out;
			}
			int n = in.readInt();
			for (int k = 0; k < n && k < 100_000; k++) {
				RigidBodies.Kind kind = RigidBodies.Kind.values()[in.readByte()];
				String source = in.readUTF();
				double x = in.readDouble(), y = in.readDouble(), z = in.readDouble();
				float qx = in.readFloat(), qy = in.readFloat(), qz = in.readFloat(), qw = in.readFloat();
				int count = in.readInt();
				List<RigidBodies.Part> parts = new ArrayList<>();
				for (int i = 0; i < count && i < 100_000; i++) {
					int px = in.readInt(), py = in.readInt(), pz = in.readInt();
					Block block = BuiltInRegistries.BLOCK.getValue(Identifier.parse(in.readUTF()));
					BlockState state = block.defaultBlockState();
					parts.add(new RigidBodies.Part(px, py, pz, state));
				}
				out.add(new Saved(kind, source, x, y, z, qx, qy, qz, qw, parts));
			}
		}
		return out;
	}
}
