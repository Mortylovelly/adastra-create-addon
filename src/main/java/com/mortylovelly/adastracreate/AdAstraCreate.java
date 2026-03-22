package com.mortylovelly.adastracreate;

import com.mortylovelly.adastracreate.client.JupiterRenderer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;

@Mod(AdAstraCreate.MOD_ID)
public class AdAstraCreate {
    public static final String MOD_ID = "adastra_create";

    public AdAstraCreate() {
        IEventBus bus = FMLJavaModLoadingContext.get().getModEventBus();
        ModItems.ITEMS.register(bus);
        ModItems.TABS.register(bus);

        // Регистрируем рендерер Юпитера только на клиенте
        if (FMLEnvironment.dist == Dist.CLIENT) {
            JupiterRenderer.register();
        }
    }
}
