package dev.packsage.world.minecraft;

import dev.packsage.knowledge.Evidence;
import dev.packsage.knowledge.nativegame.NativeItemFacts;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Villager and wandering-trader offers, read from the stored field. {@code AbstractVillager.getOffers()} generates
 * offers when none exist yet, so it is never called; prices for the player are computed without touching the offer.
 */
final class MinecraftTrades {
    /** The merchant's stored offers; null when the field cannot be read. */
    static final Field OFFERS = offersField();
    static final int MAX_OFFERS = 64;
    /** Changed components per offered stack; 64 offers of up to three stacks stay within a few hundred kilobytes. */
    static final int MAX_COMPONENT_BYTES = 1024;

    private MinecraftTrades() {
    }

    /** Adds the merchant's offers, whether it is trading, and this player's reputation and prices. */
    static void facts(AbstractVillager merchant, ServerPlayer player, Map<String, Evidence.JsonValue> values) {
        var offers = storedOffers(merchant);
        var trading = merchant.isTrading();
        var level = merchant.level();
        var registries = level == null ? null : level.registryAccess();
        Integer reputation = merchant instanceof Villager villager && player != null ? villager.getPlayerReputation(player) : null;
        if (offers == null) {
            values.put("offers", new Evidence.JsonString("not generated yet"));
        } else {
            var priced = reputation != null && !trading;
            var hero = priced ? player.getEffect(MobEffects.HERO_OF_THE_VILLAGE) : null;
            var heroAmplifier = hero == null ? -1 : hero.getAmplifier();
            var entries = new ArrayList<Evidence.JsonValue>();
            for (int index = 0; index < offers.size() && index < MAX_OFFERS; index++) {
                var offer = offers.get(index);
                entries.add(offer(offer, index, priced ? costForPlayer(offer, reputation, heroAmplifier) : null, registries));
            }
            values.put("offers", new Evidence.JsonArray(entries));
            if (offers.size() > MAX_OFFERS) values.put("offersStatus", new Evidence.JsonString("bounded"));
        }
        values.put("trading", new Evidence.JsonBoolean(trading));
        if (reputation != null) values.put("playerReputation", number(reputation));
        if (offers != null) values.put("pricesNote", new Evidence.JsonString(pricesNote(trading)));
    }

    /** While trading, the game has already lowered each offer's price for the trading player. */
    static String pricesNote(boolean trading) {
        return trading ? "costA.count already includes demand and the trading player's reputation and Hero of the Village discounts,"
                + " which the game applied when trading started"
                : "costA.count already includes demand; costForPlayer adds this player's reputation and Hero of the Village discounts,"
                + " which the game applies when trading starts";
    }

    /** The stored offers, or null when they were never generated. Never calls {@code getOffers()}. */
    static MerchantOffers storedOffers(AbstractVillager merchant) {
        if (OFFERS == null) throw new IllegalStateException("villager offers cannot be read without generating them");
        try {
            return (MerchantOffers) OFFERS.get(merchant);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("villager offers cannot be read without generating them: " + exception, exception);
        }
    }

    static Evidence.JsonObject offer(MerchantOffer offer, int index, Integer costForPlayer, HolderLookup.Provider registries) {
        var fields = new LinkedHashMap<String, Evidence.JsonValue>();
        fields.put("index", number(index));
        var costA = stack(offer.getCostA(), registries);
        costA.put("baseCount", number(offer.getBaseCostA().getCount()));
        fields.put("costA", new Evidence.JsonObject(costA));
        offer.getItemCostB().ifPresent(cost -> fields.put("costB", new Evidence.JsonObject(stack(cost.itemStack(), registries))));
        var result = stack(offer.getResult(), registries);
        result.put("name", new Evidence.JsonString(offer.getResult().getHoverName().getString()));
        fields.put("result", new Evidence.JsonObject(result));
        fields.put("uses", number(offer.getUses()));
        fields.put("maxUses", number(offer.getMaxUses()));
        fields.put("outOfStock", new Evidence.JsonBoolean(offer.isOutOfStock()));
        fields.put("specialPriceDiff", number(offer.getSpecialPriceDiff()));
        fields.put("demand", number(offer.getDemand()));
        fields.put("priceMultiplier", new Evidence.JsonNumber(new BigDecimal(Float.toString(offer.getPriceMultiplier()))));
        fields.put("xp", number(offer.getXp()));
        fields.put("rewardsXp", new Evidence.JsonBoolean(offer.shouldRewardExp()));
        if (costForPlayer != null) fields.put("costForPlayer", number(costForPlayer));
        return new Evidence.JsonObject(fields);
    }

    /**
     * The first cost this player would pay once trading starts: {@code Villager.updateSpecialPrices} lowers the price by
     * reputation, then by Hero of the Village ({@code heroAmplifier} -1 when absent), on top of demand. The offer is not changed.
     */
    static int costForPlayer(MerchantOffer offer, int reputation, int heroAmplifier) {
        var base = offer.getBaseCostA();
        int count = base.getCount();
        int special = offer.getSpecialPriceDiff();
        if (reputation != 0) special -= Mth.floor((float) reputation * offer.getPriceMultiplier());
        if (heroAmplifier >= 0) special -= Math.max((int) Math.floor((0.3 + 0.0625 * heroAmplifier) * count), 1);
        int demand = Math.max(0, Mth.floor((float) (count * offer.getDemand()) * offer.getPriceMultiplier()));
        return Mth.clamp(count + demand + special, 1, base.getMaxStackSize());
    }

    /** Item, count and what distinguishes it: enchantments, potion contents and the other changed components. */
    private static Map<String, Evidence.JsonValue> stack(ItemStack stack, HolderLookup.Provider registries) {
        var fields = new LinkedHashMap<String, Evidence.JsonValue>();
        fields.put("item", new Evidence.JsonString(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString()));
        fields.put("count", number(stack.getCount()));
        MinecraftInventories.putEnchantments(fields, "enchantments", stack.get(DataComponents.ENCHANTMENTS));
        MinecraftInventories.putEnchantments(fields, "storedEnchantments", stack.get(DataComponents.STORED_ENCHANTMENTS));
        if (stack.has(DataComponents.POTION_CONTENTS)) fields.put("potion", NativeItemFacts.potionContents(stack));
        if (!stack.getComponentsPatch().isEmpty()) {
            fields.put("components", registries == null ? new Evidence.JsonObject(Map.of("status", new Evidence.JsonString("unknown")))
                    : NativeItemFacts.componentChanges(stack, registries, MAX_COMPONENT_BYTES));
        }
        return fields;
    }

    private static Field offersField() {
        try {
            var field = AbstractVillager.class.getDeclaredField("offers");
            return field.getType() == MerchantOffers.class && field.trySetAccessible() ? field : null;
        } catch (NoSuchFieldException | SecurityException exception) {
            return null;
        }
    }

    private static Evidence.JsonNumber number(long value) {
        return new Evidence.JsonNumber(BigDecimal.valueOf(value));
    }
}
