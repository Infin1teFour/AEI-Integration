package me.infin1te.aei;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

@Mod(AeiMod.MOD_ID)
@SuppressWarnings("null")
public class AeiMod {

    public static final String MOD_ID = "aei";
    private static final Logger LOGGER = LogUtils.getLogger();

    public AeiMod() {
        LOGGER.info("AEI initialized");
    }

    @Mod.EventBusSubscriber(modid = MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
    public static class ClientCommandEvents {

        @SubscribeEvent
        public static void registerClientCommands(RegisterClientCommandsEvent event) {
            event.getDispatcher().register(
                    Commands.literal("aei")
                            .then(Commands.literal("export_block_items")
                                    .executes(context -> startBlockItemExport(ExporterScreen.DEFAULT_IMAGE_SIZE))
                                    .then(Commands.argument("size", IntegerArgumentType.integer(16, 1024))
                                            .executes(context -> {
                                                int size = IntegerArgumentType.getInteger(context, "size");
                                                return startBlockItemExport(size);
                                            })))
                            .then(Commands.literal("export_all")
                                    .executes(context -> startFullExport(ExporterScreen.DEFAULT_IMAGE_SIZE))
                                    .then(Commands.argument("size", IntegerArgumentType.integer(16, 1024))
                                            .executes(context -> {
                                                int size = IntegerArgumentType.getInteger(context, "size");
                                                return startFullExport(size);
                                            })))
                            .then(Commands.literal("export_recipes")
                                    .executes(context -> exportRecipes()))
                            .then(Commands.literal("build_package")
                                    .executes(context -> buildPackage())));
        }

        private static int startBlockItemExport(int size) {
            Minecraft mc = Minecraft.getInstance();
            mc.tell(() -> mc.setScreen(new ExporterScreen(size)));

            if (mc.player != null) {
                mc.player.displayClientMessage(Component.literal("AEI: Starting block item export at " + size + "x" + size + "..."), false);
            }
            return Command.SINGLE_SUCCESS;
        }

        private static int startFullExport(int size) {
            Minecraft mc = Minecraft.getInstance();
            mc.tell(() -> mc.setScreen(new ExporterScreen(size, true)));

            if (mc.player != null) {
                mc.player.displayClientMessage(Component.literal("AEI: Starting full export at " + size + "x" + size + " (assets + recipes + package)..."), false);
            }
            return Command.SINGLE_SUCCESS;
        }

        private static int exportRecipes() {
            Minecraft mc = Minecraft.getInstance();
            if (!RecipeExport.exportRecipesAsync()) {
                if (mc.player != null) {
                    mc.player.displayClientMessage(Component.literal("AEI: JEI runtime not ready yet, open JEI once and try again."), false);
                }
                return 0;
            }

            if (mc.player != null) {
                mc.player.displayClientMessage(Component.literal("AEI: Exporting recipes to AEIExport/recipes.json..."), false);
            }
            return Command.SINGLE_SUCCESS;
        }

        private static int buildPackage() {
            Minecraft mc = Minecraft.getInstance();
            try {
                var output = AeiPackageBuilder.buildPackage();
                if (mc.player != null) {
                    mc.player.displayClientMessage(Component.literal("AEI: Package created at " + output), false);
                }
                return Command.SINGLE_SUCCESS;
            } catch (Exception e) {
                LOGGER.error("AEI: Failed to build package", e);
                if (mc.player != null) {
                    mc.player.displayClientMessage(Component.literal("AEI: Package build failed. Ensure translations, recipes, and inventory images exist."), false);
                }
                return 0;
            }
        }
    }
}