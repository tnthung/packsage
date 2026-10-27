package dev.packsage.jei;

import dev.packsage.client.recipe.RecipeCardModel;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JeiRecipeCardSourceTest {
    private static RecipeCardModel model(RecipeCardModel.Category category, String recipeId, String viewerHandle) {
        return new RecipeCardModel(category, 0, 0, List.of(), "", List.of(), 0, recipeId, viewerHandle);
    }

    private static final JeiIndex INDEX = new JeiSearchTest.Fake() {
        @Override public Optional<RecipeRef> byRecipeId(String category, String id) {
            return category.equals("create:mixing") && id.equals("create:mixing/brass") ? Optional.of(new RecipeRef(category, 3))
                    : category.equals("minecraft:crafting") && id.equals("minecraft:beacon") ? Optional.of(new RecipeRef(category, 5))
                    : category.equals("minecraft:campfire_cooking") && id.equals("minecraft:cooked_beef_from_campfire_cooking")
                    ? Optional.of(new RecipeRef(category, 1)) : Optional.empty();
        }
    };

    @Test void viewerHandlesResolveByRecipeIdOrCurrentOrdinal() {
        var viewer = RecipeCardModel.Category.VIEWER;
        assertEquals(Optional.of(new JeiIndex.RecipeRef("create:mixing", 3)), JeiRecipeCardSource.ref(INDEX, model(viewer, null, "create:mixing|create:mixing/brass")));
        assertEquals(Optional.of(new JeiIndex.RecipeRef("mod:altar", 2)), JeiRecipeCardSource.ref(INDEX, model(viewer, null, "mod:altar|#2@7~" + JeiRuntimeHolder.session())));
        assertTrue(JeiRecipeCardSource.ref(INDEX, model(viewer, null, "mod:altar|#2@6~" + JeiRuntimeHolder.session())).isEmpty());
        assertTrue(JeiRecipeCardSource.ref(INDEX, model(viewer, null, "create:mixing|create:mixing/missing")).isEmpty());
        assertTrue(JeiRecipeCardSource.ref(INDEX, model(viewer, null, "malformed")).isEmpty());
    }

    @Test void nativeModelsResolveThroughTheirVanillaCategory() {
        assertEquals(Optional.of(new JeiIndex.RecipeRef("minecraft:crafting", 5)),
                JeiRecipeCardSource.ref(INDEX, model(RecipeCardModel.Category.CRAFTING_SHAPELESS, "minecraft:beacon", null)));
        assertEquals(Optional.of(new JeiIndex.RecipeRef("minecraft:campfire_cooking", 1)),
                JeiRecipeCardSource.ref(INDEX, model(RecipeCardModel.Category.CAMPFIRE, "minecraft:cooked_beef_from_campfire_cooking", null)));
        assertTrue(JeiRecipeCardSource.ref(INDEX, model(RecipeCardModel.Category.SMELTING, "minecraft:beacon", null)).isEmpty());
        assertTrue(JeiRecipeCardSource.ref(INDEX, model(RecipeCardModel.Category.BREWING, null, null)).isEmpty());
    }

    @Test void wideCardsScaleDownToThreeQuartersThenFallBack() {
        assertEquals(1.0, JeiRecipeCardSource.scale(120, 200));
        assertEquals(0.8, JeiRecipeCardSource.scale(200, 160), 1e-9);
        assertEquals(0.75, JeiRecipeCardSource.scale(200, 150), 1e-9);
        assertEquals(0, JeiRecipeCardSource.scale(200, 149));
    }

    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return type.cast(java.lang.reflect.Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, handler));
    }

    @Test void cardsFromAnotherRuntimeGenerationStopDrawing() {
        // A null drawable proves a stale card never touches its layout.
        var card = new JeiRecipeCardSource.JeiCard(new JeiRecipeCardSource.Layout(null, 4, 4, 108, 58), "Mixing", 1.0, JeiRuntimeHolder.generation());
        assertTrue(card.current());
        JeiRuntimeHolder.unavailable();
        assertFalse(card.current());
        card.draw(null, 0, 0, 10, 10);
        assertTrue(card.itemAt(0, 0, 10, 10).isEmpty());
        assertTrue(card.hoverAt(0, 0, 10, 10).isEmpty());
        assertEquals(108, card.width());
    }

    @Test void unexpectedFailuresAreReportedOncePerCategory() {
        var runtime = proxy(mezz.jei.api.runtime.IJeiRuntime.class, (self, method, args) -> { throw new IllegalStateException("broken"); });
        try {
            JeiRuntimeHolder.available(runtime);
            var source = new JeiRecipeCardSource(new java.util.HashSet<>());
            assertTrue(source.card(model(RecipeCardModel.Category.VIEWER, null, "create:mixing|create:mixing/brass")).isEmpty());
            assertTrue(source.card(model(RecipeCardModel.Category.VIEWER, null, "create:mixing|create:mixing/bronze")).isEmpty());
            assertTrue(source.card(model(RecipeCardModel.Category.SMELTING, "minecraft:glass", null)).isEmpty());
            assertEquals(java.util.Set.of("create:mixing", "minecraft:smelting"), source.reportedCategories());
            for (int i = 0; i < 100; i++) source.card(model(RecipeCardModel.Category.VIEWER, null, "mod:c" + i + "|mod:r"));
            assertEquals(JeiRecipeCardSource.REPORT_LIMIT, source.reportedCategories().size());
        } finally {
            JeiRuntimeHolder.unavailable();
        }
    }

    @Test void absentRuntimeIsSilent() {
        JeiRuntimeHolder.unavailable();
        var source = new JeiRecipeCardSource(new java.util.HashSet<>());
        assertTrue(source.card(model(RecipeCardModel.Category.VIEWER, null, "create:mixing|create:mixing/brass")).isEmpty());
        assertTrue(source.reportedCategories().isEmpty());
    }

    @Test void cacheIsBoundedAndKeyedByGeneration() {
        var cache = new JeiRecipeCardSource.Cache<String>();
        for (int i = 0; i < 65; i++) cache.put(new JeiRecipeCardSource.Key("h" + i, 1), "v" + i);
        assertEquals(64, cache.size());
        assertFalse(cache.containsKey(new JeiRecipeCardSource.Key("h0", 1)));
        assertFalse(cache.containsKey(new JeiRecipeCardSource.Key("h64", 2)));
        assertTrue(cache.containsKey(new JeiRecipeCardSource.Key("h64", 1)));
    }
}
