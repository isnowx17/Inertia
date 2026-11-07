package io.github.isnowx17.inertia.client.fluid;

import io.github.isnowx17.inertia.util.WaterAcoustics;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * The water's sounds, all Minecraft's own: drops and splashes where falling water lands, sloshing
 * where it is stirred, the gurgle of flowing water where it runs fast, the player
 * splashing in and swimming, and the buckets. Client thread, once per tick.
 */
final class WaterSounds {
	/** Sounds further away than this are not played (they could not be heard anyway). */
	private static final double REACH = 48.0;
	/** Small sounds (drops, a little stirred water) are only played this close. */
	private static final double SMALL_REACH = 24.0;
	/** At most this many splashes and moving-water sounds start per physics step. */
	private static final int SPLASHES_PER_STEP = 3;
	private static final int FLOWS_PER_STEP = 2;
	/** Blocks of falling water in a column below which it is drops, and a small splash. */
	private static final float DROPS = 0.03f;
	private static final float SMALL_SPLASH = 0.4f;
	/** Water slower than this (blocks per second) is only stirred: a light slosh, not a current. */
	private static final float CURRENT = 2.5f;
	/** Submersion the player counts as in the water from. */
	private static final double IN_WATER = 0.08;

	private final RandomSource random = RandomSource.create();
	/** Per cell (and kind): the game time it may sound again. */
	private final Long2LongOpenHashMap quietUntil = new Long2LongOpenHashMap();
	private long heardTick = Long.MIN_VALUE;
	private double lastSubmersion;
	private int swimDelay;

	void tick(Minecraft client, FluidSimulation sim, double submersion) {
		ClientLevel level = client.level;
		Player player = client.player;
		if (level == null || player == null) {
			return;
		}
		long now = level.getGameTime();
		WaterAcoustics.Result heard = sim.acoustics();
		if (heard != null && heard.tick != heardTick) {
			heardTick = heard.tick;
			if (quietUntil.size() > 4096) {
				quietUntil.clear();
			}
			splashes(level, player, heard, now);
			flows(level, player, heard, now);
		}
		playerSounds(level, player, submersion);
	}

	private void splashes(ClientLevel level, Player player, WaterAcoustics.Result heard, long now) {
		int played = 0;
		for (int i = 0; i < heard.splashCount && played < SPLASHES_PER_STEP; i++) {
			Vec3 at = spot(heard, heard.splashes, i);
			float amount = heard.splashes[i * WaterAcoustics.SPOT + 3];
			double reach = amount < SMALL_SPLASH ? SMALL_REACH : REACH;
			if (at.distanceToSqr(player.getEyePosition()) > reach * reach) {
				continue;
			}
			boolean onWater = heard.splashes[i * WaterAcoustics.SPOT + 5] > 0.5f;
			if (!onWater && !groundBelow(level, at)) {
				continue; // still falling through the air
			}
			SoundEvent sound;
			float volume;
			float pitch;
			int quiet;
			if (amount < DROPS) {
				// A few drops.
				sound = SoundEvents.POINTED_DRIPSTONE_DRIP_WATER;
				volume = 0.35f + amount * 8f;
				pitch = 0.9f + random.nextFloat() * 0.5f;
				quiet = 2 + random.nextInt(5);
			} else {
				// More water falling: louder, deeper splashes (never the player's diving sounds).
				sound = SoundEvents.GENERIC_SPLASH;
				volume = Mth.clamp(0.12f + amount * 0.25f, 0.12f, 0.7f);
				pitch = Mth.clamp(1.35f - amount * 0.2f, 0.75f, 1.35f) * (0.92f + random.nextFloat() * 0.16f);
				quiet = amount < SMALL_SPLASH ? 4 + random.nextInt(7) : 5 + random.nextInt(6);
			}
			if (!mayPlay(at, 0, now, quiet)) {
				continue;
			}
			level.playLocalSound(at.x, at.y, at.z, sound, SoundSource.BLOCKS, volume, pitch, false);
			played++;
		}
	}

	private void flows(ClientLevel level, Player player, WaterAcoustics.Result heard, long now) {
		int played = 0;
		for (int i = 0; i < heard.flowCount && played < FLOWS_PER_STEP; i++) {
			Vec3 at = spot(heard, heard.flows, i);
			float amount = heard.flows[i * WaterAcoustics.SPOT + 3];
			float speed = heard.flows[i * WaterAcoustics.SPOT + 4];
			boolean current = speed >= CURRENT;
			double reach = current ? REACH : SMALL_REACH;
			if (at.distanceToSqr(player.getEyePosition()) > reach * reach
				|| !mayPlay(at, 1, now, current ? 25 + random.nextInt(40) : 10 + random.nextInt(20))) {
				continue;
			}
			if (current) {
				level.playLocalSound(at.x, at.y, at.z, SoundEvents.WATER_AMBIENT, SoundSource.BLOCKS,
					Mth.clamp(0.2f + amount * 0.15f, 0.2f, 1f), 0.8f + random.nextFloat() * 0.4f, false);
			} else {
				// Water stirred a little: a light slosh.
				level.playLocalSound(at.x, at.y, at.z, SoundEvents.BOAT_PADDLE_WATER, SoundSource.BLOCKS,
					Mth.clamp(0.08f + amount * 0.4f, 0.08f, 0.3f), 1f + random.nextFloat() * 0.4f, false);
			}
			played++;
		}
	}

	private void playerSounds(ClientLevel level, Player player, double submersion) {
		if (player.isSpectator()) {
			lastSubmersion = 0;
			return;
		}
		Vec3 motion = player.getDeltaMovement();
		if (lastSubmersion < IN_WATER && submersion >= IN_WATER && motion.y < -0.1) {
			// Getting into the water: a dive only after a real fall, otherwise a light splash.
			float speed = (float) -motion.y;
			SoundEvent sound = speed > 1.2f ? SoundEvents.PLAYER_SPLASH_HIGH_SPEED
				: speed > 0.6f ? SoundEvents.PLAYER_SPLASH : SoundEvents.GENERIC_SPLASH;
			level.playLocalSound(player.getX(), player.getY(), player.getZ(), sound, SoundSource.PLAYERS,
				Mth.clamp(speed * 0.6f, 0.2f, 0.8f), 0.9f + random.nextFloat() * 0.2f, false);
		}
		double horizontal = motion.horizontalDistance();
		if (submersion > IN_WATER * 0.5 && horizontal > 0.03 && !player.getAbilities().flying) {
			// Wading or swimming: a sound every few steps, more often the faster.
			if (--swimDelay <= 0) {
				boolean deep = submersion > 0.4;
				level.playLocalSound(player.getX(), player.getY(), player.getZ(), deep ? SoundEvents.PLAYER_SWIM : SoundEvents.GENERIC_SWIM,
					SoundSource.PLAYERS, Mth.clamp((float) horizontal * 1.5f, 0.08f, deep ? 0.35f : 0.25f),
					0.9f + random.nextFloat() * 0.3f, false);
				swimDelay = Mth.clamp(14 - (int) (horizontal * 40), 6, 14) + random.nextInt(3);
			}
		} else {
			swimDelay = 0;
		}
		lastSubmersion = submersion;
	}

	/** Water poured from a bucket or a source turned on or off. */
	static void bucket(net.minecraft.world.level.Level level, Vec3 at, boolean empty) {
		level.playLocalSound(at.x, at.y, at.z, empty ? SoundEvents.BUCKET_EMPTY : SoundEvents.BUCKET_FILL, SoundSource.BLOCKS,
			1f, 1f, false);
	}

	/** Whether there is something solid right below (falling water landing on the ground). */
	private static boolean groundBelow(ClientLevel level, Vec3 at) {
		for (double dy = 0.1; dy <= 0.9; dy += 0.4) {
			BlockPos pos = BlockPos.containing(at.x, at.y - dy, at.z);
			if (!level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()) {
				return true;
			}
		}
		return false;
	}

	private boolean mayPlay(Vec3 at, int kind, long now, int quietTicks) {
		long key = BlockPos.asLong(Mth.floor(at.x / WaterAcoustics.CELL), Mth.floor(at.y / WaterAcoustics.CELL),
			Mth.floor(at.z / WaterAcoustics.CELL)) * 2 + kind;
		if (quietUntil.get(key) > now) {
			return false;
		}
		quietUntil.put(key, now + quietTicks);
		return true;
	}

	private static Vec3 spot(WaterAcoustics.Result heard, float[] spots, int i) {
		int o = i * WaterAcoustics.SPOT;
		return new Vec3(spots[o] + heard.originX, spots[o + 1] + heard.originY, spots[o + 2] + heard.originZ);
	}

	void reset() {
		quietUntil.clear();
		heardTick = Long.MIN_VALUE;
		lastSubmersion = 0;
		swimDelay = 0;
	}
}
