package dev.packsage.knowledge.config;

import dev.packsage.knowledge.Evidence;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigProviderTest {
    private static final Evidence.Scope SCOPE = new Evidence.Scope("world", "en_us", "team", "config-test", 1);

    @Test
    void savedAndLoadedValuesAndMetadataRemainDistinct() throws Exception {
        Path root = Files.createTempDirectory("packsage-config");
        Path file = root.resolve("example-common.toml");
        Files.writeString(file, "[feature]\nenabled = false\n");
        var entry = new NeoForgeConfigCatalog.Entry("example", "COMMON", "example:COMMON:example-common.toml",
                file.getFileName().toString(), file, false, Map.of("feature.enabled",
                new NeoForgeConfigCatalog.Value(null, true, true, "Enables the feature", "WORLD", 0, 1)));
        var provider = new ConfigProvider(NeoForgeConfigCatalog.supplied(() -> List.of(entry)), List.of(root));

        var result = provider.query(new Evidence.Query(Evidence.Operation.GET,
                new Evidence.JsonObject(Map.of("key", new Evidence.JsonString("feature.enabled"))), 10, null, null), SCOPE)
                .toCompletableFuture().join();

        assertEquals(1, result.records().size());
        var payload = result.records().getFirst().payload().values();
        assertEquals(new Evidence.JsonBoolean(false), payload.get("savedValue"));
        assertEquals(new Evidence.JsonBoolean(true), payload.get("loadedValue"));
        assertEquals(new Evidence.JsonBoolean(true), payload.get("default"));
        assertEquals(new Evidence.JsonString("WORLD"), payload.get("restart"));
        assertEquals(Evidence.Authority.LOADED, result.records().getFirst().source().authority());
    }

    @Test
    void synchronizedServerValueHasNoLocalPathAndRemoteUnknownIsAbsent() {
        var entry = new NeoForgeConfigCatalog.Entry("servermod", "SERVER", "servermod:SERVER:servermod-server.toml",
                "servermod-server.toml", null, true, Map.of("rules.limit",
                new NeoForgeConfigCatalog.Value(null, 7, 3, "Server limit", "NONE", 0, 10)));
        var provider = new ConfigProvider(NeoForgeConfigCatalog.supplied(() -> List.of(entry)), List.of());

        var result = provider.query(new Evidence.Query(Evidence.Operation.SEARCH,
                new Evidence.JsonObject(Map.of("text", new Evidence.JsonString("limit"))), 10, null, null), SCOPE)
                .toCompletableFuture().join();

        assertEquals(1, result.records().size());
        var payload = result.records().getFirst().payload().values();
        assertEquals(new Evidence.JsonNull(), payload.get("filePath"));
        assertEquals(new Evidence.JsonNumber(new java.math.BigDecimal("7")), payload.get("loadedValue"));
        assertTrue(provider.status().availability() == dev.packsage.knowledge.KnowledgeProvider.Availability.AVAILABLE);
    }

    @Test
    void unsupportedBridgeStillReportsSavedFilesAndRejectsEscapingPaths() throws Exception {
        Path root = Files.createTempDirectory("packsage-config");
        Path file = root.resolve("safe.toml");
        Files.writeString(file, "answer = 42\n");
        var safe = new NeoForgeConfigCatalog.Entry("safe", "CLIENT", "safe:CLIENT:safe.toml", "safe.toml", file,
                false, Map.of());
        var escape = new NeoForgeConfigCatalog.Entry("escape", "COMMON", "escape:COMMON:../outside.toml", "../outside.toml",
                root.resolve("..").resolve("outside.toml"), false, Map.of());
        var reader = new NeoForgeConfigCatalog.Reader() {
            public List<NeoForgeConfigCatalog.Entry> read() { return List.of(safe, escape); }
            public boolean supported() { return false; }
            public String detail() { return "unsupported test bridge"; }
        };
        var provider = new ConfigProvider(reader, List.of(root));

        var result = provider.query(new Evidence.Query(Evidence.Operation.SEARCH,
                new Evidence.JsonObject(Map.of()), 10, null, null), SCOPE).toCompletableFuture().join();

        assertEquals(1, result.records().size());
        assertEquals(Evidence.Coverage.PARTIAL, result.coverage());
        assertEquals(new Evidence.JsonNumber(new java.math.BigDecimal("42")),
                result.records().getFirst().payload().values().get("savedValue"));
    }
}
