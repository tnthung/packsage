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

public final class NativeEnchantmentEligibility {
    static final int MAX_ENCHANTMENTS = 512;
    static final int MAX_EXCLUSIVE = 32;

    private NativeEnchantmentEligibility() {
    }

    public static Evidence.JsonObject read(ItemStack stack, Registry<Enchantment> enchantments) {
        return read(stack, (Iterable<Holder.Reference<Enchantment>>) enchantments.holders()::iterator);
    }

    public static Evidence.JsonObject read(ItemStack stack, net.minecraft.core.HolderLookup.RegistryLookup<Enchantment> enchantments) {
        return read(stack, (Iterable<Holder.Reference<Enchantment>>) enchantments.listElements()::iterator);
    }

    private static Evidence.JsonObject read(ItemStack stack, Iterable<Holder.Reference<Enchantment>> holders) {
        int failures = 0;
        net.minecraft.world.item.enchantment.ItemEnchantments current;
        try {
            current = stack.getTagEnchantments();
        } catch (RuntimeException exception) {
            current = net.minecraft.world.item.enchantment.ItemEnchantments.EMPTY;
            failures++;
        }
        var supported = new ArrayList<Evidence.JsonValue>();
        var entries = new ArrayList<LinkedHashMap<String, Evidence.JsonValue>>();
        var supportedHolders = new ArrayList<Holder.Reference<Enchantment>>();
        int inspected = 0;
        boolean bounded = false;
        for (var holder : holders) {
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
                entries.add(entry);
                supportedHolders.add(holder);
            } catch (RuntimeException exception) {
                failures++;
            }
        }
        for (int index = 0; index < entries.size(); index++) {
            var exclusive = new java.util.TreeSet<String>();
            for (int other = 0; other < supportedHolders.size(); other++) {
                if (other == index) continue;
                try {
                    if (!Enchantment.areCompatible(supportedHolders.get(index), supportedHolders.get(other))) {
                        exclusive.add(supportedHolders.get(other).key().location().toString());
                    }
                } catch (RuntimeException exception) {
                    failures++;
                }
            }
            var ids = new ArrayList<Evidence.JsonValue>();
            for (var id : exclusive) {
                if (ids.size() >= MAX_EXCLUSIVE) break;
                ids.add(new Evidence.JsonString(id));
            }
            var entry = entries.get(index);
            entry.put("exclusiveWith", new Evidence.JsonArray(ids));
            if (exclusive.size() > MAX_EXCLUSIVE) entry.put("exclusiveWithStatus", new Evidence.JsonString("bounded"));
            supported.add(new Evidence.JsonObject(entry));
        }
        var values = new LinkedHashMap<String, Evidence.JsonValue>();
        Evidence.JsonValue enchantable;
        try {
            enchantable = new Evidence.JsonBoolean(stack.isEnchantable());
        } catch (RuntimeException exception) {
            enchantable = new Evidence.JsonNull();
            failures++;
        }
        values.put("enchantable", enchantable);
        values.put("supported", new Evidence.JsonArray(supported));
        values.put("inspected", new Evidence.JsonNumber(new BigDecimal(inspected)));
        if (failures > 0) values.put("failures", new Evidence.JsonNumber(new BigDecimal(failures)));
        values.put("status", new Evidence.JsonString(bounded ? "bounded" : failures > 0 ? "partial" : "complete"));
        return new Evidence.JsonObject(values);
    }

    public static boolean complete(Evidence.JsonObject eligibility) {
        return new Evidence.JsonString("complete").equals(eligibility.values().get("status"));
    }
}
