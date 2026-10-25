package dev.packsage.world.minecraft;

import dev.packsage.knowledge.Evidence;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class MinecraftTradesTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
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

    @Test
    void readingAnUngeneratedVillagerLeavesOffersNull() throws Exception {
        var unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        var villager = (Villager) ((sun.misc.Unsafe) unsafeField.get(null)).allocateInstance(Villager.class);

        assertNull(MinecraftTrades.storedOffers(villager));
        assertNull(MinecraftTrades.storedOffers(villager));
        assertNull(MinecraftTrades.OFFERS.get(villager));
    }

    @Test
    void offerFactsReportCostsUsesAndPriceAdjustments() {
        var facts = MinecraftTrades.offer(offer(), 0, null).values();
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

    private static Evidence.JsonNumber number(long value) {
        return new Evidence.JsonNumber(BigDecimal.valueOf(value));
    }
}
