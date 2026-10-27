package dev.packsage.jei.bookmark;

import dev.packsage.jei.JeiIndex;
import dev.packsage.jei.JeiSearchTest;
import dev.packsage.knowledge.AdapterRegistry;
import dev.packsage.knowledge.Evidence;
import dev.packsage.knowledge.Toolbox;
import dev.packsage.model.PackSageTools;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JeiBookmarkProviderTest {
    private static final Evidence.Scope SCOPE = new Evidence.Scope("world", "en_us", "team", "runtime-1", 1);

    static final class FakeBookmarks implements JeiBookmarks {
        final Set<String> held = new HashSet<>();
        @Override public boolean item(Action action, String itemId, String componentsJson) {
            return apply(action, "item:" + itemId + componentsJson);
        }
        @Override public boolean recipe(Action action, JeiIndex.RecipeRef ref) {
            return apply(action, "recipe:" + ref);
        }
        private boolean apply(Action action, String key) {
            switch (action) {
                case ADD -> held.add(key);
                case REMOVE -> held.remove(key);
                case CHECK -> { }
            }
            return held.contains(key);
        }
    }

    private static Evidence.JsonString text(String value) { return new Evidence.JsonString(value); }

    private static PackSageTools.ReadRequest request(Map<String, Evidence.JsonValue> values) {
        return new PackSageTools.ReadRequest("bookmark", new Evidence.JsonObject(values), SCOPE);
    }

    private static Toolbox.Context context(Map<String, Toolbox.Reference> references) {
        return new Toolbox.Context(() -> SCOPE, references, new AdapterRegistry(), new Toolbox.DocumentBudget(), null);
    }

    private static Evidence.JsonObject item(String id) { return new Evidence.JsonObject(Map.of("id", text(id))); }

    private static JeiBookmarkProvider provider(FakeBookmarks bookmarks, JeiIndex index) {
        return new JeiBookmarkProvider(() -> Optional.of(new JeiBookmarkProvider.Session(index, bookmarks)), Runnable::run, () -> SCOPE);
    }

    private static Toolbox.Code failure(java.util.concurrent.CompletionStage<?> stage) {
        return ((Toolbox.Failure) assertThrows(CompletionException.class, () -> stage.toCompletableFuture().join()).getCause()).code();
    }

    @Test
    void exactlyOneTarget() {
        var provider = provider(new FakeBookmarks(), new JeiSearchTest.Fake());
        for (var args : List.of(
                Map.<String, Evidence.JsonValue>of("action", text("add")),
                Map.<String, Evidence.JsonValue>of("action", text("add"), "item", item("minecraft:glass"), "recipe", text("jei:a|b")),
                Map.<String, Evidence.JsonValue>of("action", text("bogus"), "item", item("minecraft:glass")),
                Map.<String, Evidence.JsonValue>of("action", text("add"), "item", item("Bad ID")))) {
            assertEquals(Toolbox.Code.INVALID_ARGUMENTS, failure(provider.handle(request(args), context(Map.of()))));
        }
    }

    @Test
    void addRemoveCheckItem() {
        var bookmarks = new FakeBookmarks();
        var provider = provider(bookmarks, new JeiSearchTest.Fake());
        var context = context(Map.of());
        var steps = List.of(new Object[] {"check", false, false}, new Object[] {"add", true, true}, new Object[] {"add", true, false},
                new Object[] {"check", true, false}, new Object[] {"remove", false, true}, new Object[] {"remove", false, false});
        for (var step : steps) {
            var result = provider.handle(request(Map.of("action", text((String) step[0]), "item", item("minecraft:glass"))), context)
                    .toCompletableFuture().join();
            var record = result.records().getFirst();
            assertEquals(Evidence.RecordKind.OTHER, record.kind());
            assertEquals(new Evidence.JsonBoolean((Boolean) step[1]), record.payload().values().get("bookmarked"), (String) step[0]);
            assertEquals(new Evidence.JsonBoolean((Boolean) step[2]), record.payload().values().get("changed"), (String) step[0]);
            assertEquals(text("minecraft:glass"), record.payload().values().get("target"));
            assertEquals(text((String) step[0]), record.payload().values().get("action"));
        }
    }

    @Test
    void recipeTargetNeedsObservedReference() {
        var index = new JeiSearchTest.Fake() {
            @Override public Optional<RecipeRef> byRecipeId(String category, String id) {
                return id.equals("test:recipe0") ? Optional.of(new RecipeRef(category, 0)) : Optional.empty();
            }
        };
        index.add(0, "mod:machine", "Machine", true);
        var bookmarks = new FakeBookmarks();
        var provider = provider(bookmarks, index);
        var args = Map.<String, Evidence.JsonValue>of("action", text("add"), "recipe", text("jei:mod:machine|test:recipe0"));

        assertEquals(Toolbox.Code.INVALID_REFERENCE, failure(provider.handle(request(args), context(Map.of()))));
        var wrongKind = new Toolbox.Reference("jei", "mod:machine|test:recipe0", Evidence.RecordKind.ITEM, SCOPE);
        assertEquals(Toolbox.Code.INVALID_REFERENCE, failure(provider.handle(request(args),
                context(Map.of("jei:mod:machine|test:recipe0", wrongKind)))));

        var observed = new Toolbox.Reference("jei", "mod:machine|test:recipe0", Evidence.RecordKind.RECIPE, SCOPE);
        var result = provider.handle(request(args), context(Map.of("jei:mod:machine|test:recipe0", observed))).toCompletableFuture().join();
        assertEquals(new Evidence.JsonBoolean(true), result.records().getFirst().payload().values().get("bookmarked"));
        assertEquals(Set.of("recipe:" + new JeiIndex.RecipeRef("mod:machine", 0)), bookmarks.held);

        var stale = new Toolbox.Reference("jei", "mod:machine|test:gone", Evidence.RecordKind.RECIPE, SCOPE);
        assertEquals(Toolbox.Code.INVALID_REFERENCE, failure(provider.handle(request(Map.of("action", text("add"),
                "recipe", text("jei:mod:machine|test:gone"))), context(Map.of("jei:mod:machine|test:gone", stale)))));
    }

    @Test
    void hiddenStableRecipeCanBeBookmarked() {
        var fake = new dev.packsage.jei.FakeJeiRuntime();
        var recipe = new Object();
        fake.category("mod:hidden_machine", true, recipe);
        fake.registryNames.put(recipe, net.minecraft.resources.ResourceLocation.parse("mod:secret"));
        var bookmarks = new FakeBookmarks();
        var provider = provider(bookmarks, fake.index());
        var observed = new Toolbox.Reference("jei", "mod:hidden_machine|mod:secret", Evidence.RecordKind.RECIPE, SCOPE);

        var result = provider.handle(request(Map.of("action", text("add"), "recipe", text("jei:mod:hidden_machine|mod:secret"))),
                context(Map.of("jei:mod:hidden_machine|mod:secret", observed))).toCompletableFuture().join();
        assertEquals(new Evidence.JsonBoolean(true), result.records().getFirst().payload().values().get("bookmarked"));
        assertEquals(Set.of("recipe:" + new JeiIndex.RecipeRef("mod:hidden_machine", 0)), bookmarks.held);
    }

    @Test
    void notReadyIsUnavailable() {
        var provider = new JeiBookmarkProvider(Optional::empty, Runnable::run, () -> SCOPE);
        assertFalse(provider.enabled());
        assertEquals(Toolbox.Code.UNAVAILABLE, failure(provider.handle(request(Map.of("action", text("check"),
                "item", item("minecraft:glass"))), context(Map.of()))));
        assertEquals(Toolbox.Code.NOT_HANDLED, failure(provider.handle(new PackSageTools.ReadRequest("other",
                new Evidence.JsonObject(Map.of()), SCOPE), context(Map.of()))));
    }
}
