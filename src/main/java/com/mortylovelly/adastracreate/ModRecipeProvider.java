package com.mortylovelly.adastracreate;

import com.simibubi.create.content.processing.recipe.ProcessingRecipeBuilder;
import com.simibubi.create.content.processing.recipe.ProcessingRecipeSerializer;
import com.simibubi.create.AllRecipeTypes;
import net.minecraft.data.PackOutput;
import net.minecraft.data.recipes.FinishedRecipe;
import net.minecraft.data.recipes.RecipeProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Items;
import net.minecraftforge.common.crafting.conditions.IConditionBuilder;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import net.minecraft.core.HolderLookup;

public class ModRecipeProvider extends RecipeProvider implements IConditionBuilder {

    public ModRecipeProvider(DataGenerator generator, CompletableFuture<HolderLookup.Provider> lookupProvider) {
        super(generator.getPackOutput(), lookupProvider);
    }

    @Override
    protected void buildRecipes(Consumer<FinishedRecipe> consumer) {

        // Железо -> Стальной слиток (обжиг вентилятором над горелкой)
        AllRecipeTypes.BLASTING.create(new ResourceLocation(AdAstraCreate.MOD_ID, "iron_to_steel"))
            .withItemIngredients(Items.IRON_INGOT)
            .output(new ResourceLocation("ad_astra", "steel_ingot"))
            .duration(100)
            .build(consumer);

        // Стальной слиток -> Стальная пластина (пресс)
        AllRecipeTypes.PRESSING.create(new ResourceLocation(AdAstraCreate.MOD_ID, "steel_ingot_to_plate"))
            .withItemIngredients(new ResourceLocation("ad_astra", "steel_ingot"))
            .output(new ResourceLocation("ad_astra", "steel_plate"))
            .duration(100)
            .build(consumer);

        // Стальной слиток -> Стальной стержень (пила)
        AllRecipeTypes.CUTTING.create(new ResourceLocation(AdAstraCreate.MOD_ID, "steel_ingot_to_rod"))
            .withItemIngredients(new ResourceLocation("ad_astra", "steel_ingot"))
            .output(new ResourceLocation("ad_astra", "steel_rod"), 2)
            .duration(50)
            .build(consumer);

        // 9 Стальных слитков -> Стальной блок (миксер/уплотнение)
        AllRecipeTypes.COMPACTING.create(new ResourceLocation(AdAstraCreate.MOD_ID, "steel_ingots_to_block"))
            .withItemIngredients(
                new ResourceLocation("ad_astra", "steel_ingot"),
                new ResourceLocation("ad_astra", "steel_ingot"),
                new ResourceLocation("ad_astra", "steel_ingot"),
                new ResourceLocation("ad_astra", "steel_ingot"),
                new ResourceLocation("ad_astra", "steel_ingot"),
                new ResourceLocation("ad_astra", "steel_ingot"),
                new ResourceLocation("ad_astra", "steel_ingot"),
                new ResourceLocation("ad_astra", "steel_ingot"),
                new ResourceLocation("ad_astra", "steel_ingot")
            )
            .output(new ResourceLocation("ad_astra", "steel_block"))
            .duration(200)
            .build(consumer);
    }
}
