package dev.packsage.jei;

import dev.packsage.knowledge.Evidence;
import dev.packsage.knowledge.KnowledgeService;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.runtime.IRecipesGui;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JeiNavigationTest {
    private static final Evidence.Scope OLD = new Evidence.Scope("world", "en_us", "team", "runtime-1", 1);
    private static final Evidence.Scope CURRENT = new Evidence.Scope("world", "en_us", "team", "runtime-2", 2);

    static class Fake extends JeiSearchTest.Fake {
        final long generation;

        Fake(long generation, int ordinal, String recipeId) {
            this.generation = generation;
            add(ordinal, "mod:machine", "Machine", true);
            layouts.put(new RecipeRef("mod:machine", ordinal), new JeiLayout("mod:machine", "Machine", recipeId,
                    ordinal, generation, false, true, null, List.of(), List.of()));
        }

        @Override public long generation() { return generation; }
        @Override public Optional<RecipeRef> byRecipeId(String category, String recipeId) {
            var matches = layouts.entrySet().stream().filter(entry -> entry.getKey().categoryUid().equals(category)
                    && recipeId.equals(entry.getValue().recipeId())).limit(2).toList();
            return matches.size() == 1 ? Optional.of(matches.getFirst().getKey()) : Optional.empty();
        }
    }

    @Test
    void stableLinkSurvivesNewRuntime() {
        var saved = JeiProjection.record(new Fake(1, 0, "mod:recipe").layout(new JeiIndex.RecipeRef("mod:machine", 0)), OLD,
                "19.27", Instant.EPOCH).targets().getFirst();
        var current = new Fake(2, 3, "mod:recipe");
        var service = new KnowledgeService(List.of(new JeiProvider(() -> Optional.of(current), Runnable::run, () -> CURRENT)), () -> CURRENT);

        assertEquals(new Evidence.NavigationTarget(CURRENT, "jei", "mod:machine|mod:recipe", "recipe"),
                service.refreshNavigation(saved, CURRENT).toCompletableFuture().join());
        assertEquals(OLD, saved.scope());
        assertEquals(Evidence.NavigationStatus.STALE, service.open(saved, CURRENT).status());
    }

    @Test
    void temporaryLinkExpiresWithRuntime() {
        var current = new AtomicReference<JeiIndex>(new Fake(1, 0, null));
        var provider = new JeiProvider(() -> Optional.of(current.get()), Runnable::run, () -> CURRENT);
        var saved = new Evidence.NavigationTarget(OLD, "jei", "mod:machine|#0@1", "temporary");
        assertEquals(new Evidence.NavigationTarget(CURRENT, "jei", "mod:machine|#0@1", "temporary"),
                provider.refreshNavigation(saved, CURRENT).toCompletableFuture().join());

        current.set(new Fake(2, 0, null));
        assertNull(provider.refreshNavigation(saved, CURRENT).toCompletableFuture().join());
        assertEquals(Evidence.NavigationStatus.UNAVAILABLE, provider.open(
                new Evidence.NavigationTarget(CURRENT, "jei", saved.sourceHandle(), "temporary"), CURRENT).status());
    }

    @Test
    void removedRecipeFailsExplicitly() {
        var provider = new JeiProvider(() -> Optional.of(new Fake(2, 0, "mod:replacement")), Runnable::run, () -> CURRENT);
        var saved = new Evidence.NavigationTarget(CURRENT, "jei", "mod:machine|mod:recipe", "recipe");
        assertNull(provider.refreshNavigation(saved, CURRENT).toCompletableFuture().join());
        assertEquals(Evidence.NavigationStatus.UNAVAILABLE, provider.open(saved, CURRENT).status());
    }

    @Test
    void duplicateMatchesAreRejected() {
        var index = new Fake(2, 0, "mod:recipe");
        index.layouts.put(new JeiIndex.RecipeRef("mod:machine", 1), new JeiLayout("mod:machine", "Machine", "mod:recipe",
                1, 2, false, true, null, List.of(), List.of()));
        var provider = new JeiProvider(() -> Optional.of(index), Runnable::run, () -> CURRENT);
        assertNull(provider.refreshNavigation(new Evidence.NavigationTarget(OLD, "jei", "mod:machine|mod:recipe", "recipe"),
                CURRENT).toCompletableFuture().join());
    }

    @Test
    void malformedHandleIsNull() {
        var provider = new JeiProvider(() -> Optional.of(new Fake(2, 0, "mod:recipe")), Runnable::run, () -> CURRENT);
        for (var handle : List.of("", "mod:machine", "|mod:recipe", "mod:machine|", "mod:machine|mod:recipe|extra",
                "mod:machine|#0", "mod:machine|#-1@2", "mod:machine|#0@bad", "mod:machine|#0@2@3",
                "mod:machine|#2147483648@2", "Bad ID|mod:recipe", "mod:machine|Bad ID")) {
            assertNull(provider.refreshNavigation(new Evidence.NavigationTarget(OLD, "jei", handle,
                    handle.contains("#") ? "temporary" : "recipe"), CURRENT)
                    .toCompletableFuture().join(), handle);
        }
    }

    @Test
    void hiddenOrRemovedCategoryIsRejected() {
        var index = new Fake(2, 0, "mod:recipe");
        var provider = new JeiProvider(() -> Optional.of(index), Runnable::run, () -> CURRENT);
        for (boolean hidden : List.of(true, false)) {
            index.layouts.put(new JeiIndex.RecipeRef("mod:machine", 0), new JeiLayout("mod:machine", "Machine", "mod:recipe",
                    0, 2, hidden, hidden, null, List.of(), List.of()));
            assertNull(provider.refreshNavigation(new Evidence.NavigationTarget(OLD, "jei", "mod:machine|mod:recipe", "recipe"),
                    CURRENT).toCompletableFuture().join());
        }
    }

    @Test
    void runtimeOrScopeChangeDuringResolutionIsRejected() {
        var scope = new AtomicReference<>(CURRENT);
        var current = new AtomicReference<JeiIndex>();
        var index = new Fake(2, 0, "mod:recipe") {
            @Override public JeiLayout layout(RecipeRef ref) {
                current.set(new Fake(3, 0, "mod:recipe"));
                return super.layout(ref);
            }
        };
        current.set(index);
        var provider = new JeiProvider(() -> Optional.ofNullable(current.get()), Runnable::run, scope::get);
        var saved = new Evidence.NavigationTarget(OLD, "jei", "mod:machine|mod:recipe", "recipe");
        assertNull(provider.refreshNavigation(saved, CURRENT).toCompletableFuture().join());

        current.set(new Fake(2, 0, "mod:recipe") {
            @Override public JeiLayout layout(RecipeRef ref) {
                scope.set(OLD);
                return super.layout(ref);
            }
        });
        assertNull(provider.refreshNavigation(saved, CURRENT).toCompletableFuture().join());
    }

    @Test
    void wrongProviderOrDestinationIsRejected() {
        var provider = new JeiProvider(() -> Optional.of(new Fake(2, 0, "mod:recipe")), Runnable::run, () -> CURRENT);
        for (var target : List.of(new Evidence.NavigationTarget(OLD, "native", "mod:machine|mod:recipe", "recipe"),
                new Evidence.NavigationTarget(OLD, "jei", "mod:machine|mod:recipe", "temporary"),
                new Evidence.NavigationTarget(OLD, "jei", "mod:machine|mod:recipe", "other"))) {
            assertNull(provider.refreshNavigation(target, CURRENT).toCompletableFuture().join());
        }
        assertEquals(Evidence.NavigationStatus.STALE, provider.open(
                new Evidence.NavigationTarget(OLD, "jei", "mod:machine|mod:recipe", "recipe"), CURRENT).status());
    }

    @Test
    void unavailableRuntimeReturnsNull() {
        var provider = new JeiProvider(Optional::empty, Runnable::run, () -> CURRENT);
        assertNull(provider.refreshNavigation(new Evidence.NavigationTarget(OLD, "jei", "mod:machine|mod:recipe", "recipe"),
                CURRENT).toCompletableFuture().join());
    }

    @Test
    void viewerOpensOnlyTheResolvedRecipe() {
        var category = new IRecipeCategory<Object>() {
            public RecipeType<Object> getRecipeType() { return null; }
            public Component getTitle() { return null; }
            public IDrawable getIcon() { return null; }
            public void setRecipe(IRecipeLayoutBuilder builder, Object recipe, IFocusGroup focuses) { throw new AssertionError("unexpected layout"); }
        };
        var recipe = new Object();
        var shown = new AtomicReference<List<?>>();
        var gui = (IRecipesGui) Proxy.newProxyInstance(IRecipesGui.class.getClassLoader(), new Class<?>[]{IRecipesGui.class},
                (proxy, method, args) -> {
                    assertEquals("showRecipes", method.getName());
                    assertSame(category, args[0]);
                    assertEquals(List.of(), args[2]);
                    shown.set((List<?>) args[1]);
                    return null;
                });
        var ref = new JeiIndex.RecipeRef("mod:machine", 3);
        var viewer = new JeiViewer(gui, value -> value.equals(ref)
                ? Optional.<JeiRuntimeIndex.Resolved<?>>of(new JeiRuntimeIndex.Resolved<>(category, recipe)) : Optional.empty());

        assertFalse(viewer.showRecipe(new JeiIndex.RecipeRef("mod:machine", 0)));
        assertNull(shown.get());
        assertTrue(viewer.showRecipe(ref));
        assertEquals(1, shown.get().size());
        assertSame(recipe, shown.get().getFirst());
    }
}
