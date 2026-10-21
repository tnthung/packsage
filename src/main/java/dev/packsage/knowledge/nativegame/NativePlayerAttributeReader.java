package dev.packsage.knowledge.nativegame;

import dev.packsage.knowledge.Evidence;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

final class NativePlayerAttributeReader {
    static final int MAX_MODIFIERS = 64;

    private NativePlayerAttributeReader() {
    }

    static Evidence.Record record(AttributeInstance instance, Evidence.Scope scope) {
        var attributeId = attributeId(instance);
        var name = name(instance);
        boolean syncable = instance.getAttribute().value().isClientSyncable();
        var sorted = new ArrayList<>(instance.getModifiers());
        sorted.sort(Comparator.comparing((AttributeModifier modifier) -> modifier.id().toString()));
        boolean bounded = sorted.size() > MAX_MODIFIERS;
        var modifiers = new ArrayList<Evidence.JsonValue>();
        for (var modifier : sorted.subList(0, Math.min(sorted.size(), MAX_MODIFIERS))) {
            var fields = new LinkedHashMap<String, Evidence.JsonValue>();
            fields.put("id", new Evidence.JsonString(modifier.id().toString()));
            fields.put("amount", new Evidence.JsonNumber(BigDecimal.valueOf(modifier.amount())));
            fields.put("operation", new Evidence.JsonString(modifier.operation().getSerializedName()));
            modifiers.add(new Evidence.JsonObject(fields));
        }
        var payload = new LinkedHashMap<String, Evidence.JsonValue>();
        payload.put("registry", new Evidence.JsonString("player_attribute"));
        payload.put("id", new Evidence.JsonString(attributeId));
        payload.put("identity", new Evidence.JsonString(attributeId));
        payload.put("attribute", new Evidence.JsonString(attributeId));
        payload.put("name", new Evidence.JsonString(name));
        payload.put("syncable", new Evidence.JsonBoolean(syncable));
        payload.put("base", new Evidence.JsonNumber(BigDecimal.valueOf(instance.getBaseValue())));
        payload.put("value", new Evidence.JsonNumber(BigDecimal.valueOf(instance.getValue())));
        payload.put("modifiers", new Evidence.JsonArray(modifiers));
        if (bounded) payload.put("modifiersStatus", new Evidence.JsonString("bounded"));
        var source = new Evidence.Source(scope, "native", "1.21.1", "player_attribute:" + attributeId,
                scope.requestGeneration(), Instant.now(), Evidence.Activity.ACTIVE,
                syncable && !bounded ? Evidence.Coverage.COMPLETE : Evidence.Coverage.PARTIAL,
                Evidence.Authority.LOADED, List.of("client-player:attributes"));
        return new Evidence.Record(source, Evidence.RecordKind.OTHER, name, new Evidence.JsonObject(payload), List.of());
    }

    static NativeRegistryReader.Page search(Collection<AttributeInstance> instances, String text, List<String> ids,
                                            List<String> tags, int offset, int limit, Evidence.Scope scope) {
        var matches = new ArrayList<AttributeInstance>();
        for (var instance : instances) {
            var attributeId = attributeId(instance);
            if (!ids.isEmpty() && !ids.contains(attributeId)) continue;
            if (!tags.isEmpty() && instance.getAttribute().tags().map(tag -> tag.location().toString()).noneMatch(tags::contains)) continue;
            if (text != null && !(attributeId + " " + name(instance)).toLowerCase(Locale.ROOT)
                    .contains(text.toLowerCase(Locale.ROOT))) continue;
            matches.add(instance);
        }
        matches.sort(Comparator.comparing(NativePlayerAttributeReader::attributeId));
        int start = Math.min(Math.max(offset, 0), matches.size());
        int end = Math.min(matches.size(), start + limit);
        var records = matches.subList(start, end).stream().map(instance -> record(instance, scope)).toList();
        return new NativeRegistryReader.Page(records, end, end == matches.size(), false, matches.size());
    }

    static Evidence.Record read(Collection<AttributeInstance> instances, String id, Evidence.Scope scope) {
        for (var instance : instances) {
            if (attributeId(instance).equals(id)) return record(instance, scope);
        }
        return null;
    }

    private static String attributeId(AttributeInstance instance) {
        return instance.getAttribute().unwrapKey().map(key -> key.location().toString()).orElse("unknown:attribute");
    }

    private static String name(AttributeInstance instance) {
        return Component.translatable(instance.getAttribute().value().getDescriptionId()).getString();
    }
}
