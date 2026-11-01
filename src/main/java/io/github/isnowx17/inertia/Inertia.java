package io.github.isnowx17.inertia;

import io.github.isnowx17.inertia.item.InertiaItems;
import net.fabricmc.api.ModInitializer;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Common entrypoint. Everything that touches PhysX lives in the client source set:
 * the fluid simulation runs on the player's NVIDIA GPU and Inertia is singleplayer only.
 */
public final class Inertia implements ModInitializer {
	public static final String MOD_ID = "inertia";
	public static final Logger LOGGER = LoggerFactory.getLogger("Inertia");

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}

	@Override
	public void onInitialize() {
		InertiaItems.register();
	}
}
