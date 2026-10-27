package dev.packsage.knowledge.nativegame;

import dev.packsage.knowledge.Evidence;
import dev.packsage.knowledge.RecipePlanner;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.NonNullList;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.core.RegistryAccess;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeRecipeReaderTest {
    @Test void progressDetailNamesTheRangePhaseAndEmptyContinuation() {
        assertEquals("scanned=0-512 of 1300 (ordinary recipes 1200, brewing candidates 100); phase=ordinary; matches=0; "
                + "no matches in this range; continue with the cursor to scan the rest",
                NativeRecipeReader.progressDetail(0, 512, 1200, 100, 0, true));
        assertEquals("scanned=1200-1300 of 1300 (ordinary recipes 1200, brewing candidates 100); phase=brewing; matches=3",
                NativeRecipeReader.progressDetail(1200, 1300, 1200, 100, 3, false));
    }

    private static final Evidence.Scope SCOPE = new Evidence.Scope("world", "en_us", "team", "native", 1);
    private static RegistryAccess registries;
    private static final Registry<Item> ITEMS = BuiltInRegistries.ITEM;

    @BeforeAll
    static void bootstrapMinecraft() {
        Bootstrap.bootStrap();
        registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
    }

    @Test
    void planReachesLateOutputWithoutCopyingUnrelatedRecipes() {
        var recipes = new ArrayList<RecipeHolder<?>>();
        for (int index = 0; index < 600; index++) {
            recipes.add(recipe("test:unrelated_" + index, new AtomicReference<>(Items.STONE),
                    new AtomicInteger(), true));
        }
        recipes.add(new RecipeHolder<>(net.minecraft.resources.ResourceLocation.parse("test:late_beacon"),
                new net.minecraft.world.item.crafting.ShapelessRecipe("", net.minecraft.world.item.crafting.CraftingBookCategory.MISC,
                        new ItemStack(Items.BEACON), NonNullList.of(Ingredient.EMPTY, Ingredient.of(Items.NETHER_STAR)))));

        var result = NativeProvider.planRecipe(NativeRecipeReader.planSource(recipes,
                new net.minecraft.world.item.alchemy.PotionBrewing.Builder(net.minecraft.world.flag.FeatureFlags.DEFAULT_FLAGS).build(),
                registries, ITEMS, null, SCOPE), "minecraft:beacon", 2, SCOPE, 4);

        assertEquals(1, result.records().size());
        assertEquals("plan|minecraft:beacon|2|4|0|test:late_beacon", result.records().getFirst().source().sourceHandle());
        assertEquals(new Evidence.JsonNumber(java.math.BigDecimal.valueOf(2)), result.records().getFirst().payload().values().get("batches"));
        assertTrue(result.detail().contains("minecraft:nether_star"));
    }

    @Test
    void planVisitsLateIntermediateRecipesAndRebuildsTheSameRouteHandles() {
        var recipes = new ArrayList<RecipeHolder<?>>();
        recipes.add(new RecipeHolder<>(net.minecraft.resources.ResourceLocation.parse("test:table"),
                new net.minecraft.world.item.crafting.ShapelessRecipe("", net.minecraft.world.item.crafting.CraftingBookCategory.MISC,
                        new ItemStack(Items.CRAFTING_TABLE), NonNullList.of(Ingredient.EMPTY, Ingredient.of(Items.OAK_PLANKS)))));
        for (int index = 0; index < 600; index++) {
            recipes.add(recipe("test:unrelated_" + index, new AtomicReference<>(Items.STONE), new AtomicInteger(), true));
        }
        recipes.add(new RecipeHolder<>(net.minecraft.resources.ResourceLocation.parse("test:planks"),
                new net.minecraft.world.item.crafting.ShapelessRecipe("", net.minecraft.world.item.crafting.CraftingBookCategory.MISC,
                        new ItemStack(Items.OAK_PLANKS, 4), NonNullList.of(Ingredient.EMPTY, Ingredient.of(Items.OAK_LOG)))));
        recipes.add(new RecipeHolder<>(net.minecraft.resources.ResourceLocation.parse("test:log"),
                new net.minecraft.world.item.crafting.ShapelessRecipe("", net.minecraft.world.item.crafting.CraftingBookCategory.MISC,
                        new ItemStack(Items.OAK_LOG), NonNullList.create())));
        var brewing = new net.minecraft.world.item.alchemy.PotionBrewing.Builder(net.minecraft.world.flag.FeatureFlags.DEFAULT_FLAGS).build();

        var source = NativeRecipeReader.planSource(recipes, brewing, registries, ITEMS, null, SCOPE);
        // Supply known consumption for this fixture; native extraction deliberately leaves it unknown.
        var knownInputs = new RecipePlanner.Source() {
            @Override public boolean available(Evidence.Scope scope) { return source.available(scope); }
            @Override public List<Evidence.Record> recipeOutputs(String identity, Evidence.JsonObject components, Evidence.Scope scope) {
                return source.recipeOutputs(identity, components, scope).stream().map(record -> {
                    var payload = record.recipePayload();
                    return new Evidence.Record(record.source(), record.kind(), record.title(), record.payload(), record.targets(),
                            new Evidence.RecipePayload(payload.canonicalRecipeId(), payload.inputs().stream().map(slot -> new Evidence.RecipeSlot(slot.groupId(),
                                    slot.alternatives().stream().map(input -> new Evidence.RecipeAlternative(input.type(), input.comparisonIdentity(),
                                            input.components(), input.quantity(), Evidence.Consumption.CONSUMED)).toList())).toList(),
                                    payload.outputs(), payload.remainders(), payload.catalysts(), payload.constraints(), payload.decodingStatus()));
                }).toList();
            }
        };
        var result = new RecipePlanner(knownInputs).plan("minecraft:crafting_table", 5, SCOPE, 4);
        assertEquals(List.of("test:table", "test:planks", "test:log"),
                result.records().stream().map(record -> record.source().sourceHandle()).toList());
        var planned = NativeProvider.planRecipe(NativeRecipeReader.planSource(recipes, brewing, registries, ITEMS, null, SCOPE),
                "minecraft:crafting_table", 5, SCOPE, 4);
        var reread = NativeProvider.planRecipe(NativeRecipeReader.planSource(recipes, brewing, registries, ITEMS, null, SCOPE),
                "minecraft:crafting_table", 5, SCOPE, 4);

        assertEquals("plan|minecraft:crafting_table|5|4|0|test:table", planned.records().getFirst().source().sourceHandle());
        for (int route = 0; route < planned.records().size(); route++) {
            assertEquals(planned.records().get(route).source().sourceHandle(), reread.records().get(route).source().sourceHandle());
            assertEquals(planned.records().get(route).payload(), reread.records().get(route).payload());
        }
    }

    @Test
    void nativePistonTagPlanAggregatesThreeSlotsBeforeExpandingOakPlanks() {
        var planks = net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.ITEM,
                net.minecraft.resources.ResourceLocation.parse("minecraft:planks"));
        var oakLogs = net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.ITEM,
                net.minecraft.resources.ResourceLocation.parse("minecraft:oak_logs"));
        var itemRegistry = (net.minecraft.core.MappedRegistry<Item>) ITEMS;
        itemRegistry.bindTags(Map.of(planks, List.<net.minecraft.core.Holder<Item>>of(Items.OAK_PLANKS.builtInRegistryHolder(),
                        Items.SPRUCE_PLANKS.builtInRegistryHolder()),
                oakLogs, List.<net.minecraft.core.Holder<Item>>of(Items.OAK_LOG.builtInRegistryHolder(), Items.BIRCH_LOG.builtInRegistryHolder())));
        assertEquals(2, ITEMS.getTag(planks).orElseThrow().size());
        assertEquals(2, ITEMS.getTag(oakLogs).orElseThrow().size());
        var pattern = net.minecraft.world.item.crafting.ShapedRecipePattern.of(Map.of(
                'P', Ingredient.of(planks), 'I', Ingredient.of(Items.IRON_INGOT), 'R', Ingredient.of(Items.REDSTONE)), "PPP", " I ", " R ");
        var piston = new net.minecraft.world.item.crafting.ShapedRecipe("", net.minecraft.world.item.crafting.CraftingBookCategory.REDSTONE,
                pattern, new ItemStack(Items.PISTON));
        var oakPlanks = new net.minecraft.world.item.crafting.ShapelessRecipe("", net.minecraft.world.item.crafting.CraftingBookCategory.BUILDING,
                new ItemStack(Items.OAK_PLANKS, 4), NonNullList.of(Ingredient.EMPTY, Ingredient.of(oakLogs)));
        var recipes = List.<RecipeHolder<?>>of(new RecipeHolder<>(net.minecraft.resources.ResourceLocation.parse("test:piston"), piston),
                new RecipeHolder<>(net.minecraft.resources.ResourceLocation.parse("test:oak_planks"), oakPlanks),
                new RecipeHolder<>(net.minecraft.resources.ResourceLocation.parse("test:oak_log"), new net.minecraft.world.item.crafting.ShapelessRecipe("",
                        net.minecraft.world.item.crafting.CraftingBookCategory.BUILDING, new ItemStack(Items.OAK_LOG), NonNullList.create())));
        var brewing = new net.minecraft.world.item.alchemy.PotionBrewing.Builder(net.minecraft.world.flag.FeatureFlags.DEFAULT_FLAGS).build();

        try {
            var result = NativeProvider.planRecipe(NativeRecipeReader.planSource(recipes, brewing, registries, ITEMS, null, SCOPE),
                    "minecraft:piston", 10, SCOPE, 2);

            var planksRoute = result.records().stream().filter(record -> record.source().sourceHandle().endsWith("test:oak_planks"))
                    .findFirst().orElseThrow();
            assertEquals(new Evidence.JsonNumber(java.math.BigDecimal.valueOf(30)), planksRoute.payload().values().get("requestedQuantity"));
            assertEquals(new Evidence.JsonNumber(java.math.BigDecimal.valueOf(8)), planksRoute.payload().values().get("batches"));
            assertEquals(new Evidence.JsonNumber(java.math.BigDecimal.valueOf(32)), planksRoute.payload().values().get("producedQuantity"));
            assertEquals(1, result.records().stream().filter(record -> record.source().sourceHandle().endsWith("test:oak_planks")).count());
            var logsRoute = result.records().stream().filter(record -> record.source().sourceHandle().endsWith("test:oak_log"))
                    .findFirst().orElseThrow();
            assertEquals(new Evidence.JsonNumber(java.math.BigDecimal.valueOf(8)), logsRoute.payload().values().get("requestedQuantity"));
        } finally {
            itemRegistry.resetTags();
        }
    }

    @Test
    void planReportsTagMemberCapForLargeNativeIngredientTag() {
        var largeTag = net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.ITEM,
                net.minecraft.resources.ResourceLocation.parse("minecraft:flowers"));
        var recipe = new net.minecraft.world.item.crafting.ShapelessRecipe("", net.minecraft.world.item.crafting.CraftingBookCategory.MISC,
                new ItemStack(Items.DIAMOND), NonNullList.of(Ingredient.EMPTY, Ingredient.of(largeTag)));
        var brewing = new net.minecraft.world.item.alchemy.PotionBrewing.Builder(net.minecraft.world.flag.FeatureFlags.DEFAULT_FLAGS).build();

        var itemRegistry = (net.minecraft.core.MappedRegistry<Item>) ITEMS;
        itemRegistry.bindTags(Map.of(largeTag, List.of(Items.DANDELION, Items.POPPY, Items.BLUE_ORCHID, Items.ALLIUM,
                Items.AZURE_BLUET, Items.RED_TULIP, Items.ORANGE_TULIP, Items.WHITE_TULIP, Items.PINK_TULIP,
                Items.OXEYE_DAISY, Items.CORNFLOWER, Items.LILY_OF_THE_VALLEY, Items.WITHER_ROSE, Items.TORCHFLOWER,
                Items.SUNFLOWER, Items.LILAC, Items.ROSE_BUSH, Items.PEONY).stream()
                .map(item -> (net.minecraft.core.Holder<Item>) item.builtInRegistryHolder()).toList()));
        assertTrue(ITEMS.getTag(largeTag).orElseThrow().size() > 16);
        try {
            var result = NativeProvider.planRecipe(NativeRecipeReader.planSource(List.of(
                    new RecipeHolder<>(net.minecraft.resources.ResourceLocation.parse("test:tagged_diamond"), recipe)), brewing,
                    registries, ITEMS, null, SCOPE), "minecraft:diamond", 1, SCOPE, 1);

            assertTrue(result.detail().contains("native-recipe-tag-member-cap=#minecraft:flowers:16"));
            var alternatives = result.records().getFirst().recipePayload().inputs().getFirst().alternatives();
            assertEquals(16, alternatives.size());
            assertEquals(alternatives.stream().map(Evidence.RecipeAlternative::comparisonIdentity).toList(),
                    alternatives.stream().map(Evidence.RecipeAlternative::comparisonIdentity)
                            .sorted(java.util.Comparator.comparingInt(identity -> ITEMS.getId(ITEMS.get(
                                    net.minecraft.resources.ResourceLocation.parse(identity))))).toList());
        } finally {
            itemRegistry.resetTags();
        }
    }

    @Test
    void moddedRecipeTagExpansionPreservesUnknownConsumption() {
        var planks = net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.ITEM,
                net.minecraft.resources.ResourceLocation.parse("minecraft:planks"));
        var itemRegistry = (net.minecraft.core.MappedRegistry<Item>) ITEMS;
        itemRegistry.bindTags(Map.of(planks, List.<net.minecraft.core.Holder<Item>>of(Items.OAK_PLANKS.builtInRegistryHolder(),
                Items.SPRUCE_PLANKS.builtInRegistryHolder())));
        var modded = new net.minecraft.world.item.crafting.ShapelessRecipe("", net.minecraft.world.item.crafting.CraftingBookCategory.MISC,
                new ItemStack(Items.DIAMOND), NonNullList.of(Ingredient.EMPTY, Ingredient.of(planks))) {
        };
        var brewing = new net.minecraft.world.item.alchemy.PotionBrewing.Builder(net.minecraft.world.flag.FeatureFlags.DEFAULT_FLAGS).build();
        try {
            var result = NativeProvider.planRecipe(NativeRecipeReader.planSource(List.of(
                    new RecipeHolder<>(net.minecraft.resources.ResourceLocation.parse("test:modded_tagged"), modded)), brewing,
                    registries, ITEMS, null, SCOPE), "minecraft:diamond", 1, SCOPE, 1);
            var alternatives = result.records().getFirst().recipePayload().inputs().getFirst().alternatives();
            assertEquals(2, alternatives.size());
            assertTrue(alternatives.stream().allMatch(alternative -> alternative.consumption() == Evidence.Consumption.UNKNOWN));
        } finally {
            itemRegistry.resetTags();
        }
    }

    @Test
    void sharedTagCacheKeepsConsumptionSpecificToEachRecipeClass() {
        var planks = net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.ITEM,
                net.minecraft.resources.ResourceLocation.parse("minecraft:planks"));
        var itemRegistry = (net.minecraft.core.MappedRegistry<Item>) ITEMS;
        itemRegistry.bindTags(Map.of(planks, List.<net.minecraft.core.Holder<Item>>of(Items.OAK_PLANKS.builtInRegistryHolder(),
                Items.SPRUCE_PLANKS.builtInRegistryHolder())));
        var vanilla = new net.minecraft.world.item.crafting.ShapelessRecipe("", net.minecraft.world.item.crafting.CraftingBookCategory.MISC,
                new ItemStack(Items.DIAMOND), NonNullList.of(Ingredient.EMPTY, Ingredient.of(planks)));
        var modded = new net.minecraft.world.item.crafting.ShapelessRecipe("", net.minecraft.world.item.crafting.CraftingBookCategory.MISC,
                new ItemStack(Items.GOLD_INGOT), NonNullList.of(Ingredient.EMPTY, Ingredient.of(planks))) {
        };
        var brewing = new net.minecraft.world.item.alchemy.PotionBrewing.Builder(net.minecraft.world.flag.FeatureFlags.DEFAULT_FLAGS).build();
        var source = NativeRecipeReader.planSource(List.of(
                new RecipeHolder<>(net.minecraft.resources.ResourceLocation.parse("test:vanilla_tagged"), vanilla),
                new RecipeHolder<>(net.minecraft.resources.ResourceLocation.parse("test:modded_tagged"), modded)), brewing,
                registries, ITEMS, null, SCOPE);
        try {
            var vanillaPlan = NativeProvider.planRecipe(source, "minecraft:diamond", 1, SCOPE, 1);
            var moddedPlan = NativeProvider.planRecipe(source, "minecraft:gold_ingot", 1, SCOPE, 1);
            assertTrue(vanillaPlan.records().getFirst().recipePayload().inputs().getFirst().alternatives().stream()
                    .allMatch(alternative -> alternative.consumption() == Evidence.Consumption.CONSUMED));
            assertTrue(moddedPlan.records().getFirst().recipePayload().inputs().getFirst().alternatives().stream()
                    .allMatch(alternative -> alternative.consumption() == Evidence.Consumption.UNKNOWN));
        } finally {
            itemRegistry.resetTags();
        }
    }

    @Test
    void planIncludesLateBrewingOutputsAndTheirStableRecipeIdentity() {
        var brewing = new net.minecraft.world.item.alchemy.PotionBrewing.Builder(net.minecraft.world.flag.FeatureFlags.DEFAULT_FLAGS);
        for (int index = 0; index < 600; index++) {
            brewing.addRecipe(new net.neoforged.neoforge.common.brewing.BrewingRecipe(Ingredient.of(Items.POTION),
                    Ingredient.of(Items.REDSTONE), new ItemStack(Items.STONE)));
        }
        brewing.addRecipe(new net.neoforged.neoforge.common.brewing.BrewingRecipe(Ingredient.of(Items.POTION),
                Ingredient.of(Items.EMERALD), new ItemStack(Items.DIAMOND, 3)));
        var built = brewing.build();

        var result = NativeProvider.planRecipe(NativeRecipeReader.planSource(List.of(), built, registries, ITEMS, null, SCOPE),
                "minecraft:diamond", 3, SCOPE, 4);

        assertEquals(1, result.records().size());
        var handle = result.records().getFirst().source().sourceHandle();
        assertTrue(handle.startsWith("plan|minecraft:diamond|3|4|0|brewing:custom:600:0:"));
        var recipe = NativeBrewingReader.readCandidate(built, handle.split("\\|", 6)[5], registries, ITEMS, SCOPE);
        assertEquals(result.records().getFirst().recipePayload(), recipe.recipePayload());
    }

    @Test
    void planCachesCopiedRecipesAcrossComponentLookups() {
        var reads = new AtomicInteger();
        var source = NativeRecipeReader.planSource(List.of(recipe("test:cached", new AtomicReference<>(Items.DIAMOND), reads, false)),
                new net.minecraft.world.item.alchemy.PotionBrewing.Builder(net.minecraft.world.flag.FeatureFlags.DEFAULT_FLAGS).build(),
                registries, ITEMS, null, SCOPE);
        assertEquals(1, reads.get());
        assertEquals(1, source.recipeOutputs("minecraft:diamond", new Evidence.JsonObject(Map.of()), SCOPE).size());
        assertTrue(source.recipeOutputs("minecraft:diamond", new Evidence.JsonObject(Map.of("different", new Evidence.JsonString("component"))), SCOPE).isEmpty());
        assertEquals(1, source.recipeOutputs("minecraft:diamond", new Evidence.JsonObject(Map.of()), SCOPE).size());
        assertEquals(2, reads.get());
    }

    @Test
    void copiedRecipeCapReportsPartialOnlyWhenAnotherCopyIsNeeded() {
        var recipes = new ArrayList<RecipeHolder<?>>();
        for (int index = 0; index < 512; index++) {
            recipes.add(recipe("test:target_" + index, new AtomicReference<>(Items.DIAMOND), new AtomicInteger(), false));
        }
        var brewing = new net.minecraft.world.item.alchemy.PotionBrewing.Builder(net.minecraft.world.flag.FeatureFlags.DEFAULT_FLAGS).build();
        var exact = NativeProvider.planRecipe(NativeRecipeReader.planSource(recipes, brewing, registries, ITEMS, null, SCOPE),
                "minecraft:diamond", 1, SCOPE, 0);
        assertTrue(exact.detail().contains("bounded-depth:minecraft:diamond"));
        recipes.add(recipe("test:over_copy_cap", new AtomicReference<>(Items.DIAMOND), new AtomicInteger(), true));

        var capped = NativeProvider.planRecipe(NativeRecipeReader.planSource(recipes, brewing, registries, ITEMS, null, SCOPE),
                "minecraft:diamond", 1, SCOPE, 0);

        assertEquals(Evidence.Coverage.PARTIAL, capped.coverage());
        assertTrue(capped.detail().contains("native-recipe-copy-cap=512"));
        assertEquals(64, capped.records().size());
    }

    @Test
    void specialRecipesWithComputedOutputsQualifyOnlyARequestedItemWithoutIndexedRecipes() {
        var recipes = new ArrayList<RecipeHolder<?>>();
        recipes.add(recipe("test:special", new AtomicReference<>(Items.AIR), new AtomicInteger(), true));
        recipes.add(new RecipeHolder<>(net.minecraft.resources.ResourceLocation.parse("test:beacon"),
                new net.minecraft.world.item.crafting.ShapelessRecipe("", net.minecraft.world.item.crafting.CraftingBookCategory.MISC,
                        new ItemStack(Items.BEACON), NonNullList.of(Ingredient.EMPTY, Ingredient.of(Items.NETHER_STAR)))));
        var brewing = new net.minecraft.world.item.alchemy.PotionBrewing.Builder(net.minecraft.world.flag.FeatureFlags.DEFAULT_FLAGS).build();

        var beacon = NativeProvider.planRecipe(NativeRecipeReader.planSource(recipes, brewing, registries, ITEMS, null, SCOPE),
                "minecraft:beacon", 1, SCOPE, 4);
        var rocket = NativeProvider.planRecipe(NativeRecipeReader.planSource(recipes, brewing, registries, ITEMS, null, SCOPE),
                "minecraft:firework_rocket", 1, SCOPE, 4);

        assertEquals(1, beacon.records().size());
        assertTrue(beacon.detail().contains("minecraft:nether_star"));
        assertTrue(rocket.records().isEmpty());
        assertTrue(rocket.detail().contains("native-recipe-dynamic-outputs-unindexed=1"));
        assertTrue(beacon.detail().contains("could still make them=[minecraft:nether_star]"), beacon.detail());
        assertFalse(beacon.detail().contains("obtained rather than crafted"), beacon.detail());
    }

    @Test
    void mapExtensionAndTrimsAreNeitherIndexedByTheirIllustrationNorASourceOfTheirItem() {
        var recipes = new ArrayList<RecipeHolder<?>>();
        recipes.add(new RecipeHolder<>(net.minecraft.resources.ResourceLocation.parse("minecraft:map_extending"),
                new net.minecraft.world.item.crafting.MapExtendingRecipe(net.minecraft.world.item.crafting.CraftingBookCategory.MISC)));
        recipes.add(new RecipeHolder<>(net.minecraft.resources.ResourceLocation.parse("test:star_trim"),
                new net.minecraft.world.item.crafting.SmithingTrimRecipe(Ingredient.of(Items.SENTRY_ARMOR_TRIM_SMITHING_TEMPLATE),
                        Ingredient.of(Items.NETHER_STAR), Ingredient.of(Items.QUARTZ))));
        var brewing = new net.minecraft.world.item.alchemy.PotionBrewing.Builder(net.minecraft.world.flag.FeatureFlags.DEFAULT_FLAGS).build();
        var source = NativeRecipeReader.planSource(recipes, brewing, registries, ITEMS, null, SCOPE);
        var none = new Evidence.JsonObject(Map.of());

        assertTrue(source.recipeOutputs("minecraft:map", none, SCOPE).isEmpty());
        // Both need the item itself as an input, so neither is how it is first obtained.
        assertTrue(source.recipesComplete("minecraft:filled_map", none));
        assertTrue(source.recipesComplete("minecraft:nether_star", none));
        assertTrue(source.recipesComplete("minecraft:obsidian", none));
    }

    @Test
    void aKnownVanillaSpecialRecipeOnlyQualifiesTheItemsItCanMake() {
        var recipes = new ArrayList<RecipeHolder<?>>();
        recipes.add(new RecipeHolder<>(net.minecraft.resources.ResourceLocation.parse("minecraft:repair_item"),
                new net.minecraft.world.item.crafting.RepairItemRecipe(net.minecraft.world.item.crafting.CraftingBookCategory.MISC)));
        recipes.add(new RecipeHolder<>(net.minecraft.resources.ResourceLocation.parse("minecraft:suspicious_stew"),
                new net.minecraft.world.item.crafting.SuspiciousStewRecipe(net.minecraft.world.item.crafting.CraftingBookCategory.MISC)));
        recipes.add(new RecipeHolder<>(net.minecraft.resources.ResourceLocation.parse("minecraft:shulker_box_coloring"),
                new net.minecraft.world.item.crafting.ShulkerBoxColoring(net.minecraft.world.item.crafting.CraftingBookCategory.MISC)));
        recipes.add(new RecipeHolder<>(net.minecraft.resources.ResourceLocation.parse("test:beacon"),
                new net.minecraft.world.item.crafting.ShapelessRecipe("", net.minecraft.world.item.crafting.CraftingBookCategory.MISC,
                        new ItemStack(Items.BEACON), NonNullList.of(Ingredient.EMPTY, Ingredient.of(Items.NETHER_STAR)))));
        var brewing = new net.minecraft.world.item.alchemy.PotionBrewing.Builder(net.minecraft.world.flag.FeatureFlags.DEFAULT_FLAGS).build();
        var source = NativeRecipeReader.planSource(recipes, brewing, registries, ITEMS, null, SCOPE);

        var none = new Evidence.JsonObject(Map.of());
        assertTrue(source.recipesComplete("minecraft:nether_star", none));
        assertFalse(source.recipesComplete("minecraft:suspicious_stew", none));
        assertFalse(source.recipesComplete("minecraft:red_shulker_box", none));
        // Repair turns two of an item into one, so it never supplies an item the player lacks.
        assertTrue(source.recipesComplete("minecraft:elytra", none));
        assertTrue(source.recipesComplete("minecraft:saddle", new Evidence.JsonObject(Map.of("minecraft:max_damage", new Evidence.JsonNumber(java.math.BigDecimal.TEN)))));
        var beacon = NativeProvider.planRecipe(source, "minecraft:beacon", 1, SCOPE, 4);
        assertTrue(beacon.detail().contains("obtained rather than crafted=[minecraft:nether_star]"), beacon.detail());
    }

    @Test
    void recipeIndexCapReportsTheOmittedLateTarget() {
        var recipes = new java.util.AbstractList<RecipeHolder<?>>() {
            @Override public RecipeHolder<?> get(int index) {
                return recipe("test:entry_" + index, new AtomicReference<>(index == 65536 ? Items.DIAMOND : Items.STONE),
                        new AtomicInteger(), true);
            }
            @Override public int size() { return 65537; }
        };

        var result = NativeProvider.planRecipe(NativeRecipeReader.planSource(recipes,
                new net.minecraft.world.item.alchemy.PotionBrewing.Builder(net.minecraft.world.flag.FeatureFlags.DEFAULT_FLAGS).build(),
                registries, ITEMS, null, SCOPE), "minecraft:diamond", 1, SCOPE, 0);

        assertTrue(result.records().isEmpty());
        assertEquals(Evidence.Coverage.PARTIAL, result.coverage());
        assertTrue(result.detail().contains("native-recipe-index-cap=65536"));
    }

    @Test
    void exactOutputSearchReachesLateMatchWithoutProjectingNonmatchesAndKeepsTextAndFilter() {
        var lateResultReads = new AtomicInteger();
        var recipes = new ArrayList<RecipeHolder<?>>();
        for (int index = 0; index < 600; index++) {
            recipes.add(recipe("test:unrelated_" + index, new AtomicReference<>(Items.STONE),
                    new AtomicInteger(), true));
        }
        recipes.add(recipe("test:late_target", new AtomicReference<>(Items.DIAMOND), lateResultReads, false));

        var page = NativeRecipeReader.searchCandidates(recipes, registries, ITEMS, null,
                "minecraft:diamond", "late_target", 0, 4, SCOPE);

        assertEquals(1, page.records().size());
        assertEquals("test:late_target", page.records().getFirst().title());
        assertEquals(601, page.scanned());
        assertTrue(page.exhausted());
        assertEquals(1, lateResultReads.get());

        var textMiss = NativeRecipeReader.searchCandidates(recipes, registries, ITEMS, null,
                "minecraft:diamond", "not present", 0, 4, SCOPE);
        assertTrue(textMiss.records().isEmpty());
        assertEquals(2, lateResultReads.get());
    }

    @Test
    void exactOutputSearchContinuesAtBoundAndReadsChangedRecipeOnNextPage() {
        var recipes = new ArrayList<RecipeHolder<?>>();
        for (int index = 0; index < 4096; index++) {
            recipes.add(recipe("test:unrelated_" + index, new AtomicReference<>(Items.STONE),
                    new AtomicInteger(), false));
        }
        var currentOutput = new AtomicReference<>(Items.STONE);
        var resultReads = new AtomicInteger();
        recipes.add(recipe("test:live_target", currentOutput, resultReads, false));

        var first = NativeRecipeReader.searchCandidates(recipes, registries, ITEMS, null,
                "minecraft:diamond", null, 0, 4, SCOPE);

        assertTrue(first.records().isEmpty());
        assertEquals(4096, first.scanned());
        assertEquals(4096, first.nextOffset());
        assertFalse(first.exhausted());
        assertEquals(0, resultReads.get());

        currentOutput.set(Items.DIAMOND);
        var second = NativeRecipeReader.searchCandidates(recipes, registries, ITEMS, null,
                "minecraft:diamond", null, first.nextOffset(), 4, SCOPE);

        assertEquals(1, second.records().size());
        assertEquals("test:live_target", second.records().getFirst().title());
        assertEquals(1, second.scanned());
        assertTrue(second.exhausted());
        assertEquals(1, resultReads.get());
    }

    @Test
    void smithingTransformDecodesThreeConsumedInputsAndPlansTheirRequirements() {
        var recipe = new net.minecraft.world.item.crafting.SmithingTransformRecipe(
                Ingredient.of(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE), Ingredient.of(Items.DIAMOND_SWORD),
                Ingredient.of(Items.NETHERITE_INGOT), new ItemStack(Items.NETHERITE_SWORD));
        var holder = new RecipeHolder<>(net.minecraft.resources.ResourceLocation.parse("test:netherite_sword"), recipe);
        var record = NativeSnapshot.copyRecipe(holder, registries, ITEMS, null, SCOPE, null);

        assertEquals(List.of("minecraft:netherite_upgrade_smithing_template", "minecraft:diamond_sword", "minecraft:netherite_ingot"),
                record.recipePayload().inputs().stream().map(slot -> slot.alternatives().getFirst().comparisonIdentity()).toList());
        assertTrue(record.recipePayload().inputs().stream().allMatch(slot -> slot.alternatives().getFirst().quantity() == 1
                && slot.alternatives().getFirst().consumption() == Evidence.Consumption.CONSUMED));
        assertTrue(record.recipePayload().constraints().contains("output components=unknown: the result keeps the base item's components"));
        assertEquals(Evidence.DecodingStatus.PARTIAL, record.recipePayload().decodingStatus());
        assertEquals(new Evidence.JsonObject(Map.of()), record.recipePayload().outputs().getFirst().components());
        var result = NativeProvider.planRecipe(NativeRecipeReader.planSource(List.of(holder),
                new net.minecraft.world.item.alchemy.PotionBrewing.Builder(net.minecraft.world.flag.FeatureFlags.DEFAULT_FLAGS).build(),
                registries, ITEMS, null, SCOPE), "minecraft:netherite_sword", 2, SCOPE, 4);

        assertEquals(Evidence.Coverage.PARTIAL, result.coverage());
        assertTrue(result.detail().contains("minecraft:netherite_upgrade_smithing_template"));
        assertTrue(result.detail().contains("minecraft:diamond_sword"));
        assertTrue(result.detail().contains("minecraft:netherite_ingot"));
        assertEquals(record.recipePayload().inputs(), result.records().getFirst().recipePayload().inputs());
        assertEquals(new Evidence.JsonNumber(java.math.BigDecimal.valueOf(2)), result.records().getFirst().payload().values().get("batches"));
    }

    @Test
    void smithingTrimDecodesInputsButKeepsItsDynamicOutputPartial() {
        var recipe = new net.minecraft.world.item.crafting.SmithingTrimRecipe(Ingredient.of(Items.SENTRY_ARMOR_TRIM_SMITHING_TEMPLATE),
                Ingredient.of(Items.DIAMOND_CHESTPLATE), Ingredient.of(Items.QUARTZ));
        var record = NativeSnapshot.copyRecipe(new RecipeHolder<>(net.minecraft.resources.ResourceLocation.parse("test:trim"), recipe),
                registries, ITEMS, null, SCOPE, null);

        assertEquals(3, record.recipePayload().inputs().size());
        assertTrue(record.recipePayload().inputs().stream().allMatch(slot -> slot.alternatives().getFirst().quantity() == 1
                && slot.alternatives().getFirst().consumption() == Evidence.Consumption.CONSUMED));
        assertEquals(Evidence.DecodingStatus.PARTIAL, record.recipePayload().decodingStatus());
        assertEquals(Evidence.Coverage.PARTIAL, record.source().coverage());
        assertEquals("unknown", record.recipePayload().outputs().getFirst().type());
        assertTrue(record.recipePayload().constraints().stream().anyMatch(value -> value.contains("dynamic") && value.contains("trim")));
    }

    @Test
    void smeltingRecordCarriesCookingTimeAndExperience() {
        var smelting = new net.minecraft.world.item.crafting.SmeltingRecipe("", net.minecraft.world.item.crafting.CookingBookCategory.MISC,
                Ingredient.of(Items.SAND), new ItemStack(Items.GLASS), 0.1f, 200);
        var pattern = net.minecraft.world.item.crafting.ShapedRecipePattern.of(Map.of('P', Ingredient.of(Items.OAK_PLANKS)), "PP", "PP");
        var shaped = new net.minecraft.world.item.crafting.ShapedRecipe("", net.minecraft.world.item.crafting.CraftingBookCategory.MISC,
                pattern, new ItemStack(Items.CRAFTING_TABLE));
        var page = NativeRecipeReader.searchCandidates(List.of(
                new RecipeHolder<>(net.minecraft.resources.ResourceLocation.parse("test:glass"), smelting),
                new RecipeHolder<>(net.minecraft.resources.ResourceLocation.parse("test:table"), shaped)),
                registries, ITEMS, null, null, null, 0, 10, SCOPE);

        var cookingPayload = page.records().getFirst().payload().values();
        assertEquals(new Evidence.JsonNumber(java.math.BigDecimal.valueOf(200)), cookingPayload.get("cookingTime"));
        assertEquals("0.1", ((Evidence.JsonNumber) cookingPayload.get("experience")).value().toPlainString());
        var craftingPayload = page.records().get(1).payload().values();
        assertFalse(craftingPayload.containsKey("cookingTime"));
        assertFalse(craftingPayload.containsKey("experience"));
    }

    @Test
    void smeltingConsumesMilkBucketWithoutCraftingRemainder() {
        var recipe = new net.minecraft.world.item.crafting.SmeltingRecipe("", net.minecraft.world.item.crafting.CookingBookCategory.MISC,
                Ingredient.of(Items.MILK_BUCKET), new ItemStack(Items.STONE), 0, 200);
        var record = NativeSnapshot.copyRecipe(new RecipeHolder<>(net.minecraft.resources.ResourceLocation.parse("test:smelt_milk"), recipe),
                registries, ITEMS, null, SCOPE, null);

        assertTrue(record.recipePayload().remainders().isEmpty());
        assertEquals(Evidence.Consumption.CONSUMED, record.recipePayload().inputs().getFirst().alternatives().getFirst().consumption());
        assertEquals(Evidence.DecodingStatus.VERIFIED, record.recipePayload().decodingStatus());
        assertEquals(Evidence.Coverage.COMPLETE, record.source().coverage());
    }

    @Test
    void failedComponentEncodingKeepsDamagedOutputPartial() {
        var output = new ItemStack(Items.DIAMOND_SWORD);
        output.setDamageValue(7);
        var recipe = new net.minecraft.world.item.crafting.ShapelessRecipe("", net.minecraft.world.item.crafting.CraftingBookCategory.MISC,
                output, NonNullList.of(Ingredient.EMPTY, Ingredient.of(Items.DIAMOND)));
        var failingRegistries = (net.minecraft.core.HolderLookup.Provider) Proxy.newProxyInstance(
                net.minecraft.core.HolderLookup.Provider.class.getClassLoader(), new Class<?>[] {net.minecraft.core.HolderLookup.Provider.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("createSerializationContext")) throw new IllegalStateException("serialization context unavailable");
                    return method.invoke(registries, arguments);
                });
        var record = NativeSnapshot.copyRecipe(new RecipeHolder<>(net.minecraft.resources.ResourceLocation.parse("test:damaged"), recipe),
                failingRegistries, ITEMS, null, SCOPE, null);

        assertEquals(Evidence.DecodingStatus.PARTIAL, record.recipePayload().decodingStatus());
        assertEquals(Evidence.Coverage.PARTIAL, record.source().coverage());
        assertTrue(record.recipePayload().constraints().stream().anyMatch(value -> value.contains("components") && value.contains("encoding")));
    }

    @Test
    void successfulEmptyComponentEncodingRemainsComplete() {
        var recipe = new net.minecraft.world.item.crafting.ShapelessRecipe("", net.minecraft.world.item.crafting.CraftingBookCategory.MISC,
                new ItemStack(Items.STICK), NonNullList.of(Ingredient.EMPTY, Ingredient.of(Items.OAK_PLANKS)));
        var record = NativeSnapshot.copyRecipe(new RecipeHolder<>(net.minecraft.resources.ResourceLocation.parse("test:empty_components"), recipe),
                registries, ITEMS, null, SCOPE, null);

        assertEquals(new Evidence.JsonObject(Map.of()), record.recipePayload().outputs().getFirst().components());
        assertEquals(Evidence.DecodingStatus.VERIFIED, record.recipePayload().decodingStatus());
        assertEquals(Evidence.Coverage.COMPLETE, record.source().coverage());
    }

    @Test
    void shapedRecipeReportsGridShapeConstraintsAndPayload() {
        var pattern = net.minecraft.world.item.crafting.ShapedRecipePattern.of(java.util.Map.of(
                'P', Ingredient.of(Items.OAK_PLANKS), 'C', Ingredient.of(Items.COBBLESTONE),
                'I', Ingredient.of(Items.IRON_INGOT), 'R', Ingredient.of(Items.REDSTONE)), "PPP", "CIC", "CRC");
        var shaped = new net.minecraft.world.item.crafting.ShapedRecipe("", net.minecraft.world.item.crafting.CraftingBookCategory.MISC,
                pattern, new ItemStack(Items.PISTON));
        var record = NativeSnapshot.copyRecipe(new RecipeHolder<>(net.minecraft.resources.ResourceLocation.parse("test:piston"), shaped),
                registries, ITEMS, null, SCOPE, null);
        var constraints = record.recipePayload().constraints();
        assertTrue(constraints.contains("shape=3x3"));
        assertTrue(constraints.contains("slot-index=row*width+column"));
        var shape = (Evidence.JsonObject) record.payload().values().get("shape");
        assertEquals(new Evidence.JsonNumber(java.math.BigDecimal.valueOf(3)), shape.values().get("width"));
        assertEquals(new Evidence.JsonNumber(java.math.BigDecimal.valueOf(3)), shape.values().get("height"));
        assertEquals(1, record.recipePayload().inputs().getFirst().alternatives().getFirst().quantity());
        assertEquals(Evidence.Consumption.CONSUMED, record.recipePayload().inputs().getFirst().alternatives().getFirst().consumption());
    }

    @Test
    void customRecipeSubclassHasUnknownConsumption() {
        var shapeless = new net.minecraft.world.item.crafting.ShapelessRecipe("", net.minecraft.world.item.crafting.CraftingBookCategory.MISC,
                new ItemStack(Items.STICK), NonNullList.of(Ingredient.EMPTY, Ingredient.of(Items.OAK_PLANKS))) {
        };
        var record = NativeSnapshot.copyRecipe(new RecipeHolder<>(net.minecraft.resources.ResourceLocation.parse("test:modded"), shapeless),
                registries, ITEMS, null, SCOPE, null);
        assertEquals(Evidence.DecodingStatus.UNKNOWN, record.recipePayload().decodingStatus());
        assertEquals(Evidence.Consumption.UNKNOWN, record.recipePayload().inputs().getFirst().alternatives().getFirst().consumption());
    }

    @Test
    void shapedSubclassWithEmptyOutputStaysUnknownAndHasNoCard() {
        var pattern = net.minecraft.world.item.crafting.ShapedRecipePattern.of(Map.of('P', Ingredient.of(Items.OAK_PLANKS)), "P");
        var shaped = new net.minecraft.world.item.crafting.ShapedRecipe("", net.minecraft.world.item.crafting.CraftingBookCategory.MISC,
                pattern, new ItemStack(Items.STICK)) {
            @Override public ItemStack getResultItem(net.minecraft.core.HolderLookup.Provider registries) { return ItemStack.EMPTY; }
        };
        var record = NativeSnapshot.copyRecipe(new RecipeHolder<>(net.minecraft.resources.ResourceLocation.parse("test:modded_empty"), shaped),
                registries, ITEMS, null, SCOPE, null);
        assertTrue(dev.packsage.client.recipe.RecipeCardModels.fromRecord(dev.packsage.model.PackSageTools.encodeRecord(record)).isEmpty());
        assertEquals(Evidence.DecodingStatus.UNKNOWN, record.recipePayload().decodingStatus());
    }

    @Test
    void vanillaRecipeSlotQuantityIsOneEvenWhenItemValueStackHasMore() {
        var shapeless = new net.minecraft.world.item.crafting.ShapelessRecipe("", net.minecraft.world.item.crafting.CraftingBookCategory.MISC,
                new ItemStack(Items.STICK), NonNullList.of(Ingredient.EMPTY, Ingredient.of(new ItemStack(Items.OAK_PLANKS, 4))));
        var record = NativeSnapshot.copyRecipe(new RecipeHolder<>(net.minecraft.resources.ResourceLocation.parse("test:stacked"), shapeless),
                registries, ITEMS, null, SCOPE, null);
        assertEquals(1, record.recipePayload().inputs().getFirst().alternatives().getFirst().quantity());
    }

    @Test
    void vanillaCraftingRecipeReportsBucketRemainder() {
        var shapeless = new net.minecraft.world.item.crafting.ShapelessRecipe("", net.minecraft.world.item.crafting.CraftingBookCategory.MISC,
                new ItemStack(Items.CAKE), NonNullList.of(Ingredient.EMPTY, Ingredient.of(Items.MILK_BUCKET)));
        var record = NativeSnapshot.copyRecipe(new RecipeHolder<>(net.minecraft.resources.ResourceLocation.parse("test:milk"), shapeless),
                registries, ITEMS, null, SCOPE, null);
        assertEquals("minecraft:bucket", record.recipePayload().remainders().getFirst().comparisonIdentity());
    }

    @Test
    void nativeRemainderPreservesStackSensitiveCount() throws ReflectiveOperationException {
        var unsafeClass = Class.forName("sun.misc.Unsafe");
        var unsafeField = unsafeClass.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        var input = (Item) unsafeClass.getMethod("allocateInstance", Class.class).invoke(unsafeField.get(null), CountedRemainderItem.class);
        for (var field : Item.class.getDeclaredFields()) {
            if (!java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                field.setAccessible(true);
                field.set(input, field.get(Items.MILK_BUCKET));
            }
        }
        var countField = CountedRemainderItem.class.getDeclaredField("remainderCount");
        countField.setAccessible(true);
        countField.setInt(input, 2);
        var recipe = new net.minecraft.world.item.crafting.ShapelessRecipe("", net.minecraft.world.item.crafting.CraftingBookCategory.MISC,
                new ItemStack(Items.CAKE), NonNullList.of(Ingredient.EMPTY, Ingredient.of(input)));

        var record = NativeSnapshot.copyRecipe(new RecipeHolder<>(net.minecraft.resources.ResourceLocation.parse("test:counted_remainder"), recipe),
                registries, ITEMS, null, SCOPE, null);

        assertEquals(2, record.recipePayload().remainders().getFirst().quantity());
    }

    @Test
    void differingAlternativeRemainderCountsRemainAmbiguous() throws ReflectiveOperationException {
        var unsafeClass = Class.forName("sun.misc.Unsafe");
        var unsafeField = unsafeClass.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        var allocateInstance = unsafeClass.getMethod("allocateInstance", Class.class);
        var twoBuckets = (Item) allocateInstance.invoke(unsafeField.get(null), CountedRemainderItem.class);
        var oneBucket = (Item) allocateInstance.invoke(unsafeField.get(null), CountedRemainderItem.class);
        for (var field : Item.class.getDeclaredFields()) {
            if (!java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                field.setAccessible(true);
                field.set(twoBuckets, field.get(Items.MILK_BUCKET));
                field.set(oneBucket, field.get(Items.MILK_BUCKET));
            }
        }
        var countField = CountedRemainderItem.class.getDeclaredField("remainderCount");
        countField.setAccessible(true);
        countField.setInt(twoBuckets, 2);
        countField.setInt(oneBucket, 1);
        var recipe = new net.minecraft.world.item.crafting.ShapelessRecipe("", net.minecraft.world.item.crafting.CraftingBookCategory.MISC,
                new ItemStack(Items.CAKE), NonNullList.of(Ingredient.EMPTY, Ingredient.of(twoBuckets, oneBucket)));

        var record = NativeSnapshot.copyRecipe(new RecipeHolder<>(net.minecraft.resources.ResourceLocation.parse("test:ambiguous_remainder"), recipe),
                registries, ITEMS, null, SCOPE, null);

        assertEquals(Evidence.DecodingStatus.PARTIAL, record.recipePayload().decodingStatus());
        assertTrue(record.recipePayload().constraints().contains("remainders=ambiguous-by-ingredient-alternative"));
    }

    private static final class CountedRemainderItem extends Item {
        private int remainderCount;

        private CountedRemainderItem() {
            super(new Item.Properties());
        }

        @Override public boolean hasCraftingRemainingItem(ItemStack stack) { return true; }

        @Override public ItemStack getCraftingRemainingItem(ItemStack stack) { return new ItemStack(Items.BUCKET, remainderCount); }
    }

    @Test
    void shapedRecipeWithEmptyCellsNumbersSlotsByRowTimesWidthPlusColumn() {
        var pattern = net.minecraft.world.item.crafting.ShapedRecipePattern.of(java.util.Map.of(
                'P', Ingredient.of(Items.OAK_PLANKS), 'I', Ingredient.of(Items.IRON_INGOT)), " P ", "PIP", " P ");
        var shaped = new net.minecraft.world.item.crafting.ShapedRecipe("", net.minecraft.world.item.crafting.CraftingBookCategory.MISC,
                pattern, new ItemStack(Items.PISTON));
        var record = NativeSnapshot.copyRecipe(new RecipeHolder<>(net.minecraft.resources.ResourceLocation.parse("test:plus"), shaped),
                registries, ITEMS, null, SCOPE, null);
        assertEquals(List.of("slot-1", "slot-3", "slot-4", "slot-5", "slot-7"),
                record.recipePayload().inputs().stream().map(Evidence.RecipeSlot::groupId).toList());
        assertTrue(record.recipePayload().constraints().contains("shape=3x3"));
    }

    @Test
    void throwingShapedRecipeKeepsRecordWithoutShape() {
        var pattern = net.minecraft.world.item.crafting.ShapedRecipePattern.of(java.util.Map.of(
                'P', Ingredient.of(Items.OAK_PLANKS)), "PP", "PP");
        var shaped = new net.minecraft.world.item.crafting.ShapedRecipe("", net.minecraft.world.item.crafting.CraftingBookCategory.MISC,
                pattern, new ItemStack(Items.CRAFTING_TABLE)) {
            @Override public int getWidth() {
                throw new IllegalStateException("broken");
            }
        };
        var record = NativeSnapshot.copyRecipe(new RecipeHolder<>(net.minecraft.resources.ResourceLocation.parse("test:broken"), shaped),
                registries, ITEMS, null, SCOPE, null);
        assertEquals(4, record.recipePayload().inputs().size());
        assertTrue(record.recipePayload().constraints().stream().noneMatch(c -> c.startsWith("shape=") || c.startsWith("slot-index=")));
        assertFalse(record.payload().values().containsKey("shape"));
    }

    @Test
    void shapelessRecipeReportsShapelessConstraintAndPayload() {
        var shapeless = new net.minecraft.world.item.crafting.ShapelessRecipe("", net.minecraft.world.item.crafting.CraftingBookCategory.MISC,
                new ItemStack(Items.STICK), NonNullList.of(Ingredient.EMPTY, Ingredient.of(Items.OAK_PLANKS)));
        var record = NativeSnapshot.copyRecipe(new RecipeHolder<>(net.minecraft.resources.ResourceLocation.parse("test:stick"), shapeless),
                registries, ITEMS, null, SCOPE, null);
        assertTrue(record.recipePayload().constraints().contains("shape=shapeless"));
        assertEquals(new Evidence.JsonString("shapeless"), record.payload().values().get("shape"));
    }

    private static RecipeHolder<?> recipe(String id, AtomicReference<Item> output, AtomicInteger resultReads,
                                          boolean failIngredients) {
        var value = (Recipe<?>) Proxy.newProxyInstance(Recipe.class.getClassLoader(), new Class<?>[] {Recipe.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getResultItem" -> {
                        resultReads.incrementAndGet();
                        yield new ItemStack(output.get());
                    }
                    case "getIngredients" -> {
                        if (failIngredients) throw new AssertionError("ingredient projection ran for output mismatch");
                        yield NonNullList.of(Ingredient.EMPTY, Ingredient.of(Items.STONE));
                    }
                    case "getType" -> BuiltInRegistries.RECIPE_TYPE.iterator().next();
                    case "getSerializer" -> BuiltInRegistries.RECIPE_SERIALIZER.iterator().next();
                    case "toString" -> id;
                    default -> throw new AssertionError("unexpected recipe method: " + method.getName());
                });
        return new RecipeHolder<>(net.minecraft.resources.ResourceLocation.parse(id), value);
    }
}
