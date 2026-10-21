package dev.packsage.knowledge.nativegame;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import dev.packsage.knowledge.Evidence;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class NativeItemFacts {
    private static final int MAX_EFFECTS = 32;

    private NativeItemFacts() {
    }

    /** Returns true when the enriched payload is partial: bounded effects or an unavailable fingerprint. */
    public static boolean enrich(Map<String, Evidence.JsonValue> payload, ItemStack stack, HolderLookup.Provider registries) {
        var partial = false;
        var fingerprint = componentFingerprint(stack, registries);
        if (fingerprint == null) {
            payload.put("componentFingerprint", new Evidence.JsonNull());
            payload.put("variantDetail", new Evidence.JsonString("unavailable"));
            partial = true;
        } else {
            payload.put("componentFingerprint", new Evidence.JsonString(fingerprint));
        }
        if (stack.has(DataComponents.POTION_CONTENTS)) {
            var potion = potionContents(stack);
            payload.put("potionContents", potion);
            if (potion.values().containsKey("effectsStatus")) partial = true;
        }
        return partial;
    }

    static String componentFingerprint(ItemStack stack, HolderLookup.Provider registries) {
        try {
            var encoded = DataComponentPatch.CODEC.encodeStart(registries.createSerializationContext(JsonOps.INSTANCE),
                    stack.getComponentsPatch()).result();
            if (encoded.isEmpty()) return null;
            var id = BuiltInRegistries.ITEM.getKey(stack.getItem());
            var text = id + "\n" + sorted(encoded.get());
            var digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (RuntimeException | NoSuchAlgorithmException failure) {
            return null;
        }
    }

    private static JsonElement sorted(JsonElement value) {
        if (value.isJsonObject()) {
            var entries = new TreeMap<String, JsonElement>();
            value.getAsJsonObject().entrySet().forEach(entry -> entries.put(entry.getKey(), entry.getValue()));
            var result = new JsonObject();
            entries.forEach((key, child) -> result.add(key, sorted(child)));
            return result;
        }
        if (value.isJsonArray()) {
            var result = new JsonArray();
            value.getAsJsonArray().forEach(child -> result.add(sorted(child)));
            return result;
        }
        return value;
    }

    static Evidence.JsonObject potionContents(ItemStack stack) {
        var contents = stack.get(DataComponents.POTION_CONTENTS);
        var values = new LinkedHashMap<String, Evidence.JsonValue>();
        values.put("potion", contents == null || contents.potion().isEmpty() ? new Evidence.JsonNull()
                : contents.potion().get().unwrapKey()
                .<Evidence.JsonValue>map(key -> new Evidence.JsonString(key.location().toString()))
                .orElseGet(Evidence.JsonNull::new));
        values.put("customColor", contents == null || contents.customColor().isEmpty() ? new Evidence.JsonNull()
                : new Evidence.JsonNumber(BigDecimal.valueOf(contents.customColor().get())));
        var effects = new ArrayList<Evidence.JsonValue>();
        var bounded = false;
        if (contents != null) {
            var potionEffects = contents.potion().map(potion -> potion.value().getEffects())
                    .orElse(List.<MobEffectInstance>of());
            var count = 0;
            for (var effect : potionEffects) {
                if (count++ == MAX_EFFECTS) {
                    bounded = true;
                    break;
                }
                effects.add(effect(effect, "potion"));
            }
            if (!bounded) {
                for (var effect : contents.customEffects()) {
                    if (count++ == MAX_EFFECTS) {
                        bounded = true;
                        break;
                    }
                    effects.add(effect(effect, "custom"));
                }
            }
        }
        values.put("effects", new Evidence.JsonArray(effects));
        if (bounded) values.put("effectsStatus", new Evidence.JsonString("bounded"));
        var scale = durationScale(stack.getItem());
        if (scale == null) {
            values.put("durationScale", new Evidence.JsonNull());
            values.put("durationScaleStatus", new Evidence.JsonString("unknown_item"));
        } else {
            values.put("durationScale", new Evidence.JsonNumber(scale));
        }
        return new Evidence.JsonObject(values);
    }

    private static Evidence.JsonValue effect(MobEffectInstance effect, String origin) {
        var values = new LinkedHashMap<String, Evidence.JsonValue>();
        values.put("id", effect.getEffect().unwrapKey()
                .<Evidence.JsonValue>map(key -> new Evidence.JsonString(key.location().toString()))
                .orElseGet(Evidence.JsonNull::new));
        values.put("durationTicks", new Evidence.JsonNumber(BigDecimal.valueOf(effect.getDuration())));
        values.put("amplifier", new Evidence.JsonNumber(BigDecimal.valueOf(effect.getAmplifier())));
        values.put("ambient", new Evidence.JsonBoolean(effect.isAmbient()));
        values.put("visible", new Evidence.JsonBoolean(effect.isVisible()));
        values.put("showIcon", new Evidence.JsonBoolean(effect.showIcon()));
        values.put("origin", new Evidence.JsonString(origin));
        return new Evidence.JsonObject(values);
    }

    private static BigDecimal durationScale(Item item) {
        if (item == Items.POTION || item == Items.SPLASH_POTION) return BigDecimal.ONE;
        if (item == Items.LINGERING_POTION) return new BigDecimal("0.25");
        if (item == Items.TIPPED_ARROW) return new BigDecimal("0.125");
        return null;
    }
}
