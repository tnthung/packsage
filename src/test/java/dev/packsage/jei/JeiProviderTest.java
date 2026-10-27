package dev.packsage.jei;

import dev.packsage.knowledge.Evidence;
import dev.packsage.knowledge.KnowledgeService;
import dev.packsage.knowledge.Toolbox;
import dev.packsage.model.PackSageTools;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JeiProviderTest {
    private static final Evidence.Scope SCOPE = new Evidence.Scope("world", "en_us", "team", "runtime-1", 1);

    private static PackSageTools.ReadRequest request(String tool, Evidence.Scope scope, Map<String, Evidence.JsonValue> values) {
        return new PackSageTools.ReadRequest(tool, new Evidence.JsonObject(values), scope);
    }

    private static Toolbox.Context context(Evidence.Scope scope) {
        return new Toolbox.Context(() -> scope, Map.of(), new dev.packsage.knowledge.AdapterRegistry(), new Toolbox.DocumentBudget(), null);
    }

    private static Toolbox.Code failure(java.util.concurrent.CompletionStage<?> stage) {
        return ((Toolbox.Failure) assertThrows(CompletionException.class, () -> stage.toCompletableFuture().join()).getCause()).code();
    }

    @Test
    void validatesLikeNative() {
        var index = new JeiSearchTest.Fake();
        var provider = new JeiProvider(() -> Optional.of(index), Runnable::run, () -> SCOPE);
        for (var args : List.of(
                Map.<String, Evidence.JsonValue>of("filter", new Evidence.JsonString("bad")),
                Map.<String, Evidence.JsonValue>of("filter", new Evidence.JsonObject(Map.of("outputItem", new Evidence.JsonString("Bad ID")))),
                Map.<String, Evidence.JsonValue>of("filter", new Evidence.JsonObject(Map.of("inputItem", new Evidence.JsonString("Bad ID")))),
                Map.<String, Evidence.JsonValue>of("limit", new Evidence.JsonNumber(BigDecimal.ZERO)),
                Map.<String, Evidence.JsonValue>of("limit", new Evidence.JsonNumber(BigDecimal.valueOf(65))))) {
            assertEquals(Toolbox.Code.INVALID_ARGUMENTS, failure(provider.searchRecipe(request("search_recipe", SCOPE, args), context(SCOPE))));
        }
        assertEquals(Toolbox.Code.NOT_HANDLED, failure(provider.searchRecipe(request("search_recipe", SCOPE, Map.of("filter",
                new Evidence.JsonObject(Map.of("provider", new Evidence.JsonString("native"))))), context(SCOPE))));
    }

    @Test
    void notReadyIsUnavailable() {
        var provider = new JeiProvider(Optional::empty, Runnable::run, () -> SCOPE);
        assertEquals(Toolbox.Code.UNAVAILABLE, failure(provider.searchRecipe(request("search_recipe", SCOPE, Map.of("filter",
                new Evidence.JsonObject(Map.of("provider", new Evidence.JsonString("jei"))))), context(SCOPE))));
    }

    @Test
    void cursorFromEarlierRuntimeOrGenerationIsStale() {
        var generation = new AtomicLong(7);
        var index = new JeiSearchTest.Fake() { @Override public long generation() { return generation.get(); } };
        index.add(0, "mod:machine", "Machine", true); index.add(1, "mod:machine", "Machine", true);
        index.categoryMatches.put("", List.of("mod:machine"));
        var current = new AtomicReference<>(SCOPE);
        var provider = new JeiProvider(() -> Optional.of(index), Runnable::run, current::get);
        var args = Map.<String, Evidence.JsonValue>of("limit", new Evidence.JsonNumber(BigDecimal.ONE));
        var first = provider.searchRecipe(request("search_recipe", SCOPE, args), context(SCOPE)).toCompletableFuture().join();
        var cursor = new Evidence.JsonString(first.nextCursor());
        generation.incrementAndGet();
        assertEquals(Toolbox.Code.INVALID_CURSOR, failure(provider.searchRecipe(
                request("search_recipe", SCOPE, Map.of("cursor", cursor)), context(SCOPE))));
        var next = new Evidence.Scope("world", "en_us", "team", "runtime-2", 2); current.set(next);
        assertEquals(Toolbox.Code.STALE_SCOPE, failure(provider.searchRecipe(request("search_recipe", next, Map.of("cursor", cursor)), context(next))));
    }

    @Test
    void searchPropagatesTruncationAndInputFocus() {
        var index = new JeiSearchTest.Fake();
        index.add(0, "mod:machine", "Machine", true); index.add(1, "mod:machine", "Machine", true);
        index.focuses.put("INPUT:minecraft:stone", List.of(new JeiIndex.RecipeRef("mod:machine", 1)));
        index.categoryMatches.put("machine", List.of("mod:machine")); index.truncated = true;
        var provider = new JeiProvider(() -> Optional.of(index), Runnable::run, () -> SCOPE);
        var result = provider.searchRecipe(request("search_recipe", SCOPE, Map.of("filter", new Evidence.JsonObject(Map.of(
                "inputItem", new Evidence.JsonString("minecraft:stone"), "text", new Evidence.JsonString("machine"))))), context(SCOPE)).toCompletableFuture().join();
        assertEquals(1, result.records().size()); assertEquals(Evidence.Coverage.PARTIAL, result.coverage());
        assertEquals("test:recipe1", result.records().getFirst().recipePayload().canonicalRecipeId());
        assertTrue(result.detail().contains("truncated"));
    }

    @Test
    void cursorContinuesOnlyTheOriginalQuery() {
        var index = new JeiSearchTest.Fake();
        for (int i = 0; i < 3; i++) {
            index.add(i, "minecraft:crafting", "Crafting", true);
            index.layouts.put(new JeiIndex.RecipeRef("minecraft:crafting", i), new JeiLayout("minecraft:crafting", "Crafting",
                    "test:recipe" + i, i, 7, false, true, Evidence.DecodingStatus.VERIFIED, List.of(new JeiLayout.Slot(0, JeiLayout.Role.OUTPUT,
                    List.of(new JeiLayout.Ingredient("item", "minecraft:torch", "{}", 4, null)), List.of())), List.of()));
        }
        index.focuses.put("OUTPUT:minecraft:torch", List.copyOf(index.layouts.keySet()));
        var provider = new JeiProvider(() -> Optional.of(index), Runnable::run, () -> SCOPE);
        var filter = new Evidence.JsonObject(Map.of("outputItem", new Evidence.JsonString("minecraft:torch")));
        var first = provider.searchRecipe(request("search_recipe", SCOPE, Map.of("filter", filter,
                "limit", new Evidence.JsonNumber(BigDecimal.ONE))), context(SCOPE)).toCompletableFuture().join();
        assertEquals(Evidence.Coverage.PARTIAL, first.coverage());
        var cursor = new Evidence.JsonString(first.nextCursor());
        assertEquals(Toolbox.Code.INVALID_CURSOR, failure(provider.searchRecipe(request("search_recipe", SCOPE,
                Map.of("cursor", cursor)), context(SCOPE))));
        var next = provider.searchRecipe(request("search_recipe", SCOPE, Map.of("filter", filter, "cursor", cursor,
                "limit", new Evidence.JsonNumber(BigDecimal.valueOf(2)))), context(SCOPE)).toCompletableFuture().join();
        assertEquals(List.of("test:recipe1", "test:recipe2"), next.records().stream()
                .map(record -> record.recipePayload().canonicalRecipeId()).toList());
        assertEquals(Evidence.Coverage.COMPLETE, next.coverage());
        assertNull(next.nextCursor());
    }

    @Test
    void unavailablePlanningIsNotHandled() {
        var provider = new JeiProvider(Optional::empty, Runnable::run, () -> SCOPE);
        for (var args : List.of(Map.<String, Evidence.JsonValue>of(),
                Map.<String, Evidence.JsonValue>of("provider", new Evidence.JsonString("jei")))) {
            assertEquals(Toolbox.Code.NOT_HANDLED, failure(provider.planRecipe(request("plan_recipe", SCOPE, args), context(SCOPE))));
        }
    }

    @Test
    void validatesPlanArgumentsLikeNative() {
        var provider = new JeiProvider(() -> Optional.of(new JeiSearchTest.Fake()), Runnable::run, () -> SCOPE);
        for (var args : List.of(
                Map.<String, Evidence.JsonValue>of(),
                Map.<String, Evidence.JsonValue>of("identity", new Evidence.JsonNumber(BigDecimal.ONE)),
                Map.<String, Evidence.JsonValue>of("identity", new Evidence.JsonString("mod:product"), "quantity", new Evidence.JsonString("2")),
                Map.<String, Evidence.JsonValue>of("identity", new Evidence.JsonString("mod:product"), "quantity", new Evidence.JsonNumber(new BigDecimal("1.5"))),
                Map.<String, Evidence.JsonValue>of("identity", new Evidence.JsonString("mod:product"), "quantity", new Evidence.JsonNumber(BigDecimal.ZERO)),
                Map.<String, Evidence.JsonValue>of("identity", new Evidence.JsonString("mod:product"), "quantity", new Evidence.JsonNumber(BigDecimal.valueOf(1_000_001))),
                Map.<String, Evidence.JsonValue>of("identity", new Evidence.JsonString("mod:product"), "depth", new Evidence.JsonNumber(new BigDecimal("1.5"))),
                Map.<String, Evidence.JsonValue>of("identity", new Evidence.JsonString("mod:product"), "depth", new Evidence.JsonNumber(BigDecimal.valueOf(-1))),
                Map.<String, Evidence.JsonValue>of("identity", new Evidence.JsonString("mod:product"), "depth", new Evidence.JsonNumber(BigDecimal.valueOf(9))))) {
            assertEquals(Toolbox.Code.INVALID_ARGUMENTS, failure(provider.planRecipe(request("plan_recipe", SCOPE, args), context(SCOPE))));
        }
    }

    @Test
    void runtimeChangeDuringExtractionIsStale() {
        for (var tool : List.of("get", "search_recipe", "plan_recipe")) {
            var current = new AtomicReference<JeiIndex>();
            var index = new JeiSearchTest.Fake() {
                @Override public JeiLayout layout(RecipeRef ref) {
                    current.set(new JeiSearchTest.Fake() { @Override public long generation() { return 8; } });
                    return super.layout(ref);
                }
            };
            index.add(0, "mod:machine", "Machine", true);
            index.layouts.put(new JeiIndex.RecipeRef("mod:machine", 0), new JeiLayout("mod:machine", "Machine", null, 0, 7,
                    false, true, null, List.of(new JeiLayout.Slot(0, JeiLayout.Role.OUTPUT,
                    List.of(new JeiLayout.Ingredient("item", "mod:product", "{}", 1, null)), List.of())), List.of()));
            index.focuses.put("OUTPUT:mod:product", List.copyOf(index.layouts.keySet()));
            current.set(index);
            var provider = new JeiProvider(() -> Optional.ofNullable(current.get()), Runnable::run, () -> SCOPE);
            var stage = switch (tool) {
                case "get" -> provider.query(new Evidence.Query(Evidence.Operation.GET,
                        new Evidence.JsonObject(Map.of("id", new Evidence.JsonString("mod:machine|#0@7"))), 1, null, null), SCOPE);
                case "search_recipe" -> provider.searchRecipe(request(tool, SCOPE, Map.of("filter",
                        new Evidence.JsonObject(Map.of("outputItem", new Evidence.JsonString("mod:product"))))), context(SCOPE));
                default -> provider.planRecipe(request(tool, SCOPE, Map.of("identity", new Evidence.JsonString("mod:product"))), context(SCOPE));
            };
            assertEquals(Toolbox.Code.STALE_SCOPE, failure(stage), tool);
        }
    }

    @Test
    void readsStableAndTemporaryHandles() {
        var index = new JeiSearchTest.Fake() {
            @Override public Optional<RecipeRef> byRecipeId(String category, String id) {
                return layouts.keySet().stream().filter(ref -> ref.categoryUid().equals(category) && layouts.get(ref).recipeId().equals(id)).findFirst();
            }
        };
        index.add(0, "mod:machine", "Machine", true);
        var provider = new JeiProvider(() -> Optional.of(index), Runnable::run, () -> SCOPE);
        var result = provider.query(new Evidence.Query(Evidence.Operation.GET, new Evidence.JsonObject(Map.of("id",
                new Evidence.JsonString("mod:machine|test:recipe0"))), 1, null, null), SCOPE).toCompletableFuture().join();
        assertEquals("mod:machine|test:recipe0", result.records().getFirst().source().sourceHandle());
        var stale = provider.query(new Evidence.Query(Evidence.Operation.GET, new Evidence.JsonObject(Map.of("id",
                new Evidence.JsonString("mod:machine|#0@6"))), 1, null, null), SCOPE).toCompletableFuture().join();
        assertTrue(stale.records().isEmpty());
        index.layouts.put(new JeiIndex.RecipeRef("mod:machine", 0), new JeiLayout("mod:machine", "Machine", null, 0, 7,
                false, true, null, List.of(), List.of()));
        var temporary = provider.query(new Evidence.Query(Evidence.Operation.GET, new Evidence.JsonObject(Map.of("id",
                new Evidence.JsonString("mod:machine|#0@7"))), 1, null, null), SCOPE).toCompletableFuture().join();
        assertEquals("mod:machine|#0@7", temporary.records().getFirst().source().sourceHandle());
    }

    @Test
    void planRoutesAndCanBeReread() {
        var index = new JeiSearchTest.Fake();
        var ref = new JeiIndex.RecipeRef("mod:machine", 0);
        index.layouts.put(ref, new JeiLayout("mod:machine", "Machine", "mod:recipe", 0, 7, false, true, null,
                List.of(new JeiLayout.Slot(0, JeiLayout.Role.OUTPUT, List.of(new JeiLayout.Ingredient("item", "mod:product", "{}", 1, null)), List.of())), List.of()));
        index.focuses.put("OUTPUT:mod:product", List.of(ref));
        var provider = new JeiProvider(() -> Optional.of(index), Runnable::run, () -> SCOPE);
        var service = new KnowledgeService(List.of(provider), () -> SCOPE);
        var plan = service.execute(request("plan_recipe", SCOPE, Map.of("identity", new Evidence.JsonString("mod:product")))).toCompletableFuture().join();
        assertTrue(plan.records().getFirst().source().sourceHandle().startsWith("plan|mod:product|1|4|0|"));
        var handle = "jei:" + plan.records().getFirst().source().sourceHandle();
        var reread = service.execute(request("read_evidence", SCOPE, Map.of("handles", new Evidence.JsonArray(List.of(new Evidence.JsonString(handle)))))).toCompletableFuture().join();
        assertEquals(plan.records().getFirst().source().sourceHandle(), reread.records().getFirst().source().sourceHandle());
        assertEquals(Toolbox.Code.NOT_HANDLED, failure(provider.planRecipe(request("plan_recipe", SCOPE,
                Map.of("provider", new Evidence.JsonString("native"))), context(SCOPE))));
    }
}
