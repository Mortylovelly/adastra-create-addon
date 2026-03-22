package com.mortylovelly.adastracreate;

import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

@Mod(AdAstraCreate.MOD_ID)
public class AdAstraCreate {
    public static final String MOD_ID = "adastra_create";

    public AdAstraCreate() {
        IEventBus bus = FMLJavaModLoadingContext.get().getModEventBus();
        ModItems.ITEMS.register(bus);
    }
}
