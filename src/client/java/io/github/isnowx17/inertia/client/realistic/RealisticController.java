package io.github.isnowx17.inertia.client.realistic;

import io.github.isnowx17.inertia.client.fluid.FluidManager;
import io.github.isnowx17.inertia.client.fluid.FluidSimulation;
import io.github.isnowx17.inertia.client.fluid.RigidBodies;
import io.github.isnowx17.inertia.item.InertiaItems;
import io.github.isnowx17.inertia.realistic.RealisticItem;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * The player and the realistic bodies: placing realistic items (on a body's face they stick to it),
 * breaking a body's block with a hit (it drops as a realistic item), holding a body with the use key
 * and throwing it on release, and standing on bodies. Client thread.
 */
public final class RealisticController {
	private static final double REACH = 5.0;
	/** How far in front of the eyes a held body is carried, and how hard it is pulled there. */
	private static final double HOLD_DISTANCE = 2.5;
	private static final double HOLD_PULL = 12.0;
	private static final double HOLD_MAX_SPEED = 12.0;

	/** Driving: forward acceleration on water and on rails, turning, top speed (blocks per second). */
	private static final double BOAT_THRUST = 18.0;
	private static final float BOAT_TURN = 5.0f;
	/** A boat stops speeding up at this speed (blocks per second), like a vanilla boat on water. */
	private static final double BOAT_TOP_SPEED = 8.0;
	private static final double CART_PUSH = 0.35;

	private final FluidManager water;
	private RigidBodies.Body held;
	/** The vehicle the player rides, or null. */
	private RigidBodies.Body riding;
	/** Which paddles of the boat {@link #rowingBody} row this tick (for the renderer). */
	private volatile boolean rowingLeft;
	private volatile boolean rowingRight;
	private volatile int rowingBody = -1;

	/** Whether the left (0) or right (1) paddle of body {@code id} rows. */
	public boolean rowing(int id, int side) {
		return id == rowingBody && (side == 0 ? rowingLeft : rowingRight);
	}
	private double heldDistance;

	public RealisticController(FluidManager water) {
		this.water = water;
	}

	/** What the player looks at among the bodies, if nearer than the block or entity looked at. */
	private RigidBodies.Hit aim(Minecraft client) {
		FluidSimulation sim = water.simulation();
		Player player = client.player;
		if (sim == null || player == null || sim.rigids().isEmpty()) {
			return null;
		}
		Vec3 eye = player.getEyePosition();
		Vec3 local = eye.subtract(sim.originX(), sim.originY(), sim.originZ());
		RigidBodies.Hit hit = sim.rigids().raycast(local, player.getLookAngle(), REACH);
		if (hit == null) {
			return null;
		}
		HitResult vanilla = client.hitResult;
		if (vanilla != null && vanilla.getType() != HitResult.Type.MISS && vanilla.getLocation().distanceTo(eye) < hit.distance()) {
			return null;
		}
		return hit;
	}

	// ------------------------------------------------------------------------------------------
	// Placing
	// ------------------------------------------------------------------------------------------

	public boolean place(Player player, InteractionHand hand, ItemStack stack, String kind, Identifier source) {
		Minecraft client = Minecraft.getInstance();
		if (!client.hasSingleplayerServer()) {
			return false;
		}
		RigidBodies.Hit bodyHit = aim(client);
		BlockHitResult blockHit = client.hitResult instanceof BlockHitResult b && b.getType() == HitResult.Type.BLOCK ? b : null;
		BlockPos around = blockHit != null ? blockHit.getBlockPos() : player.blockPosition();
		FluidSimulation sim = water.simulationForBodies(player, around);
		if (sim == null) {
			return false;
		}
		bodyHit = aim(client); // the step that was running may have moved them
		RigidBodies rigids = sim.rigids();
		Vec3 origin = new Vec3(sim.originX(), sim.originY(), sim.originZ());
		if (RealisticItem.BLOCK.equals(kind)) {
			if (!(BuiltInRegistries.ITEM.getValue(source) instanceof BlockItem blockItem)) {
				return false;
			}
			BlockState state = blockItem.getBlock().defaultBlockState();
			if (bodyHit != null && bodyHit.body().kind == RigidBodies.Kind.BLOCKS && bodyHit.part() >= 0) {
				// On a realistic block's face: stuck to it.
				RigidBodies.Part on = bodyHit.body().parts().get(bodyHit.part());
				rigids.attach(bodyHit.body(), on.x() + bodyHit.faceX(), on.y() + bodyHit.faceY(), on.z() + bodyHit.faceZ(), state);
			} else {
				rigids.addBlock(state, spot(player, blockHit).subtract(origin), Vec3.ZERO);
			}
			player.level().playLocalSound(BlockPos.containing(spot(player, blockHit)), state.getSoundType().getPlaceSound(), SoundSource.BLOCKS, 1f, 0.9f, false);
		} else {
			RigidBodies.Kind vehicle = RealisticItem.BOAT.equals(kind) ? RigidBodies.Kind.BOAT : RigidBodies.Kind.MINECART;
			Vec3 at = spot(player, blockHit).add(0, -0.2, 0);
			rigids.addVehicle(vehicle, source.toString(), at.subtract(origin), player.getYRot());
		}
		consume(player, hand);
		return true;
	}

	/** The centre of the block cell in front of the clicked face, or a little in front of the player. */
	private static Vec3 spot(Player player, BlockHitResult blockHit) {
		if (blockHit != null) {
			return Vec3.atCenterOf(blockHit.getBlockPos().relative(blockHit.getDirection()));
		}
		return player.getEyePosition().add(player.getLookAngle().scale(2.5));
	}

	private static void consume(Player player, InteractionHand hand) {
		if (player.getAbilities().instabuild) {
			return;
		}
		onServer(player, sp -> sp.getItemInHand(hand).shrink(1));
	}

	private static void onServer(Player player, Consumer<ServerPlayer> action) {
		var server = Minecraft.getInstance().getSingleplayerServer();
		if (server == null) {
			return;
		}
		java.util.UUID id = player.getUUID();
		server.execute(() -> {
			ServerPlayer sp = server.getPlayerList().getPlayer(id);
			if (sp != null) {
				action.accept(sp);
			}
		});
	}

	// ------------------------------------------------------------------------------------------
	// Breaking and holding (from the mixin on Minecraft's attack and use)
	// ------------------------------------------------------------------------------------------

	/** Whether the crosshair is on a body (vanilla then must not mine the block behind it). */
	public boolean aimingAtBody(Minecraft client) {
		return held == null && aim(client) != null;
	}

	/** The attack key: breaks the block of the body looked at. Whether it did. */
	public boolean attack(Minecraft client) {
		RigidBodies.Hit hit = aim(client);
		if (hit == null) {
			return false;
		}
		FluidSimulation sim = water.simulationForBodies(client.player, client.player.blockPosition());
		if (sim == null) {
			return false;
		}
		hit = aim(client);
		if (hit == null) {
			return true;
		}
		RigidBodies rigids = sim.rigids();
		Vec3 origin = new Vec3(sim.originX(), sim.originY(), sim.originZ());
		ItemStack drop;
		Vec3 at;
		BlockState sound;
		if (hit.part() >= 0) {
			RigidBodies.Part part = hit.body().parts().get(hit.part());
			at = RigidBodies.cellCentre(hit.body(), part.x(), part.y(), part.z()).add(origin);
			rigids.breakPart(hit.body(), hit.part());
			drop = RealisticItem.of(InertiaItems.REALISTIC, new ItemStack(part.state().getBlock().asItem()));
			sound = part.state();
		} else {
			RigidBodies.Body body = hit.body();
			at = new Vec3(body.current()[0], body.current()[1], body.current()[2]).add(origin);
			rigids.remove(body);
			drop = RealisticItem.forSource(InertiaItems.REALISTIC, Identifier.parse(body.source));
			sound = null;
		}
		client.player.swing(InteractionHand.MAIN_HAND, client.player.getMainHandItem().getAttackAnimation(), false);
		if (sound != null) {
			client.level.playLocalSound(BlockPos.containing(at), sound.getSoundType().getBreakSound(), SoundSource.BLOCKS, 1f, 0.8f, false);
		}
		if (!client.player.getAbilities().instabuild && !drop.isEmpty()) {
			Vec3 spot = at;
			ItemStack stack = drop;
			onServer(client.player, sp -> {
				ItemEntity item = new ItemEntity(sp.level(), spot.x, spot.y, spot.z, stack);
				item.setDefaultPickUpDelay();
				sp.level().addFreshEntity(item);
			});
		}
		return true;
	}

	/** The use key on a body: picks it up to carry (not with a realistic item, which places). */
	public boolean use(Minecraft client) {
		if (held != null) {
			return true;
		}
		ItemStack main = client.player.getMainHandItem();
		if (main.getItem() instanceof RealisticItem) {
			return false;
		}
		RigidBodies.Hit hit = aim(client);
		if (hit == null) {
			return false;
		}
		if (hit.body().kind != RigidBodies.Kind.BLOCKS && !client.player.isShiftKeyDown()) {
			// Getting into a boat or a minecart (sneak: pick it up instead).
			riding = hit.body();
			client.player.swing(InteractionHand.MAIN_HAND, client.player.getMainHandItem().getAttackAnimation(), false);
			return true;
		}
		held = hit.body();
		heldDistance = Math.max(1.5, Math.min(HOLD_DISTANCE, hit.distance()));
		client.player.swing(InteractionHand.MAIN_HAND, client.player.getMainHandItem().getAttackAnimation(), false);
		return true;
	}

	/** Every tick: carries the held body (dropped and thrown on releasing the key), and lets the player stand on bodies. */
	public void tick(Minecraft client) {
		Player player = client.player;
		FluidSimulation sim = water.simulation();
		if (player == null || sim == null) {
			held = null;
			return;
		}
		RigidBodies rigids = sim.rigids();
		ride(client, player, sim);
		if (held != null) {
			if (!rigids.bodies().contains(held) || !client.options.keyUse.isDown()) {
				held = null; // let go: it keeps the speed it had, so a quick turn throws it
			} else {
				Vec3 origin = new Vec3(sim.originX(), sim.originY(), sim.originZ());
				Vec3 target = player.getEyePosition().add(player.getLookAngle().scale(heldDistance)).subtract(origin);
				float[] pose = held.current();
				Vec3 at = new Vec3(pose[0], pose[1], pose[2]);
				Vec3 velocity = target.subtract(at).scale(HOLD_PULL);
				if (velocity.length() > HOLD_MAX_SPEED) {
					velocity = velocity.normalize().scale(HOLD_MAX_SPEED);
				}
				sim.settle(player.blockPosition());
				rigids.push(held, velocity);
			}
		}
		collide(player, sim);
		standOn(player, sim);
	}

	/** How hard the player pushes a body they walk into (kilograms times blocks per second squared): wood slides, stone stays. */
	private static final double PUSH_FORCE = 4800.0;
	private static final double PLAYER_RADIUS = 0.3;

	/**
	 * The bodies are solid for the player, who pushes them with their own strength: the player's box is
	 * out of the scene near them (it would shove them along like an infinitely heavy wall).
	 */
	private void collide(Player player, FluidSimulation sim) {
		RigidBodies rigids = sim.rigids();
		Vec3 origin = new Vec3(sim.originX(), sim.originY(), sim.originZ());
		Vec3 feet = player.position().subtract(origin);
		boolean near = false;
		for (RigidBodies.Body b : rigids.bodies()) {
			float[] p = b.current();
			if (feet.distanceToSqr(p[0], p[1], p[2]) < 36) {
				near = true;
				break;
			}
		}
		sim.setPlayerNearBodies(near);
		if (!near || riding != null || player.isSpectator()) {
			return;
		}
		Vec3 motion = player.getDeltaMovement();
		double pushX = 0, pushZ = 0;
		double[] heights = {0.4, 0.9, 1.45};
		for (RigidBodies.Body b : rigids.bodies()) {
			if (b == held) {
				continue;
			}
			float[] pose = b.current();
			org.joml.Quaternionf q = RigidBodies.rotation(b);
			org.joml.Quaternionf inv = new org.joml.Quaternionf(q).conjugate();
			float[] boxes = RigidBodies.boxesOf(b);
			for (double h : heights) {
				Vec3 point = feet.add(0, h, 0);
				org.joml.Vector3f local = inv.transform(new org.joml.Vector3f((float) (point.x - pose[0]), (float) (point.y - pose[1]),
					(float) (point.z - pose[2])));
				for (int k = 0; k < boxes.length; k += 6) {
					float lx = local.x - boxes[k], ly = local.y - boxes[k + 1], lz = local.z - boxes[k + 2];
					float cx = Math.max(-boxes[k + 3], Math.min(boxes[k + 3], lx));
					float cy = Math.max(-boxes[k + 4], Math.min(boxes[k + 4], ly));
					float cz = Math.max(-boxes[k + 5], Math.min(boxes[k + 5], lz));
					org.joml.Vector3f d = new org.joml.Vector3f(lx - cx, ly - cy, lz - cz);
					float dist = d.length();
					if (dist >= PLAYER_RADIUS) {
						continue;
					}
					if (dist < 1e-5f) {
						d.set(0, 0, 0); // inside: out through the nearest side face
						float ex = boxes[k + 3] - Math.abs(lx), ez = boxes[k + 5] - Math.abs(lz);
						if (ex < ez) {
							d.x = Math.signum(lx);
						} else {
							d.z = Math.signum(lz);
						}
						dist = 0f;
					} else {
						d.div(dist);
					}
					org.joml.Vector3f n = q.transform(new org.joml.Vector3f(d));
					double horizontal = Math.sqrt(n.x * n.x + n.z * n.z);
					if (horizontal < 0.5) {
						continue; // above or below it: standing on it is handled apart
					}
					double depth = PLAYER_RADIUS - dist;
					pushX += n.x / horizontal * depth;
					pushZ += n.z / horizontal * depth;
					// Walking into it: the player's strength goes into the body.
					double into = -(motion.x * n.x + motion.z * n.z);
					if (into > 0.01) {
						Vec3 contact = point.subtract(n.x * PLAYER_RADIUS, n.y * PLAYER_RADIUS, n.z * PLAYER_RADIUS);
						rigids.shove(b, new Vec3(-n.x, 0, -n.z).normalize().scale(PUSH_FORCE), contact);
					}
				}
			}
		}
		if (pushX != 0 || pushZ != 0) {
			double len = Math.sqrt(pushX * pushX + pushZ * pushZ);
			double cap = Math.min(len, PLAYER_RADIUS);
			player.setPos(player.getX() + pushX / len * cap, player.getY(), player.getZ() + pushZ / len * cap);
			double nx = pushX / len, nz = pushZ / len;
			double into = motion.x * nx + motion.z * nz;
			if (into < 0) {
				player.setDeltaMovement(motion.x - nx * into, motion.y, motion.z - nz * into);
			}
		}
	}

	/** Riding a boat or a minecart: the player sits in it and drives it; sneak gets out. */
	private void ride(Minecraft client, Player player, FluidSimulation sim) {
		RigidBodies rigids = sim.rigids();
		if (riding != null && (!rigids.bodies().contains(riding) || player.isSpectator())) {
			riding = null;
		}
		sim.setPlayerRiding(riding != null);
		rowingLeft = false;
		rowingRight = false;
		if (riding == null) {
			return;
		}
		Vec3 origin = new Vec3(sim.originX(), sim.originY(), sim.originZ());
		float[] pose = riding.current();
		Vec3 centre = new Vec3(pose[0], pose[1], pose[2]);
		if (client.options.keyShift.isDown()) {
			riding = null;
			sim.setPlayerRiding(false);
			player.setPos(centre.x + origin.x, centre.y + origin.y + 1.0, centre.z + origin.z);
			return;
		}
		sim.settle(player.blockPosition());
		var options = client.options;
		int forward = (options.keyUp.isDown() ? 1 : 0) - (options.keyDown.isDown() ? 1 : 0);
		int turn = (options.keyLeft.isDown() ? 1 : 0) - (options.keyRight.isDown() ? 1 : 0);
		Vec3 facing = RigidBodies.forward(riding);
		if (riding.kind == RigidBodies.Kind.BOAT) {
			Vec3 flat = new Vec3(facing.x, 0, facing.z);
			if (flat.lengthSqr() > 1e-6) {
				flat = flat.normalize();
			}
			double speed = Math.abs(rigids.velocity(riding).dot(flat));
			double thrust = speed < BOAT_TOP_SPEED ? BOAT_THRUST : 0.0;
			rigids.drive(riding, flat.scale(forward * thrust), turn * BOAT_TURN);
			// The paddles row like a vanilla boat's: forward both, turning the outer one.
			rowingLeft = forward != 0 || turn < 0;
			rowingRight = forward != 0 || turn > 0;
			rowingBody = riding.id;
		} else {
			minecart(client, rigids, riding, origin, forward);
		}
		// Sitting in it, looking around freely.
		float sit = riding.kind == RigidBodies.Kind.BOAT ? 0.05f : 0.1f;
		player.setPos(centre.x + origin.x, centre.y + origin.y - riding.vehicleHalf().y + sit, centre.z + origin.z);
		player.setDeltaMovement(Vec3.ZERO);
		player.setOnGround(true);
		player.resetFallDistance();
	}

	/** A minecart on rails follows them (straight, up and down, round curves); off rails it is pushed along. */
	private void minecart(Minecraft client, RigidBodies rigids, RigidBodies.Body cart, Vec3 origin, int forward) {
		float[] pose = cart.current();
		BlockPos at = BlockPos.containing(pose[0] + origin.x, pose[1] + origin.y - cart.vehicleHalf().y + 0.1, pose[2] + origin.z);
		var level = client.level;
		var state = level.getBlockState(at);
		if (!(state.getBlock() instanceof net.minecraft.world.level.block.BaseRailBlock rail)) {
			at = at.below();
			state = level.getBlockState(at);
		}
		Vec3 facing = RigidBodies.forward(cart);
		if (!(state.getBlock() instanceof net.minecraft.world.level.block.BaseRailBlock rail)) {
			Vec3 flat = new Vec3(facing.x, 0, facing.z);
			rigids.drive(cart, flat.lengthSqr() > 1e-6 ? flat.normalize().scale(forward * 2.0) : Vec3.ZERO, 0f);
			return;
		}
		var shape = state.getValue(rail.getShapeProperty());
		Vec3 dir = switch (shape) {
			case NORTH_SOUTH -> new Vec3(0, 0, 1);
			case EAST_WEST -> new Vec3(1, 0, 0);
			case ASCENDING_EAST -> new Vec3(1, 1, 0).normalize();
			case ASCENDING_WEST -> new Vec3(-1, 1, 0).normalize();
			case ASCENDING_NORTH -> new Vec3(0, 1, -1).normalize();
			case ASCENDING_SOUTH -> new Vec3(0, 1, 1).normalize();
			// Curves: the two ends; the cart goes to the one ahead of it.
			default -> curve(shape, facing);
		};
		if (dir.dot(facing) < 0 && (shape == net.minecraft.world.level.block.state.properties.RailShape.NORTH_SOUTH
			|| shape == net.minecraft.world.level.block.state.properties.RailShape.EAST_WEST)) {
			dir = dir.scale(-1);
		}
		double floor = at.getY() - origin.y + cart.vehicleHalf().y + 0.0625;
		boolean flat = dir.y == 0;
		rigids.followRail(cart, dir, forward * CART_PUSH, flat ? floor : Double.NaN);
	}

	private static Vec3 curve(net.minecraft.world.level.block.state.properties.RailShape shape, Vec3 facing) {
		Vec3 a;
		Vec3 b;
		switch (shape) {
			case SOUTH_EAST -> {
				a = new Vec3(0, 0, 1);
				b = new Vec3(1, 0, 0);
			}
			case SOUTH_WEST -> {
				a = new Vec3(0, 0, 1);
				b = new Vec3(-1, 0, 0);
			}
			case NORTH_WEST -> {
				a = new Vec3(0, 0, -1);
				b = new Vec3(-1, 0, 0);
			}
			default -> {
				a = new Vec3(0, 0, -1);
				b = new Vec3(1, 0, 0);
			}
		}
		// Coming in along one end's direction (reversed), it leaves through the other.
		return facing.dot(a.scale(-1)) > facing.dot(b.scale(-1)) ? b : a;
	}

	/** The player rests on the body under their feet. */
	private void standOn(Player player, FluidSimulation sim) {
		sim.setPlayerStanding(false);
		if (riding != null || player.isSpectator() || player.getAbilities().flying || sim.rigids().isEmpty()) {
			return;
		}
		Vec3 origin = new Vec3(sim.originX(), sim.originY(), sim.originZ());
		var box = player.getBoundingBox();
		double top = Double.NEGATIVE_INFINITY;
		double[][] points = {{0, 0}, {-0.25, -0.25}, {0.25, -0.25}, {-0.25, 0.25}, {0.25, 0.25}};
		for (double[] p : points) {
			Vec3 from = new Vec3(player.getX() + p[0], box.minY + 0.6, player.getZ() + p[1]).subtract(origin);
			RigidBodies.Hit hit = sim.rigids().raycast(from, new Vec3(0, -1, 0), 1.0);
			if (hit != null && hit.body() != held) {
				top = Math.max(top, from.y - hit.distance() + origin.y);
			}
		}
		if (top == Double.NEGATIVE_INFINITY) {
			return;
		}
		Vec3 motion = player.getDeltaMovement();
		if (box.minY > top + 0.02 || (motion.y > 0.05 && box.minY > top - 0.05)) {
			return;
		}
		sim.setPlayerStanding(true);
		player.setPos(player.getX(), top, player.getZ());
		if (motion.y < 0) {
			player.setDeltaMovement(motion.x, 0, motion.z);
		}
		player.setOnGround(true);
		player.resetFallDistance();
	}
}
