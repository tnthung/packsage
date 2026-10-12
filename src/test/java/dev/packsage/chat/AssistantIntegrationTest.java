package dev.packsage.chat;

import dev.packsage.knowledge.Evidence;
import dev.packsage.knowledge.KnowledgeProvider;
import dev.packsage.knowledge.KnowledgeService;
import dev.packsage.model.ModelBackend;
import dev.packsage.model.PackSageTools;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class AssistantIntegrationTest {
    private static final Evidence.Scope SCOPE = new Evidence.Scope("world", "resources", "team", "runtime", 1);

    @Test
    void searchesIndependentProvidersAndReadsOnlyObservedScopedHandles() throws Exception {
        var loaded = record("native", "torch", Evidence.Authority.LOADED, "7 torches");
        var external = record("guide", "torch", Evidence.Authority.INSTALLED_DOCUMENTATION, "4 torches");
        var current = new AtomicReference<>(SCOPE);
        var service = new KnowledgeService(List.of(new Provider("native", List.of(loaded), false),
                new Provider("guide", List.of(external), false)), current::get,
                request -> CompletableFuture.completedFuture(new Evidence.Result(List.of(), Evidence.Coverage.UNAVAILABLE, Evidence.Outcome.UNKNOWN, null)),
                (identities, scope) -> CompletableFuture.completedFuture(new Evidence.Result(List.of(), Evidence.Coverage.COMPLETE, Evidence.Outcome.COMPLETE, null)));
        var tools = new PackSageTools(service::execute);

        var search = tools.execute(new ModelBackend.ToolCall("search", "search_pack", "{\"text\":\"torch\"}"), SCOPE)
                .toCompletableFuture().get();
        assertEquals(List.of(loaded, external), search.records());
        var read = tools.execute(new ModelBackend.ToolCall("read", "read_evidence", "{\"handles\":[\"native:torch\",\"guide:torch\"]}"), SCOPE)
                .toCompletableFuture().get();
        assertEquals(List.of(loaded, external), read.records());
        assertTrue(tools.execute(new ModelBackend.ToolCall("bad", "read_evidence", "{\"handles\":[\"config:../../secret\"]}"), SCOPE)
                .toCompletableFuture().get().result().error());

        current.set(new Evidence.Scope("another-world", "resources", "team", "runtime", 2));
        assertTrue(tools.execute(new ModelBackend.ToolCall("old", "read_evidence", "{\"handles\":[\"native:torch\"]}"), SCOPE)
                .toCompletableFuture().get().result().error());
    }

    @Test
    void preservesUnaffectedEvidenceWhenAnotherProviderFails() throws Exception {
        var loaded = record("native", "torch", Evidence.Authority.LOADED, "7 torches");
        var service = new KnowledgeService(List.of(new Provider("native", List.of(loaded), false),
                new Provider("broken", List.of(), true)), () -> SCOPE,
                request -> CompletableFuture.completedFuture(new Evidence.Result(List.of(), Evidence.Coverage.UNAVAILABLE, Evidence.Outcome.UNKNOWN, null)),
                (identities, scope) -> CompletableFuture.completedFuture(new Evidence.Result(List.of(), Evidence.Coverage.COMPLETE, Evidence.Outcome.COMPLETE, null)));

        var response = new PackSageTools(service::execute).execute(new ModelBackend.ToolCall("search", "search_pack", "{\"text\":\"torch\"}"), SCOPE)
                .toCompletableFuture().get();
        assertTrue(response.records().contains(loaded));
        assertTrue(response.records().stream().anyMatch(record -> record.source().providerId().equals("broken")
                && record.source().coverage() == Evidence.Coverage.ERROR));
        assertTrue(response.result().serializedResult().contains("PARTIAL"));
    }

    @Test
    void rejectsLateEvidenceAfterWorldChange() throws Exception {
        var pending = new CompletableFuture<Evidence.Result>();
        var current = new AtomicReference<>(SCOPE);
        KnowledgeProvider provider = new Provider("native", List.of(), false) {
            @Override public CompletionStage<Evidence.Result> query(Evidence.Query query, Evidence.Scope scope) { return pending; }
        };
        var service = new KnowledgeService(List.of(provider), current::get,
                request -> pending, (identities, scope) -> pending);
        var request = service.execute(new PackSageTools.ReadRequest("search_pack", new Evidence.JsonObject(Map.of("text", new Evidence.JsonString("torch"))), SCOPE));
        current.set(new Evidence.Scope("another-world", "resources", "team", "runtime", 2));
        pending.complete(new Evidence.Result(List.of(record("native", "torch", Evidence.Authority.LOADED, "7 torches")),
                Evidence.Coverage.COMPLETE, Evidence.Outcome.COMPLETE, null));
        assertEquals(Evidence.Outcome.ERROR, request.toCompletableFuture().get().outcome());
    }

    private static Evidence.Record record(String provider, String handle, Evidence.Authority authority, String title) {
        return new Evidence.Record(new Evidence.Source(SCOPE, provider, "fixture", handle, 1, Instant.EPOCH,
                Evidence.Activity.ACTIVE, Evidence.Coverage.COMPLETE, authority, List.of()), Evidence.RecordKind.RECIPE,
                title, new Evidence.JsonObject(Map.of()), List.of());
    }

    private static class Provider implements KnowledgeProvider {
        private final String id;
        private final List<Evidence.Record> records;
        private final boolean fail;

        private Provider(String id, List<Evidence.Record> records, boolean fail) {
            this.id = id;
            this.records = records;
            this.fail = fail;
        }

        @Override public String id() { return id; }
        @Override public ProviderStatus status() { return new ProviderStatus(Availability.AVAILABLE, "fixture", "fixture"); }
        @Override public CompletionStage<Evidence.Result> query(Evidence.Query query, Evidence.Scope scope) {
            return fail ? CompletableFuture.failedFuture(new IllegalStateException("failed capture"))
                    : CompletableFuture.completedFuture(new Evidence.Result(records, Evidence.Coverage.COMPLETE, Evidence.Outcome.COMPLETE, null));
        }
        @Override public Evidence.NavigationResult open(Evidence.NavigationTarget target, Evidence.Scope scope) {
            return new Evidence.NavigationResult(Evidence.NavigationStatus.UNSUPPORTED, "fixture");
        }
    }
}
