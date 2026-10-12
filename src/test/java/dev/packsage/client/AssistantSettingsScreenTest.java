package dev.packsage.client;

import dev.packsage.chat.PackSageSettings;
import dev.packsage.model.ModelBackend;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssistantSettingsScreenTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void closeSavesSettingsAndReturnsToAssistantOnce() throws Exception {
        PackSageSettings settings = PackSageSettings.defaults();
        AtomicInteger returned = new AtomicInteger();
        AssistantSettingsScreen screen = new AssistantSettingsScreen(settings, temporaryDirectory.resolve("settings.properties"),
                net.minecraft.network.chat.Component.literal("Settings"),
                List.of(new ModelBackend.ModelInfo("model", "Model", true, true, 1_000, 100)), "model", returned::incrementAndGet);

        settings.setModelId("model");
        settings.setReasoningEffort(ModelBackend.ReasoningEffort.HIGH);
        screen.onClose();

        assertEquals(1, returned.get());
        assertTrue(Files.isRegularFile(temporaryDirectory.resolve("settings.properties")));
        assertEquals(ModelBackend.ReasoningEffort.HIGH, PackSageSettings.load(temporaryDirectory.resolve("settings.properties")).reasoningEffort());
    }
}
