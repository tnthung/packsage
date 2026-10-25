package dev.packsage.world.minecraft;

import dev.packsage.knowledge.Evidence;
import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.EnchantedBookItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.enchantment.EnchantmentInstance;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MinecraftTradesTest {
    private static HolderLookup.Provider lookup;

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        lookup = VanillaRegistries.createLookup();
    }

    private static MerchantOffer offer() {
        return new MerchantOffer(new ItemCost(Items.EMERALD, 10), Optional.of(new ItemCost(Items.BOOK, 1)),
                new ItemStack(Items.ENCHANTED_BOOK), 3, 12, 2, 0.05f, 4);
    }

    @Test
    void offersFieldResolvesToTheStoredOffers() {
        assertNotNull(MinecraftTrades.OFFERS);
        assertEquals(MerchantOffers.class, MinecraftTrades.OFFERS.getType());
    }

    /** A villager with no constructor run: no level, no trading player and offers never generated. */
    private static Villager bareVillager() throws Exception {
        var unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        return (Villager) ((sun.misc.Unsafe) unsafeField.get(null)).allocateInstance(Villager.class);
    }

    @Test
    void readingAnUngeneratedVillagerLeavesOffersNull() throws Exception {
        var villager = bareVillager();

        assertNull(MinecraftTrades.storedOffers(villager));
        assertNull(MinecraftTrades.storedOffers(villager));
        assertNull(MinecraftTrades.OFFERS.get(villager));
    }

    @Test
    void offerFactsReportCostsUsesAndPriceAdjustments() {
        var facts = MinecraftTrades.offer(offer(), 0, null, lookup).values();
        var costA = ((Evidence.JsonObject) facts.get("costA")).values();
        assertEquals(number(12), costA.get("count"));
        assertEquals(number(10), costA.get("baseCount"));
        assertEquals(new Evidence.JsonString("minecraft:book"), ((Evidence.JsonObject) facts.get("costB")).values().get("item"));
        assertEquals(number(3), facts.get("uses"));
        assertEquals(number(12), facts.get("maxUses"));
        assertEquals(new Evidence.JsonBoolean(false), facts.get("outOfStock"));
        assertEquals(number(4), facts.get("demand"));
        assertEquals(number(2), facts.get("xp"));
        assertEquals(0, new BigDecimal("0.05").compareTo(((Evidence.JsonNumber) facts.get("priceMultiplier")).value()));
        assertNull(facts.get("costForPlayer"));
    }

    @Test
    void costForPlayerAppliesReputationAndHeroDiscountsWithoutMutating() {
        var offer = offer();
        assertEquals(8, MinecraftTrades.costForPlayer(offer, 20, 0));
        assertEquals(12, MinecraftTrades.costForPlayer(offer, 0, -1));
        assertEquals(1, MinecraftTrades.costForPlayer(offer, 1000, 0));
        assertEquals(0, offer.getSpecialPriceDiff());
    }

    @Test
    void offerFactsSayWhichEnchantmentOrPotionIsSold() {
        var mending = lookup.lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.MENDING);
        var book = new MerchantOffer(new ItemCost(Items.EMERALD, 20), Optional.of(new ItemCost(Items.BOOK, 1)),
                EnchantedBookItem.createForEnchantment(new EnchantmentInstance(mending, 1)), 0, 12, 30, 0.2f, 0);
        var result = ((Evidence.JsonObject) MinecraftTrades.offer(book, 0, null, lookup).values().get("result")).values();
        assertEquals(new Evidence.JsonString("minecraft:enchanted_book"), result.get("item"));
        assertEquals(number(1), ((Evidence.JsonObject) result.get("storedEnchantments")).values().get("minecraft:mending"));
        assertTrue(result.get("components") instanceof Evidence.JsonObject components
                && components.values().containsKey("minecraft:stored_enchantments"), result.toString());

        var potion = new MerchantOffer(new ItemCost(Items.EMERALD, 1), PotionContents.createItemStack(Items.POTION, Potions.HEALING), 12, 1, 0.05f);
        var potionResult = ((Evidence.JsonObject) MinecraftTrades.offer(potion, 0, null, lookup).values().get("result")).values();
        assertEquals(new Evidence.JsonString("minecraft:healing"), ((Evidence.JsonObject) potionResult.get("potion")).values().get("potion"));

        var plain = ((Evidence.JsonObject) MinecraftTrades.offer(offer(), 0, null, lookup).values().get("costA")).values();
        assertFalse(plain.containsKey("components"), plain.toString());
    }

    @Test
    void factsSayWhenOffersWereNeverGenerated() throws Exception {
        var villager = bareVillager();
        var values = new LinkedHashMap<String, Evidence.JsonValue>();
        MinecraftTrades.facts(villager, null, values);
        assertEquals(new Evidence.JsonString("not generated yet"), values.get("offers"));
        assertEquals(new Evidence.JsonBoolean(false), values.get("trading"));
        assertNull(MinecraftTrades.OFFERS.get(villager));
    }

    @Test
    void factsBoundTheOfferList() throws Exception {
        var villager = bareVillager();
        var offers = new MerchantOffers();
        for (int index = 0; index <= MinecraftTrades.MAX_OFFERS; index++) offers.add(offer());
        MinecraftTrades.OFFERS.set(villager, offers);
        var values = new LinkedHashMap<String, Evidence.JsonValue>();
        MinecraftTrades.facts(villager, null, values);
        assertEquals(MinecraftTrades.MAX_OFFERS, ((Evidence.JsonArray) values.get("offers")).values().size());
        assertEquals(new Evidence.JsonString("bounded"), values.get("offersStatus"));
        assertTrue(values.get("pricesNote") instanceof Evidence.JsonString note && note.value().contains("demand"), values.toString());
    }

    @Test
    void boundedOffersReportTheTotalCount() throws Exception {
        var villager = bareVillager();
        var offers = new MerchantOffers();
        for (int index = 0; index < MinecraftTrades.MAX_OFFERS + 3; index++) offers.add(offer());
        MinecraftTrades.OFFERS.set(villager, offers);
        var values = new LinkedHashMap<String, Evidence.JsonValue>();
        MinecraftTrades.facts(villager, null, values);
        assertEquals(64, ((Evidence.JsonArray) values.get("offers")).values().size());
        assertEquals(new Evidence.JsonString("bounded"), values.get("offersStatus"));
        assertEquals(number(67), values.get("offerCount"));

        offers.clear();
        offers.add(offer());
        values.clear();
        MinecraftTrades.facts(villager, null, values);
        assertEquals(1, ((Evidence.JsonArray) values.get("offers")).values().size());
        assertEquals(number(1), values.get("offerCount"));
        assertFalse(values.containsKey("offersStatus"));

        offers.clear();
        values.clear();
        MinecraftTrades.facts(villager, null, values);
        assertEquals(number(0), values.get("offerCount"));
        assertFalse(values.containsKey("offersStatus"));
    }

    @Test
    void pricesNoteSaysTradingCostsAlreadyIncludeTheTradersDiscounts() {
        assertTrue(MinecraftTrades.pricesNote(true).contains("the trading player"), MinecraftTrades.pricesNote(true));
        assertFalse(MinecraftTrades.pricesNote(false).contains("the trading player"), MinecraftTrades.pricesNote(false));
    }

    private static Evidence.JsonNumber number(long value) {
        return new Evidence.JsonNumber(BigDecimal.valueOf(value));
    }
}
