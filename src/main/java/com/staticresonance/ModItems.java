package com.staticresonance;

import net.minecraft.world.item.Item;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * Dummy item used as the Origins origin icon. Hidden from creative.
 */
public final class ModItems {
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, StaticResonanceMod.MOD_ID);

    public static final RegistryObject<Item> ORIGIN_ICON = ITEMS.register("origin_icon",
            () -> new Item(new Item.Properties()));

    private ModItems() {
    }
}
