package me.infin1te.aei;

import com.google.gson.stream.JsonWriter;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
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

@SuppressWarnings("null")
public class ExporterScreen extends Screen {

    public static final int DEFAULT_IMAGE_SIZE = 256;
    private static final int ITEM_SIZE = 16;
    private static final Logger LOGGER = LogUtils.getLogger();
    private final int imageSize;
    private final boolean fullExport;
    private boolean exported = false;
    private int exportedCount = 0;

    protected ExporterScreen() {
        this(DEFAULT_IMAGE_SIZE, false);
    }

    protected ExporterScreen(int imageSize) {
        this(imageSize, false);
    }

    protected ExporterScreen(int imageSize, boolean fullExport) {
        super(Component.literal("AEI Block Item Exporter"));
        this.imageSize = imageSize;
        this.fullExport = fullExport;
    }

    @Override
    public void render(@Nonnull net.minecraft.client.gui.GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (!exported) {
            exported = true;
            exportAllItems();
            Minecraft.getInstance().setScreen(null);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void exportAllItems() {
        try {
            Path outputDir = Path.of("AEIExport");
            Path texturesDir = outputDir.resolve("inventory_images");
            Files.createDirectories(texturesDir);

            Path translationsFile = outputDir.resolve("translations.json");
            try (BufferedWriter writer = Files.newBufferedWriter(translationsFile,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                 JsonWriter json = new JsonWriter(writer)) {

                json.setIndent("  ");
                json.beginObject();

                Language language = Language.getInstance();

                for (Item item : ForgeRegistries.ITEMS.getValues()) {
                    if (item instanceof BlockItem) {
                        exportItem(language, item, texturesDir, json);
                        exportedCount++;
                    }
                }

                json.endObject();
            }
            var player = Minecraft.getInstance().player;
            if (player != null) {
                player.displayClientMessage(Component.literal("AEI: Exported " + exportedCount + " block item images at " + imageSize + "x" + imageSize + " to AEIExport/inventory_images"), false);
            }

            if (fullExport) {
                runRecipeAndPackageExport();
            }
        } catch (IOException e) {
            LOGGER.error("Failed to export item data", e);
        }
    }

    private void runRecipeAndPackageExport() {
        Minecraft mc = Minecraft.getInstance();
        Thread fullExportThread = new Thread(() -> {
            if (!RecipeExport.exportRecipesSync()) {
                if (mc.player != null) {
                    mc.player.displayClientMessage(Component.literal("AEI: JEI runtime not ready, exported images/translations only."), false);
                }
                return;
            }

            if (mc.player != null) {
                mc.player.displayClientMessage(Component.literal("AEI: Recipes exported to AEIExport/recipes.json"), false);
            }

            try {
                Path output = AeiPackageBuilder.buildPackage();
                if (mc.player != null) {
                    mc.player.displayClientMessage(Component.literal("AEI: Package created at " + output), false);
                }
            } catch (IOException e) {
                LOGGER.error("Failed building AEI package", e);
                if (mc.player != null) {
                    mc.player.displayClientMessage(Component.literal("AEI: Failed to build package after export."), false);
                }
            }
        }, "AEI-FullExport");
        fullExportThread.setDaemon(true);
        fullExportThread.start();
    }

    @SuppressWarnings("null")
    private void exportItem(Language language, Item item, Path texturesDir, JsonWriter json) throws IOException {
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(item);
        if (id == null) {
            return;
        }

        ItemStack stack = new ItemStack(item);
        String translated = language.has(stack.getDescriptionId())
                ? language.getOrDefault(stack.getDescriptionId())
                : stack.getDescriptionId();

        json.name(id.toString()).value(translated);

        Path path = texturesDir.resolve(id.getNamespace() + "_" + id.getPath() + ".png");
        try (NativeImage image = renderItemToImage(stack)) {
            image.writeToFile(path);
        }
    }

    private NativeImage renderItemToImage(ItemStack stack) {
        Minecraft mc = Minecraft.getInstance();
        int guiWidth = mc.getWindow().getGuiScaledWidth();
        int guiHeight = mc.getWindow().getGuiScaledHeight();
        int captureSize = Math.min(Math.min(guiWidth, guiHeight), imageSize);

        RenderTarget mainTarget = mc.getMainRenderTarget();
        TextureTarget itemTarget = new TextureTarget(guiWidth, guiHeight, true, Minecraft.ON_OSX);

        itemTarget.bindWrite(true);
        RenderSystem.viewport(0, 0, guiWidth, guiHeight);
        RenderSystem.clearColor(0.0F, 0.0F, 0.0F, 0.0F);
        RenderSystem.clear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);

        GuiGraphics offscreenGraphics = new GuiGraphics(mc, mc.renderBuffers().bufferSource());
        float scale = captureSize / (float) ITEM_SIZE;
        float drawX = (guiWidth - captureSize) / (2.0F * scale);
        float drawY = (guiHeight - captureSize) / (2.0F * scale);

        offscreenGraphics.pose().pushPose();
        offscreenGraphics.pose().scale(scale, scale, 1.0F);
        offscreenGraphics.renderItem(stack, Math.round(drawX), Math.round(drawY));
        offscreenGraphics.pose().popPose();
        offscreenGraphics.flush();

        itemTarget.bindRead();
        NativeImage frame = new NativeImage(guiWidth, guiHeight, true);
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

        mainTarget.bindWrite(true);
        RenderSystem.viewport(0, 0, mc.getWindow().getWidth(), mc.getWindow().getHeight());
        itemTarget.destroyBuffers();
        frame.close();

        return processed;
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