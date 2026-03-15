package me.infin1te.aei;

import com.google.gson.stream.JsonWriter;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotView;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.recipe.IRecipeManager;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.runtime.IJeiRuntime;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fml.loading.FMLPaths;
import org.jetbrains.annotations.NotNull;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import java.util.Optional;

@JeiPlugin
public class JEI implements IModPlugin {

    @Override
    public @NotNull ResourceLocation getPluginUid() {
        return new ResourceLocation("aei", "recipe_dump");
    }

    @Override
    public void onRuntimeAvailable(@NotNull IJeiRuntime runtime) {
        // Run in a background thread so the client doesn't freeze
        new Thread(() -> dump(runtime), "AEI-RecipeDump").start();
    }

    private void dump(IJeiRuntime runtime) {

        Path path = FMLPaths.GAMEDIR.get().resolve("jei_recipe_dump.json");

        try (
                BufferedWriter writer = Files.newBufferedWriter(
                        path,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING
                );
                JsonWriter json = new JsonWriter(writer)
        ) {
            json.setIndent("  ");
            json.beginArray();

            IRecipeManager manager = runtime.getRecipeManager();

            runtime.getJeiHelpers().getAllRecipeTypes().forEach(type -> {
                try {
                    dumpType(runtime, manager, type, json);
                } catch (Exception ignored) {}
            });

            json.endArray();

        } catch (Exception e) {
            System.out.println("Idk something's wrong");
        }
    }

    private <T> void dumpType(
            IJeiRuntime runtime,
            IRecipeManager manager,
            RecipeType<T> type,
            JsonWriter json
    ) throws IOException {

        IRecipeCategory<T> category = manager.getRecipeCategory(type);

        List<T> recipes =
                manager.createRecipeLookup(type)
                        .includeHidden()
                        .get()
                        .toList();

        for (T recipe : recipes) {

            Optional<IRecipeLayoutDrawable<T>> layoutOpt =
                    manager.createRecipeLayoutDrawable(
                            category,
                            recipe,
                            runtime.getJeiHelpers().getFocusFactory().getEmptyFocusGroup()
                    );

            if (layoutOpt.isEmpty()) continue;

            IRecipeLayoutDrawable<T> layout = layoutOpt.get();
            IRecipeSlotsView slotsView = layout.getRecipeSlotsView();

            json.beginObject();

            json.name("recipeType").value(type.getUid().toString());
            json.name("recipeClass").value(recipe.getClass().getName());

            json.name("slots").beginArray();

            for (IRecipeSlotView slot : slotsView.getSlotViews()) {

                json.beginObject();

                json.name("role").value(slot.getRole().name());

                json.name("ingredients").beginArray();

                for (ITypedIngredient<?> typed : slot.getAllIngredients().toList()) {
                    writeIngredient(typed.getIngredient(), json);
                }

                json.endArray();
                json.endObject();
            }

            json.endArray();
            json.endObject();
        }
    }

    private void writeIngredient(Object ingredient, JsonWriter json) throws IOException {

        if (ingredient instanceof ItemStack stack) {
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());

            json.beginObject();
            json.name("item").value(id.toString());
            json.name("count").value(stack.getCount());

            if (stack.hasTag()) {
                assert stack.getTag() != null;
                json.name("nbt").value(stack.getTag().toString());
            }

            json.endObject();
            return;
        }

        if (ingredient instanceof FluidStack fluid) {
            ResourceLocation id = BuiltInRegistries.FLUID.getKey(fluid.getFluid());

            json.beginObject();
            json.name("fluid").value(id.toString());
            json.name("amount").value(fluid.getAmount());
            json.endObject();
            return;
        }

        // fallback for unknown types
        json.beginObject();
        json.name("unknown").value(ingredient.toString());
        json.endObject();
    }
}