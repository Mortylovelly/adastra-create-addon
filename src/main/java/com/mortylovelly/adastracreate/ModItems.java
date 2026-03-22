package com.mortylovelly.adastracreate;

import net.minecraft.world.item.Item;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModItems {
    public static final DeferredRegister<Item> ITEMS =
        DeferredRegister.create(ForgeRegistries.ITEMS, AdAstraCreate.MOD_ID);

    public static final RegistryObject<Item> STEEL_SHEET =
        ITEMS.register("steel_sheet", () -> new Item(new Item.Properties()));

    public static final RegistryObject<Item> SUPERALLOY_PLATE =
        ITEMS.register("superalloy_plate", () -> new Item(new Item.Properties()));
}
