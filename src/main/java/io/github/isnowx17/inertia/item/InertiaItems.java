package io.github.isnowx17.inertia.item;

import io.github.isnowx17.inertia.Inertia;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

public final class InertiaItems {
	public static final ResourceKey<Item> FLUID_BUCKET_KEY = ResourceKey.create(Registries.ITEM, Inertia.id("fluid_bucket"));

	public static final Item FLUID_BUCKET = Registry.register(
		BuiltInRegistries.ITEM,
		FLUID_BUCKET_KEY,
		new FluidBucketItem(new Item.Properties().setId(FLUID_BUCKET_KEY).stacksTo(1))
	);

	public static final ResourceKey<Item> FLUID_SOURCE_KEY = ResourceKey.create(Registries.ITEM, Inertia.id("fluid_source"));

	public static final Item FLUID_SOURCE = Registry.register(
		BuiltInRegistries.ITEM,
		FLUID_SOURCE_KEY,
		new FluidSourceItem(new Item.Properties().setId(FLUID_SOURCE_KEY).stacksTo(1))
	);

	public static final ResourceKey<Item> LAVA_BUCKET_KEY = ResourceKey.create(Registries.ITEM, Inertia.id("lava_bucket"));

	public static final Item LAVA_BUCKET = Registry.register(
		BuiltInRegistries.ITEM,
		LAVA_BUCKET_KEY,
		new FluidBucketItem(new Item.Properties().setId(LAVA_BUCKET_KEY).stacksTo(1))
	);

	public static final ResourceKey<Item> LAVA_SOURCE_KEY = ResourceKey.create(Registries.ITEM, Inertia.id("lava_source"));

	public static final Item LAVA_SOURCE = Registry.register(
		BuiltInRegistries.ITEM,
		LAVA_SOURCE_KEY,
		new FluidSourceItem(new Item.Properties().setId(LAVA_SOURCE_KEY).stacksTo(1))
	);

	public static final ResourceKey<Item> SLIME_BLOCK_KEY = ResourceKey.create(Registries.ITEM, Inertia.id("slime_block"));

	public static final Item SLIME_BLOCK = Registry.register(
		BuiltInRegistries.ITEM,
		SLIME_BLOCK_KEY,
		new SlimeBlockItem(new Item.Properties().setId(SLIME_BLOCK_KEY).stacksTo(1))
	);

	private InertiaItems() {
	}

	public static void register() {
		CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.TOOLS_AND_UTILITIES)
			.register(output -> {
				output.insertAfter(Items.WATER_BUCKET, FLUID_BUCKET, FLUID_SOURCE);
				output.insertAfter(Items.LAVA_BUCKET, LAVA_BUCKET, LAVA_SOURCE, SLIME_BLOCK);
			});
	}
}
