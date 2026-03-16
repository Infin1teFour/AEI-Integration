package me.infin1te.aei;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class AeiPackageBuilder {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final DateTimeFormatter TS_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private AeiPackageBuilder() {
    }

    public static Path buildPackage() throws IOException {
        Path exportDir = Path.of("AEIExport");
        Path translations = exportDir.resolve("translations.json");
        Path recipes = exportDir.resolve("recipes.json");
        Path recipeManifest = exportDir.resolve("recipes_manifest.json");
        Path recipeChunks = exportDir.resolve("recipes_by_type");
        Path inventoryImages = exportDir.resolve("inventory_images");

        if (!Files.exists(translations)) {
            throw new IOException("Missing translations file: " + translations);
        }
        if (!Files.exists(recipes)) {
            throw new IOException("Missing recipes file: " + recipes);
        }
        if (!Files.isDirectory(inventoryImages)) {
            throw new IOException("Missing inventory images directory: " + inventoryImages);
        }

        String archiveName = "aei_export_" + LocalDateTime.now().format(TS_FORMAT) + ".aei";
        Path archivePath = exportDir.resolve(archiveName);

        try (OutputStream fileOut = Files.newOutputStream(archivePath);
             ZipOutputStream zipOut = new ZipOutputStream(fileOut)) {

            addFile(zipOut, translations, "translations.json");
            addFile(zipOut, recipes, "recipes.json");
            if (Files.exists(recipeManifest)) {
                addFile(zipOut, recipeManifest, "recipes_manifest.json");
            }
            if (Files.isDirectory(recipeChunks)) {
                addDirectory(zipOut, recipeChunks, "recipes_by_type");
            }
            addDirectory(zipOut, inventoryImages, "assets/inventory_images");
        }

        LOGGER.info("AEI: Built package {}", archivePath);
        return archivePath;
    }

    private static void addDirectory(ZipOutputStream zipOut, Path sourceDir, String zipRoot) throws IOException {
        try (var stream = Files.walk(sourceDir)) {
            for (Path path : (Iterable<Path>) stream::iterator) {
                if (Files.isDirectory(path)) {
                    continue;
                }
                Path relative = sourceDir.relativize(path);
                String entryName = zipRoot + "/" + relative.toString().replace('\\', '/');
                addFile(zipOut, path, entryName);
            }
        }
    }

    private static void addFile(ZipOutputStream zipOut, Path sourceFile, String entryName) throws IOException {
        ZipEntry entry = new ZipEntry(entryName);
        zipOut.putNextEntry(entry);
        try (InputStream in = Files.newInputStream(sourceFile)) {
            in.transferTo(zipOut);
        }
        zipOut.closeEntry();
    }
}
