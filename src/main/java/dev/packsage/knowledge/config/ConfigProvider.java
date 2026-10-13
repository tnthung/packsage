package dev.packsage.knowledge.config;

import dev.packsage.knowledge.Evidence;
import dev.packsage.knowledge.KnowledgeProvider;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

/** Bounded, read-only saved and loaded NeoForge configuration evidence. */
public final class ConfigProvider implements KnowledgeProvider {
    public static final String ID = "config";
    public static final String VERSION = "neoforge-21.1.202";
    private static final int MAX_FILE_BYTES = 1_048_576;
    private static final int MAX_KEYS = 2048;
    private static final int MAX_FILES = 256;
    private static final int MAX_DEPTH = 32;

    private final NeoForgeConfigCatalog.Reader catalog;
    private final List<Path> roots;
    private final Executor clientExecutor;
    private final Executor workerExecutor;
    private final BooleanSupplier owningThread;

    public ConfigProvider(NeoForgeConfigCatalog.Reader catalog, List<Path> knownRoots) {
        this(catalog, knownRoots, null, null, () -> true);
    }

    public ConfigProvider(NeoForgeConfigCatalog.Reader catalog, List<Path> knownRoots, Executor clientExecutor,
                          Executor workerExecutor, BooleanSupplier owningThread) {
        this.catalog = Objects.requireNonNull(catalog);
        this.roots = knownRoots.stream().map(ConfigProvider::realPath).filter(Objects::nonNull).toList();
        this.clientExecutor = clientExecutor;
        this.workerExecutor = clientExecutor == null ? null : Objects.requireNonNull(workerExecutor);
        this.owningThread = Objects.requireNonNull(owningThread);
    }

    public ConfigProvider(List<Path> knownRoots) {
        this(NeoForgeConfigCatalog.reflective(), knownRoots);
    }

    public static ConfigProvider production(List<Path> knownRoots, Executor clientExecutor, Executor workerExecutor,
                                            BooleanSupplier owningThread) {
        return new ConfigProvider(NeoForgeConfigCatalog.reflective(), knownRoots, clientExecutor, workerExecutor,
                owningThread);
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public ProviderStatus status() {
        return new ProviderStatus(catalog.supported() ? Availability.AVAILABLE : Availability.PARTIAL,
                catalog.version(), catalog.detail() + "; saved-file inspection remains available under known roots");
    }

    @Override
    public CompletionStage<Evidence.Result> query(Evidence.Query query, Evidence.Scope scope) {
        Objects.requireNonNull(query);
        Objects.requireNonNull(scope);
        if (query.cursor() != null && query.cursorScope() != null && !sameScope(query.cursorScope(), scope)) {
            return CompletableFuture.completedFuture(new Evidence.Result(List.of(), Evidence.Coverage.PARTIAL,
                    Evidence.Outcome.UNKNOWN, null));
        }
        if (clientExecutor == null) {
            try {
                return CompletableFuture.completedFuture(querySnapshot(new CatalogCapture(catalog.read(), null), query,
                        scope));
            } catch (RuntimeException failure) {
                return CompletableFuture.completedFuture(new Evidence.Result(List.of(), Evidence.Coverage.PARTIAL,
                        Evidence.Outcome.PARTIAL, null));
            }
        }
        return captureCatalog().thenApplyAsync(captured -> querySnapshot(captured, query, scope), workerExecutor);
    }

    private CompletionStage<CatalogCapture> captureCatalog() {
        if (owningThread.getAsBoolean()) {
            try {
                return CompletableFuture.completedFuture(new CatalogCapture(catalog.read(), null));
            } catch (RuntimeException failure) {
                return CompletableFuture.completedFuture(new CatalogCapture(List.of(), failure));
            }
        }
        return CompletableFuture.supplyAsync(catalog::read, clientExecutor)
                .handle((entries, failure) -> new CatalogCapture(entries == null ? List.of() : entries, failure));
    }

    private Evidence.Result querySnapshot(CatalogCapture captured, Evidence.Query query, Evidence.Scope scope) {
        var entries = new ArrayList<>(captured.entries());
        var fallback = fallbackEntries(entries);
        entries.addAll(fallback.entries());
        boolean bridgePartial = captured.failure() != null || !catalog.supported() || !fallback.complete()
                || !fallback.entries().isEmpty();
        var snapshots = entries.stream().map(this::withSavedFile).toList();
        var candidates = new ArrayList<Candidate>();
        boolean bounded = true;
        boolean savedPartial = false;
        for (var snapshot : snapshots) {
            savedPartial |= snapshot.saved().state() == SavedState.PARTIAL || snapshot.saved().state() == SavedState.UNKNOWN
                    && snapshot.entry().filePath() != null;
            int count = 0;
            for (var value : snapshot.entry().values().entrySet()) {
                if (count++ >= MAX_KEYS) {
                    bounded = false;
                    break;
                }
                candidates.add(new Candidate(snapshot, value.getKey(), value.getValue()));
            }
        }
        int start = parseCursor(query.cursor());
        if (start < 0) {
            return new Evidence.Result(List.of(), Evidence.Coverage.ERROR, Evidence.Outcome.ERROR, null);
        }
        String text = stringArgument(query.arguments(), "text");
        text = text == null ? "" : text.toLowerCase(Locale.ROOT);
        String id = stringArgument(query.arguments(), "id");
        String key = stringArgument(query.arguments(), "key");
        int limit = Math.min(query.limit(), MAX_KEYS);
        var records = new ArrayList<Evidence.Record>();
        int index = start;
        for (; index < candidates.size() && records.size() < limit; index++) {
            var candidate = candidates.get(index);
            if (key != null && !key.equals(candidate.key())) continue;
            if (id != null && !id.equals(candidate.snapshot().entry().handle())
                    && !id.equals(candidate.snapshot().entry().owner())) continue;
            if (query.operation() == Evidence.Operation.GET && key == null) continue;
            if (query.operation() == Evidence.Operation.SEARCH
                    && !matches(candidate.snapshot().entry(), candidate.key(), candidate.value(), text)) continue;
            records.add(toRecord(candidate.snapshot(), candidate.key(), candidate.value(), scope,
                    bridgePartial || !bounded || savedPartial));
        }
        String next = records.size() == limit && index < candidates.size() ? Integer.toString(index) : null;
        Evidence.Coverage coverage = bridgePartial || !bounded || savedPartial ? Evidence.Coverage.PARTIAL
                : Evidence.Coverage.COMPLETE;
        return new Evidence.Result(records, coverage,
                coverage == Evidence.Coverage.COMPLETE ? Evidence.Outcome.COMPLETE : Evidence.Outcome.PARTIAL, next,
                captured.failure() != null ? "loaded config capture failed" : !catalog.supported() ? catalog.detail() : null);
    }

    @Override
    public Evidence.NavigationResult open(Evidence.NavigationTarget target, Evidence.Scope scope) {
        return new Evidence.NavigationResult(Evidence.NavigationStatus.UNSUPPORTED, "config navigation is read-only");
    }

    private SavedEntry withSavedFile(NeoForgeConfigCatalog.Entry entry) {
        SavedFile saved;
        if (entry.filePath() == null) {
            boolean observed = entry.values().values().stream().anyMatch(value -> value.saved() != null);
            saved = new SavedFile(Map.of(), observed ? SavedState.OBSERVED : SavedState.UNKNOWN, null);
        } else if (!allowed(entry.filePath())) {
            saved = new SavedFile(Map.of(), SavedState.UNKNOWN, "saved path is outside known config roots");
        } else {
            saved = readSaved(entry.filePath());
        }
        var values = new LinkedHashMap<String, NeoForgeConfigCatalog.Value>();
        boolean supported = catalog.supported();
        entry.values().forEach((key, value) -> {
            Object savedValue = saved.values().containsKey(key) ? saved.values().get(key)
                    : entry.filePath() == null ? value.saved() : null;
            values.put(key, new NeoForgeConfigCatalog.Value(savedValue, supported ? value.loaded() : null,
                    value.defaultValue(), value.comment(), value.restart(), value.minimum(), value.maximum()));
        });
        saved.values().forEach((key, value) -> values.putIfAbsent(key,
                new NeoForgeConfigCatalog.Value(value, null, null, null, null, null, null)));
        return new SavedEntry(new NeoForgeConfigCatalog.Entry(entry.owner(), entry.scope(), entry.handle(),
                entry.fileName(), entry.filePath(), entry.synchronizedServer(), values), saved);
    }

    private Evidence.Record toRecord(SavedEntry snapshot, String key, NeoForgeConfigCatalog.Value value,
                                     Evidence.Scope scope, boolean partial) {
        boolean savedObserved = snapshot.saved().values().containsKey(key)
                || snapshot.entry().filePath() == null && value.saved() != null;
        boolean loadedObserved = value.loaded() != null;
        Evidence.JsonValue saved = json(savedObserved ? value.saved() : null);
        Evidence.JsonValue loaded = json(loadedObserved ? value.loaded() : null);
        String savedState = savedObserved ? snapshot.saved().state() == SavedState.PARTIAL ? "partial" : "observed"
                : "unknown";
        String view = savedObserved ? loadedObserved ? "saved-and-loaded" : "saved"
                : loadedObserved ? "loaded" : "unknown";
        var payload = new LinkedHashMap<String, Evidence.JsonValue>();
        payload.put("owner", new Evidence.JsonString(snapshot.entry().owner()));
        payload.put("scope", new Evidence.JsonString(snapshot.entry().scope()));
        payload.put("handle", new Evidence.JsonString(snapshot.entry().handle()));
        payload.put("fileName", new Evidence.JsonString(snapshot.entry().fileName()));
        payload.put("filePath", snapshot.entry().filePath() == null ? new Evidence.JsonNull()
                : new Evidence.JsonString(snapshot.entry().filePath().toString()));
        payload.put("key", new Evidence.JsonString(key));
        payload.put("savedValue", saved);
        payload.put("savedState", new Evidence.JsonString(savedState));
        payload.put("savedError", snapshot.saved().detail() == null ? new Evidence.JsonNull()
                : new Evidence.JsonString(snapshot.saved().detail()));
        payload.put("loadedValue", loaded);
        payload.put("loadedState", new Evidence.JsonString(loadedObserved ? "observed" : "unknown"));
        payload.put("view", new Evidence.JsonString(view));
        payload.put("synchronizedServer", new Evidence.JsonBoolean(snapshot.entry().synchronizedServer()));
        payload.put("default", json(value.defaultValue()));
        payload.put("comment", value.comment() == null ? new Evidence.JsonNull() : new Evidence.JsonString(value.comment()));
        payload.put("restart", value.restart() == null ? new Evidence.JsonNull() : new Evidence.JsonString(value.restart()));
        payload.put("minimum", json(value.minimum()));
        payload.put("maximum", json(value.maximum()));
        Evidence.Authority authority = loadedObserved ? Evidence.Authority.LOADED
                : savedObserved ? Evidence.Authority.SAVED_CONFIG : Evidence.Authority.UNKNOWN;
        Evidence.Coverage coverage = partial ? Evidence.Coverage.PARTIAL : Evidence.Coverage.COMPLETE;
        return new Evidence.Record(new Evidence.Source(scope, ID, catalog.version(),
                snapshot.entry().handle() + "#" + key, scope.requestGeneration(), java.time.Instant.now(),
                Evidence.Activity.ACTIVE, coverage, authority,
                List.of("config:" + snapshot.entry().owner(), "view:" + view)), Evidence.RecordKind.CONFIG,
                snapshot.entry().owner() + "." + key, new Evidence.JsonObject(payload), List.of());
    }

    private SavedFile readSaved(Path path) {
        try {
            if (!allowed(path)) return new SavedFile(Map.of(), SavedState.UNKNOWN,
                    "saved path is outside known config roots");
            if (!Files.exists(path)) return new SavedFile(Map.of(), SavedState.UNKNOWN, "saved config file is missing");
            if (Files.size(path) > MAX_FILE_BYTES) {
                return new SavedFile(Map.of(), SavedState.UNKNOWN, "saved config file exceeds the byte limit");
            }
            var lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            var result = new LinkedHashMap<String, Object>();
            String section = "";
            boolean partial = false;
            String detail = null;
            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                if (trimmed.startsWith("[[")) {
                    partial = true;
                    detail = "array-of-table TOML is outside the bounded parser";
                    continue;
                }
                if (trimmed.startsWith("[")) {
                    if (!trimmed.endsWith("]")) {
                        partial = true;
                        detail = "malformed TOML section";
                        continue;
                    }
                    var sectionPath = keyPath(trimmed.substring(1, trimmed.length() - 1));
                    if (sectionPath == null) {
                        partial = true;
                        detail = "malformed TOML section";
                    } else {
                        section = String.join(".", sectionPath);
                    }
                    continue;
                }
                int equals = equalsOutsideQuotes(trimmed);
                if (equals <= 0) {
                    partial = true;
                    detail = "malformed TOML assignment";
                    continue;
                }
                var keyPath = keyPath(trimmed.substring(0, equals).trim());
                var parsed = parseValue(trimmed.substring(equals + 1).trim());
                if (keyPath == null || !parsed.supported()) {
                    partial = true;
                    detail = parsed.detail() == null ? "unsupported TOML value" : parsed.detail();
                    continue;
                }
                if (result.size() >= MAX_KEYS) {
                    partial = true;
                    detail = "saved config key limit reached";
                    break;
                }
                String fullKey = section.isEmpty() ? String.join(".", keyPath)
                        : section + "." + String.join(".", keyPath);
                result.put(fullKey, parsed.value());
            }
            return new SavedFile(result, partial ? SavedState.PARTIAL : SavedState.OBSERVED, detail);
        } catch (CharacterCodingException failure) {
            return new SavedFile(Map.of(), SavedState.UNKNOWN, "saved config is not valid UTF-8");
        } catch (IOException | RuntimeException failure) {
            return new SavedFile(Map.of(), SavedState.UNKNOWN, "saved config could not be read");
        }
    }

    private static int equalsOutsideQuotes(String value) {
        boolean quoted = false;
        char quote = 0;
        int brackets = 0;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (quoted) {
                if (current == quote && (index == 0 || value.charAt(index - 1) != '\\')) quoted = false;
                continue;
            }
            if (current == '"' || current == '\'') {
                quoted = true;
                quote = current;
            } else if (current == '[') {
                brackets++;
            } else if (current == ']') {
                brackets--;
            } else if (current == '=' && brackets == 0) {
                return index;
            }
        }
        return -1;
    }

    private static List<String> keyPath(String value) {
        var result = new ArrayList<String>();
        var current = new StringBuilder();
        boolean quoted = false;
        char quote = 0;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (quoted) {
                if (character == quote && (index == 0 || value.charAt(index - 1) != '\\')) {
                    quoted = false;
                } else {
                    current.append(character);
                }
            } else if (character == '"' || character == '\'') {
                quoted = true;
                quote = character;
            } else if (character == '.') {
                if (current.isEmpty()) return null;
                result.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(character);
            }
        }
        if (quoted || current.toString().trim().isEmpty()) return null;
        result.add(current.toString().trim());
        return result;
    }

    private static ParsedValue parseValue(String source) {
        String value = stripInlineComment(source).trim();
        if (!balancedValue(value)) return new ParsedValue(null, false, "malformed TOML value");
        if (value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false")) {
            return new ParsedValue(Boolean.valueOf(value), true, null);
        }
        if (value.startsWith("\"") || value.startsWith("'")) {
            char quote = value.charAt(0);
            if (value.length() < 2 || value.charAt(value.length() - 1) != quote) {
                return new ParsedValue(null, false, "malformed TOML string");
            }
            String parsed = value.substring(1, value.length() - 1);
            return new ParsedValue(quote == '"' ? parsed.replace("\\\"", "\"") : parsed, true, null);
        }
        if (value.startsWith("[") || value.endsWith("]")) {
            if (!value.startsWith("[") || !value.endsWith("]")) {
                return new ParsedValue(null, false, "malformed TOML array");
            }
            var result = new ArrayList<Object>();
            StringBuilder item = new StringBuilder();
            boolean quoted = false;
            char quote = 0;
            int brackets = 0;
            for (int index = 1; index < value.length() - 1; index++) {
                char character = value.charAt(index);
                if (quoted) {
                    if (character == quote && (quote == '\'' || index == 0 || value.charAt(index - 1) != '\\')) {
                        quoted = false;
                    }
                } else if (character == '"' || character == '\'') {
                    quoted = true;
                    quote = character;
                } else if (character == '[') {
                    brackets++;
                } else if (character == ']') {
                    brackets--;
                }
                if (character == ',' && !quoted && brackets == 0) {
                    var parsed = parseValue(item.toString());
                    if (!parsed.supported()) return parsed;
                    result.add(parsed.value());
                    item.setLength(0);
                } else {
                    item.append(character);
                }
            }
            if (!item.toString().trim().isEmpty()) {
                var parsed = parseValue(item.toString());
                if (!parsed.supported()) return parsed;
                result.add(parsed.value());
            }
            return new ParsedValue(List.copyOf(result), true, null);
        }
        try {
            return new ParsedValue(new BigDecimal(value), true, null);
        } catch (NumberFormatException ignored) {
            return new ParsedValue(null, false, value.isEmpty() ? "malformed TOML value" : "unsupported TOML value");
        }
    }

    private static String stripInlineComment(String value) {
        boolean quoted = false;
        char quote = 0;
        int brackets = 0;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (quoted) {
                if (character == quote && (quote == '\'' || index == 0 || value.charAt(index - 1) != '\\')) {
                    quoted = false;
                }
            } else if (character == '"' || character == '\'') {
                quoted = true;
                quote = character;
            }
            if (quoted) continue;
            if (character == '[') brackets++;
            if (character == ']') brackets--;
            if (character == '#' && brackets == 0) return value.substring(0, index);
        }
        return value;
    }

    private static boolean balancedValue(String value) {
        boolean quoted = false;
        char quote = 0;
        int brackets = 0;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (quoted) {
                if (character == quote && (quote == '\'' || index == 0 || value.charAt(index - 1) != '\\')) {
                    quoted = false;
                }
            } else if (character == '"' || character == '\'') {
                quoted = true;
                quote = character;
            } else if (character == '[') {
                brackets++;
            } else if (character == ']' && --brackets < 0) {
                return false;
            }
        }
        return !quoted && brackets == 0;
    }

    private FallbackFiles fallbackEntries(List<NeoForgeConfigCatalog.Entry> entries) {
        var known = new LinkedHashSet<Path>();
        entries.stream().map(NeoForgeConfigCatalog.Entry::filePath).filter(Objects::nonNull)
                .map(path -> path.toAbsolutePath().normalize()).forEach(known::add);
        var result = new ArrayList<NeoForgeConfigCatalog.Entry>();
        int count = 0;
        int inspected = 0;
        boolean complete = true;
        for (Path root : roots) {
            if (count >= MAX_FILES || inspected >= MAX_FILES) {
                complete = false;
                break;
            }
            try {
                if (!Files.isDirectory(root)) {
                    complete = false;
                    continue;
                }
            } catch (RuntimeException ignored) {
                complete = false;
                continue;
            }
            try (Stream<Path> paths = Files.walk(root, 4)) {
                var iterator = paths.filter(Files::isRegularFile).filter(path -> path.toString().endsWith(".toml"))
                        .iterator();
                while (inspected < MAX_FILES && iterator.hasNext()) {
                    Path path = iterator.next();
                    inspected++;
                    if (!allowed(path)) continue;
                    Path normalized = path.toAbsolutePath().normalize();
                    if (known.contains(normalized)) continue;
                    known.add(normalized);
                    String fileName = path.getFileName().toString();
                    String lower = fileName.toLowerCase(Locale.ROOT);
                    String scope = lower.contains("-client.") ? "CLIENT" : lower.contains("-common.") ? "COMMON"
                            : lower.contains("-startup.") ? "STARTUP" : lower.contains("-server.") ? "SERVER" : "UNKNOWN";
                    result.add(new NeoForgeConfigCatalog.Entry("unknown", scope, "file:" + normalized, fileName,
                            normalized, false, Map.of()));
                    count++;
                }
                if (inspected >= MAX_FILES) complete = false;
            } catch (IOException | RuntimeException ignored) {
                complete = false;
            }
        }
        return new FallbackFiles(result, complete);
    }

    private boolean allowed(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        for (Path root : roots) {
            if (!normalized.startsWith(root)) continue;
            try {
                Path real = Files.exists(normalized) ? normalized.toRealPath()
                        : normalized.getParent().toRealPath().resolve(normalized.getFileName()).normalize();
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

    private static boolean matches(NeoForgeConfigCatalog.Entry entry, String key, NeoForgeConfigCatalog.Value value,
                                   String text) {
        return text.isEmpty() || entry.owner().toLowerCase(Locale.ROOT).contains(text)
                || entry.scope().toLowerCase(Locale.ROOT).contains(text)
                || entry.fileName().toLowerCase(Locale.ROOT).contains(text)
                || key.toLowerCase(Locale.ROOT).contains(text)
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

    private enum SavedState { OBSERVED, PARTIAL, UNKNOWN }

    private record SavedFile(Map<String, Object> values, SavedState state, String detail) {
        private SavedFile {
            values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
        }
    }

    private record SavedEntry(NeoForgeConfigCatalog.Entry entry, SavedFile saved) {
    }

    private record Candidate(SavedEntry snapshot, String key, NeoForgeConfigCatalog.Value value) {
    }

    private record FallbackFiles(List<NeoForgeConfigCatalog.Entry> entries, boolean complete) {
        private FallbackFiles {
            entries = List.copyOf(entries);
        }
    }

    private record CatalogCapture(List<NeoForgeConfigCatalog.Entry> entries, Throwable failure) {
        private CatalogCapture {
            entries = List.copyOf(entries);
        }
    }

    private record ParsedValue(Object value, boolean supported, String detail) {
    }
}
