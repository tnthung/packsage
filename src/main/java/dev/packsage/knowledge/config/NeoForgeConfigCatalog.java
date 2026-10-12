package dev.packsage.knowledge.config;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
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

    public record Value(Object saved, Object loaded, Object defaultValue, String comment, String restart,
                        Object minimum, Object maximum) {
    }

    public record Entry(String owner, String scope, String handle, String fileName, Path filePath,
                        boolean synchronizedServer, Map<String, Value> values) {
        public Entry {
            Objects.requireNonNull(owner);
            Objects.requireNonNull(scope);
            Objects.requireNonNull(handle);
            Objects.requireNonNull(fileName);
            values = Map.copyOf(values);
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
        private volatile String detail;

        private ReflectiveReader() {
            detail = "unsupported NeoForge ConfigTracker bridge: not read";
        }

        @Override
        public List<Entry> read() {
            var result = readTracker();
            detail = result.detail;
            return result.entries;
        }

        @Override
        public boolean supported() {
            if (!detail.startsWith("supported")) {
                var result = readTracker();
                detail = result.detail;
            }
            return detail.startsWith("supported");
        }

        @Override
        public String detail() {
            return detail;
        }
    }

    private record ReadResult(List<Entry> entries, String detail) {
    }

    private static ReadResult readTracker() {
        try {
            Class<?> trackerType = Class.forName("net.neoforged.fml.config.ConfigTracker");
            Field instanceField = trackerType.getField("INSTANCE");
            Object tracker = instanceField.get(null);
            var configSets = readField(trackerType, tracker, "configSets");
            var configsByMod = readField(trackerType, tracker, "configsByMod");
            var configs = new LinkedHashSet<Object>();
            addValues(configSets, configs);
            addValues(configsByMod, configs);
            var entries = configs.stream().map(ReflectiveReaderSupport::entry).filter(Objects::nonNull).toList();
            return new ReadResult(entries, "supported NeoForge " + LOADER_VERSION + " ConfigTracker fields");
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return new ReadResult(List.of(), "unsupported NeoForge ConfigTracker bridge: " + failure.getClass().getSimpleName());
        }
    }

    private static Object readField(Class<?> owner, Object target, String name) throws ReflectiveOperationException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void addValues(Object value, Collection<Object> result) {
        if (!(value instanceof Map<?, ?> map)) return;
        for (Object nested : map.values()) {
            if (nested instanceof Collection<?> collection) result.addAll(collection);
        }
    }

    private static final class ReflectiveReaderSupport {
        private static Entry entry(Object config) {
            try {
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
                Map<String, Value> values = new LinkedHashMap<>();
                Object spec = call(config, "getSpec");
                Object specTree = call(spec, "getSpec");
                collectSpec(specTree, List.of(), values, loadedConfig);
                if (loadedConfig != null) {
                    Object loadedTree = call(loadedConfig, "config");
                    collectLoaded(loadedTree, List.of(), values);
                }
                return new Entry(owner, scope, owner + ":" + scope + ":" + fileName, fileName, filePath,
                        "SERVER".equals(scope), values);
            } catch (ReflectiveOperationException | RuntimeException failure) {
                return null;
            }
        }

        private static void collectSpec(Object node, List<String> path, Map<String, Value> values, Object loaded) {
            Map<?, ?> map = asMap(node);
            if (map == null) return;
            for (var item : map.entrySet()) {
                if (!(item.getKey() instanceof String key)) continue;
                var next = new ArrayList<>(path);
                next.add(key);
                if (asMap(item.getValue()) != null) {
                    collectSpec(item.getValue(), next, values, loaded);
                    continue;
                }
                try {
                    Object valueSpec = item.getValue();
                    Object range = call(valueSpec, "getRange");
                    values.put(String.join(".", next), new Value(null, null, call(valueSpec, "getDefault"),
                            nullableString(call(valueSpec, "getComment")), nullableString(call(valueSpec, "restartType")),
                            range == null ? null : call(range, "getMin"), range == null ? null : call(range, "getMax")));
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                }
            }
        }

        private static void collectLoaded(Object node, List<String> path, Map<String, Value> values) {
            Map<?, ?> map = asMap(node);
            if (map == null) return;
            for (var item : map.entrySet()) {
                if (!(item.getKey() instanceof String key)) continue;
                var next = new ArrayList<>(path);
                next.add(key);
                if (asMap(item.getValue()) != null) {
                    collectLoaded(item.getValue(), next, values);
                    continue;
                }
                String name = String.join(".", next);
                var old = values.get(name);
                values.put(name, old == null ? new Value(null, copy(item.getValue()), null, null, null, null, null)
                        : new Value(old.saved(), copy(item.getValue()), old.defaultValue(), old.comment(), old.restart(), old.minimum(), old.maximum()));
            }
        }

        private static Object call(Object target, String name) throws ReflectiveOperationException {
            if (target == null) return null;
            Method method = target.getClass().getMethod(name);
            return method.invoke(target);
        }

        private static String nullableString(Object value) {
            return value == null ? null : String.valueOf(value);
        }

        private static Object copy(Object value) {
            if (value instanceof Map<?, ?> map) {
                var result = new LinkedHashMap<String, Object>();
                map.forEach((key, nested) -> result.put(String.valueOf(key), copy(nested)));
                return Collections.unmodifiableMap(result);
            }
            if (value instanceof Collection<?> collection) return List.copyOf(collection.stream().map(ReflectiveReaderSupport::copy).toList());
            return value;
        }

        private static Map<?, ?> asMap(Object value) {
            if (value instanceof Map<?, ?> map) return map;
            try {
                Object map = call(value, "valueMap");
                return map instanceof Map<?, ?> result ? result : null;
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                return null;
            }
        }
    }
}
