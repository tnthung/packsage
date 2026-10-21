package dev.packsage.knowledge.nativegame;

import com.mojang.serialization.JsonOps;
import dev.packsage.knowledge.Evidence;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.TypedDataComponent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Unit;
import net.minecraft.world.item.enchantment.ConditionalEffect;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.TargetedConditionalEffect;
import net.minecraft.world.item.enchantment.effects.AddValue;
import net.minecraft.world.item.enchantment.effects.AllOf;
import net.minecraft.world.item.enchantment.effects.EnchantmentAttributeEffect;
import net.minecraft.world.item.enchantment.effects.EnchantmentEntityEffect;
import net.minecraft.world.item.enchantment.effects.EnchantmentLocationBasedEffect;
import net.minecraft.world.item.enchantment.effects.EnchantmentValueEffect;
import net.minecraft.world.item.enchantment.effects.MultiplyValue;
import net.minecraft.world.item.enchantment.effects.RemoveBinomial;
import net.minecraft.world.item.enchantment.effects.SetValue;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class NativeEnchantmentReader {
    static final int MAX_LEVELS = 32;
    static final int MAX_EFFECT_BYTES = 16384;
    static final int MAX_COMPONENTS = 64;
    static final int MAX_EFFECTS = 32;

    private NativeEnchantmentReader() {
    }

    record Levels(Evidence.JsonArray values, boolean bounded) {
    }

    record Mechanics(Evidence.JsonArray values, boolean partial) {
    }

    private record Decoded(Evidence.JsonObject entry, boolean partial) {
    }

    private record DecodedValue(Evidence.JsonObject value, boolean partial) {
    }

    static Levels levels(Enchantment value) {
        var definition = value.definition();
        var count = Math.min(definition.maxLevel(), MAX_LEVELS);
        var values = new ArrayList<Evidence.JsonValue>();
        for (var level = 1; level <= count; level++) {
            var entry = new LinkedHashMap<String, Evidence.JsonValue>();
            entry.put("level", new Evidence.JsonNumber(BigDecimal.valueOf(level)));
            entry.put("minCost", new Evidence.JsonNumber(BigDecimal.valueOf(definition.minCost().calculate(level))));
            entry.put("maxCost", new Evidence.JsonNumber(BigDecimal.valueOf(definition.maxCost().calculate(level))));
            values.add(new Evidence.JsonObject(entry));
        }
        return new Levels(new Evidence.JsonArray(values), definition.maxLevel() > MAX_LEVELS);
    }

    static Evidence.JsonObject effects(Enchantment value, HolderLookup.Provider access) {
        java.util.Optional<Evidence.JsonObject> encoded;
        try {
            encoded = EnchantmentEffectComponents.CODEC
                    .encodeStart(access.createSerializationContext(JsonOps.INSTANCE), value.effects())
                    .result().filter(com.google.gson.JsonElement::isJsonObject)
                    .map(element -> Evidence.deserializeObject(element.toString()));
        } catch (RuntimeException failure) {
            return status("unknown");
        }
        if (encoded.isEmpty()) return status("unknown");
        var effects = encoded.get();
        if (Evidence.serialize(effects).getBytes(StandardCharsets.UTF_8).length > MAX_EFFECT_BYTES) return status("unknown_truncated");
        return effects;
    }

    static Mechanics mechanics(Enchantment value) {
        var levels = Math.min(value.definition().maxLevel(), MAX_LEVELS);
        var entries = new ArrayList<Evidence.JsonValue>();
        var partial = false;
        var count = 0;
        for (TypedDataComponent<?> typed : value.effects()) {
            if (count++ >= MAX_COMPONENTS) {
                entries.add(new Evidence.JsonObject(Map.of("status", new Evidence.JsonString("bounded"))));
                partial = true;
                break;
            }
            var id = componentId(typed.type());
            Decoded decoded;
            try {
                decoded = decodeComponent(id, typed.value(), levels);
            } catch (RuntimeException failure) {
                decoded = opaque(id);
            }
            entries.add(decoded.entry());
            partial |= decoded.partial();
        }
        return new Mechanics(new Evidence.JsonArray(entries), partial);
    }

    private static String componentId(DataComponentType<?> type) {
        var key = BuiltInRegistries.ENCHANTMENT_EFFECT_COMPONENT_TYPE.getKey(type);
        return key == null ? "unknown" : key.toString();
    }

    private static Decoded decodeComponent(String id, Object component, int levels) {
        if (id.equals("minecraft:trident_sound") || id.equals("minecraft:crossbow_charging_sounds")) {
            return entry(id, "presentation", "not_evaluated", null, false);
        }
        if (id.equals("unknown")) return opaque(id);
        if (component instanceof Unit) return entry(id, "flag", "decoded", null, false);
        if (component instanceof EnchantmentValueEffect effect) {
            var decoded = valueEffect(effect, false, levels);
            return entry(id, "value", decoded.partial() ? "partial" : "decoded", List.of(decoded.value()), decoded.partial());
        }
        if (!(component instanceof List<?> list)) return opaque(id);
        if (list.isEmpty()) return entry(id, "empty", "decoded", null, false);
        var bounded = list.size() > MAX_EFFECTS;
        var items = list.subList(0, Math.min(list.size(), MAX_EFFECTS));
        var effects = new ArrayList<Evidence.JsonValue>();
        var partial = bounded;
        String kind;
        if (items.getFirst() instanceof EnchantmentAttributeEffect) {
            kind = "attribute";
            for (var item : items) {
                if (!(item instanceof EnchantmentAttributeEffect attribute)) return opaque(id);
                effects.add(attributeEffect(attribute, levels));
            }
        } else if (isConditional(items.getFirst())) {
            kind = effectOf(items.getFirst()) instanceof EnchantmentValueEffect ? "value" : "behavior";
            for (var item : items) {
                if (!isConditional(item)) return opaque(id);
                var effect = effectOf(item);
                var conditional = requirementsPresent(item);
                if (kind.equals("value")) {
                    if (!(effect instanceof EnchantmentValueEffect valueEffect)) return opaque(id);
                    var decoded = valueEffect(valueEffect, conditional, levels);
                    effects.add(decoded.value());
                    partial |= decoded.partial();
                } else {
                    effects.add(behaviorEffect(id, effect, conditional));
                }
            }
        } else {
            return opaque(id);
        }
        var status = bounded ? "bounded" : kind.equals("behavior") ? "not_evaluated" : partial ? "partial" : "decoded";
        return entry(id, kind, status, effects, partial);
    }

    private static boolean isConditional(Object value) {
        return value instanceof ConditionalEffect<?> || value instanceof TargetedConditionalEffect<?>;
    }

    private static Object effectOf(Object conditional) {
        if (conditional instanceof ConditionalEffect<?> effect) return effect.effect();
        return ((TargetedConditionalEffect<?>) conditional).effect();
    }

    private static boolean requirementsPresent(Object conditional) {
        if (conditional instanceof ConditionalEffect<?> effect) return effect.requirements().isPresent();
        return ((TargetedConditionalEffect<?>) conditional).requirements().isPresent();
    }

    private static DecodedValue valueEffect(EnchantmentValueEffect effect, boolean conditional, int levels) {
        var result = new LinkedHashMap<String, Evidence.JsonValue>();
        var key = BuiltInRegistries.ENCHANTMENT_VALUE_EFFECT_TYPE.getKey(effect.codec());
        result.put("type", new Evidence.JsonString(key == null ? "unknown" : key.toString()));
        result.put("conditional", new Evidence.JsonBoolean(conditional));
        if (effect instanceof AllOf.ValueEffects all) {
            var nested = new ArrayList<Evidence.JsonValue>();
            var partial = false;
            for (var inner : all.effects()) {
                var decoded = valueEffect(inner, false, levels);
                nested.add(decoded.value());
                partial |= decoded.partial();
            }
            result.put("effects", new Evidence.JsonArray(nested));
            return new DecodedValue(new Evidence.JsonObject(result), partial);
        }
        String field;
        if (effect instanceof AddValue) field = "add";
        else if (effect instanceof SetValue) field = "set";
        else if (effect instanceof MultiplyValue) field = "multiply";
        else if (effect instanceof RemoveBinomial) field = "chance";
        else {
            result.put("status", new Evidence.JsonString("opaque"));
            return new DecodedValue(new Evidence.JsonObject(result), true);
        }
        var values = new ArrayList<Evidence.JsonValue>();
        for (var level = 1; level <= levels; level++) {
            var number = effect instanceof RemoveBinomial binomial ? binomial.chance().calculate(level)
                    : effect instanceof MultiplyValue ? effect.process(level, null, 1f) : effect.process(level, null, 0f);
            var entry = new LinkedHashMap<String, Evidence.JsonValue>();
            entry.put("level", new Evidence.JsonNumber(BigDecimal.valueOf(level)));
            entry.put(field, number(number));
            values.add(new Evidence.JsonObject(entry));
        }
        result.put("values", new Evidence.JsonArray(values));
        return new DecodedValue(new Evidence.JsonObject(result), false);
    }

    private static Evidence.JsonObject attributeEffect(EnchantmentAttributeEffect effect, int levels) {
        var result = new LinkedHashMap<String, Evidence.JsonValue>();
        result.put("attribute", new Evidence.JsonString(effect.attribute().unwrapKey()
                .map(key -> key.location().toString()).orElse("unknown")));
        result.put("modifierId", new Evidence.JsonString(effect.id().toString()));
        result.put("operation", new Evidence.JsonString(effect.operation().getSerializedName()));
        var amounts = new ArrayList<Evidence.JsonValue>();
        for (var level = 1; level <= levels; level++) {
            var entry = new LinkedHashMap<String, Evidence.JsonValue>();
            entry.put("level", new Evidence.JsonNumber(BigDecimal.valueOf(level)));
            entry.put("amount", number(effect.amount().calculate(level)));
            amounts.add(new Evidence.JsonObject(entry));
        }
        result.put("amounts", new Evidence.JsonArray(amounts));
        return new Evidence.JsonObject(result);
    }

    private static Evidence.JsonObject behaviorEffect(String componentId, Object effect, boolean conditional) {
        String type = componentId;
        if (effect instanceof EnchantmentEntityEffect entity) {
            var key = BuiltInRegistries.ENCHANTMENT_ENTITY_EFFECT_TYPE.getKey(entity.codec());
            if (key != null) type = key.toString();
        } else if (effect instanceof EnchantmentLocationBasedEffect location) {
            var key = BuiltInRegistries.ENCHANTMENT_LOCATION_BASED_EFFECT_TYPE.getKey(location.codec());
            if (key != null) type = key.toString();
        }
        var result = new LinkedHashMap<String, Evidence.JsonValue>();
        result.put("type", new Evidence.JsonString(type));
        result.put("conditional", new Evidence.JsonBoolean(conditional));
        return new Evidence.JsonObject(result);
    }

    private static Evidence.JsonNumber number(float value) {
        return new Evidence.JsonNumber(new BigDecimal(Float.toString(value)));
    }

    private static Decoded opaque(String id) {
        return entry(id, "opaque", "opaque", null, true);
    }

    private static Decoded entry(String id, String kind, String status, List<Evidence.JsonValue> effects, boolean partial) {
        var result = new LinkedHashMap<String, Evidence.JsonValue>();
        result.put("component", new Evidence.JsonString(id));
        result.put("kind", new Evidence.JsonString(kind));
        result.put("status", new Evidence.JsonString(status));
        if (effects != null) result.put("effects", new Evidence.JsonArray(effects));
        return new Decoded(new Evidence.JsonObject(result), partial);
    }

    private static Evidence.JsonObject status(String status) {
        return new Evidence.JsonObject(Map.of("status", new Evidence.JsonString(status)));
    }
}
