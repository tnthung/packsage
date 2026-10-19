package dev.packsage.knowledge.nativegame;

import dev.packsage.knowledge.Evidence;
import dev.packsage.knowledge.KnowledgeIndex;
import dev.packsage.knowledge.RecipePlanner;
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

import java.util.AbstractList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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

        assertTrue(page.records().stream().anyMatch(record -> record.recipePayload().outputs().stream()
                .anyMatch(output -> output.comparisonIdentity().equals("minecraft:potion")
                        && Evidence.serialize(output.components()).contains("long_swiftness"))));
        assertTrue(page.records().stream().allMatch(record -> record.recipePayload().outputs().stream()
                .allMatch(output -> output.comparisonIdentity().equals("minecraft:potion"))));
        assertTrue(page.exhausted());

        var allVariants = NativeBrewingReader.searchCandidates(brewing.build(), registries, items, null,
                "minecraft:potion", null, 0, 256, SCOPE);
        assertTrue(allVariants.records().stream().anyMatch(record -> Evidence.serialize(
                record.recipePayload().outputs().getFirst().components()).contains("long_swiftness")));
        assertTrue(allVariants.records().stream().anyMatch(record -> Evidence.serialize(
                record.recipePayload().outputs().getFirst().components()).contains("swiftness")
                && !Evidence.serialize(record.recipePayload().outputs().getFirst().components()).contains("long_swiftness")));
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
        assertTrue(record.recipePayload().inputs().stream().flatMap(slot -> slot.alternatives().stream())
                .allMatch(input -> input.quantity() == null && input.consumption() == Evidence.Consumption.UNKNOWN));

        var index = new KnowledgeIndex();
        index.replace(new NativeSnapshot(SCOPE, page.records()));
        var plan = new RecipePlanner(index).plan("minecraft:diamond", 3, SCOPE, 4);
        assertEquals(Evidence.Outcome.PARTIAL, plan.outcome());
        assertEquals(1, plan.records().size());
        assertEquals(3, plan.records().getFirst().recipePayload().outputs().getFirst().quantity());
    }

    @Test
    void opaqueRecipeDoesNotMatchAnOutputFilterAndCanBeRereadWithoutCallingExecutableMethods() {
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

        var built = brewing.build();
        var filtered = NativeBrewingReader.searchCandidates(built, registries, items, null,
                "minecraft:diamond", null, 0, 8, SCOPE);

        assertTrue(filtered.records().isEmpty());
        assertTrue(filtered.partial());
        var page = NativeBrewingReader.searchCandidates(built, registries, items, null, null, null, 0, 8, SCOPE);
        assertEquals(1, page.records().size());
        assertEquals(Evidence.DecodingStatus.OPAQUE, page.records().getFirst().recipePayload().decodingStatus());
        assertEquals(Evidence.DecodingStatus.OPAQUE, NativeBrewingReader.readCandidate(built,
                page.records().getFirst().source().sourceHandle(), registries, items, SCOPE).recipePayload().decodingStatus());
        assertEquals(0, calls.get());
    }

    @Test
    void continuationReadsTheNextRuleWithoutReplayingTheFirstPage() {
        var firstBrewing = new PotionBrewing.Builder(FeatureFlags.DEFAULT_FLAGS);
        firstBrewing.addMix(Potions.WATER, Items.REDSTONE, Potions.MUNDANE);
        firstBrewing.addMix(Potions.WATER, Items.GLOWSTONE_DUST, Potions.THICK);
        firstBrewing.addContainer(Items.POTION);
        var brewing = firstBrewing.build();
        var first = NativeBrewingReader.searchCandidates(brewing, registries, items, null,
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
    void rereadingHandleTracksTheSameTransformationAfterReorderAndReturnsNullAfterRemoval() {
        var initialBuilder = new PotionBrewing.Builder(FeatureFlags.DEFAULT_FLAGS);
        initialBuilder.addMix(Potions.WATER, Items.REDSTONE, Potions.MUNDANE);
        initialBuilder.addMix(Potions.WATER, Items.GLOWSTONE_DUST, Potions.THICK);
        initialBuilder.addContainer(Items.POTION);
        var initial = initialBuilder.build();
        var first = NativeBrewingReader.searchCandidates(initial, registries, items, null,
                "minecraft:potion", "redstone", 0, 8, SCOPE).records().getFirst();
        var initialRead = NativeBrewingReader.readCandidate(initial, first.source().sourceHandle(), registries, items, SCOPE);
        assertEquals(first.recipePayload(), initialRead.recipePayload());

        var reorderedBuilder = new PotionBrewing.Builder(FeatureFlags.DEFAULT_FLAGS);
        reorderedBuilder.addMix(Potions.WATER, Items.GLOWSTONE_DUST, Potions.THICK);
        reorderedBuilder.addMix(Potions.WATER, Items.REDSTONE, Potions.MUNDANE);
        reorderedBuilder.addContainer(Items.POTION);
        var reordered = reorderedBuilder.build();
        var reread = NativeBrewingReader.readCandidate(reordered, first.source().sourceHandle(), registries, items, SCOPE);

        assertEquals(first.recipePayload(), reread.recipePayload());
        assertFalse(first.source().sourceHandle().equals(reread.source().sourceHandle()));

        var changedBuilder = new PotionBrewing.Builder(FeatureFlags.DEFAULT_FLAGS);
        changedBuilder.addMix(Potions.WATER, Items.REDSTONE, Potions.THICK);
        changedBuilder.addContainer(Items.POTION);
        assertNull(NativeBrewingReader.readCandidate(changedBuilder.build(), first.source().sourceHandle(), registries, items, SCOPE));

        var removedBuilder = new PotionBrewing.Builder(FeatureFlags.DEFAULT_FLAGS);
        removedBuilder.addMix(Potions.WATER, Items.GLOWSTONE_DUST, Potions.THICK);
        removedBuilder.addContainer(Items.POTION);
        assertNull(NativeBrewingReader.readCandidate(removedBuilder.build(), first.source().sourceHandle(), registries, items, SCOPE));
    }

    @Test
    void outputFilteredSearchBoundsBrewingRuleTraversal() {
        var brewing = new PotionBrewing.Builder(FeatureFlags.DEFAULT_FLAGS);
        for (int index = 0; index < 5000; index++) {
            brewing.addRecipe(new BrewingRecipe(Ingredient.of(Items.POTION), Ingredient.of(Items.EMERALD),
                    new ItemStack(Items.DIAMOND)));
        }

        var page = NativeBrewingReader.searchCandidates(brewing.build(), registries, items, null,
                "minecraft:nether_star", null, 0, 8, SCOPE);

        assertTrue(page.records().isEmpty());
        assertTrue(page.scanned() > 0);
        assertTrue(page.scanned() < 5000);
        assertEquals(page.scanned(), page.nextOffset());
        assertFalse(page.exhausted());
    }

    @Test
    void unchangedLateCustomRuleCanBeRereadByItsLocator() {
        var brewing = new PotionBrewing.Builder(FeatureFlags.DEFAULT_FLAGS);
        for (int index = 0; index < 5000; index++) {
            brewing.addRecipe(new BrewingRecipe(Ingredient.of(Items.POTION), Ingredient.of(Items.EMERALD),
                    new ItemStack(Items.DIAMOND, index % 3 + 1)));
        }
        var built = brewing.build();
        var page = NativeBrewingReader.searchCandidates(built, registries, items, null,
                null, null, 4999, 1, SCOPE);

        assertEquals(1, page.records().size());
        assertTrue(page.records().getFirst().source().sourceHandle().startsWith("brewing:custom:4999:0:"));
        var reread = NativeBrewingReader.readCandidate(built, page.records().getFirst().source().sourceHandle(),
                registries, items, SCOPE);
        assertEquals(page.records().getFirst().recipePayload(), reread.recipePayload());
    }

    @Test
    void pageProjectsOnlyItsCurrentVanillaMixFromTheRawSourceList() throws ReflectiveOperationException {
        var brewing = new PotionBrewing.Builder(FeatureFlags.DEFAULT_FLAGS);
        for (int index = 0; index < 5000; index++) {
            brewing.addContainer(Items.POTION);
            brewing.addContainerRecipe(Items.POTION, Items.GUNPOWDER, Items.SPLASH_POTION);
        }
        var built = brewing.build();
        var field = PotionBrewing.class.getDeclaredField("containerMixes");
        assertTrue(field.trySetAccessible());
        List<?> original = (List<?>) field.get(built);
        var reads = new AtomicInteger();
        List<?> counting = new AbstractList<Object>() {
            @Override
            public Object get(int index) {
                reads.incrementAndGet();
                return original.get(index);
            }

            @Override
            public int size() {
                return original.size();
            }
        };
        field.set(built, counting);

        var page = NativeBrewingReader.searchCandidates(built, registries, items, null,
                "minecraft:splash_potion", null, 0, 1, SCOPE);

        assertEquals(1, page.records().size());
        assertEquals(1, reads.get());
    }

    @Test
    void oversizedKnownOutputKeepsItsItemAndCountWithBoundedComponents() {
        var output = new ItemStack(Items.DIAMOND, 2);
        output.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
                net.minecraft.network.chat.Component.literal("x".repeat(20000)));
        var brewing = new PotionBrewing.Builder(FeatureFlags.DEFAULT_FLAGS);
        brewing.addRecipe(new BrewingRecipe(Ingredient.of(Items.POTION), Ingredient.of(Items.EMERALD), output));

        var page = NativeBrewingReader.searchCandidates(brewing.build(), registries, items, null,
                "minecraft:diamond", null, 0, 1, SCOPE);

        assertEquals(1, page.records().size());
        var record = page.records().getFirst();
        assertEquals("minecraft:diamond", record.recipePayload().outputs().getFirst().comparisonIdentity());
        assertEquals(2, record.recipePayload().outputs().getFirst().quantity());
        assertEquals(new Evidence.JsonString("bounded"), record.recipePayload().outputs().getFirst()
                .components().values().get("encoding"));
        assertTrue(page.partial());
    }
}
