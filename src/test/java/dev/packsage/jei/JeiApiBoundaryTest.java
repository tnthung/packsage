package dev.packsage.jei;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;

class JeiApiBoundaryTest {
    @Test
    void jeiApiIsIsolatedAndUsesTheCompatibleSurface() throws Exception {
        Path project = Path.of(System.getProperty("user.dir"));
        while (project != null && !Files.isDirectory(project.resolve("src/main/java"))) project = project.getParent();
        Path root = project.resolve("src/main/java");
        Path jei = root.resolve("dev/packsage/jei");
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(path -> path.toString().endsWith(".java")).toList()) {
                String source = Files.readString(path);
                if (!path.startsWith(jei)) {
                    assertFalse(source.contains("mezz.jei"), path.toString());
                } else if (!path.startsWith(jei.resolve("bookmark"))) {
                    for (String forbidden : List.of("getDisplayedIngredients", "getTagKey", "getSourceRecipeIds",
                            "getBookmarkManager", "IBookmarkManager")) {
                        assertFalse(source.contains(forbidden), path + " contains " + forbidden);
                    }
                }
            }
        }
    }
}
