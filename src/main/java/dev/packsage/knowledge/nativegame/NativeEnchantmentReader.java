package dev.packsage.knowledge.nativegame;

import com.mojang.serialization.JsonOps;
import dev.packsage.knowledge.Evidence;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

final class NativeEnchantmentReader {
    static final int MAX_LEVELS = 32;
    static final int MAX_EFFECT_BYTES = 16384;

    private NativeEnchantmentReader() {
    }

    record Levels(Evidence.JsonArray values, boolean bounded) {
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
        var encoded = EnchantmentEffectComponents.CODEC
                .encodeStart(access.createSerializationContext(JsonOps.INSTANCE), value.effects())
                .result().filter(com.google.gson.JsonElement::isJsonObject)
                .map(element -> Evidence.deserializeObject(element.toString()));
        if (encoded.isEmpty()) return status("unknown");
        var effects = encoded.get();
        if (Evidence.serialize(effects).getBytes(StandardCharsets.UTF_8).length > MAX_EFFECT_BYTES) return status("unknown_truncated");
        return effects;
    }

    private static Evidence.JsonObject status(String status) {
        return new Evidence.JsonObject(Map.of("status", new Evidence.JsonString(status)));
    }
}
