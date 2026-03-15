package me.infin1te.aei;

import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
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
        public static IJeiRuntime getJeiRuntime() {
            return jeiRuntime;
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
                    if (typed.getIngredient() != null) {
                        writeIngredient(typed, jsonWriter);
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
    private static void writeIngredient(ITypedIngredient<?> typed, @Nonnull JsonWriter json) throws IOException {
        Objects.requireNonNull(json, "json");
        Object ingredient = typed.getIngredient();

        if (ingredient instanceof ItemStack stack) {
            ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
            if (id == null) return;

            json.beginObject();
            json.name("type").value("item");
            json.name("id").value(id.toString());
            json.name("count").value(stack.getCount());
            var tag = stack.getTag();
            if (tag != null) json.name("nbt").value(tag.toString());
            json.endObject();
            return;
        }

        if (ingredient instanceof FluidStack fluid) {
            ResourceLocation id = ForgeRegistries.FLUIDS.getKey(fluid.getFluid());
            if (id == null) return;

            json.beginObject();
            json.name("type").value("fluid");
            json.name("id").value(id.toString());
            json.name("amount").value(fluid.getAmount());
            json.endObject();
            return;
        }

        // Generic reflection-based handler for modded ingredient types
        // (Mekanism GasStack, InfusionStack, PigmentStack, SlurryStack, etc.)
        // These all share the pattern: getType().getRegistryName() + getAmount()
        String typeUid = typed.getType().getUid().toString();
        Optional<ResourceLocation> registryKey = tryGetRegistryName(ingredient);
        if (registryKey.isPresent()) {
            json.beginObject();
            json.name("type").value(typeUid.toString());
            json.name("id").value(registryKey.get().toString());
            OptionalLong amount = tryGetAmount(ingredient);
            if (amount.isPresent()) json.name("amount").value(amount.getAsLong());
            json.endObject();
            return;
        }

        // Last-resort fallback: at least record the JEI type and toString
        json.beginObject();
        json.name("type").value(typeUid.toString());
        json.name("raw").value(String.valueOf(ingredient));
        json.endObject();
    }

    /**
     * Tries ingredient.getType().getRegistryName() — the standard pattern for Forge
     * registry entries (Mekanism chemicals, etc.).
     */
    private static Optional<ResourceLocation> tryGetRegistryName(Object ingredient) {
        try {
            Method getType = ingredient.getClass().getMethod("getType");
            Object chemType = getType.invoke(ingredient);
            if (chemType == null) return Optional.empty();

            // ForgeRegistryEntry path: getRegistryName()
            try {
                Method grn = chemType.getClass().getMethod("getRegistryName");
                Object result = grn.invoke(chemType);
                if (result instanceof ResourceLocation rl) return Optional.of(rl);
            } catch (NoSuchMethodException ignored) {}

            // Fallback: getName() returning ResourceLocation (some Mekanism internals)
            try {
                Method getName = chemType.getClass().getMethod("getName");
                Object result = getName.invoke(chemType);
                if (result instanceof ResourceLocation rl) return Optional.of(rl);
            } catch (NoSuchMethodException ignored) {}
        } catch (Exception ignored) {}
        return Optional.empty();
    }

    /** Tries ingredient.getAmount() — works for GasStack, InfusionStack, FluidStack, etc. */
    private static OptionalLong tryGetAmount(Object ingredient) {
        try {
            Method m = ingredient.getClass().getMethod("getAmount");
            Object val = m.invoke(ingredient);
            if (val instanceof Number n) return OptionalLong.of(n.longValue());
        } catch (Exception ignored) {}
        return OptionalLong.empty();
    }
}