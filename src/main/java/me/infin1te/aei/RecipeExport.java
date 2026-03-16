package me.infin1te.aei;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

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
    private static final String LEGACY_RECIPES_FILE = "recipes.json";
    private static final String RECIPE_CHUNKS_DIR = "recipes_by_type";
    private static final String RECIPES_MANIFEST_FILE = "recipes_manifest.json";

    private static volatile IJeiRuntime jeiRuntime;

    private record RecipeSerializationResult(String json, int slotCount, int ingredientCount) {
    }

    private record TypeExportSummary(
            String recipeType,
            String file,
            int recipeCount,
            int slotCount,
            int ingredientCount) {
    }

    @Override
    public @NotNull ResourceLocation getPluginUid() {
        return ResourceLocation.fromNamespaceAndPath(AeiMod.MOD_ID, "recipe_dump");
    }

    @Override
    public void onRuntimeAvailable(@Nonnull IJeiRuntime runtime) {
        jeiRuntime = runtime;
        LOGGER.info("AEI: JEI runtime is available for manual recipe export");
    }

    public static IJeiRuntime getJeiRuntime() {
        return jeiRuntime;
    }

    public static boolean exportRecipesAsync() {
        IJeiRuntime runtime = jeiRuntime;
        if (runtime == null) {
            return false;
        }

        Thread dumpThread = new Thread(RecipeExport::exportRecipesSync, "AEI-RecipeDump");
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
        Path legacyPath = outputDir.resolve(LEGACY_RECIPES_FILE);
        Path chunksDir = outputDir.resolve(RECIPE_CHUNKS_DIR);
        List<TypeExportSummary> summaries = new ArrayList<>();

        try {
            Files.createDirectories(outputDir);
            Files.createDirectories(chunksDir);
        } catch (IOException e) {
            LOGGER.error("Failed to create AEI export directory", e);
            return;
        }

        try (
                BufferedWriter writer = Files.newBufferedWriter(
                        legacyPath,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING
                );
                JsonWriter legacyJson = new JsonWriter(writer)
        ) {
            legacyJson.setIndent("  ");
            legacyJson.beginArray();

            IRecipeManager manager = runtime.getRecipeManager();
            runtime.getJeiHelpers().getAllRecipeTypes().forEach(type -> {
                try {
                    TypeExportSummary summary = dumpType(runtime, manager, type, legacyJson, chunksDir);
                    summaries.add(summary);
                } catch (IOException e) {
                    LOGGER.warn("Failed dumping recipe type {}", type.getUid(), e);
                }
            });

            legacyJson.endArray();
            writeManifest(outputDir.resolve(RECIPES_MANIFEST_FILE), summaries);

        } catch (IOException e) {
            LOGGER.error("Failed to write JEI recipe dump", e);
        }
    }

    @SuppressWarnings("null")
    private static <T> TypeExportSummary dumpType(
            IJeiRuntime runtime,
            IRecipeManager manager,
            RecipeType<T> type,
            @Nonnull JsonWriter legacyJson,
            Path chunksDir
    ) throws IOException {
        Objects.requireNonNull(legacyJson, "legacyJson");

        IRecipeCategory<T> category = manager.getRecipeCategory(type);
        List<T> recipes = manager.createRecipeLookup(type)
                .includeHidden()
                .get()
                .toList();

        String chunkFile = sanitizeTypeUid(type.getUid().toString()) + ".ndjson";
        Path chunkPath = chunksDir.resolve(chunkFile);

        int recipeCount = 0;
        int slotCount = 0;
        int ingredientCount = 0;

        try (BufferedWriter chunkWriter = Files.newBufferedWriter(
                chunkPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
        )) {
            for (T recipe : recipes) {
                if (recipe == null) {
                    continue;
                }

                Optional<IRecipeLayoutDrawable<T>> layoutOpt = manager.createRecipeLayoutDrawable(
                        category,
                        recipe,
                        runtime.getJeiHelpers().getFocusFactory().getEmptyFocusGroup()
                );

                if (layoutOpt.isEmpty()) {
                    continue;
                }

                IRecipeSlotsView slotsView = layoutOpt.get().getRecipeSlotsView();
                if (slotsView == null) {
                    continue;
                }

                RecipeSerializationResult serialized = serializeRecipe(type, recipe, slotsView);
                legacyJson.jsonValue(serialized.json());
                chunkWriter.write(serialized.json());
                chunkWriter.newLine();

                recipeCount++;
                slotCount += serialized.slotCount();
                ingredientCount += serialized.ingredientCount();
            }
        }

        return new TypeExportSummary(
                type.getUid().toString(),
                RECIPE_CHUNKS_DIR + "/" + chunkFile,
                recipeCount,
                slotCount,
                ingredientCount
        );
    }

    @SuppressWarnings("null")
    private static <T> RecipeSerializationResult serializeRecipe(
            RecipeType<T> type,
            T recipe,
            IRecipeSlotsView slotsView
    ) throws IOException {
        StringWriter sw = new StringWriter(1024);
        int slotCount = 0;
        int ingredientCount = 0;

        try (JsonWriter jsonWriter = new JsonWriter(sw)) {
            jsonWriter.beginObject();
            jsonWriter.name("recipeType").value(type.getUid().toString());
            jsonWriter.name("recipeClass").value(recipe.getClass().getName());

            jsonWriter.name("slots").beginArray();
            for (IRecipeSlotView slot : slotsView.getSlotViews()) {
                slotCount++;

                jsonWriter.beginObject();
                jsonWriter.name("role").value(slot.getRole().name());

                jsonWriter.name("ingredients").beginArray();
                for (ITypedIngredient<?> typed : slot.getAllIngredients().toList()) {
                    if (typed.getIngredient() != null) {
                        writeIngredient(typed, jsonWriter);
                        ingredientCount++;
                    }
                }
                jsonWriter.endArray();

                jsonWriter.endObject();
            }
            jsonWriter.endArray();

            jsonWriter.endObject();
        }

        return new RecipeSerializationResult(sw.toString(), slotCount, ingredientCount);
    }

    private static void writeManifest(Path manifestPath, List<TypeExportSummary> summaries) throws IOException {
        int totalRecipes = 0;
        int totalSlots = 0;
        int totalIngredients = 0;
        for (TypeExportSummary summary : summaries) {
            totalRecipes += summary.recipeCount();
            totalSlots += summary.slotCount();
            totalIngredients += summary.ingredientCount();
        }

        try (
                BufferedWriter writer = Files.newBufferedWriter(
                        manifestPath,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING
                );
                JsonWriter json = new JsonWriter(writer)
        ) {
            json.setIndent("  ");
            json.beginObject();
            json.name("formatVersion").value(1);
            json.name("legacyFile").value(LEGACY_RECIPES_FILE);
            json.name("chunkDirectory").value(RECIPE_CHUNKS_DIR);

            json.name("totals").beginObject();
            json.name("recipeTypes").value(summaries.size());
            json.name("recipes").value(totalRecipes);
            json.name("slots").value(totalSlots);
            json.name("ingredients").value(totalIngredients);
            json.endObject();

            json.name("types").beginArray();
            for (TypeExportSummary summary : summaries) {
                json.beginObject();
                json.name("recipeType").value(summary.recipeType());
                json.name("file").value(summary.file());
                json.name("recipes").value(summary.recipeCount());
                json.name("slots").value(summary.slotCount());
                json.name("ingredients").value(summary.ingredientCount());
                json.endObject();
            }
            json.endArray();
            json.endObject();
        }
    }

    private static String sanitizeTypeUid(String uid) {
        StringBuilder sb = new StringBuilder(uid.length());
        for (int i = 0; i < uid.length(); i++) {
            char c = uid.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '_' || c == '-') {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        return sb.toString();
    }

    @SuppressWarnings("null")
    private static void writeIngredient(ITypedIngredient<?> typed, @Nonnull JsonWriter json) throws IOException {
        Objects.requireNonNull(json, "json");
        Object ingredient = typed.getIngredient();

        if (ingredient instanceof ItemStack stack) {
            ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
            if (id == null) {
                return;
            }

            json.beginObject();
            json.name("type").value("item");
            json.name("id").value(id.toString());
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
            json.name("type").value("fluid");
            json.name("id").value(id.toString());
            json.name("amount").value(fluid.getAmount());
            json.endObject();
            return;
        }

        // Generic reflection-based handler for modded ingredient types
        // (Mekanism GasStack, InfusionStack, PigmentStack, SlurryStack, etc.)
        String typeUid = typed.getType().getUid().toString();
        Optional<ResourceLocation> registryKey = tryGetRegistryName(ingredient);
        if (registryKey.isPresent()) {
            json.beginObject();
            json.name("type").value(typeUid);
            json.name("id").value(registryKey.get().toString());
            OptionalLong amount = tryGetAmount(ingredient);
            if (amount.isPresent()) {
                json.name("amount").value(amount.getAsLong());
            }
            json.endObject();
            return;
        }

        // Last-resort fallback: at least record the JEI type and toString
        json.beginObject();
        json.name("type").value(typeUid);
        json.name("raw").value(String.valueOf(ingredient));
        json.endObject();
    }

    /**
     * Tries ingredient.getType().getRegistryName() - the standard pattern for Forge
     * registry entries (Mekanism chemicals, etc.).
     */
    private static Optional<ResourceLocation> tryGetRegistryName(Object ingredient) {
        try {
            Method getType = ingredient.getClass().getMethod("getType");
            Object chemType = getType.invoke(ingredient);
            if (chemType == null) {
                return Optional.empty();
            }

            // ForgeRegistryEntry path: getRegistryName()
            try {
                Method grn = chemType.getClass().getMethod("getRegistryName");
                Object result = grn.invoke(chemType);
                if (result instanceof ResourceLocation rl) {
                    return Optional.of(rl);
                }
            } catch (NoSuchMethodException ignored) {
            }

            // Fallback: getName() returning ResourceLocation (some Mekanism internals)
            try {
                Method getName = chemType.getClass().getMethod("getName");
                Object result = getName.invoke(chemType);
                if (result instanceof ResourceLocation rl) {
                    return Optional.of(rl);
                }
            } catch (NoSuchMethodException ignored) {
            }
        } catch (Exception ignored) {
        }
        return Optional.empty();
    }

    /** Tries ingredient.getAmount() - works for GasStack, InfusionStack, FluidStack, etc. */
    private static OptionalLong tryGetAmount(Object ingredient) {
        try {
            Method m = ingredient.getClass().getMethod("getAmount");
            Object val = m.invoke(ingredient);
            if (val instanceof Number n) {
                return OptionalLong.of(n.longValue());
            }
        } catch (Exception ignored) {
        }
        return OptionalLong.empty();
    }
}
