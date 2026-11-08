package io.github.isnowx17.inertia.client.mixin;

import io.github.isnowx17.inertia.client.InertiaClient;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Hitting and using the realistic bodies (they are not blocks or entities, so vanilla does not see them). */
@Mixin(Minecraft.class)
abstract class MinecraftMixin {
	@Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
	private void inertia$attackBody(CallbackInfoReturnable<Boolean> cir) {
		if (InertiaClient.realistic() != null && InertiaClient.realistic().attack((Minecraft) (Object) this)) {
			cir.setReturnValue(false);
		}
	}

	@Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
	private void inertia$notThroughBody(boolean down, CallbackInfo ci) {
		if (down && InertiaClient.realistic() != null && InertiaClient.realistic().aimingAtBody((Minecraft) (Object) this)) {
			ci.cancel();
		}
	}

	@Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
	private void inertia$holdBody(CallbackInfo ci) {
		if (InertiaClient.realistic() != null && InertiaClient.realistic().use((Minecraft) (Object) this)) {
			ci.cancel();
		}
	}
}
