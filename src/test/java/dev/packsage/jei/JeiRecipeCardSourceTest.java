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

    @Test void stripGeometryAndExclusions() {
        var strip = JeiRecipeCardSource.strip("create:mixing", 5, 54, true);
        assertEquals(36, strip.width());
        assertEquals(5, strip.visible());
        assertEquals(0, strip.hidden());
        assertEquals(54, strip.height());
        var shortStrip = JeiRecipeCardSource.strip("mod:altar", 5, 12, true);
        assertEquals(90, shortStrip.width());
        assertEquals(5, shortStrip.visible());
        assertEquals(0, shortStrip.hidden());
        assertEquals(18, shortStrip.height());
        assertEquals(18, JeiRecipeCardSource.strip("mod:altar", 2, 54).width());
        assertEquals(72, JeiRecipeCardSource.strip("mod:altar", 12, 54, true).width());
        assertEquals(12, JeiRecipeCardSource.strip("mod:altar", 12, 54, true).visible());
        assertEquals(0, JeiRecipeCardSource.strip("mod:altar", 12, 54, true).hidden());
        var overflow = JeiRecipeCardSource.strip("mod:altar", 20, 53, true);
        assertEquals(180, overflow.width());
        assertEquals(20, overflow.visible());
        assertEquals(0, overflow.hidden());
        assertEquals(0, JeiRecipeCardSource.strip("minecraft:crafting", 5, 54).width());
        assertEquals(0, JeiRecipeCardSource.strip("mod:empty", 0, 54).width());
    }

    @Test void stripWidthParticipatesInScaleAndFallback() {
        int width = 102 + JeiRecipeCardSource.strip("minecraft:smelting", 5, 54).width();
        assertEquals(1, JeiRecipeCardSource.scale(width, 120));
        assertEquals(0.75, JeiRecipeCardSource.scale(width, 90));
        assertEquals(0, JeiRecipeCardSource.scale(width, 89));
    }

    @Test void stripHitTestingAtFullAndMinimumScale() {
        var strip = JeiRecipeCardSource.strip("mod:altar", 12, 54, true);
        for (double scale : List.of(1.0, 0.75)) {
            assertEquals(0, strip.at(55 * scale, 1 * scale, scale));
            assertEquals(2, strip.at(55 * scale, 37 * scale, scale));
            assertEquals(3, strip.at(37 * scale, 1 * scale, scale));
            assertEquals(6, strip.at(19 * scale, 1 * scale, scale));
            assertEquals(9, strip.at(1 * scale, 1 * scale, scale));
            assertEquals(11, strip.at(1 * scale, 37 * scale, scale));
            assertEquals(-1, strip.at(72 * scale, 0, scale));
            assertEquals(-1, strip.at(0, 54 * scale, scale));
            assertEquals(-1, strip.at(-1, 0, scale));
            assertEquals(-1, JeiRecipeCardSource.strip("mod:altar", 5, 54, true).at(1, 37 * scale, scale));
        }
    }

    @Test void collapsedColumnCountsAllHiddenMachines() {
        var collapsed = JeiRecipeCardSource.strip("mod:altar", 25, 54);
        assertEquals(18, collapsed.width());
        assertEquals(2, collapsed.visible());
        assertEquals(23, collapsed.hidden());
        var shortCard = JeiRecipeCardSource.strip("mod:altar", 5, 12);
        assertEquals(0, shortCard.visible());
        assertEquals(5, shortCard.hidden());
    }

    @Test void expandedPanelGrowsLeftOrOverTheCardAndUsesTheCardScale() {
        var strip = JeiRecipeCardSource.strip("mod:altar", 12, 54, true);
        for (double scale : List.of(1.0, 0.75)) {
            var left = JeiRecipeCardSource.panel(strip, 100, 20, scale, 400);
            assertEquals(100 - 54 * scale, left.left());
            assertEquals(0, left.at(100 + scale, 20 + scale));
            assertEquals(9, left.at(left.left() + scale, 20 + scale));
            assertEquals(11, left.at(left.left() + scale, 20 + 37 * scale));
            var right = JeiRecipeCardSource.panel(strip, 0, 20, scale, 400);
            assertEquals(0, right.left());
            assertEquals(0, right.at(scale, 20 + scale));
            assertEquals(9, right.at(55 * scale, 20 + scale));
            assertEquals(-1, right.at(72 * scale, 20));
        }
    }

    @Test void expandedPanelAddsRowsUntilEveryCatalystFitsOnScreen() {
        var expanded = JeiRecipeCardSource.strip("mod:altar", 25, 54, true);
        assertEquals(9, expanded.columns());
        assertEquals(0, expanded.hidden());
        var panel = JeiRecipeCardSource.panel(expanded, 10, 20, 1, 100);
        assertEquals(5, panel.strip().rows());
        assertEquals(5, panel.strip().columns());
        assertEquals(25, panel.strip().visible());
        assertEquals(0, panel.strip().hidden());
        assertTrue(panel.left() + panel.strip().width() <= 100);
        assertEquals(24, panel.at(99, 109));
        var scaled = JeiRecipeCardSource.panel(expanded, 10, 20, 0.75, 100);
        assertEquals(5, scaled.strip().rows());
        assertTrue(scaled.left() + scaled.strip().width() * 0.75 <= 100);
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

    @Test void fallbackOpeningRefusesHiddenRecipes() throws Exception {
        var fake = new FakeJeiRuntime();
        var recipe = new Object();
        fake.category("mod:hidden_machine", true, recipe);
        fake.registryNames.put(recipe, net.minecraft.resources.ResourceLocation.parse("mod:secret"));
        var runtime = proxy(mezz.jei.api.runtime.IJeiRuntime.class, (self, method, args) -> null);
        try {
            JeiRuntimeHolder.available(runtime);
            var source = new JeiRecipeCardSource(new java.util.HashSet<>());
            var index = JeiRecipeCardSource.class.getDeclaredField("index");
            index.setAccessible(true);
            index.set(source, fake.index());
            var generation = JeiRecipeCardSource.class.getDeclaredField("indexGeneration");
            generation.setAccessible(true);
            generation.setLong(source, JeiRuntimeHolder.generation());
            assertFalse(source.open(model(RecipeCardModel.Category.VIEWER, null, "mod:hidden_machine|mod:secret")));
        } finally {
            JeiRuntimeHolder.unavailable();
        }
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
