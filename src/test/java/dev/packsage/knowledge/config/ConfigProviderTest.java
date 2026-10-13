package dev.packsage.knowledge.config;

import com.electronwill.nightconfig.core.CommentedConfig;
import dev.packsage.knowledge.Evidence;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ConfigTracker;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforgespi.language.IModInfo;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigProviderTest {
    private static final Evidence.Scope SCOPE = new Evidence.Scope("world", "en_us", "team", "config-test", 1);

    @Test
    void runtimeVersionRangesAcceptTheSupportedNeoForgeAndFmlLines() {
        assertTrue(NeoForgeConfigCatalog.versionMatches("[21.1.202,21.2)", "21.1.202"));
        assertTrue(NeoForgeConfigCatalog.versionMatches("[21.1.202,21.2)", "21.1.250-NEOFORGE"));
        assertFalse(NeoForgeConfigCatalog.versionMatches("[21.1.202,21.2)", "21.1.201"));
        assertFalse(NeoForgeConfigCatalog.versionMatches("[21.1.202,21.2)", "21.2"));
        assertFalse(NeoForgeConfigCatalog.versionMatches("[21.1.202,21.2)", "22"));
        assertFalse(NeoForgeConfigCatalog.versionMatches("[21.1.202,21.2)", "21.1.250-FORGE"));
        assertFalse(NeoForgeConfigCatalog.versionMatches("[21.1.202,21.2)", "21..250"));

        assertTrue(NeoForgeConfigCatalog.versionMatches("[4.0.41,5)", "4.0.41"));
        assertTrue(NeoForgeConfigCatalog.versionMatches("[4.0.41,5)", "4.0.45"));
        assertFalse(NeoForgeConfigCatalog.versionMatches("[4.0.41,5)", "4.0.40"));
        assertFalse(NeoForgeConfigCatalog.versionMatches("[4.0.41,5)", "5"));
        assertFalse(NeoForgeConfigCatalog.versionMatches("[4.0.41,5)", "4.0.45-BETA"));
    }

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
        assertEquals(new Evidence.JsonString("loaded"), payload.get("view"));
        assertEquals(new Evidence.JsonString("unknown"), payload.get("savedState"));
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
            public String version() { return "neoforge-21.1.250"; }
        };
        var provider = new ConfigProvider(reader, List.of(root));

        var result = provider.query(new Evidence.Query(Evidence.Operation.SEARCH,
                new Evidence.JsonObject(Map.of()), 10, null, null), SCOPE).toCompletableFuture().join();

        assertEquals(1, result.records().size());
        assertEquals(Evidence.Coverage.PARTIAL, result.coverage());
        assertEquals("unsupported test bridge", result.detail());
        assertEquals(new Evidence.JsonNumber(new java.math.BigDecimal("42")),
                result.records().getFirst().payload().values().get("savedValue"));
        assertEquals("neoforge-21.1.250", provider.status().version());
        assertEquals("neoforge-21.1.250", result.records().getFirst().source().providerVersion());
    }

    @Test
    void filteredCursorAdvancesPastSkippedRecords() {
        var values = new LinkedHashMap<String, NeoForgeConfigCatalog.Value>();
        values.put("other", new NeoForgeConfigCatalog.Value(null, null, null, "unrelated", null, null, null));
        values.put("target", new NeoForgeConfigCatalog.Value(null, null, null, "target", null, null, null));
        values.put("targetAgain", new NeoForgeConfigCatalog.Value(null, null, null, "target again", null, null, null));
        var entries = List.of(new NeoForgeConfigCatalog.Entry("example", "CLIENT", "example:CLIENT:one.toml",
                "one.toml", null, false, values));
        var provider = new ConfigProvider(NeoForgeConfigCatalog.supplied(() -> entries), List.of());

        var page = provider.query(new Evidence.Query(Evidence.Operation.SEARCH,
                new Evidence.JsonObject(Map.of("text", new Evidence.JsonString("target"))), 1, null, null), SCOPE)
                .toCompletableFuture().join();

        assertEquals("2", page.nextCursor());
        var next = provider.query(new Evidence.Query(Evidence.Operation.SEARCH,
                new Evidence.JsonObject(Map.of("text", new Evidence.JsonString("target"))), 1, SCOPE,
                page.nextCursor()), SCOPE).toCompletableFuture().join();
        assertEquals("example.targetAgain", next.records().getFirst().title());
    }

    @Test
    void malformedSavedFileIsPartialAndKeepsObservedKeys() throws Exception {
        Path root = Files.createTempDirectory("packsage-config");
        Path file = root.resolve("malformed.toml");
        Files.writeString(file, "[feature]\nenabled = false\nbroken line\nvalues = [1, 2]\n");
        var entry = new NeoForgeConfigCatalog.Entry("example", "COMMON", "example:COMMON:malformed.toml",
                file.getFileName().toString(), file, false, Map.of("feature.enabled",
                new NeoForgeConfigCatalog.Value(null, null, true, null, null, null, null)));
        var provider = new ConfigProvider(NeoForgeConfigCatalog.supplied(() -> List.of(entry)), List.of(root));

        var result = provider.query(new Evidence.Query(Evidence.Operation.GET,
                new Evidence.JsonObject(Map.of("key", new Evidence.JsonString("feature.enabled"))), 10, null, null), SCOPE)
                .toCompletableFuture().join();

        assertEquals(Evidence.Coverage.PARTIAL, result.coverage());
        assertEquals(new Evidence.JsonString("partial"), result.records().getFirst().payload().values().get("savedState"));
        assertEquals(new Evidence.JsonBoolean(false), result.records().getFirst().payload().values().get("savedValue"));
    }

    @Test
    void oversizedSavedFileIsUnknownAndPartial() throws Exception {
        Path root = Files.createTempDirectory("packsage-config");
        Path file = root.resolve("oversized.toml");
        Files.writeString(file, "answer = \"" + "x".repeat(1_048_600) + "\"\n");
        var entry = new NeoForgeConfigCatalog.Entry("example", "CLIENT", "example:CLIENT:oversized.toml",
                file.getFileName().toString(), file, false, Map.of("answer",
                new NeoForgeConfigCatalog.Value(null, null, null, null, null, null, null)));
        var provider = new ConfigProvider(NeoForgeConfigCatalog.supplied(() -> List.of(entry)), List.of(root));

        var result = provider.query(new Evidence.Query(Evidence.Operation.GET,
                new Evidence.JsonObject(Map.of("key", new Evidence.JsonString("answer"))), 10, null, null), SCOPE)
                .toCompletableFuture().join();

        assertEquals(Evidence.Coverage.PARTIAL, result.coverage());
        assertEquals(new Evidence.JsonString("unknown"), result.records().getFirst().payload().values().get("savedState"));
        assertEquals(new Evidence.JsonString("unknown"), result.records().getFirst().payload().values().get("view"));
    }

    @Test
    void unreadableSavedFileIsUnknownAndPartial() throws Exception {
        Path root = Files.createTempDirectory("packsage-config");
        Path file = root.resolve("unreadable.toml");
        Files.write(file, new byte[]{'a', 'n', 's', 'w', 'e', 'r', ' ', '=', ' ', (byte) 0xc3, (byte) 0x28});
        var entry = new NeoForgeConfigCatalog.Entry("example", "CLIENT", "example:CLIENT:unreadable.toml",
                file.getFileName().toString(), file, false, Map.of("answer",
                new NeoForgeConfigCatalog.Value(null, null, null, null, null, null, null)));
        var provider = new ConfigProvider(NeoForgeConfigCatalog.supplied(() -> List.of(entry)), List.of(root));

        var result = provider.query(new Evidence.Query(Evidence.Operation.GET,
                new Evidence.JsonObject(Map.of("key", new Evidence.JsonString("answer"))), 10, null, null), SCOPE)
                .toCompletableFuture().join();

        assertEquals(Evidence.Coverage.PARTIAL, result.coverage());
        assertEquals(new Evidence.JsonString("unknown"), result.records().getFirst().payload().values().get("savedState"));
    }

    @Test
    void unsupportedCatalogStillEnumeratesFilesUnderKnownRoots() throws Exception {
        Path root = Files.createTempDirectory("packsage-config");
        Path file = root.resolve("orphan-server.toml");
        Files.writeString(file, "answer = 42\n");
        var reader = new NeoForgeConfigCatalog.Reader() {
            public List<NeoForgeConfigCatalog.Entry> read() { return List.of(); }
            public boolean supported() { return false; }
            public String detail() { return "unsupported test bridge"; }
        };
        var provider = new ConfigProvider(reader, List.of(root));

        var result = provider.query(new Evidence.Query(Evidence.Operation.SEARCH,
                new Evidence.JsonObject(Map.of()), 10, null, null), SCOPE).toCompletableFuture().join();

        assertEquals(1, result.records().size());
        assertEquals(Evidence.Coverage.PARTIAL, result.coverage());
        assertEquals(new Evidence.JsonString("unknown"), result.records().getFirst().payload().values().get("owner"));
        assertEquals(new Evidence.JsonNumber(new java.math.BigDecimal("42")),
                result.records().getFirst().payload().values().get("savedValue"));
        assertFalse(((Evidence.JsonBoolean) result.records().getFirst().payload().values()
                .get("synchronizedServer")).value());
    }

    @Test
    void accessibleEmptyRootRemainsCompleteEmpty() throws Exception {
        Path root = Files.createTempDirectory("packsage-config");
        var reader = new NeoForgeConfigCatalog.Reader() {
            public List<NeoForgeConfigCatalog.Entry> read() { return List.of(); }
            public boolean supported() { return true; }
            public String detail() { return "empty test bridge"; }
        };
        var result = new ConfigProvider(reader, List.of(root)).query(new Evidence.Query(Evidence.Operation.SEARCH,
                new Evidence.JsonObject(Map.of()), 10, null, null), SCOPE).toCompletableFuture().join();

        assertTrue(result.records().isEmpty());
        assertEquals(Evidence.Coverage.COMPLETE, result.coverage());
        assertEquals(Evidence.Outcome.COMPLETE, result.outcome());
    }

    @Test
    void missingKnownRootDoesNotBecomeCompleteEmpty() throws Exception {
        Path root = Files.createTempDirectory("packsage-config").resolve("missing");
        var reader = new NeoForgeConfigCatalog.Reader() {
            public List<NeoForgeConfigCatalog.Entry> read() { return List.of(); }
            public boolean supported() { return true; }
            public String detail() { return "missing root test bridge"; }
        };
        var result = new ConfigProvider(reader, List.of(root)).query(new Evidence.Query(Evidence.Operation.SEARCH,
                new Evidence.JsonObject(Map.of()), 10, null, null), SCOPE).toCompletableFuture().join();

        assertTrue(result.records().isEmpty());
        assertEquals(Evidence.Coverage.PARTIAL, result.coverage());
        assertEquals(Evidence.Outcome.PARTIAL, result.outcome());
    }

    @Test
    void nonDirectoryKnownRootDoesNotBecomeCompleteEmpty() throws Exception {
        Path root = Files.createTempFile("packsage-config", ".root");
        Files.writeString(root, "not a config directory");
        var reader = new NeoForgeConfigCatalog.Reader() {
            public List<NeoForgeConfigCatalog.Entry> read() { return List.of(); }
            public boolean supported() { return true; }
            public String detail() { return "invalid root test bridge"; }
        };
        var result = new ConfigProvider(reader, List.of(root)).query(new Evidence.Query(Evidence.Operation.SEARCH,
                new Evidence.JsonObject(Map.of()), 10, null, null), SCOPE).toCompletableFuture().join();

        assertTrue(result.records().isEmpty());
        assertEquals(Evidence.Coverage.PARTIAL, result.coverage());
        assertEquals(Evidence.Outcome.PARTIAL, result.outcome());
    }

    @Test
    void malformedQuotedAndUnbalancedValuesAreNotObserved() throws Exception {
        Path root = Files.createTempDirectory("packsage-config");
        Path file = root.resolve("malformed-values.toml");
        Files.writeString(file, "good = 42\nbadQuote = \"unterminated\nbadValue = 1]\n");
        var values = new LinkedHashMap<String, NeoForgeConfigCatalog.Value>();
        values.put("good", new NeoForgeConfigCatalog.Value(null, null, null, null, null, null, null));
        values.put("badQuote", new NeoForgeConfigCatalog.Value(null, null, null, null, null, null, null));
        values.put("badValue", new NeoForgeConfigCatalog.Value(null, null, null, null, null, null, null));
        var entry = new NeoForgeConfigCatalog.Entry("example", "COMMON", "example:COMMON:malformed-values.toml",
                file.getFileName().toString(), file, false, values);
        var provider = new ConfigProvider(NeoForgeConfigCatalog.supplied(() -> List.of(entry)), List.of(root));

        var result = provider.query(new Evidence.Query(Evidence.Operation.SEARCH,
                new Evidence.JsonObject(Map.of()), 10, null, null), SCOPE).toCompletableFuture().join();

        assertEquals(Evidence.Coverage.PARTIAL, result.coverage());
        var badQuote = result.records().stream().filter(record -> record.title().endsWith(".badQuote")).findFirst()
                .orElseThrow();
        var badValue = result.records().stream().filter(record -> record.title().endsWith(".badValue")).findFirst()
                .orElseThrow();
        assertEquals(new Evidence.JsonString("unknown"), badQuote.payload().values().get("savedState"));
        assertEquals(new Evidence.JsonNull(), badQuote.payload().values().get("savedValue"));
        assertEquals(new Evidence.JsonString("unknown"), badValue.payload().values().get("savedState"));
        assertEquals(new Evidence.JsonNull(), badValue.payload().values().get("savedValue"));
        assertEquals(new Evidence.JsonNumber(new java.math.BigDecimal("42")), result.records().stream()
                .filter(record -> record.title().endsWith(".good")).findFirst().orElseThrow().payload().values()
                .get("savedValue"));
    }

    @Test
    void suppliedValuesCopyOpaqueArraysAndBoundContainers() {
        int[] array = {1, 2};
        var opaque = new StringBuilder("before");
        var cyclic = new LinkedHashMap<String, Object>();
        cyclic.put("self", cyclic);
        var oversized = new java.util.ArrayList<Integer>();
        for (int index = 0; index < 10_000; index++) oversized.add(index);
        var value = new NeoForgeConfigCatalog.Value(opaque, array, cyclic, null, null, oversized, null);

        opaque.append("-after");
        array[0] = 99;

        assertEquals("<opaque value>", value.saved());
        assertEquals(List.of(1, 2), value.loaded());
        assertEquals(Map.of("self", "<value omitted>"), value.defaultValue());
        assertTrue(((List<?>) value.minimum()).size() < oversized.size());
    }

    @Test
    void fallbackFileLimitProducesPartialBoundedResult() throws Exception {
        Path root = Files.createTempDirectory("packsage-config");
        for (int index = 0; index < 257; index++) {
            Files.writeString(root.resolve("file" + index + ".toml"), "answer = 42\n");
        }
        var reader = new NeoForgeConfigCatalog.Reader() {
            public List<NeoForgeConfigCatalog.Entry> read() { return List.of(); }
            public boolean supported() { return false; }
            public String detail() { return "unsupported test bridge"; }
        };
        var result = new ConfigProvider(reader, List.of(root)).query(new Evidence.Query(Evidence.Operation.SEARCH,
                new Evidence.JsonObject(Map.of()), 1_000, null, null), SCOPE).toCompletableFuture().join();

        assertEquals(256, result.records().size());
        assertEquals(Evidence.Coverage.PARTIAL, result.coverage());
    }

    @Test
    void catalogValuesAreBoundedBeforeQueryLimitIsApplied() {
        var values = new LinkedHashMap<String, NeoForgeConfigCatalog.Value>();
        for (int index = 0; index < 2_100; index++) {
            values.put("key" + index, new NeoForgeConfigCatalog.Value(null, null, null, null, null, null, null));
        }
        var entry = new NeoForgeConfigCatalog.Entry("large", "COMMON", "large:COMMON:large.toml", "large.toml",
                null, false, values);
        var provider = new ConfigProvider(NeoForgeConfigCatalog.supplied(() -> List.of(entry)), List.of());

        var result = provider.query(new Evidence.Query(Evidence.Operation.SEARCH,
                new Evidence.JsonObject(Map.of()), 10_000, null, null), SCOPE).toCompletableFuture().join();

        assertEquals(2_048, result.records().size());
        assertEquals(Evidence.Coverage.PARTIAL, result.coverage());
    }

    @Test
    @SuppressWarnings("unchecked")
    void pinnedReflectiveBridgeReadsPackagePrivateLoadedConfigThroughPublicInterface() throws Exception {
        FMLPaths.loadAbsolutePaths(Files.createTempDirectory("packsage-config-runtime"));
        var value = new ModConfigSpec.Builder().defineInRange("value", 3, 0, 10);
        ModConfigSpec spec = value.next().build();
        IModInfo modInfo = (IModInfo) Proxy.newProxyInstance(IModInfo.class.getClassLoader(), new Class<?>[]{IModInfo.class},
                (proxy, method, arguments) -> method.getName().equals("getModId") ? "bridge" : null);
        ModContainer container = new ModContainer(modInfo) {
            @Override
            public IEventBus getEventBus() {
                return null;
            }
        };
        var constructor = ModConfig.class.getDeclaredConstructor(ModConfig.Type.class, net.neoforged.fml.config.IConfigSpec.class,
                ModContainer.class, String.class, ReentrantLock.class);
        constructor.setAccessible(true);
        ModConfig config = constructor.newInstance(ModConfig.Type.COMMON, spec, container, "bridge-common.toml",
                new ReentrantLock());
        CommentedConfig loadedValues = CommentedConfig.inMemory();
        loadedValues.set("value", 7);
        var loadedConstructor = Class.forName("net.neoforged.fml.config.LoadedConfig")
                .getDeclaredConstructor(CommentedConfig.class, Path.class, ModConfig.class);
        loadedConstructor.setAccessible(true);
        Object loaded = loadedConstructor.newInstance(loadedValues, null, config);
        var loadedField = ModConfig.class.getDeclaredField("loadedConfig");
        loadedField.setAccessible(true);
        loadedField.set(config, loaded);
        var setsField = ConfigTracker.class.getDeclaredField("configSets");
        var byModField = ConfigTracker.class.getDeclaredField("configsByMod");
        setsField.setAccessible(true);
        byModField.setAccessible(true);
        var sets = (Map<ModConfig.Type, Set<ModConfig>>) setsField.get(ConfigTracker.INSTANCE);
        var byMod = (Map<String, List<ModConfig>>) byModField.get(ConfigTracker.INSTANCE);
        sets.computeIfAbsent(ModConfig.Type.COMMON, ignored -> new java.util.LinkedHashSet<>()).add(config);
        byMod.computeIfAbsent("bridge", ignored -> new java.util.ArrayList<>()).add(config);
        try {
            var reader = NeoForgeConfigCatalog.reflective();
            var entries = reader.read();
            var entry = entries.stream().filter(candidate -> candidate.owner().equals("bridge")).findFirst().orElseThrow();
            assertTrue(reader.supported(), reader.detail());
            assertEquals(7, entry.values().get("value").loaded());
            assertEquals(3, entry.values().get("value").defaultValue());
            assertEquals(0, entry.values().get("value").minimum());
            assertEquals(10, entry.values().get("value").maximum());
        } finally {
            sets.getOrDefault(ModConfig.Type.COMMON, Set.of()).remove(config);
            byMod.getOrDefault("bridge", List.of()).remove(config);
        }
    }

    @Test
    void productionFactoryCapturesCatalogOnClientAndParsesFilesOnWorker() {
        var catalogThread = new AtomicReference<Thread>();
        var fileThread = new AtomicReference<Thread>();
        Path root;
        try {
            root = Files.createTempDirectory("packsage-config");
            Files.writeString(root.resolve("thread.toml"), "answer = 42\n");
        } catch (Exception failure) {
            throw new RuntimeException(failure);
        }
        var reader = new NeoForgeConfigCatalog.Reader() {
            public List<NeoForgeConfigCatalog.Entry> read() {
                catalogThread.set(Thread.currentThread());
                return List.of(new NeoForgeConfigCatalog.Entry("thread", "CLIENT", "thread:CLIENT:thread.toml",
                        "thread.toml", root.resolve("thread.toml"), false, Map.of()));
            }
            public boolean supported() { return true; }
            public String detail() { return "thread test"; }
        };
        Executor client = command -> {
            var thread = new Thread(command, "packsage-config-client");
            thread.start();
            try {
                thread.join();
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
            }
        };
        Executor worker = command -> {
            var thread = new Thread(() -> {
                fileThread.set(Thread.currentThread());
                command.run();
            }, "packsage-config-worker");
            thread.start();
            try {
                thread.join();
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
            }
        };
        var provider = new ConfigProvider(reader, List.of(root), client, worker, () -> false);

        var result = provider.query(new Evidence.Query(Evidence.Operation.SEARCH,
                new Evidence.JsonObject(Map.of()), 10, null, null), SCOPE).toCompletableFuture().join();

        assertEquals("packsage-config-client", catalogThread.get().getName());
        assertEquals("packsage-config-worker", fileThread.get().getName());
        assertEquals(new Evidence.JsonNumber(new java.math.BigDecimal("42")),
                result.records().getFirst().payload().values().get("savedValue"));
    }
}
