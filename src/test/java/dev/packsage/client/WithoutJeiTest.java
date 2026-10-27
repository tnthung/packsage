package dev.packsage.client;

import dev.packsage.knowledge.Evidence;
import dev.packsage.knowledge.KnowledgeService;
import dev.packsage.knowledge.Toolbox;
import dev.packsage.model.PackSageTools;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("without-jei")
class WithoutJeiTest {
    @Test
    void optionalLoadingAndDiscoveryWorkWithoutJei() throws Exception {
        assertThrows(ClassNotFoundException.class, () -> Class.forName("mezz.jei.api.runtime.IJeiRuntime"));
        Class.forName("dev.packsage.PackSage");
        Class.forName("dev.packsage.client.ClientAssistant");
        var support = new JeiSupport();
        assertTrue(support.installedVersion().isEmpty());
        assertTrue(support.providers(null, Runnable::run, () -> null).isEmpty());
        assertTrue(support.viewer().isEmpty());
        assertTrue(support.cardSource().isEmpty());
        assertEquals(0, support.runtimeGeneration());

        var scope = new Evidence.Scope("world", "resources", "team", "runtime", 1);
        var record = new Evidence.Record(new Evidence.Source(scope, "native", "1.21.1", "recipe:minecraft:torch",
                scope.requestGeneration(), Instant.EPOCH, Evidence.Activity.ACTIVE, Evidence.Coverage.COMPLETE,
                Evidence.Authority.LOADED, List.of()), Evidence.RecordKind.RECIPE, "Torch",
                new Evidence.JsonObject(Map.of()), List.of());
        var adapters = new ArrayList<Toolbox>(support.providers(null, Runnable::run, () -> scope));
        adapters.add(new Toolbox() {
            @Override public Metadata metadata() { return new Metadata("native", List.of(), true, List.of()); }
            @Override public CompletionStage<Evidence.Result> searchRecipe(PackSageTools.ReadRequest request, Context context) {
                assertEquals(new Evidence.JsonString("native"),
                        ((Evidence.JsonObject) request.arguments().values().get("filter")).values().get("provider"));
                return CompletableFuture.completedFuture(new Evidence.Result(List.of(record), Evidence.Coverage.COMPLETE,
                        Evidence.Outcome.COMPLETE, null));
            }
        });
        var knowledge = new KnowledgeService(adapters, () -> scope);
        assertEquals(List.of("discovery", "native"), knowledge.metadata().stream().map(Toolbox.Metadata::id).toList());
        assertTrue(knowledge.optionalTools().isEmpty());
        var result = knowledge.execute(new PackSageTools.ReadRequest("search_recipe", new Evidence.JsonObject(Map.of(
                "filter", new Evidence.JsonObject(Map.of("outputItem", new Evidence.JsonString("minecraft:torch"))))), scope))
                .toCompletableFuture().join();
        assertEquals(Evidence.Outcome.COMPLETE, result.outcome());
        assertEquals(List.of(record), result.records());
        assertNull(result.detail());
    }
}
