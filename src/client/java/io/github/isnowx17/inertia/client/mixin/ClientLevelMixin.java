package io.github.isnowx17.inertia.client.mixin;

import io.github.isnowx17.inertia.client.InertiaClient;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Tells the fluid simulation about block changes so its collision geometry stays current. */
@Mixin(ClientLevel.class)
abstract class ClientLevelMixin {
	@Inject(method = "sendBlockUpdated", at = @At("HEAD"))
	private void inertia$onBlockUpdated(BlockPos pos, BlockState old, BlockState current, int updateFlags, CallbackInfo ci) {
		for (var fluid : InertiaClient.allFluids()) {
			fluid.onBlockChanged(pos);
		}
	}

	@Inject(method = "setSectionDirtyWithNeighbors", at = @At("HEAD"))
	private void inertia$onSectionDirty(int chunkX, int chunkY, int chunkZ, CallbackInfo ci) {
		// Collision shapes never extend past their own section, so the neighbours stay valid.
		for (var fluid : InertiaClient.allFluids()) {
			fluid.onSectionChanged(chunkX, chunkY, chunkZ);
		}
	}
}
