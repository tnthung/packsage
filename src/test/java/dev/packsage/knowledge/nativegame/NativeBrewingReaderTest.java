package dev.packsage.knowledge.nativegame;

import dev.packsage.knowledge.Evidence;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionBrewing;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.crafting.Ingredient;
import net.neoforged.neoforge.common.brewing.BrewingRecipe;
import net.neoforged.neoforge.common.brewing.IBrewingRecipe;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeBrewingReaderTest {
    private static final Evidence.Scope SCOPE = new Evidence.Scope("world", "en_us", "team", "native", 1);
    private static RegistryAccess registries;
    private static Registry<Item> items;

    @BeforeAll
    static void bootstrapMinecraft() {
        Bootstrap.bootStrap();
        registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        items = registries.registryOrThrow(Registries.ITEM);
    }

    @Test
    void potionMixesKeepDistinctPotionComponentsAndOutputFilterSelectsItemType() {
        var brewing = new PotionBrewing.Builder(FeatureFlags.DEFAULT_FLAGS);
        PotionBrewing.addVanillaMixes(brewing);

        var page = NativeBrewingReader.searchCandidates(brewing.build(), registries, items, null,
                "minecraft:potion", "long_swiftness", 0, 16, SCOPE);

        assertEquals(1, page.records().size());
        var record = page.records().getFirst();
        assertEquals("minecraft:potion", record.recipePayload().outputs().getFirst().comparisonIdentity());
        assertTrue(Evidence.serialize(record.recipePayload().outputs().getFirst().components()).contains("long_swiftness"));
        assertTrue(Evidence.serialize(record.recipePayload().inputs().getFirst().alternatives().getFirst().components())
                .contains("swiftness"));
        assertTrue(page.exhausted());
    }

    @Test
    void containerConversionPreservesPotionComponentsAcrossItemChange() {
        var brewing = new PotionBrewing.Builder(FeatureFlags.DEFAULT_FLAGS);
        brewing.addContainer(Items.POTION);
        brewing.addContainerRecipe(Items.POTION, Items.GUNPOWDER, Items.SPLASH_POTION);

        var page = NativeBrewingReader.searchCandidates(brewing.build(), registries, items, null,
                "minecraft:splash_potion", "strong_healing", 0, 8, SCOPE);

        assertEquals(1, page.records().size());
        var record = page.records().getFirst();
        assertEquals("minecraft:splash_potion", record.recipePayload().outputs().getFirst().comparisonIdentity());
        assertTrue(Evidence.serialize(record.recipePayload().outputs().getFirst().components()).contains("strong_healing"));
        assertTrue(Evidence.serialize(record.recipePayload().inputs().getFirst().alternatives().getFirst().components())
                .contains("strong_healing"));
    }

    @Test
    void exactDeclarativeRecipePreservesOutputComponentsAndCount() {
        var brewing = new PotionBrewing.Builder(FeatureFlags.DEFAULT_FLAGS);
        var output = new ItemStack(Items.DIAMOND, 3);
        output.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
                net.minecraft.network.chat.Component.literal("brewed diamond"));
        brewing.addRecipe(new BrewingRecipe(Ingredient.of(Items.POTION), Ingredient.of(Items.EMERALD), output));

        var page = NativeBrewingReader.searchCandidates(brewing.build(), registries, items, null,
                "minecraft:diamond", "brewed diamond", 0, 8, SCOPE);

        assertEquals(1, page.records().size());
        var record = page.records().getFirst();
        assertEquals(3, record.recipePayload().outputs().getFirst().quantity());
        assertTrue(Evidence.serialize(record.recipePayload().outputs().getFirst().components()).contains("brewed diamond"));
        assertEquals(Evidence.DecodingStatus.PARTIAL, record.recipePayload().decodingStatus());
    }

    @Test
    void opaqueRecipeIsReportedWithoutCallingExecutableMethods() {
        var calls = new AtomicInteger();
        var brewing = new PotionBrewing.Builder(FeatureFlags.DEFAULT_FLAGS);
        brewing.addRecipe(new IBrewingRecipe() {
            @Override
            public boolean isInput(ItemStack input) {
                calls.incrementAndGet();
                throw new AssertionError("opaque recipe input predicate must not run");
            }

            @Override
            public boolean isIngredient(ItemStack ingredient) {
                calls.incrementAndGet();
                throw new AssertionError("opaque recipe ingredient predicate must not run");
            }

            @Override
            public ItemStack getOutput(ItemStack input, ItemStack ingredient) {
                calls.incrementAndGet();
                throw new AssertionError("opaque recipe output method must not run");
            }
        });

        var page = NativeBrewingReader.searchCandidates(brewing.build(), registries, items, null,
                null, null, 0, 8, SCOPE);

        assertEquals(1, page.records().size());
        assertEquals(Evidence.DecodingStatus.OPAQUE, page.records().getFirst().recipePayload().decodingStatus());
        assertEquals(0, calls.get());
    }

    @Test
    void continuationReadsTheNextRuleWithoutReplayingTheFirstPage() {
        var firstBrewing = new PotionBrewing.Builder(FeatureFlags.DEFAULT_FLAGS);
        firstBrewing.addMix(Potions.WATER, Items.REDSTONE, Potions.MUNDANE);
        firstBrewing.addMix(Potions.WATER, Items.GLOWSTONE_DUST, Potions.THICK);
        var brewing = firstBrewing.build();
        var first = NativeBrewingReader.searchCandidates(firstBrewing.build(), registries, items, null,
                "minecraft:potion", null, 0, 1, SCOPE);
        var second = NativeBrewingReader.searchCandidates(brewing, registries, items, null,
                "minecraft:potion", null, first.nextOffset(), 1, SCOPE);

        assertEquals(1, first.records().size());
        assertFalse(first.exhausted());
        assertEquals(1, second.records().size());
        assertEquals(1, second.scanned());
        assertTrue(second.exhausted());
        assertFalse(first.records().getFirst().source().sourceHandle()
                .equals(second.records().getFirst().source().sourceHandle()));
    }

    @Test
    void outputFilteredSearchBoundsBrewingRuleTraversal() {
        var brewing = new PotionBrewing.Builder(FeatureFlags.DEFAULT_FLAGS);
        for (int index = 0; index < 600; index++) {
            brewing.addRecipe(new BrewingRecipe(Ingredient.of(Items.POTION), Ingredient.of(Items.EMERALD),
                    new ItemStack(Items.DIAMOND)));
        }

        var page = NativeBrewingReader.searchCandidates(brewing.build(), registries, items, null,
                "minecraft:nether_star", null, 0, 8, SCOPE);

        assertTrue(page.records().isEmpty());
        assertTrue(page.scanned() > 0);
        assertTrue(page.scanned() < 600);
        assertEquals(page.scanned(), page.nextOffset());
        assertFalse(page.exhausted());
    }
}
