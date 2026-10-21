package dev.packsage.knowledge.nativegame;

import dev.packsage.knowledge.Evidence;
import net.minecraft.ChatFormatting;
import net.minecraft.locale.Language;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class NativeEffectReader {
    private static final int MAX_DESCRIPTION = 1024;

    private NativeEffectReader() {
    }

    static Evidence.Record activeRecord(MobEffectInstance instance, Evidence.Scope scope) {
        var effectId = effectId(instance);
        var payload = new LinkedHashMap<String, Evidence.JsonValue>();
        payload.put("registry", new Evidence.JsonString("active_effect"));
        payload.put("id", new Evidence.JsonString(effectId));
        payload.put("identity", new Evidence.JsonString(effectId));
        payload.put("effect", new Evidence.JsonString(effectId));
        payload.put("durationTicks", new Evidence.JsonNumber(BigDecimal.valueOf(instance.getDuration())));
        payload.put("infinite", new Evidence.JsonBoolean(instance.isInfiniteDuration()));
        payload.put("amplifier", new Evidence.JsonNumber(BigDecimal.valueOf(instance.getAmplifier())));
        payload.put("ambient", new Evidence.JsonBoolean(instance.isAmbient()));
        payload.put("visible", new Evidence.JsonBoolean(instance.isVisible()));
        payload.put("showIcon", new Evidence.JsonBoolean(instance.showIcon()));
        var modifiers = contained(instance.getEffect().value(), instance.getAmplifier(), instance.getAmplifier());
        payload.put("attributeModifiers", modifiers);
        describe(payload, instance.getEffect().value(), Language.getInstance());
        var source = new Evidence.Source(scope, "native", "1.21.1", "active_effect:" + effectId,
                scope.requestGeneration(), Instant.now(), Evidence.Activity.ACTIVE,
                modifiers instanceof Evidence.JsonObject ? Evidence.Coverage.PARTIAL : Evidence.Coverage.COMPLETE,
                Evidence.Authority.LOADED, List.of("client-player:active-effects"));
        return new Evidence.Record(source, Evidence.RecordKind.OTHER, instance.getEffect().value().getDisplayName().getString(),
                new Evidence.JsonObject(payload), List.of());
    }

    static NativeRegistryReader.Page searchActive(Collection<MobEffectInstance> active, String text, List<String> ids,
                                                  List<String> tags, int offset, int limit, Evidence.Scope scope) {
        var matches = new ArrayList<MobEffectInstance>();
        for (var instance : active) {
            var effectId = effectId(instance);
            if (!ids.isEmpty() && !ids.contains(effectId)) continue;
            if (!tags.isEmpty() && instance.getEffect().tags().map(tag -> tag.location().toString()).noneMatch(tags::contains)) continue;
            if (text != null && !(effectId + " " + instance.getEffect().value().getDisplayName().getString())
                    .toLowerCase(Locale.ROOT).contains(text.toLowerCase(Locale.ROOT))) continue;
            matches.add(instance);
        }
        matches.sort(Comparator.comparing(NativeEffectReader::effectId));
        int start = Math.min(Math.max(offset, 0), matches.size());
        int end = Math.min(matches.size(), start + limit);
        var records = matches.subList(start, end).stream().map(instance -> activeRecord(instance, scope)).toList();
        return new NativeRegistryReader.Page(records, end, end == matches.size(), false, matches.size());
    }

    static Evidence.Record readActive(Collection<MobEffectInstance> active, String effectId, Evidence.Scope scope) {
        for (var instance : active) {
            if (effectId(instance).equals(effectId)) return activeRecord(instance, scope);
        }
        return null;
    }

    private static String effectId(MobEffectInstance instance) {
        return instance.getEffect().unwrapKey().map(key -> key.location().toString()).orElse("unknown:effect");
    }

    /** Reads the optional {@code <descriptionId>.description} translation shipped by some mods. */
    static void describe(Map<String, Evidence.JsonValue> payload, MobEffect effect, Language language) {
        try {
            var key = effect.getDescriptionId() + ".description";
            if (!language.has(key)) {
                payload.put("descriptionStatus", new Evidence.JsonString("not_provided"));
                return;
            }
            var text = ChatFormatting.stripFormatting(language.getOrDefault(key));
            if (text == null) text = "";
            boolean bounded = text.length() > MAX_DESCRIPTION;
            payload.put("description", new Evidence.JsonString(bounded ? text.substring(0, MAX_DESCRIPTION) : text));
            payload.put("descriptionSource", new Evidence.JsonString("language:" + key));
            if (bounded) payload.put("descriptionStatus", new Evidence.JsonString("bounded"));
        } catch (RuntimeException failure) {
            payload.remove("description");
            payload.remove("descriptionSource");
            payload.put("descriptionStatus", new Evidence.JsonString("unknown"));
        }
    }

    /** Modifiers, or {status:"unknown"} when mod code throws while creating them. */
    static Evidence.JsonValue contained(MobEffect effect, int fromAmplifier, int toAmplifier) {
        try {
            return attributeModifiers(effect, fromAmplifier, toAmplifier);
        } catch (RuntimeException failure) {
            return new Evidence.JsonObject(Map.of("status", new Evidence.JsonString("unknown")));
        }
    }

    static Evidence.JsonArray attributeModifiers(MobEffect effect, int fromAmplifier, int toAmplifier) {
        var grouped = new LinkedHashMap<String, Map<String, Object>>();
        for (int amplifier = fromAmplifier; amplifier <= toAmplifier; amplifier++) {
            int current = amplifier;
            effect.createModifiers(amplifier, (attribute, modifier) -> {
                var attributeId = attribute.unwrapKey().map(key -> key.location().toString()).orElse("unknown:attribute");
                var modifierId = modifier.id().toString();
                var entry = grouped.computeIfAbsent(attributeId + "\n" + modifierId, ignored -> {
                    var fields = new LinkedHashMap<String, Object>();
                    fields.put("attribute", attributeId);
                    fields.put("modifierId", modifierId);
                    fields.put("operation", modifier.operation().getSerializedName());
                    fields.put("amounts", new ArrayList<Evidence.JsonValue>());
                    return fields;
                });
                var amount = new LinkedHashMap<String, Evidence.JsonValue>();
                amount.put("amplifier", new Evidence.JsonNumber(BigDecimal.valueOf(current)));
                amount.put("amount", new Evidence.JsonNumber(BigDecimal.valueOf(modifier.amount())));
                @SuppressWarnings("unchecked")
                var amounts = (ArrayList<Evidence.JsonValue>) entry.get("amounts");
                amounts.add(new Evidence.JsonObject(amount));
            });
        }
        var result = new ArrayList<Evidence.JsonValue>();
        for (var entry : grouped.values()) {
            var fields = new LinkedHashMap<String, Evidence.JsonValue>();
            fields.put("attribute", new Evidence.JsonString((String) entry.get("attribute")));
            fields.put("modifierId", new Evidence.JsonString((String) entry.get("modifierId")));
            fields.put("operation", new Evidence.JsonString((String) entry.get("operation")));
            @SuppressWarnings("unchecked")
            var amounts = (ArrayList<Evidence.JsonValue>) entry.get("amounts");
            fields.put("amounts", new Evidence.JsonArray(amounts));
            result.add(new Evidence.JsonObject(fields));
        }
        return new Evidence.JsonArray(result);
    }
}
