package dev.packsage.knowledge.config;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/** The narrow, read-only ConfigTracker bridge for NeoForge 21.1.202. */
public final class NeoForgeConfigCatalog {
    public static final String LOADER_VERSION = "21.1.202";
    public static final String FML_VERSION = "4.0.41";
    private static final int MAX_ENTRIES = 256;
    private static final int MAX_KEYS = 2048;
    private static final int MAX_DEPTH = 32;
    private static final int MAX_COPIED_NODES = MAX_KEYS * 4;

    public record Value(Object saved, Object loaded, Object defaultValue, String comment, String restart,
                        Object minimum, Object maximum) {
        public Value {
            saved = immutableCopy(saved);
            loaded = immutableCopy(loaded);
            defaultValue = immutableCopy(defaultValue);
            minimum = immutableCopy(minimum);
            maximum = immutableCopy(maximum);
        }
    }

    public record Entry(String owner, String scope, String handle, String fileName, Path filePath,
                        boolean synchronizedServer, Map<String, Value> values) {
        public Entry {
            Objects.requireNonNull(owner);
            Objects.requireNonNull(scope);
            Objects.requireNonNull(handle);
            Objects.requireNonNull(fileName);
            var copied = new LinkedHashMap<String, Value>();
            values.forEach((key, value) -> {
                if (key != null && value != null) copied.put(key, value);
            });
            values = Collections.unmodifiableMap(copied);
        }
    }

    public interface Reader {
        List<Entry> read();

        boolean supported();

        String detail();
    }

    private NeoForgeConfigCatalog() {
    }

    public static Reader supplied(Supplier<List<Entry>> source) {
        Objects.requireNonNull(source);
        return new Reader() {
            @Override
            public List<Entry> read() {
                return List.copyOf(source.get());
            }

            @Override
            public boolean supported() {
                return true;
            }

            @Override
            public String detail() {
                return "supplied copied catalog";
            }
        };
    }

    public static Reader reflective() {
        return new ReflectiveReader();
    }

    private static final class ReflectiveReader implements Reader {
        private volatile String detail = "unsupported NeoForge ConfigTracker bridge: not captured";
        private volatile boolean supported;

        @Override
        public List<Entry> read() {
            var result = readTracker();
            supported = result.supported;
            detail = result.detail;
            return List.copyOf(result.entries);
        }

        @Override
        public boolean supported() {
            return supported;
        }

        @Override
        public String detail() {
            return detail;
        }
    }

    private record ReadResult(List<Entry> entries, boolean supported, String detail) {
    }

    private record EntryCapture(Entry entry, boolean complete) {
    }

    private record RuntimeVersion(String neoForge, String fml) {
    }

    private static ReadResult readTracker() {
        var runtime = runtimeVersion();
        if (!LOADER_VERSION.equals(runtime.neoForge()) || !FML_VERSION.equals(runtime.fml())) {
            return new ReadResult(List.of(), false, "unsupported NeoForge ConfigTracker bridge: expected NeoForge "
                    + LOADER_VERSION + " FML " + FML_VERSION + ", found NeoForge " + runtime.neoForge()
                    + " FML " + runtime.fml());
        }
        try {
            Class<?> trackerType = Class.forName("net.neoforged.fml.config.ConfigTracker");
            Field instanceField = trackerType.getField("INSTANCE");
            Object tracker = instanceField.get(null);
            var configSets = readField(trackerType, tracker, "configSets");
            var configsByMod = readField(trackerType, tracker, "configsByMod");
            var configs = new LinkedHashSet<Object>();
            boolean completeSets = addValues(configSets, configs);
            boolean completeByMod = addValues(configsByMod, configs);
            boolean complete = completeSets && completeByMod;
            var entries = new ArrayList<Entry>();
            int failures = 0;
            for (Object config : configs) {
                try {
                    var capture = ReflectiveReaderSupport.entry(config);
                    entries.add(capture.entry());
                    complete &= capture.complete();
                } catch (ReflectiveOperationException | RuntimeException failure) {
                    failures++;
                }
            }
            if (failures != 0) {
                return new ReadResult(entries, false, "unsupported NeoForge " + LOADER_VERSION + " FML "
                        + FML_VERSION + " ConfigTracker entry extraction failed for " + failures + " record(s)");
            }
            if (!complete) {
                return new ReadResult(entries, false, "unsupported NeoForge " + LOADER_VERSION + " FML "
                        + FML_VERSION + " ConfigTracker catalog exceeded bounded extraction limits");
            }
            return new ReadResult(entries, true, "supported NeoForge " + LOADER_VERSION + " FML " + FML_VERSION
                    + " ConfigTracker fields");
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return new ReadResult(List.of(), false, "unsupported NeoForge ConfigTracker bridge: "
                    + failure.getClass().getSimpleName());
        }
    }

    private static RuntimeVersion runtimeVersion() {
        String neoForge = codeSourceVersion("net.neoforged.neoforge.common.ModConfigSpec", "neoforge-");
        String fml = codeSourceVersion("net.neoforged.fml.config.ConfigTracker", "loader-");
        try {
            Class<?> loader = Class.forName("net.neoforged.fml.loading.FMLLoader");
            Object info = loader.getMethod("versionInfo").invoke(null);
            if (info != null) {
                Object value = info.getClass().getMethod("neoForgeVersion").invoke(info);
                if (value != null) neoForge = String.valueOf(value);
                value = info.getClass().getMethod("fmlVersion").invoke(info);
                if (value != null) fml = String.valueOf(value);
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
        }
        return new RuntimeVersion(neoForge, fml);
    }

    private static String codeSourceVersion(String typeName, String marker) {
        try {
            Class<?> type = Class.forName(typeName);
            var codeSource = type.getProtectionDomain().getCodeSource();
            if (codeSource == null) return null;
            URI location = codeSource.getLocation().toURI();
            String fileName = Path.of(location).getFileName().toString();
            int begin = fileName.indexOf(marker);
            if (begin < 0) return null;
            String version = fileName.substring(begin + marker.length());
            int end = version.indexOf('-');
            if (end < 0) end = version.lastIndexOf('.');
            return end < 0 ? version : version.substring(0, end);
        } catch (ReflectiveOperationException | URISyntaxException | RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    private static Object readField(Class<?> owner, Object target, String name) throws ReflectiveOperationException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static boolean addValues(Object value, Collection<Object> result) {
        if (!(value instanceof Map<?, ?> map)) return false;
        for (Object nested : map.values()) {
            if (!(nested instanceof Collection<?> collection)) return false;
            for (Object config : collection) {
                if (result.size() >= MAX_ENTRIES) return false;
                result.add(config);
            }
        }
        return true;
    }

    private static final class ReflectiveReaderSupport {
        private static EntryCapture entry(Object config) throws ReflectiveOperationException {
            String owner = String.valueOf(call(config, "getModId"));
            Object type = call(config, "getType");
            String scope = String.valueOf(type);
            String fileName = String.valueOf(call(config, "getFileName"));
            Path filePath = null;
            try {
                filePath = (Path) call(config, "getFullPath");
            } catch (ReflectiveOperationException | RuntimeException ignored) {
            }
            Object loadedConfig = call(config, "getLoadedConfig");
            var values = new LinkedHashMap<String, Value>();
            var copyState = new CopyState();
            Object spec = call(config, "getSpec");
            Object specTree = call(spec, "getSpec");
            boolean complete = collectSpec(specTree, List.of(), values, copyState);
            if (loadedConfig != null) {
                Object loadedTree = loadedConfigValue(loadedConfig);
                complete &= collectLoaded(loadedTree, List.of(), values, copyState);
            }
            return new EntryCapture(new Entry(owner, scope, owner + ":" + scope + ":" + fileName, fileName,
                    filePath, "SERVER".equals(scope), values), complete && !copyState.truncated);
        }

        private static boolean collectSpec(Object node, List<String> path, Map<String, Value> values,
                                           CopyState copyState) {
            Map<?, ?> map = asMap(node);
            if (map == null || path.size() > MAX_DEPTH) return false;
            boolean complete = true;
            for (var item : map.entrySet()) {
                if (!(item.getKey() instanceof String key)) {
                    complete = false;
                    continue;
                }
                var next = new ArrayList<>(path);
                next.add(key);
                if (asMap(item.getValue()) != null) {
                    complete &= collectSpec(item.getValue(), next, values, copyState);
                    continue;
                }
                if (values.size() >= MAX_KEYS) {
                    complete = false;
                    break;
                }
                try {
                    Object valueSpec = item.getValue();
                    Object range = call(valueSpec, "getRange");
                    values.put(String.join(".", next), new Value(null, null, copy(call(valueSpec, "getDefault"), 0,
                            copyState), nullableString(call(valueSpec, "getComment")),
                            nullableString(call(valueSpec, "restartType")), range == null ? null
                                    : copy(call(range, "getMin"), 0, copyState), range == null ? null
                                    : copy(call(range, "getMax"), 0, copyState)));
                } catch (ReflectiveOperationException | RuntimeException failure) {
                    complete = false;
                }
            }
            return complete;
        }

        private static boolean collectLoaded(Object node, List<String> path, Map<String, Value> values,
                                             CopyState copyState) {
            Map<?, ?> map = asMap(node);
            if (map == null || path.size() > MAX_DEPTH) return false;
            boolean complete = true;
            for (var item : map.entrySet()) {
                if (!(item.getKey() instanceof String key)) {
                    complete = false;
                    continue;
                }
                var next = new ArrayList<>(path);
                next.add(key);
                if (asMap(item.getValue()) != null) {
                    complete &= collectLoaded(item.getValue(), next, values, copyState);
                    continue;
                }
                String name = String.join(".", next);
                if (!values.containsKey(name) && values.size() >= MAX_KEYS) {
                    complete = false;
                    break;
                }
                Object copied = copy(item.getValue(), 0, copyState);
                var old = values.get(name);
                values.put(name, old == null ? new Value(null, copied, null, null, null, null, null)
                        : new Value(old.saved(), copied, old.defaultValue(), old.comment(), old.restart(),
                        old.minimum(), old.maximum()));
            }
            return complete;
        }

        private static Object loadedConfigValue(Object loadedConfig) throws ReflectiveOperationException {
            Class<?> loadedInterface = Class.forName("net.neoforged.fml.config.IConfigSpec$ILoadedConfig");
            return loadedInterface.getMethod("config").invoke(loadedConfig);
        }

        private static Object call(Object target, String name) throws ReflectiveOperationException {
            if (target == null) return null;
            Method method = target.getClass().getMethod(name);
            try {
                return method.invoke(target);
            } catch (IllegalAccessException failure) {
                for (Class<?> interfaceType : target.getClass().getInterfaces()) {
                    if (!Modifier.isPublic(interfaceType.getModifiers())) continue;
                    try {
                        return interfaceType.getMethod(name).invoke(target);
                    } catch (NoSuchMethodException ignored) {
                    }
                }
                throw failure;
            }
        }

        private static String nullableString(Object value) {
            return value == null ? null : String.valueOf(value);
        }

        private static Object copy(Object value, int depth, CopyState state) {
            if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean
                    || value instanceof Character || value.getClass().isEnum()) return value;
            if (depth > MAX_DEPTH || state.remaining-- <= 0) {
                state.truncated = true;
                return "<value omitted>";
            }
            if (value instanceof Map<?, ?> map) {
                var result = new LinkedHashMap<String, Object>();
                for (var item : map.entrySet()) {
                    if (state.remaining <= 0) {
                        state.truncated = true;
                        break;
                    }
                    result.put(String.valueOf(item.getKey()), copy(item.getValue(), depth + 1, state));
                }
                return Collections.unmodifiableMap(result);
            }
            if (value instanceof Collection<?> collection) {
                var result = new ArrayList<Object>();
                for (Object nested : collection) {
                    if (state.remaining <= 0) {
                        state.truncated = true;
                        break;
                    }
                    result.add(copy(nested, depth + 1, state));
                }
                return List.copyOf(result);
            }
            return value;
        }

        private static Map<?, ?> asMap(Object value) {
            if (value instanceof Map<?, ?> map) return map;
            if (value == null) return null;
            try {
                Class<?> configType = Class.forName("com.electronwill.nightconfig.core.UnmodifiableConfig");
                Object map = configType.getMethod("valueMap").invoke(value);
                return map instanceof Map<?, ?> result ? result : null;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                return null;
            }
        }
    }

    private static final class CopyState {
        private int remaining = MAX_COPIED_NODES;
        private boolean truncated;
    }

    private static Object immutableCopy(Object value) {
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean
                || value instanceof Character || value.getClass().isEnum()) return value;
        if (value instanceof Map<?, ?> map) {
            var copied = new LinkedHashMap<String, Object>();
            map.forEach((key, nested) -> copied.put(String.valueOf(key), immutableCopy(nested)));
            return Collections.unmodifiableMap(copied);
        }
        if (value instanceof Collection<?> collection) return List.copyOf(collection.stream().map(NeoForgeConfigCatalog::immutableCopy).toList());
        return value;
    }
}
