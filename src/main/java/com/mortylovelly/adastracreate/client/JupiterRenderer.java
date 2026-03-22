package com.mortylovelly.adastracreate.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Axis;
import earth.terrarium.adastra.api.client.events.AdAstraClientEvents;
import earth.terrarium.adastra.client.screens.PlanetsScreen;
import earth.terrarium.adastra.common.constants.PlanetConstants;
import net.minecraft.Util;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = adastra_create, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class JupiterRenderer {

    public static final ResourceLocation JUPITER =
        new ResourceLocation(adastra_create, textures/environment/jupiter.png);

    public static void register() {
        AdAstraClientEvents.RenderSolarSystemEvent.register((graphics, solarSystem, width, height) -> {
            if (!PlanetConstants.SOLAR_SYSTEM.equals(solarSystem)) return;

            // Рисуем орбиту Юпитера - радиус 150 (5-я орбита)
            Tesselator tessellator = Tesselator.getInstance();
            BufferBuilder bufferBuilder = tessellator.getBuilder();
            RenderSystem.setShader(GameRenderer::getPositionColorShader);
            bufferBuilder.begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);
            PlanetsScreen.drawCircle(bufferBuilder, width / 2f, height / 2f, 150, 75, 0xff24327b);
            tessellator.end();

            // Рисуем Юпитер на орбите
            // Скорость вращения медленнее Марса (реалистично - Юпитер дальше)
            float rotation = Util.getMillis() / 100f * 0.2f;

            graphics.pose().pushPose();
            graphics.pose().translate(width / 2f, height / 2f, 0);
            graphics.pose().mulPose(Axis.ZP.rotationDegrees(rotation));
            // Смещаем на 150 пикселей (радиус орбиты) минус половина размера иконки
            graphics.pose().translate(150 - 8, 0, 0);
            // Рисуем Юпитер размером 16x16 (как другие планеты)
            graphics.blit(JUPITER, 0, 0, 0, 0, 16, 16, 16, 16);
            graphics.pose().popPose();
        });
    }
}
