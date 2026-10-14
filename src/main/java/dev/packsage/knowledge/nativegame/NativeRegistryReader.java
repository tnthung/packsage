package dev.packsage.knowledge.nativegame;

import com.mojang.serialization.JsonOps;
import dev.packsage.knowledge.Evidence;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.tags.TagKey;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.level.block.Block;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;

final class NativeRegistryReader {
    static final int MAX_CANDIDATES = 512;
    private static final int MAX_MEMBERS = 128;

    private NativeRegistryReader() { }

    static Page search(Minecraft client, String selectedRegistry, String text, List<String> ids, List<String> tags,
                       int offset, int limit, Evidence.Scope scope) {
        if (!client.isSameThread()) throw new IllegalStateException("native registry read must run on the client thread");
        if (client.level == null) throw new IllegalStateException("client level unavailable");
        if (ids.size() > 32 || tags.size() > 32 || java.util.stream.Stream.concat(ids.stream(), tags.stream()).anyMatch(identity -> {
            var parsed = ResourceLocation.tryParse(identity);
            return parsed == null || !parsed.toString().equals(identity);
        })) throw new IllegalArgumentException("registry IDs and tags must be canonical identities, up to 32 each");
        if (selectedRegistry.equals("item_tag")) {
            if (!tags.isEmpty()) throw new UnsupportedOperationException("item_tag does not support an entry-membership tags filter");
            return searchItemTags(client.level.registryAccess().registryOrThrow(Registries.ITEM), text, ids, offset, limit, scope);
        }
        return switch (selectedRegistry) {
            case "item" -> {
                var registry = client.level.registryAccess().registryOrThrow(Registries.ITEM);
                yield searchEntries(registry, selectedRegistry, text, ids, tags, offset, limit, scope,
                        value -> entry(selectedRegistry, value, registry, client, scope));
            }
            case "block" -> {
                var registry = client.level.registryAccess().registryOrThrow(Registries.BLOCK);
                yield searchEntries(registry, selectedRegistry, text, ids, tags, offset, limit, scope,
                        value -> entry(selectedRegistry, value, registry, client, scope));
            }
            case "enchantment" -> {
                var registry = client.level.registryAccess().registryOrThrow(Registries.ENCHANTMENT);
                yield searchEntries(registry, selectedRegistry, text, ids, tags, offset, limit, scope,
                        value -> entry(selectedRegistry, value, registry, client, scope));
            }
            case "mob_effect" -> searchEntries(BuiltInRegistries.MOB_EFFECT, selectedRegistry, text, ids, tags, offset, limit,
                    scope, value -> entry(selectedRegistry, value, BuiltInRegistries.MOB_EFFECT, client, scope));
            case "potion" -> searchEntries(BuiltInRegistries.POTION, selectedRegistry, text, ids, tags, offset, limit,
                    scope, value -> entry(selectedRegistry, value, BuiltInRegistries.POTION, client, scope));
            case "dimension_type" -> {
                var registry = client.level.registryAccess().registryOrThrow(Registries.DIMENSION_TYPE);
                yield searchEntries(registry, selectedRegistry, text, ids, tags, offset, limit, scope,
                        value -> dimensionType(value, registry, scope));
            }
            default -> throw new IllegalArgumentException("unsupported native registry: " + selectedRegistry);
        };
    }

    static Evidence.Record read(Minecraft client, String selectedRegistry, String identity, Evidence.Scope scope) {
        if (!client.isSameThread()) throw new IllegalStateException("native registry read must run on the client thread");
        if (client.level == null) return null;
        var key = ResourceLocation.tryParse(identity);
        if (key == null || !key.toString().equals(identity)) return null;
        if (selectedRegistry.equals("item_tag")) {
            var items = client.level.registryAccess().registryOrThrow(Registries.ITEM);
            return items.getTag(TagKey.create(Registries.ITEM, key)).map(value -> itemTag(key, value, items, scope)).orElse(null);
        }
        return switch (selectedRegistry) {
            case "item" -> {
                var registry = client.level.registryAccess().registryOrThrow(Registries.ITEM);
                if (!registry.containsKey(key)) yield null;
                var value = registry.get(key);
                yield value == null ? null : entry(selectedRegistry, value, registry, client, scope);
            }
            case "block" -> {
                var registry = client.level.registryAccess().registryOrThrow(Registries.BLOCK);
                if (!registry.containsKey(key)) yield null;
                var value = registry.get(key);
                yield value == null ? null : entry(selectedRegistry, value, registry, client, scope);
            }
            case "enchantment" -> {
                var registry = client.level.registryAccess().registryOrThrow(Registries.ENCHANTMENT);
                if (!registry.containsKey(key)) yield null;
                var value = registry.get(key);
                yield value == null ? null : entry(selectedRegistry, value, registry, client, scope);
            }
            case "mob_effect" -> {
                if (!BuiltInRegistries.MOB_EFFECT.containsKey(key)) yield null;
                var value = BuiltInRegistries.MOB_EFFECT.get(key);
                yield value == null ? null : entry(selectedRegistry, value, BuiltInRegistries.MOB_EFFECT, client, scope);
            }
            case "potion" -> {
                if (!BuiltInRegistries.POTION.containsKey(key)) yield null;
                var value = BuiltInRegistries.POTION.get(key);
                yield value == null ? null : entry(selectedRegistry, value, BuiltInRegistries.POTION, client, scope);
            }
            case "dimension_type" -> {
                var registry = client.level.registryAccess().registryOrThrow(Registries.DIMENSION_TYPE);
                if (!registry.containsKey(key)) yield null;
                var value = registry.get(key);
                yield value == null ? null : dimensionType(value, registry, scope);
            }
            default -> null;
        };
    }

    static <T> Page searchEntries(Registry<T> registry, String selectedRegistry, String text,
                                  List<String> ids, List<String> tags, int offset, int limit,
                                  Evidence.Scope scope, Function<T, Evidence.Record> recordFactory) {
        if (!ids.isEmpty()) {
            var matches = new ArrayList<Evidence.Record>();
            var seen = new java.util.HashSet<String>();
            for (String identity : ids) {
                var key = ResourceLocation.tryParse(identity);
                if (key == null || !key.toString().equals(identity) || !seen.add(identity)) continue;
                if (!registry.containsKey(key)) continue;
                T value = registry.get(key);
                if (value == null || !matchesIdentityAndTags(registry, value, identity, text, ids, tags)) continue;
                var record = recordFactory.apply(value);
                matches.add(record);
            }
            int start = Math.min(offset, matches.size());
            int end = Math.min(matches.size(), start + limit);
            var pageRecords = matches.subList(start, end);
            return new Page(pageRecords, end, end == matches.size(), pageRecords.stream()
                    .anyMatch(record -> record.source().coverage() != Evidence.Coverage.COMPLETE), matches.size());
        }
        int size = registry.size();
        int index = Math.min(offset, size);
        int scanned = 0;
        var records = new ArrayList<Evidence.Record>();
        var partial = false;
        while (index < size && scanned < MAX_CANDIDATES) {
            int candidateIndex = index++;
            scanned++;
            T value = registry.byId(candidateIndex);
            if (value == null) continue;
            var id = registry.getKey(value);
            if (id == null || !matchesIdentityAndTags(registry, value, id.toString(), text, ids, tags)) continue;
            if (records.size() == limit) return new Page(records, candidateIndex, false, partial, scanned);
            records.add(recordFactory.apply(value));
            partial |= records.getLast().source().coverage() != Evidence.Coverage.COMPLETE;
            if (records.size() == limit) return new Page(records, index, index >= size, partial, scanned);
        }
        return new Page(records, index, index >= size, partial, scanned);
    }

    private static Page searchItemTags(Registry<Item> registry, String text, List<String> ids, int offset, int limit,
                                       Evidence.Scope scope) {
        if (!ids.isEmpty()) {
            var matches = new ArrayList<Evidence.Record>();
            var seen = new java.util.HashSet<String>();
            for (String identity : ids) {
                var key = ResourceLocation.tryParse(identity);
                if (key == null || !key.toString().equals(identity) || !seen.add(identity)) continue;
                var tag = registry.getTag(TagKey.create(Registries.ITEM, key)).orElse(null);
                if (tag == null || text != null && !identity.toLowerCase(Locale.ROOT).contains(text.toLowerCase(Locale.ROOT))) continue;
                matches.add(itemTag(key, tag, registry, scope));
            }
            int start = Math.min(offset, matches.size());
            int end = Math.min(matches.size(), start + limit);
            var pageRecords = matches.subList(start, end);
            return new Page(pageRecords, end, end == matches.size(), pageRecords.stream()
                    .anyMatch(record -> record.source().coverage() != Evidence.Coverage.COMPLETE), matches.size());
        }
        return page(registry.getTags().iterator(), offset, limit, pair -> {
            var identity = pair.getFirst().location().toString();
            return (ids.isEmpty() || ids.contains(identity))
                    && (text == null || identity.toLowerCase(Locale.ROOT).contains(text.toLowerCase(Locale.ROOT)));
        }, pair -> itemTag(pair.getFirst().location(), pair.getSecond(), registry, scope));
    }

    static <T> Page page(java.util.Iterator<T> iterator, int offset, int limit, Predicate<T> matches,
                         Function<T, Evidence.Record> recordFactory) {
        if (offset < 0 || offset > 2048) throw new dev.packsage.knowledge.Toolbox.Failure(
                dev.packsage.knowledge.Toolbox.Code.INVALID_CURSOR, "item-tag continuation exceeds the bounded seek; use an exact tag ID");
        int index = 0;
        while (index < offset && iterator.hasNext()) {
            iterator.next();
            index++;
        }
        int scanned = 0;
        var records = new ArrayList<Evidence.Record>();
        var partial = false;
        while (iterator.hasNext() && scanned < MAX_CANDIDATES) {
            T candidate = iterator.next();
            index++;
            scanned++;
            if (!matches.test(candidate)) continue;
            var record = recordFactory.apply(candidate);
            records.add(record);
            partial |= record.source().coverage() != Evidence.Coverage.COMPLETE;
            if (records.size() == limit) return new Page(records, index, !iterator.hasNext(), partial, scanned);
        }
        return new Page(records, index, !iterator.hasNext(), partial, scanned);
    }

    private static <T> boolean matchesIdentityAndTags(Registry<T> registry, T value, String identity,
                                                       String text, List<String> ids, List<String> tags) {
        if (!ids.isEmpty() && !ids.contains(identity)) return false;
        var key = ResourceLocation.tryParse(identity);
        var holder = key == null ? null : registry.getHolder(key).orElse(null);
        if (!tags.isEmpty() && (holder == null || holder.tags().map(tag -> tag.location().toString()).noneMatch(tags::contains))) return false;
        if (text == null) return true;
        return (identity + " " + name(value)).toLowerCase(Locale.ROOT).contains(text.toLowerCase(Locale.ROOT));
    }

    private static String name(Object value) {
        if (value instanceof Item item) return new ItemStack(item).getHoverName().getString();
        if (value instanceof Block block) return block.getName().getString();
        if (value instanceof Enchantment enchantment) return enchantment.description().getString();
        if (value instanceof MobEffect effect) return effect.getDisplayName().getString();
        return value instanceof Potion ? value.toString() : "";
    }

    private static <T> Evidence.Record entry(String selectedRegistry, T value, Registry<T> registry,
                                              Minecraft client, Evidence.Scope scope) {
        var id = registry.getKey(value);
        var identity = id == null ? "unknown:" + selectedRegistry : id.toString();
        if (value instanceof Item item) return item(item, identity,
                client.level.registryAccess().registryOrThrow(Registries.ITEM), client.level.registryAccess(), scope);
        if (value instanceof Block block) return block(block, identity, scope);
        if (value instanceof Enchantment enchantment) return enchantment(enchantment, identity, client.level.registryAccess(), scope);
        if (value instanceof MobEffect effect) return effect(effect, identity, scope);
        if (value instanceof Potion potion) return potion(potion, identity, scope);
        throw new IllegalArgumentException("unsupported native registry value: " + selectedRegistry);
    }

    private static Evidence.Record dimensionType(DimensionType value, Registry<DimensionType> registry, Evidence.Scope scope) {
        var key = registry.getKey(value);
        var identity = key == null ? "unknown:dimension_type" : key.toString();
        var tags = registry.getHolder(key).map(holder -> holder.tags().map(tag -> tag.location().toString()).toList()).orElse(List.of());
        var payload = new LinkedHashMap<String, Evidence.JsonValue>();
        payload.put("registry", new Evidence.JsonString("dimension_type"));
        payload.put("id", new Evidence.JsonString(identity));
        payload.put("identity", new Evidence.JsonString(identity));
        payload.put("minY", new Evidence.JsonNumber(BigDecimal.valueOf(value.minY())));
        payload.put("height", new Evidence.JsonNumber(BigDecimal.valueOf(value.height())));
        payload.put("logicalHeight", new Evidence.JsonNumber(BigDecimal.valueOf(value.logicalHeight())));
        payload.put("coordinateScale", new Evidence.JsonNumber(BigDecimal.valueOf(value.coordinateScale())));
        payload.put("skylight", new Evidence.JsonBoolean(value.hasSkyLight()));
        payload.put("ceiling", new Evidence.JsonBoolean(value.hasCeiling()));
        payload.put("natural", new Evidence.JsonBoolean(value.natural()));
        payload.put("ultraWarm", new Evidence.JsonBoolean(value.ultraWarm()));
        payload.put("bedWorks", new Evidence.JsonBoolean(value.bedWorks()));
        payload.put("respawnAnchorWorks", new Evidence.JsonBoolean(value.respawnAnchorWorks()));
        payload.put("piglinSafe", new Evidence.JsonBoolean(value.piglinSafe()));
        payload.put("hasRaids", new Evidence.JsonBoolean(value.hasRaids()));
        value.fixedTime().ifPresent(time -> payload.put("fixedTime", new Evidence.JsonNumber(BigDecimal.valueOf(time))));
        payload.put("effects", new Evidence.JsonString(value.effectsLocation().toString()));
        payload.put("infiniburn", new Evidence.JsonString(value.infiniburn().location().toString()));
        payload.put("tags", new Evidence.JsonArray(tags.stream().map(Evidence.JsonString::new)
                .map(tag -> (Evidence.JsonValue) tag).toList()));
        return record("dimension_type", identity, Evidence.RecordKind.OTHER, identity, payload,
                Evidence.Coverage.COMPLETE, List.of("client-registry:dimension-type"), scope);
    }

    private static Evidence.Record item(Item item, String identity, Registry<Item> registry,
                                        net.minecraft.core.HolderLookup.Provider access, Evidence.Scope scope) {
        var tags = registry.getHolder(ResourceLocation.parse(identity))
                .map(holder -> holder.tags().map(tag -> tag.location().toString()).limit(MAX_MEMBERS + 1L).toList()).orElse(List.of());
        var tagsPartial = tags.size() > MAX_MEMBERS;
        var tagValues = tags.stream().limit(MAX_MEMBERS).map(Evidence.JsonString::new).map(value -> (Evidence.JsonValue) value).toList();
        var components = DataComponentMap.CODEC.encodeStart(access.createSerializationContext(JsonOps.INSTANCE), new ItemStack(item).getComponents())
                .result().filter(com.google.gson.JsonElement::isJsonObject).map(value -> Evidence.deserializeObject(value.toString()))
                .orElseGet(() -> new Evidence.JsonObject(Map.of("status", new Evidence.JsonString("unknown"))));
        if (Evidence.serialize(components).length() > 8192) components = new Evidence.JsonObject(Map.of("status", new Evidence.JsonString("unknown_truncated")));
        var payload = new LinkedHashMap<String, Evidence.JsonValue>();
        payload.put("registry", new Evidence.JsonString("item"));
        payload.put("id", new Evidence.JsonString(identity));
        payload.put("identity", new Evidence.JsonString(identity));
        payload.put("tags", new Evidence.JsonArray(tagValues));
        payload.put("components", components);
        var coverage = tagsPartial || components.values().containsKey("status") ? Evidence.Coverage.PARTIAL : Evidence.Coverage.COMPLETE;
        return record("item", identity, Evidence.RecordKind.ITEM, new ItemStack(item).getHoverName().getString(), payload,
                coverage, List.of("client-registry:item"), scope);
    }

    private static Evidence.Record block(Block block, String identity, Evidence.Scope scope) {
        return record("block", identity, Evidence.RecordKind.BLOCK, block.getName().getString(), Map.of(
                "registry", new Evidence.JsonString("block"), "id", new Evidence.JsonString(identity),
                "identity", new Evidence.JsonString(identity)), Evidence.Coverage.COMPLETE, List.of("client-registry:block"), scope);
    }

    private static Evidence.Record itemTag(ResourceLocation identity, HolderSet.Named<Item> members, Registry<Item> registry,
                                           Evidence.Scope scope) {
        var memberIds = members.stream().limit(MAX_MEMBERS + 1L).map(Holder::value).map(registry::getKey)
                .filter(Objects::nonNull).map(ResourceLocation::toString).toList();
        boolean truncated = memberIds.size() > MAX_MEMBERS;
        var values = new LinkedHashMap<String, Evidence.JsonValue>();
        values.put("registry", new Evidence.JsonString("item_tag"));
        values.put("id", new Evidence.JsonString(identity.toString()));
        values.put("identity", new Evidence.JsonString(identity.toString()));
        values.put("members", new Evidence.JsonArray(memberIds.stream().limit(MAX_MEMBERS).map(Evidence.JsonString::new)
                .map(value -> (Evidence.JsonValue) value).toList()));
        values.put("memberCountObserved", new Evidence.JsonNumber(BigDecimal.valueOf(memberIds.size())));
        return record("item_tag", identity.toString(), Evidence.RecordKind.OTHER, identity.toString(), values,
                truncated ? Evidence.Coverage.PARTIAL : Evidence.Coverage.COMPLETE, List.of("client-registry:item-tag"), scope);
    }

    private static Evidence.Record enchantment(Enchantment value, String identity,
                                                net.minecraft.core.HolderLookup.Provider access, Evidence.Scope scope) {
        var definition = value.definition();
        var supported = definition.supportedItems().stream().limit(MAX_MEMBERS + 1L)
                .map(holder -> holder.unwrapKey().map(key -> key.location().toString()).orElse("unknown:item")).toList();
        var primary = definition.primaryItems().map(items -> items.stream().limit(MAX_MEMBERS + 1L)
                .map(holder -> holder.unwrapKey().map(key -> key.location().toString()).orElse("unknown:item")).toList()).orElse(List.of());
        var exclusive = value.exclusiveSet().stream().limit(MAX_MEMBERS + 1L)
                .map(holder -> holder.unwrapKey().map(key -> key.location().toString()).orElse("unknown:enchantment")).toList();
        var slots = definition.slots().stream().map(Enum::name).map(Evidence.JsonString::new)
                .map(item -> (Evidence.JsonValue) item).toList();
        var payload = new LinkedHashMap<String, Evidence.JsonValue>();
        payload.put("registry", new Evidence.JsonString("enchantment"));
        payload.put("id", new Evidence.JsonString(identity));
        payload.put("identity", new Evidence.JsonString(identity));
        payload.put("maxLevel", new Evidence.JsonNumber(BigDecimal.valueOf(definition.maxLevel())));
        payload.put("weight", new Evidence.JsonNumber(BigDecimal.valueOf(definition.weight())));
        payload.put("minCost", new Evidence.JsonNumber(BigDecimal.valueOf(definition.minCost().calculate(1))));
        payload.put("maxCost", new Evidence.JsonNumber(BigDecimal.valueOf(definition.maxCost().calculate(definition.maxLevel()))));
        payload.put("anvilCost", new Evidence.JsonNumber(BigDecimal.valueOf(definition.anvilCost())));
        payload.put("slots", new Evidence.JsonArray(slots));
        payload.put("supportedItems", new Evidence.JsonArray(supported.stream().limit(MAX_MEMBERS).map(Evidence.JsonString::new)
                .map(item -> (Evidence.JsonValue) item).toList()));
        payload.put("primaryItems", new Evidence.JsonArray(primary.stream().limit(MAX_MEMBERS).map(Evidence.JsonString::new)
                .map(item -> (Evidence.JsonValue) item).toList()));
        payload.put("exclusiveSet", new Evidence.JsonArray(exclusive.stream().limit(MAX_MEMBERS).map(Evidence.JsonString::new)
                .map(item -> (Evidence.JsonValue) item).toList()));
        payload.put("effectsStatus", new Evidence.JsonString("registered_components_not_interpreted"));
        boolean partial = supported.size() > MAX_MEMBERS || primary.size() > MAX_MEMBERS || exclusive.size() > MAX_MEMBERS;
        return record("enchantment", identity, Evidence.RecordKind.OTHER, value.description().getString(), payload,
                partial ? Evidence.Coverage.PARTIAL : Evidence.Coverage.COMPLETE, List.of("client-registry:enchantment"), scope);
    }

    private static Evidence.Record effect(MobEffect value, String identity, Evidence.Scope scope) {
        var payload = new LinkedHashMap<String, Evidence.JsonValue>();
        payload.put("registry", new Evidence.JsonString("mob_effect"));
        payload.put("id", new Evidence.JsonString(identity));
        payload.put("identity", new Evidence.JsonString(identity));
        payload.put("descriptionKey", new Evidence.JsonString(value.getDescriptionId()));
        payload.put("category", new Evidence.JsonString(value.getCategory().name()));
        payload.put("color", new Evidence.JsonNumber(BigDecimal.valueOf(value.getColor())));
        payload.put("mechanicsStatus", new Evidence.JsonString("callbacks_not_interpreted"));
        return record("mob_effect", identity, Evidence.RecordKind.OTHER, value.getDisplayName().getString(), payload,
                Evidence.Coverage.PARTIAL, List.of("client-registry:mob-effect"), scope);
    }

    private static Evidence.Record potion(Potion value, String identity, Evidence.Scope scope) {
        var effects = new ArrayList<Evidence.JsonValue>();
        for (MobEffectInstance instance : value.getEffects()) {
            var effectId = instance.getEffect().unwrapKey().map(key -> key.location().toString()).orElse("unknown:effect");
            var fields = new LinkedHashMap<String, Evidence.JsonValue>();
            fields.put("id", new Evidence.JsonString(effectId));
            fields.put("durationTicks", new Evidence.JsonNumber(BigDecimal.valueOf(instance.getDuration())));
            fields.put("amplifier", new Evidence.JsonNumber(BigDecimal.valueOf(instance.getAmplifier())));
            fields.put("ambient", new Evidence.JsonBoolean(instance.isAmbient()));
            fields.put("visible", new Evidence.JsonBoolean(instance.isVisible()));
            fields.put("showIcon", new Evidence.JsonBoolean(instance.showIcon()));
            effects.add(new Evidence.JsonObject(fields));
        }
        var payload = new LinkedHashMap<String, Evidence.JsonValue>();
        payload.put("registry", new Evidence.JsonString("potion"));
        payload.put("id", new Evidence.JsonString(identity));
        payload.put("identity", new Evidence.JsonString(identity));
        payload.put("effects", new Evidence.JsonArray(effects));
        payload.put("itemContentsStatus", new Evidence.JsonString("custom_potion_contents_are_instance_evidence"));
        return record("potion", identity, Evidence.RecordKind.OTHER, identity, payload, Evidence.Coverage.COMPLETE,
                List.of("client-registry:potion"), scope);
    }

    private static Evidence.Record record(String registry, String identity, Evidence.RecordKind kind, String title,
                                          Map<String, Evidence.JsonValue> payload, Evidence.Coverage coverage,
                                          List<String> provenance, Evidence.Scope scope) {
        var source = new Evidence.Source(scope, "native", "1.21.1", sourceHandle(registry, identity),
                scope.requestGeneration(), Instant.now(), Evidence.Activity.ACTIVE, coverage,
                Evidence.Authority.LOADED, provenance);
        return new Evidence.Record(source, kind, title, new Evidence.JsonObject(payload), List.of());
    }

    private static String sourceHandle(String registry, String identity) {
        return registry.equals("item_tag") ? "tag:" + identity : registry + ":" + identity;
    }

    record Page(List<Evidence.Record> records, int nextOffset, boolean exhausted, boolean partial, int scanned) {
        Page {
            records = List.copyOf(records);
        }
    }
}
