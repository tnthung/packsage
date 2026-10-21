package dev.packsage.knowledge.nativegame;

import dev.packsage.knowledge.Evidence;
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
