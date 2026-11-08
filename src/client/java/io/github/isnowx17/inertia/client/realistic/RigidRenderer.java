package io.github.isnowx17.inertia.client.realistic;

import com.mojang.blaze3d.vertex.PoseStack;
import io.github.isnowx17.inertia.client.fluid.FluidManager;
import io.github.isnowx17.inertia.client.fluid.FluidSimulation;
import io.github.isnowx17.inertia.client.fluid.RigidBodies;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import java.util.List;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

/**
 * Draws the realistic bodies at their PhysX poses, moved smoothly between two physics steps: blocks
 * with the game's own block models (like falling blocks), boats and minecarts with the game's own
 * entity models (a stand-in entity that is never in the world).
 */
public final class RigidRenderer {
	private final FluidManager water;
	private final Int2ObjectOpenHashMap<Entity> standIns = new Int2ObjectOpenHashMap<>();
	private final Int2ObjectOpenHashMap<float[]> paddles = new Int2ObjectOpenHashMap<>();
	private long lastFrameNanos;
	private float frameSeconds;
	private ClientLevel standInLevel;

	public RigidRenderer(FluidManager water) {
		this.water = water;
	}

	public void collectSubmits(LevelRenderContext context) {
		FluidSimulation sim = water.simulation();
		Minecraft client = Minecraft.getInstance();
		ClientLevel level = client.level;
		if (sim == null || level == null) {
			standIns.clear();
			return;
		}
		List<RigidBodies.View> views = sim.rigids().views();
		if (level != standInLevel) {
			standIns.clear();
			standInLevel = level;
		}
		if (views.isEmpty()) {
			standIns.clear();
			return;
		}
		long now = System.nanoTime();
		frameSeconds = lastFrameNanos == 0 ? 0f : Math.min(0.1f, (now - lastFrameNanos) / 1e9f);
		lastFrameNanos = now;
		float t = Math.min(1f, Math.max(0f, (now - sim.rigids().stepNanos()) / 50_000_000f));
		var camera = context.levelState().cameraRenderState;
		Vec3 cam = camera.pos;
		var collector = context.submitNodeCollector();
		IntOpenHashSet seen = new IntOpenHashSet();
		for (RigidBodies.View view : views) {
			float[] a = view.previous();
			float[] b = view.current();
			double x = a[0] + (b[0] - a[0]) * t + sim.originX();
			double y = a[1] + (b[1] - a[1]) * t + sim.originY();
			double z = a[2] + (b[2] - a[2]) * t + sim.originZ();
			Quaternionf q = new Quaternionf(a[3], a[4], a[5], a[6]).slerp(new Quaternionf(b[3], b[4], b[5], b[6]), t);
			PoseStack pose = new PoseStack();
			pose.translate(x - cam.x, y - cam.y, z - cam.z);
			pose.rotate(q);
			if (view.kind() == RigidBodies.Kind.BLOCKS) {
				// All of the body's light comes from the open cell at its centre: the body is no block of
				// the world, so the light of the cells around it (inside the ground, say) would darken it.
				BlockPos lightAt = BlockPos.containing(x, y, z);
				if (!level.getBlockState(lightAt).getCollisionShape(level, lightAt).isEmpty()) {
					lightAt = lightAt.above();
				}
				var shading = turned(level.cardinalLighting(), q);
				for (RigidBodies.Part part : view.parts()) {
					pose.pushPose();
					pose.translate(part.x() - 0.5f, part.y() - 0.5f, part.z() - 0.5f);
					BlockPos at = BlockPos.containing(x + part.x(), y + part.y(), z + part.z());
					LitBlock state = new LitBlock(lightAt);
					state.randomSeedPos = BlockPos.ZERO;
					state.blockPos = at;
					state.blockState = part.state();
					state.biome = level.getBiome(at);
					state.cardinalLighting = shading;
					state.lightEngine = level.getLightEngine();
					collector.submitMovingBlock(pose, state, 0);
					pose.popPose();
				}
			} else {
				seen.add(view.id());
				Entity standIn = standIns.computeIfAbsent(view.id(), id -> create(level, view.source()));
				if (standIn == null) {
					continue;
				}
				standIn.setPos(x, y, z);
				standIn.setYRot(0f);
				standIn.setOldPosAndRot();
				var renderState = client.getEntityRenderDispatcher().extractEntity(standIn, 1f);
				if (renderState instanceof net.minecraft.client.renderer.entity.state.BoatRenderState boat) {
					// The paddles: they move while rowing (a vanilla boat's eighth of a turn per tick) and stay put otherwise.
					float[] phase = paddles.computeIfAbsent(view.id(), id -> new float[2]);
					var controller = io.github.isnowx17.inertia.client.InertiaClient.realistic();
					for (int side = 0; side < 2; side++) {
						if (controller != null && controller.rowing(view.id(), side)) {
							phase[side] += frameSeconds * 20f * (float) (Math.PI / 8);
						}
					}
					boat.rowingTimeLeft = phase[0];
					boat.rowingTimeRight = phase[1];
				}
				// The entity stands on its box's bottom; the body's pose is its centre.
				float half = view.kind() == RigidBodies.Kind.BOAT ? 0.22f : 0.35f;
				pose.translate(0f, -half, 0f);
				client.getEntityRenderDispatcher().submit(renderState, camera, 0, 0, 0, pose, collector);
			}
		}
		standIns.keySet().removeIf(id -> !seen.contains(id));
		paddles.keySet().removeIf(id -> !seen.contains(id));
	}

	/**
	 * The face shading for a turned body: each face of the model gets the shade of the way it faces in
	 * the world (a block on its side has its top shaded like a side, and the side that faces up lit
	 * like a top), blended by the normal's parts while the body is between two of those ways.
	 */
	private static net.minecraft.world.level.CardinalLighting turned(net.minecraft.world.level.CardinalLighting world, Quaternionf q) {
		float[] shade = new float[6];
		for (net.minecraft.core.Direction face : net.minecraft.core.Direction.values()) {
			org.joml.Vector3f n = q.transform(new org.joml.Vector3f(face.getStepX(), face.getStepY(), face.getStepZ()));
			shade[face.get3DDataValue()] = n.x * n.x * (n.x > 0 ? world.east() : world.west())
				+ n.y * n.y * (n.y > 0 ? world.up() : world.down())
				+ n.z * n.z * (n.z > 0 ? world.south() : world.north());
		}
		// get3DDataValue order: down, up, north, south, west, east (the record's order too).
		return new net.minecraft.world.level.CardinalLighting(shade[0], shade[1], shade[2], shade[3], shade[4], shade[5]);
	}

	/** A moving block whose light is the same everywhere: the light of one cell. */
	private static final class LitBlock extends MovingBlockRenderState {
		private final BlockPos lightAt;

		LitBlock(BlockPos lightAt) {
			this.lightAt = lightAt;
		}

		@Override
		public int getBrightness(net.minecraft.world.level.LightLayer layer, BlockPos pos) {
			return getLightEngine().getLayerListener(layer).getLightValue(lightAt);
		}

		@Override
		public int getRawBrightness(BlockPos pos, int darkening) {
			return getLightEngine().getRawBrightness(lightAt, darkening);
		}
	}

	private static Entity create(ClientLevel level, String source) {
		EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.tryParse(source));
		return type == null ? null : type.create(level, EntitySpawnReason.LOAD);
	}
}
