package dev.packsage.knowledge.nativegame;

import dev.packsage.knowledge.Evidence;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class NativeEnchantmentEligibility {
    static final int MAX_ENCHANTMENTS = 512;

    private NativeEnchantmentEligibility() {
    }

    static Evidence.JsonObject read(ItemStack stack, Registry<Enchantment> enchantments) {
        var current = stack.getTagEnchantments();
        var supported = new ArrayList<Evidence.JsonValue>();
        int inspected = 0;
        int failures = 0;
        boolean bounded = false;
        for (var holder : (Iterable<Holder.Reference<Enchantment>>) enchantments.holders()::iterator) {
            if (inspected >= MAX_ENCHANTMENTS) {
                bounded = true;
                break;
            }
            inspected++;
            try {
                if (!stack.supportsEnchantment(holder)) continue;
                boolean compatible = true;
                for (var other : current.keySet()) {
                    if (!other.equals(holder) && !Enchantment.areCompatible(holder, other)) {
                        compatible = false;
                        break;
                    }
                }
                var entry = new LinkedHashMap<String, Evidence.JsonValue>();
                entry.put("id", new Evidence.JsonString(holder.key().location().toString()));
                entry.put("primary", new Evidence.JsonBoolean(stack.isPrimaryItemFor(holder)));
                entry.put("compatibleWithCurrent", new Evidence.JsonBoolean(compatible));
                entry.put("currentLevel", new Evidence.JsonNumber(new BigDecimal(current.getLevel(holder))));
                supported.add(new Evidence.JsonObject(entry));
            } catch (RuntimeException exception) {
                failures++;
            }
        }
        var values = new LinkedHashMap<String, Evidence.JsonValue>();
        values.put("enchantable", new Evidence.JsonBoolean(stack.isEnchantable()));
        values.put("supported", new Evidence.JsonArray(supported));
        values.put("inspected", new Evidence.JsonNumber(new BigDecimal(inspected)));
        if (failures > 0) values.put("failures", new Evidence.JsonNumber(new BigDecimal(failures)));
        values.put("status", new Evidence.JsonString(bounded ? "bounded" : failures > 0 ? "partial" : "complete"));
        return new Evidence.JsonObject(values);
    }

    static boolean complete(Evidence.JsonObject eligibility) {
        return new Evidence.JsonString("complete").equals(eligibility.values().get("status"));
    }
}
