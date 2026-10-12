package dev.packsage.knowledge.config;

import dev.packsage.knowledge.Evidence;
import dev.packsage.knowledge.KnowledgeProvider;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Bounded, read-only saved and loaded NeoForge configuration evidence. */
public final class ConfigProvider implements KnowledgeProvider {
    public static final String ID = "config";
    public static final String VERSION = "neoforge-21.1.202";
    private static final int MAX_FILE_BYTES = 1_048_576;
    private static final int MAX_KEYS = 2048;

    private final NeoForgeConfigCatalog.Reader catalog;
    private final List<Path> roots;

    public ConfigProvider(NeoForgeConfigCatalog.Reader catalog, List<Path> knownRoots) {
        this.catalog = Objects.requireNonNull(catalog);
        this.roots = knownRoots.stream().map(ConfigProvider::realPath).filter(Objects::nonNull).toList();
    }

    public ConfigProvider(List<Path> knownRoots) {
        this(NeoForgeConfigCatalog.reflective(), knownRoots);
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public ProviderStatus status() {
        return new ProviderStatus(catalog.supported() ? Availability.AVAILABLE : Availability.PARTIAL,
                VERSION, catalog.detail() + "; saved-file inspection remains available");
    }

    @Override
    public CompletionStage<Evidence.Result> query(Evidence.Query query, Evidence.Scope scope) {
        if (query.cursor() != null && query.cursorScope() != null && !sameScope(query.cursorScope(), scope)) {
            return CompletableFuture.completedFuture(new Evidence.Result(List.of(), Evidence.Coverage.PARTIAL,
                    Evidence.Outcome.UNKNOWN, null));
        }
        List<NeoForgeConfigCatalog.Entry> entries;
        try {
            entries = catalog.read().stream().map(this::withSavedFile).toList();
        } catch (RuntimeException failure) {
            return CompletableFuture.completedFuture(new Evidence.Result(List.of(), Evidence.Coverage.ERROR,
                    Evidence.Outcome.ERROR, null));
        }
        String text = stringArgument(query.arguments(), "text");
        text = text == null ? "" : text.toLowerCase(Locale.ROOT);
        String id = stringArgument(query.arguments(), "id");
        String key = stringArgument(query.arguments(), "key");
        int start = parseCursor(query.cursor());
        if (start < 0) return CompletableFuture.completedFuture(new Evidence.Result(List.of(), Evidence.Coverage.ERROR,
                Evidence.Outcome.ERROR, null));
        var records = new ArrayList<Evidence.Record>();
        int index = 0;
        for (var entry : entries) {
            for (var value : entry.values().entrySet()) {
                if (index++ < start || records.size() >= query.limit()) continue;
                if (key != null && !key.equals(value.getKey())) continue;
                if (id != null && !id.equals(entry.handle()) && !id.equals(entry.owner())) continue;
                if (query.operation() == Evidence.Operation.GET && key == null) continue;
                if (query.operation() == Evidence.Operation.SEARCH && !matches(entry, value.getKey(), value.getValue(), text)) continue;
                records.add(toRecord(entry, value.getKey(), value.getValue(), scope));
            }
        }
        String next = records.size() == query.limit() ? Integer.toString(start + records.size()) : null;
        Evidence.Coverage coverage = catalog.supported() ? Evidence.Coverage.COMPLETE : Evidence.Coverage.PARTIAL;
        return CompletableFuture.completedFuture(new Evidence.Result(records, coverage,
                coverage == Evidence.Coverage.COMPLETE ? Evidence.Outcome.COMPLETE : Evidence.Outcome.PARTIAL, next));
    }

    @Override
    public Evidence.NavigationResult open(Evidence.NavigationTarget target, Evidence.Scope scope) {
        return new Evidence.NavigationResult(Evidence.NavigationStatus.UNSUPPORTED, "config navigation is read-only");
    }

    private NeoForgeConfigCatalog.Entry withSavedFile(NeoForgeConfigCatalog.Entry entry) {
        Map<String, Object> saved = entry.filePath() == null || !allowed(entry.filePath()) ? Map.of() : readSaved(entry.filePath());
        var values = new LinkedHashMap<String, NeoForgeConfigCatalog.Value>();
        entry.values().forEach((key, value) -> values.put(key, new NeoForgeConfigCatalog.Value(saved.containsKey(key) ? saved.get(key) : value.saved(),
                catalog.supported() ? value.loaded() : null,
                value.defaultValue(), value.comment(), value.restart(), value.minimum(), value.maximum())));
        saved.forEach((key, value) -> values.putIfAbsent(key, new NeoForgeConfigCatalog.Value(value, null, null, null, null, null, null)));
        if (values.equals(entry.values()) && catalog.supported()) return entry;
        return new NeoForgeConfigCatalog.Entry(entry.owner(), entry.scope(), entry.handle(), entry.fileName(), entry.filePath(),
                entry.synchronizedServer(), values);
    }

    private Evidence.Record toRecord(NeoForgeConfigCatalog.Entry entry, String key, NeoForgeConfigCatalog.Value value,
                                     Evidence.Scope scope) {
        Evidence.JsonValue saved = json(value.saved());
        Evidence.JsonValue loaded = json(value.loaded());
        var payload = new LinkedHashMap<String, Evidence.JsonValue>();
        payload.put("owner", new Evidence.JsonString(entry.owner()));
        payload.put("scope", new Evidence.JsonString(entry.scope()));
        payload.put("handle", new Evidence.JsonString(entry.handle()));
        payload.put("fileName", new Evidence.JsonString(entry.fileName()));
        payload.put("filePath", entry.filePath() == null ? new Evidence.JsonNull() : new Evidence.JsonString(entry.filePath().toString()));
        payload.put("key", new Evidence.JsonString(key));
        payload.put("savedValue", saved);
        payload.put("loadedValue", loaded);
        payload.put("loadedState", value.loaded() == null ? new Evidence.JsonString("unknown") : new Evidence.JsonString("observed"));
        payload.put("view", new Evidence.JsonString(value.loaded() != null ? "saved-and-loaded" : "saved"));
        payload.put("synchronizedServer", new Evidence.JsonBoolean(entry.synchronizedServer()));
        payload.put("default", json(value.defaultValue()));
        payload.put("comment", value.comment() == null ? new Evidence.JsonNull() : new Evidence.JsonString(value.comment()));
        payload.put("restart", value.restart() == null ? new Evidence.JsonNull() : new Evidence.JsonString(value.restart()));
        payload.put("minimum", json(value.minimum()));
        payload.put("maximum", json(value.maximum()));
        Evidence.Authority authority = value.loaded() != null ? Evidence.Authority.LOADED : Evidence.Authority.SAVED_CONFIG;
        var source = new Evidence.Source(scope, ID, VERSION, entry.handle() + "#" + key, scope.requestGeneration(),
                java.time.Instant.now(), Evidence.Activity.ACTIVE, catalog.supported() ? Evidence.Coverage.COMPLETE : Evidence.Coverage.PARTIAL,
                authority, List.of("config:" + entry.owner(), "view:" + (value.loaded() != null ? "loaded" : "saved")));
        return new Evidence.Record(source, Evidence.RecordKind.CONFIG, entry.owner() + "." + key,
                new Evidence.JsonObject(payload), List.of());
    }

    private Map<String, Object> readSaved(Path path) {
        try {
            if (!allowed(path) || Files.size(path) > MAX_FILE_BYTES) return Map.of();
            var lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            var result = new LinkedHashMap<String, Object>();
            String section = "";
            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                    section = trimmed.substring(1, trimmed.length() - 1).trim();
                    continue;
                }
                int equals = trimmed.indexOf('=');
                if (equals <= 0 || result.size() >= MAX_KEYS) continue;
                String key = trimmed.substring(0, equals).trim();
                result.put(section.isEmpty() ? key : section + "." + key, parseValue(trimmed.substring(equals + 1).trim()));
            }
            return result;
        } catch (IOException | RuntimeException ignored) {
            return Map.of();
        }
    }

    private static Object parseValue(String value) {
        if (value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false")) return Boolean.valueOf(value);
        if (value.startsWith("\"") && value.endsWith("\"")) return value.substring(1, value.length() - 1);
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException ignored) {
            return value;
        }
    }

    private boolean allowed(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        for (Path root : roots) {
            try {
                Path real = Files.exists(normalized) ? normalized.toRealPath() : normalized.getParent().toRealPath().resolve(normalized.getFileName()).normalize();
                if (real.startsWith(root)) return true;
            } catch (IOException | RuntimeException ignored) {
            }
        }
        return false;
    }

    private static Path realPath(Path path) {
        try {
            return path.toAbsolutePath().normalize().toRealPath();
        } catch (IOException | RuntimeException ignored) {
            return path.toAbsolutePath().normalize();
        }
    }

    private static boolean matches(NeoForgeConfigCatalog.Entry entry, String key, NeoForgeConfigCatalog.Value value, String text) {
        return text.isEmpty() || entry.owner().toLowerCase(Locale.ROOT).contains(text)
                || entry.scope().toLowerCase(Locale.ROOT).contains(text) || key.toLowerCase(Locale.ROOT).contains(text)
                || value.comment() != null && value.comment().toLowerCase(Locale.ROOT).contains(text);
    }

    private static String stringArgument(Evidence.JsonObject arguments, String key) {
        var value = arguments.values().get(key);
        return value instanceof Evidence.JsonString string ? string.value() : null;
    }

    private static int parseCursor(String cursor) {
        if (cursor == null) return 0;
        try {
            int value = Integer.parseInt(cursor);
            return value < 0 ? -1 : value;
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static boolean sameScope(Evidence.Scope left, Evidence.Scope right) {
        return left.worldSession().equals(right.worldSession()) && left.resourcesLocale().equals(right.resourcesLocale())
                && left.playerTeam().equals(right.playerTeam()) && left.providerRuntime().equals(right.providerRuntime())
                && left.requestGeneration() == right.requestGeneration();
    }

    private static Evidence.JsonValue json(Object value) {
        if (value == null) return new Evidence.JsonNull();
        if (value instanceof Boolean booleanValue) return new Evidence.JsonBoolean(booleanValue);
        if (value instanceof Number number) return new Evidence.JsonNumber(new BigDecimal(number.toString()));
        if (value instanceof Map<?, ?> map) {
            var result = new LinkedHashMap<String, Evidence.JsonValue>();
            map.forEach((key, nested) -> result.put(String.valueOf(key), json(nested)));
            return new Evidence.JsonObject(result);
        }
        if (value instanceof List<?> list) return new Evidence.JsonArray(list.stream().map(ConfigProvider::json).toList());
        return new Evidence.JsonString(String.valueOf(value));
    }
}
