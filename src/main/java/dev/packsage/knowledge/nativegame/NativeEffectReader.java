package dev.packsage.knowledge.nativegame;

import dev.packsage.knowledge.Evidence;
import net.minecraft.world.effect.MobEffect;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

final class NativeEffectReader {
    private NativeEffectReader() {
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
