package me.infin1te.aei;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import javax.annotation.Nonnull;

import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import com.google.gson.stream.JsonWriter;
import com.mojang.logging.LogUtils;

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
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.registries.ForgeRegistries;

@JeiPlugin
@SuppressWarnings("null")
public class RecipeExport implements IModPlugin {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static volatile IJeiRuntime jeiRuntime;

    @Override
    public @NotNull ResourceLocation getPluginUid() {
        return ResourceLocation.fromNamespaceAndPath(AeiMod.MOD_ID, "recipe_dump");
    }

    @Override
    public void onRuntimeAvailable(@Nonnull IJeiRuntime runtime) {
        jeiRuntime = runtime;
        LOGGER.info("AEI: JEI runtime is available for manual recipe export");
    }

    public static boolean exportRecipesAsync() {
        IJeiRuntime runtime = jeiRuntime;
        if (runtime == null) {
            return false;
        }

        Thread dumpThread = new Thread(() -> exportRecipesSync(), "AEI-RecipeDump");
        dumpThread.setDaemon(true);
        dumpThread.start();
        return true;
    }

    public static boolean exportRecipesSync() {
        IJeiRuntime runtime = jeiRuntime;
        if (runtime == null) {
            return false;
        }

        dump(runtime);
        return true;
    }

    private static void dump(IJeiRuntime runtime) {

        Path outputDir = Path.of("AEIExport");
        Path path = outputDir.resolve("recipes.json");

        try {
            Files.createDirectories(outputDir);
        } catch (IOException e) {
            LOGGER.error("Failed to create AEI export directory", e);
            return;
        }

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
                } catch (IOException e) {
                    LOGGER.warn("Failed dumping recipe type {}", type.getUid(), e);
                }
            });

            json.endArray();

        } catch (IOException e) {
            LOGGER.error("Failed to write JEI recipe dump", e);
        }
    }

    @SuppressWarnings("null")
    private static <T> void dumpType(
            IJeiRuntime runtime,
            IRecipeManager manager,
            RecipeType<T> type,
            @Nonnull JsonWriter json
    ) throws IOException {
        JsonWriter jsonWriter = Objects.requireNonNull(json, "json");
        IRecipeCategory<T> category = manager.getRecipeCategory(type);

        List<T> recipes =
                manager.createRecipeLookup(type)
                        .includeHidden()
                        .get()
                        .toList();

        for (T recipe : recipes) {
            if (recipe == null) {
                continue;
            }

            Optional<IRecipeLayoutDrawable<T>> layoutOpt =
                    manager.createRecipeLayoutDrawable(
                            category,
                            recipe,
                            runtime.getJeiHelpers().getFocusFactory().getEmptyFocusGroup()
                    );

            if (layoutOpt.isEmpty()) continue;

            IRecipeLayoutDrawable<T> layout = layoutOpt.get();
            IRecipeSlotsView slotsView = layout.getRecipeSlotsView();
            if (slotsView == null) {
                continue;
            }

            jsonWriter.beginObject();

            jsonWriter.name("recipeType").value(type.getUid().toString());
            jsonWriter.name("recipeClass").value(recipe.getClass().getName());

            jsonWriter.name("slots").beginArray();

            for (IRecipeSlotView slot : slotsView.getSlotViews()) {

                jsonWriter.beginObject();

                jsonWriter.name("role").value(slot.getRole().name());

                jsonWriter.name("ingredients").beginArray();

                for (ITypedIngredient<?> typed : slot.getAllIngredients().toList()) {
                    Object ingredient = typed.getIngredient();
                    if (ingredient != null) {
                        writeIngredient(ingredient, jsonWriter);
                    }
                }

                jsonWriter.endArray();
                jsonWriter.endObject();
            }

            jsonWriter.endArray();
            jsonWriter.endObject();
        }
    }

    @SuppressWarnings("null")
    private static void writeIngredient(Object ingredient, @Nonnull JsonWriter json) throws IOException {
        Objects.requireNonNull(json, "json");

        if (ingredient instanceof ItemStack stack) {
            ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
            if (id == null) {
                return;
            }

            json.beginObject();
            json.name("item").value(id.toString());
            json.name("count").value(stack.getCount());

            var tag = stack.getTag();
            if (tag != null) {
                json.name("nbt").value(tag.toString());
            }

            json.endObject();
            return;
        }

        if (ingredient instanceof FluidStack fluid) {
            ResourceLocation id = ForgeRegistries.FLUIDS.getKey(fluid.getFluid());
            if (id == null) {
                return;
            }

            json.beginObject();
            json.name("fluid").value(id.toString());
            json.name("amount").value(fluid.getAmount());
            json.endObject();
            return;
        }

        // fallback for unknown types
        json.beginObject();
        json.name("unknown").value(String.valueOf(ingredient));
        json.endObject();
    }
}