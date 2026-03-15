package me.infin1te.aei;

import com.google.gson.stream.JsonWriter;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import mezz.jei.api.ingredients.IIngredientHelper;
import mezz.jei.api.ingredients.IIngredientRenderer;
import mezz.jei.api.ingredients.IIngredientType;
import mezz.jei.api.runtime.IIngredientManager;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;
import org.lwjgl.opengl.GL11;
import org.slf4j.Logger;

import javax.annotation.Nonnull;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Supplier;

@SuppressWarnings("null")
public class ExporterScreen extends Screen {

    public static final int DEFAULT_IMAGE_SIZE = 256;
    private static final int ITEM_SIZE = 16;
    /** Number of items rendered per frame. Higher = faster export but more frame drops. */
    private static final int ITEMS_PER_FRAME = 100;
    private static final int PROGRESS_CHAT_STEP_PERCENT = 1;
    private static final Logger LOGGER = LogUtils.getLogger();

    private final int imageSize;
    private final boolean fullExport;

    /**
     * One export unit: optionally writes a translation entry, then renders and saves a PNG.
     * translationKey/translationValue may be null to skip the JSON entry.
     * renderFn is called on the GL thread; null return skips the image.
     */
    @SuppressWarnings("rawtypes")
    private record ExportTask(
            String translationKey,
            String translationValue,
            Path relativePath,
            Supplier<NativeImage> renderFn) {
    }

    // State initialized in init()
    private List<ExportTask> tasks;
    private int currentIndex = 0;
    private int exportedCount = 0;
    private JsonWriter jsonWriter;
    private BufferedWriter fileWriter;
    private Path texturesDir;
    private ExecutorService ioExecutor;
    private final List<CompletableFuture<Void>> pendingWrites = new ArrayList<>();
    private boolean initialized = false;
    private boolean finishing = false;
    private int lastProgressPct = -1;

    // Reused render buffers to avoid reallocating per texture
    private TextureTarget reusableTarget;
    private NativeImage reusableFrame;
    private int reusableWidth = -1;
    private int reusableHeight = -1;

    protected ExporterScreen() {
        this(DEFAULT_IMAGE_SIZE, false);
    }

    protected ExporterScreen(int imageSize) {
        this(imageSize, false);
    }

    protected ExporterScreen(int imageSize, boolean fullExport) {
        super(Component.literal("AEI Exporter"));
        this.imageSize = imageSize;
        this.fullExport = fullExport;
    }

    @Override
    public void init() {
        try {
            Path outputDir = Path.of("AEIExport");
            texturesDir = outputDir.resolve("inventory_images");
            Files.createDirectories(texturesDir);

            Path translationsFile = outputDir.resolve("translations.json");
            fileWriter = Files.newBufferedWriter(translationsFile,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            jsonWriter = new JsonWriter(fileWriter);
            jsonWriter.setIndent("  ");
            jsonWriter.beginObject();

            tasks = new ArrayList<>();
            buildItemTasks(tasks);

            // Export textures for all non-item JEI ingredient types
            // (Forge fluids, Mekanism gases/infuse-types/pigments/slurries, etc.)
            IJeiRuntime jeiRuntime = RecipeExport.getJeiRuntime();
            if (jeiRuntime != null) {
                IIngredientManager mgr = jeiRuntime.getIngredientManager();
                for (IIngredientType<?> type : mgr.getRegisteredIngredientTypes()) {
                    if (type.getIngredientClass() == ItemStack.class) {
                        continue;
                    }
                    addJeiIngredientTasks(mgr, type, tasks);
                }
            }

            int ioThreads = Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors() / 2));
            ioExecutor = Executors.newFixedThreadPool(ioThreads, r -> {
                Thread t = new Thread(r, "AEI-ImageWriter");
                t.setDaemon(true);
                return t;
            });

            var player = Minecraft.getInstance().player;
            if (player != null) {
                player.displayClientMessage(Component.literal(
                        "AEI: Started export of " + tasks.size() + " textures"), false);
            }

            initialized = true;
        } catch (IOException e) {
            LOGGER.error("Failed to initialise AEI export", e);
            Minecraft.getInstance().setScreen(null);
        }
    }

    @Override
    public void render(@Nonnull GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (!initialized || finishing) {
            return;
        }

        int total = tasks.size();

        // Process a small batch each frame so the game remains responsive
        int batchEnd = Math.min(currentIndex + ITEMS_PER_FRAME, total);
        for (int i = currentIndex; i < batchEnd; i++) {
            ExportTask task = tasks.get(i);

            if (task.translationKey() != null) {
                try {
                    jsonWriter.name(task.translationKey()).value(task.translationValue());
                } catch (IOException e) {
                    LOGGER.error("AEI: Failed writing translation for {}", task.translationKey(), e);
                }
            }

            // GL render must stay on the main thread
            NativeImage image = null;
            try {
                image = task.renderFn().get();
            } catch (Exception e) {
                LOGGER.error("AEI: Failed rendering {}", task.relativePath(), e);
            }

            if (image != null) {
                Path dest = texturesDir.resolve(task.relativePath());
                final NativeImage img = image;
                pendingWrites.add(CompletableFuture.runAsync(() -> {
                    try (img) {
                        Files.createDirectories(dest.getParent());
                        img.writeToFile(dest);
                    } catch (IOException e) {
                        LOGGER.error("AEI: Failed saving {}", dest, e);
                    }
                }, ioExecutor));
                exportedCount++;
            }
        }
        currentIndex = batchEnd;

        logProgressToChat(total);

        // Progress overlay
        int w = this.width;
        int h = this.height;
        graphics.fill(0, 0, w, h, 0xAA000000);
        int pct = total > 0 ? (currentIndex * 100 / total) : 0;
        graphics.drawCenteredString(font,
                "AEI Export: " + currentIndex + " / " + total + " (" + pct + "%)",
                w / 2, h / 2 - 10, 0xFFFFFFFF);
        int barW = w / 2;
        int barX = (w - barW) / 2;
        int barY = h / 2 + 5;
        graphics.fill(barX, barY, barX + barW, barY + 8, 0xFF555555);
        graphics.fill(barX, barY, barX + (barW * currentIndex / Math.max(total, 1)), barY + 8, 0xFF55FF55);

        if (currentIndex >= total) {
            finishExport();
        }
    }

    private void finishExport() {
        finishing = true;
        try {
            jsonWriter.endObject();
            jsonWriter.close();
        } catch (IOException e) {
            LOGGER.error("Failed to close translations writer", e);
        }

        Minecraft mc = Minecraft.getInstance();
        int count = exportedCount;
        CompletableFuture.allOf(pendingWrites.toArray(new CompletableFuture[0]))
                .thenRunAsync(() -> mc.execute(() -> {
                    if (ioExecutor != null) {
                        ioExecutor.shutdown();
                    }
                    cleanupReusableRenderBuffers();
                    if (mc.player != null) {
                        mc.player.displayClientMessage(Component.literal(
                                "AEI: Exported " + count + " textures to AEIExport/inventory_images"), false);
                    }
                    mc.setScreen(null);
                    if (fullExport) {
                        runRecipeAndPackageExport();
                    }
                }), ioExecutor);
    }

    @Override
    public void onClose() {
        if (initialized && !finishing) {
            finishing = true;
            try {
                jsonWriter.close();
            } catch (IOException ignored) {
            }
            if (ioExecutor != null) {
                ioExecutor.shutdown();
            }
            cleanupReusableRenderBuffers();
        }
        super.onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void runRecipeAndPackageExport() {
        Minecraft mc = Minecraft.getInstance();
        Thread thread = new Thread(() -> {
            if (!RecipeExport.exportRecipesSync()) {
                if (mc.player != null) {
                    mc.player.displayClientMessage(Component.literal(
                            "AEI: JEI runtime not ready, exported images/translations only."), false);
                }
                return;
            }
            if (mc.player != null) {
                mc.player.displayClientMessage(Component.literal(
                        "AEI: Recipes exported to AEIExport/recipes.json"), false);
            }
            try {
                Path output = AeiPackageBuilder.buildPackage();
                if (mc.player != null) {
                    mc.player.displayClientMessage(Component.literal(
                            "AEI: Package created at " + output), false);
                }
            } catch (IOException e) {
                LOGGER.error("Failed building AEI package", e);
                if (mc.player != null) {
                    mc.player.displayClientMessage(Component.literal(
                            "AEI: Failed to build package after export."), false);
                }
            }
        }, "AEI-FullExport");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Builds one ExportTask per item in the Forge registry.
     * Items are always included regardless of JEI availability.
     */
    private void buildItemTasks(List<ExportTask> out) {
        Language language = Language.getInstance();
        for (Item item : ForgeRegistries.ITEMS.getValues()) {
            ResourceLocation id = ForgeRegistries.ITEMS.getKey(item);
            if (id == null) {
                continue;
            }
            final ItemStack stack = new ItemStack(item);
            final String translationKey = id.toString();
            final String displayName = language.has(stack.getDescriptionId())
                    ? language.getOrDefault(stack.getDescriptionId())
                    : stack.getDescriptionId();
            final Path relativePath = Path.of(id.getNamespace() + "_" + id.getPath() + ".png");
            out.add(new ExportTask(translationKey, displayName, relativePath, () -> {
                Minecraft mc = Minecraft.getInstance();
                int guiWidth = mc.getWindow().getGuiScaledWidth();
                int guiHeight = mc.getWindow().getGuiScaledHeight();
                int captureSize = Math.min(Math.min(guiWidth, guiHeight), imageSize);
                float scale = captureSize / (float) ITEM_SIZE;
                float drawX = (guiWidth - captureSize) / (2.0F * scale);
                float drawY = (guiHeight - captureSize) / (2.0F * scale);
                return renderToImage(g -> {
                    g.pose().pushPose();
                    g.pose().scale(scale, scale, 1.0F);
                    g.renderItem(stack, Math.round(drawX), Math.round(drawY));
                    g.pose().popPose();
                });
            }));
        }
    }

    /**
     * Builds ExportTasks for every registered ingredient of the given JEI type.
     * Uses JEI's own IIngredientRenderer so it works for any mod's ingredient.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private void addJeiIngredientTasks(IIngredientManager mgr, IIngredientType<?> rawType, List<ExportTask> out) {
        final IIngredientHelper helper;
        final IIngredientRenderer renderer;
        try {
            helper = mgr.getIngredientHelper(rawType);
            renderer = mgr.getIngredientRenderer(rawType);
        } catch (Exception e) {
            LOGGER.warn("AEI: Cannot get helper/renderer for JEI type {}, skipping", rawType.getUid());
            return;
        }

        String typeUid = rawType.getUid().toString();
        String subfolder = typeUid.replace(":", "_").replace("/", "_");

        for (Object ingredient : mgr.getAllIngredients(rawType)) {
            if (ingredient == null) {
                continue;
            }
            ResourceLocation id;
            try {
                Object rl = helper.getResourceLocation(ingredient);
                if (!(rl instanceof ResourceLocation)) {
                    continue;
                }
                id = (ResourceLocation) rl;
            } catch (Exception e) {
                LOGGER.debug("AEI: No ResourceLocation for {} ingredient, skipping", typeUid);
                continue;
            }
            final Object ing = ingredient;
            final IIngredientRenderer rend = renderer;
            final String translationKey = typeUid + "/" + id;
            String translationValue;
            try {
                translationValue = String.valueOf(helper.getDisplayName(ingredient));
            } catch (Exception e) {
                LOGGER.debug("AEI: No display name for {} ingredient {}, using id", typeUid, id);
                translationValue = id.toString();
            }
            final Path relativePath = Path.of(subfolder, id.getNamespace() + "_" + id.getPath() + ".png");
            out.add(new ExportTask(translationKey, translationValue, relativePath, () -> renderJeiIngredient(rend, ing)));
        }
    }

    /**
     * Core render-to-image pipeline shared by all ingredient types.
     * Must be called on the GL/main thread.
     */
    private NativeImage renderToImage(Consumer<GuiGraphics> drawCall) {
        Minecraft mc = Minecraft.getInstance();
        int guiWidth = mc.getWindow().getGuiScaledWidth();
        int guiHeight = mc.getWindow().getGuiScaledHeight();
        int captureSize = Math.min(Math.min(guiWidth, guiHeight), imageSize);

        RenderTarget mainTarget = mc.getMainRenderTarget();
        ensureReusableRenderBuffers(guiWidth, guiHeight);
        TextureTarget itemTarget = reusableTarget;

        itemTarget.bindWrite(true);
        RenderSystem.viewport(0, 0, guiWidth, guiHeight);
        RenderSystem.clearColor(0.0F, 0.0F, 0.0F, 0.0F);
        RenderSystem.clear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);

        GuiGraphics offscreenGraphics = new GuiGraphics(mc, mc.renderBuffers().bufferSource());
        drawCall.accept(offscreenGraphics);
        offscreenGraphics.flush();

        itemTarget.bindRead();
        NativeImage frame = reusableFrame;
        frame.downloadTexture(0, true);
        frame.flipY();

        int cropX = (guiWidth - captureSize) / 2;
        int cropY = (guiHeight - captureSize) / 2;
        NativeImage image = new NativeImage(imageSize, imageSize, true);
        for (int y = 0; y < imageSize; y++) {
            int sy = cropY + (y * captureSize / imageSize);
            for (int x = 0; x < imageSize; x++) {
                int sx = cropX + (x * captureSize / imageSize);
                image.setPixelRGBA(x, y, frame.getPixelRGBA(sx, sy));
            }
        }

        NativeImage processed = makeBlackTransparentAndCenter(image);
        image.close();
        frame.flipY();

        mainTarget.bindWrite(true);
        RenderSystem.viewport(0, 0, mc.getWindow().getWidth(), mc.getWindow().getHeight());

        return processed;
    }

    private void ensureReusableRenderBuffers(int width, int height) {
        if (reusableTarget != null && reusableFrame != null && reusableWidth == width && reusableHeight == height) {
            return;
        }

        cleanupReusableRenderBuffers();
        reusableTarget = new TextureTarget(width, height, true, Minecraft.ON_OSX);
        reusableFrame = new NativeImage(width, height, true);
        reusableWidth = width;
        reusableHeight = height;
    }

    private void cleanupReusableRenderBuffers() {
        if (reusableTarget != null) {
            reusableTarget.destroyBuffers();
            reusableTarget = null;
        }
        if (reusableFrame != null) {
            reusableFrame.close();
            reusableFrame = null;
        }
        reusableWidth = -1;
        reusableHeight = -1;
    }

    private void logProgressToChat(int total) {
        var player = Minecraft.getInstance().player;
        if (player == null || total <= 0) {
            return;
        }

        int pct = currentIndex * 100 / total;
        if (pct >= 100) {
            return;
        }

        int bucket = pct / PROGRESS_CHAT_STEP_PERCENT;
        int lastBucket = lastProgressPct < 0 ? -1 : lastProgressPct / PROGRESS_CHAT_STEP_PERCENT;
        if (bucket > lastBucket) {
            lastProgressPct = pct;
            player.displayClientMessage(Component.literal(
                    "AEI: Export progress " + pct + "% (" + currentIndex + "/" + total + ")"), false);
        }
    }

    /**
     * Renders any JEI ingredient using JEI's own IIngredientRenderer.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private NativeImage renderJeiIngredient(IIngredientRenderer renderer, Object ingredient) {
        Minecraft mc = Minecraft.getInstance();
        int guiWidth = mc.getWindow().getGuiScaledWidth();
        int guiHeight = mc.getWindow().getGuiScaledHeight();
        int captureSize = Math.min(Math.min(guiWidth, guiHeight), imageSize);
        float scale = captureSize / (float) ITEM_SIZE;
        float drawX = (guiWidth - captureSize) / (2.0F * scale);
        float drawY = (guiHeight - captureSize) / (2.0F * scale);
        return renderToImage(g -> {
            g.pose().pushPose();
            g.pose().scale(scale, scale, 1.0F);
            g.pose().translate(drawX, drawY, 0.0);
            renderer.render(g, ingredient);
            g.pose().popPose();
        });
    }

    private NativeImage makeBlackTransparentAndCenter(NativeImage src) {
        int width = src.getWidth();
        int height = src.getHeight();

        int minX = width;
        int minY = height;
        int maxX = -1;
        int maxY = -1;

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int rgba = src.getPixelRGBA(x, y);
                int a = (rgba >>> 24) & 0xFF;
                int b = (rgba >>> 16) & 0xFF;
                int g = (rgba >>> 8) & 0xFF;
                int r = rgba & 0xFF;

                if (a > 0 && r <= 8 && g <= 8 && b <= 8) {
                    src.setPixelRGBA(x, y, 0x00000000);
                    continue;
                }

                if (((src.getPixelRGBA(x, y) >>> 24) & 0xFF) > 0) {
                    if (x < minX) minX = x;
                    if (y < minY) minY = y;
                    if (x > maxX) maxX = x;
                    if (y > maxY) maxY = y;
                }
            }
        }

        NativeImage out = new NativeImage(width, height, true);
        if (maxX < minX || maxY < minY) {
            return out;
        }

        int contentWidth = maxX - minX + 1;
        int contentHeight = maxY - minY + 1;
        int dstX = (width - contentWidth) / 2;
        int dstY = (height - contentHeight) / 2;

        for (int y = 0; y < contentHeight; y++) {
            for (int x = 0; x < contentWidth; x++) {
                out.setPixelRGBA(dstX + x, dstY + y, src.getPixelRGBA(minX + x, minY + y));
            }
        }

        return out;
    }
}
