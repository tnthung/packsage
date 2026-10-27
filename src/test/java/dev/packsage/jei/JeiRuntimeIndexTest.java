package dev.packsage.jei;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JeiRuntimeIndexTest {
    @Test
    void hiddenStableRecipesResolveForReadsCardsAndBookmarksButNotNavigation() {
        var fake = new FakeJeiRuntime();
        var inHiddenCategory = new Object();
        var hiddenInVisibleCategory = new Object();
        fake.category("mod:hidden_machine", true, inHiddenCategory);
        fake.category("mod:machine", false, new Object(), hiddenInVisibleCategory);
        fake.registryNames.put(inHiddenCategory, ResourceLocation.parse("mod:secret"));
        fake.registryNames.put(hiddenInVisibleCategory, ResourceLocation.parse("mod:tucked"));
        fake.hiddenRecipes.add(hiddenInVisibleCategory);
        var index = fake.index();

        var hiddenCategory = index.byRecipeId("mod:hidden_machine", "mod:secret");
        assertEquals(Optional.of(new JeiIndex.RecipeRef("mod:hidden_machine", 0)), hiddenCategory);
        assertSame(inHiddenCategory, index.resolve(hiddenCategory.get()).orElseThrow().recipe());
        assertTrue(index.resolveVisible(hiddenCategory.get()).isEmpty());

        var hiddenRecipe = index.byRecipeId("mod:machine", "mod:tucked");
        assertEquals(Optional.of(new JeiIndex.RecipeRef("mod:machine", 1)), hiddenRecipe);
        assertSame(hiddenInVisibleCategory, index.resolve(hiddenRecipe.get()).orElseThrow().recipe());
        assertTrue(index.resolveVisible(hiddenRecipe.get()).isEmpty());
    }

    private static Recipe<?> nativeRecipe() {
        return (Recipe<?>) Proxy.newProxyInstance(Recipe.class.getClassLoader(), new Class<?>[] { Recipe.class },
                (self, method, args) -> switch (method.getName()) {
                    case "equals" -> self == args[0];
                    case "hashCode" -> System.identityHashCode(self);
                    case "toString" -> "recipe";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static RecipeHolder<?> holder(String id, Recipe<?> recipe) {
        return new RecipeHolder<>(ResourceLocation.parse(id), recipe);
    }

    private static final String ALTAR = "naturesaura:altar";
    private static final String GOLD_POWDER = "naturesaura:altar/gold_powder";

    @Test
    void rawNativeRecipeTakesItsHolderIdByExactObjectIdentity() {
        var recipe = nativeRecipe();
        var fake = new FakeJeiRuntime();
        fake.category(ALTAR, false, new Object(), recipe);
        var index = fake.index(() -> List.of(holder("naturesaura:altar/other", nativeRecipe()), holder(GOLD_POWDER, recipe)));

        var ref = new JeiIndex.RecipeRef(ALTAR, 1);
        assertEquals(Optional.of(GOLD_POWDER), index.recipeId(ref));
        assertEquals(Optional.of(ref), index.byRecipeId(ALTAR, GOLD_POWDER));
        assertEquals(Optional.empty(), index.recipeId(new JeiIndex.RecipeRef(ALTAR, 0)));
    }

    @Test
    void recreatedRuntimeFindsTheSameHolderIdOnANewObject() {
        for (int run = 0; run < 2; run++) {
            var recipe = nativeRecipe();
            var fake = new FakeJeiRuntime();
            fake.category(ALTAR, false, recipe);
            var index = fake.index(() -> List.of(holder(GOLD_POWDER, recipe)));
            assertEquals(Optional.of(new JeiIndex.RecipeRef(ALTAR, 0)), index.byRecipeId(ALTAR, GOLD_POWDER), "run " + run);
        }
    }

    @Test
    void explicitJeiIdTakesPrecedenceOverTheNativeHolder() {
        var recipe = nativeRecipe();
        var fake = new FakeJeiRuntime();
        fake.category(ALTAR, false, recipe);
        fake.registryNames.put(recipe, ResourceLocation.parse("mod:explicit"));
        var index = fake.index(() -> List.of(holder(GOLD_POWDER, recipe)));

        assertEquals(Optional.of("mod:explicit"), index.recipeId(new JeiIndex.RecipeRef(ALTAR, 0)));
        assertEquals(Optional.empty(), index.byRecipeId(ALTAR, GOLD_POWDER));
    }

    @Test
    void ambiguousMissingOrUnavailableNativeIdentitySuppliesNoId() {
        var recipe = nativeRecipe();
        var sameContent = nativeRecipe();
        var cases = new java.util.LinkedHashMap<String, java.util.function.Supplier<java.util.Collection<RecipeHolder<?>>>>();
        cases.put("aliases", () -> List.of(holder(GOLD_POWDER, recipe), holder("naturesaura:altar/alias", recipe)));
        cases.put("missing", () -> List.of(holder("naturesaura:altar/other", nativeRecipe())));
        cases.put("different object", () -> List.of(holder(GOLD_POWDER, sameContent)));
        cases.put("no native data", () -> null);
        cases.put("native failure", () -> { throw new IllegalStateException("no level"); });
        for (var entry : cases.entrySet()) {
            var fake = new FakeJeiRuntime();
            fake.category(ALTAR, false, recipe);
            var index = fake.index(entry.getValue());
            assertEquals(Optional.empty(), index.recipeId(new JeiIndex.RecipeRef(ALTAR, 0)), entry.getKey());
            assertEquals(Optional.empty(), index.byRecipeId(ALTAR, GOLD_POWDER), entry.getKey());
        }
    }

    @Test
    void nativeEnumerationIsBoundedAndAnUnfinishedScanSuppliesNoId() {
        var recipe = nativeRecipe();
        var filler = nativeRecipe();
        for (int extra : new int[] { 0, 1 }) {
            var holders = new ArrayList<RecipeHolder<?>>();
            holders.add(holder(GOLD_POWDER, recipe));
            for (int i = 1; i < JeiRuntimeIndex.NATIVE_RECIPE_LIMIT + extra; i++) holders.add(holder("filler:r" + i, filler));
            var fake = new FakeJeiRuntime();
            fake.category(ALTAR, false, recipe);
            var index = fake.index(() -> holders);
            assertEquals(extra == 0 ? Optional.of(GOLD_POWDER) : Optional.empty(), index.recipeId(new JeiIndex.RecipeRef(ALTAR, 0)),
                    holders.size() + " holders");
        }
    }
}
