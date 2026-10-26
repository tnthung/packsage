package dev.packsage.world.minecraft;

import dev.packsage.knowledge.Evidence;
import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.network.chat.Component;
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
import java.nio.charset.StandardCharsets;
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
    void sharedItemFactsResolveToTheCorrectOfferItems() throws Exception {
        var villager = bareVillager();
        var enchantments = lookup.lookupOrThrow(Registries.ENCHANTMENT);
        var mending = EnchantedBookItem.createForEnchantment(new EnchantmentInstance(enchantments.getOrThrow(Enchantments.MENDING), 1));
        var sharpness = EnchantedBookItem.createForEnchantment(new EnchantmentInstance(enchantments.getOrThrow(Enchantments.SHARPNESS), 3));
        var healing = PotionContents.createItemStack(Items.POTION, Potions.HEALING);
        var offers = new MerchantOffers();
        offers.add(new MerchantOffer(new ItemCost(Items.EMERALD, 20), Optional.of(new ItemCost(Items.BOOK, 1)), mending, 0, 12, 30, 0.2f, 0));
        offers.add(new MerchantOffer(new ItemCost(Items.EMERALD, 22), Optional.of(new ItemCost(Items.BOOK, 1)), mending.copy(), 0, 12, 30, 0.2f, 0));
        offers.add(new MerchantOffer(new ItemCost(Items.EMERALD, 24), Optional.of(new ItemCost(Items.BOOK, 1)), sharpness, 0, 12, 30, 0.2f, 0));
        offers.add(new MerchantOffer(new ItemCost(Items.EMERALD, 3), healing, 12, 1, 0.05f));
        MinecraftTrades.OFFERS.set(villager, offers);
        var values = new LinkedHashMap<String, Evidence.JsonValue>();

        MinecraftTrades.facts(villager, null, values);

        var entries = ((Evidence.JsonArray) values.get("offers")).values();
        var itemFacts = ((Evidence.JsonArray) values.get("offerItemFacts")).values();
        var first = ((Evidence.JsonObject) entries.get(0)).values();
        var second = ((Evidence.JsonObject) entries.get(1)).values();
        var third = ((Evidence.JsonObject) entries.get(2)).values();
        var fourth = ((Evidence.JsonObject) entries.get(3)).values();
        assertEquals(first.get("itemFactsIndex"), second.get("itemFactsIndex"));
        assertFalse(first.get("itemFactsIndex").equals(third.get("itemFactsIndex")));
        var mendingFacts = ((Evidence.JsonObject) itemFacts.get(((Evidence.JsonNumber) first.get("itemFactsIndex")).value().intValue())).values();
        var sharpnessFacts = ((Evidence.JsonObject) itemFacts.get(((Evidence.JsonNumber) third.get("itemFactsIndex")).value().intValue())).values();
        var healingFacts = ((Evidence.JsonObject) itemFacts.get(((Evidence.JsonNumber) fourth.get("itemFactsIndex")).value().intValue())).values();
        assertEquals(new Evidence.JsonString("minecraft:enchanted_book"), mendingFacts.get("item"));
        assertEquals(number(1), ((Evidence.JsonObject) mendingFacts.get("storedEnchantments")).values().get("minecraft:mending"));
        assertEquals(number(3), ((Evidence.JsonObject) sharpnessFacts.get("storedEnchantments")).values().get("minecraft:sharpness"));
        assertEquals(new Evidence.JsonString("minecraft:potion"), healingFacts.get("item"));
        assertEquals(new Evidence.JsonString("minecraft:healing"), ((Evidence.JsonObject) healingFacts.get("potion")).values().get("potion"));
        assertEquals(number(20), ((Evidence.JsonObject) first.get("costA")).values().get("count"));
        assertEquals(number(22), ((Evidence.JsonObject) second.get("costA")).values().get("count"));
    }

    @Test
    void richTraderOffersFitWithoutTrimmingAnyOffers() throws Exception {
        var villager = bareVillager();
        var enchantment = lookup.lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.MENDING);
        var book = EnchantedBookItem.createForEnchantment(new EnchantmentInstance(enchantment, 1));
        book.set(DataComponents.CUSTOM_NAME, Component.literal("enchanted book " + "x".repeat(3000)));
        var potion = PotionContents.createItemStack(Items.POTION, Potions.HEALING);
        potion.set(DataComponents.CUSTOM_NAME, Component.literal("healing potion " + "y".repeat(3000)));
        var offers = new MerchantOffers();
        for (int index = 0; index < MinecraftTrades.MAX_OFFERS; index++) {
            var result = index % 2 == 0 ? book.copy() : potion.copy();
            offers.add(new MerchantOffer(new ItemCost(Items.EMERALD, index + 1), result, 12, 1, 0.05f));
        }
        MinecraftTrades.OFFERS.set(villager, offers);
        var values = new LinkedHashMap<String, Evidence.JsonValue>();

        MinecraftTrades.facts(villager, null, values);

        var payload = Evidence.serialize(new Evidence.JsonObject(values));
        assertTrue(payload.getBytes(StandardCharsets.UTF_8).length < 65_536, "payload bytes " + payload.length());
        assertEquals(MinecraftTrades.MAX_OFFERS, ((Evidence.JsonArray) values.get("offers")).values().size());
        assertEquals(number(MinecraftTrades.MAX_OFFERS), values.get("offerCount"));
        assertFalse(values.containsKey("offersStatus"));
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
