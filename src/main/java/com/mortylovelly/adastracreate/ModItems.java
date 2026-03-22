package com.mortylovelly.adastracreate;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModItems {
    public static final DeferredRegister<Item> ITEMS =
        DeferredRegister.create(ForgeRegistries.ITEMS, AdAstraCreate.MOD_ID);

    public static final DeferredRegister<CreativeModeTab> TABS =
        DeferredRegister.create(Registries.CREATIVE_MODE_TAB, AdAstraCreate.MOD_ID);

    public static final RegistryObject<Item> SUPERALLOY_PLATE =
        ITEMS.register("superalloy_plate", () -> new Item(new Item.Properties()));

    public static final RegistryObject<CreativeModeTab> MOD_TAB =
        TABS.register("adastra_create_tab", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.adastra_create"))
            .icon(() -> SUPERALLOY_PLATE.get().getDefaultInstance())
            .displayItems((params, output) -> {
                output.accept(SUPERALLOY_PLATE.get());
            })
            .build());
}
