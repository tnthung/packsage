package dev.packsage.client;

import dev.packsage.chat.ChatContracts;
import dev.packsage.knowledge.Evidence;
import dev.packsage.knowledge.KnowledgeProvider;
import dev.packsage.knowledge.jei.PackSageJeiPlugin;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientAssistantTest {
    @Test
    void optionalVersionRangesAcceptValidatedAdapterReleasesAndRejectOtherLines() {
        assertTrue(ClientAssistant.versionMatches("[19.27.0.340,20)", "19.27.0.340"));
        assertTrue(ClientAssistant.versionMatches("[19.27.0.340,20)", "19.57.0.450"));
        assertTrue(ClientAssistant.versionMatches("[19.27.0.340,20)", "19.99.99.999"));
        assertFalse(ClientAssistant.versionMatches("[19.27.0.340,20)", "19.27.0.339"));
        assertFalse(ClientAssistant.versionMatches("[19.27.0.340,20)", "20.0.0.0"));

        assertTrue(ClientAssistant.versionMatches("[2101.1.36,2101.2)", "2101.1.36"));
        assertTrue(ClientAssistant.versionMatches("[2101.1.36,2101.2)", "2101.1.37"));
        assertFalse(ClientAssistant.versionMatches("[2101.1.36,2101.2)", "2101.1.35"));
        assertFalse(ClientAssistant.versionMatches("[2101.1.36,2101.2)", "2101.2"));

        assertTrue(ClientAssistant.versionMatches("[1.21.1-92,1.21.1-94)", "1.21.1-92-NEOFORGE"));
        assertTrue(ClientAssistant.versionMatches("[1.21.1-92,1.21.1-94)", "1.21.1-93-NEOFORGE"));
        assertFalse(ClientAssistant.versionMatches("[1.21.1-92,1.21.1-94)", "1.21.1-91"));
        assertFalse(ClientAssistant.versionMatches("[1.21.1-92,1.21.1-94)", "1.21.1-94"));
        assertFalse(ClientAssistant.versionMatches("[1.21.1-92,1.21.1-94)", "1.21.1-93-FORGE"));
        assertFalse(ClientAssistant.versionMatches("[1.21.1-92,1.21.1-94)", "1.21.1--93"));
        assertFalse(ClientAssistant.versionMatches("[1.21.1-92,1.21.1-94)", "unknown"));
    }

    @Test
    void optionalProviderPreservesObservedVersionAndEvidenceWhenWrappingResults() {
        var scope = new Evidence.Scope("world", "en_us", "player", "runtime", 4);
        var target = new Evidence.NavigationTarget(scope, "jei", "recipe-handle", "target-id");
        var source = new Evidence.Source(scope, "jei", "baseline", "recipe-handle", 7, Instant.EPOCH,
                Evidence.Activity.ACTIVE, Evidence.Coverage.PARTIAL, Evidence.Authority.VIEWER, List.of("source"));
        var record = new Evidence.Record(source, Evidence.RecordKind.RECIPE, "Recipe",
                new Evidence.JsonObject(Map.of("value", new Evidence.JsonString("kept"))), List.of(target),
                new Evidence.RecipePayload("mod:recipe", List.of(), List.of(new Evidence.RecipeOutput("item",
                        "mod:result", new Evidence.JsonObject(Map.of()), 3)), List.of(), List.of(),
                        List.of("requires power"), Evidence.DecodingStatus.PARTIAL));
        var result = new Evidence.Result(List.of(record), Evidence.Coverage.PARTIAL, Evidence.Outcome.PARTIAL,
                "next", "query detail");
        KnowledgeProvider delegate = new KnowledgeProvider() {
            public String id() { return "jei"; }
            public ProviderStatus status() {
                return new ProviderStatus(Availability.AVAILABLE, "baseline", "ready");
            }
            public java.util.concurrent.CompletionStage<Evidence.Result> query(Evidence.Query query,
                                                                              Evidence.Scope requestedScope) {
                return CompletableFuture.completedFuture(result);
            }
            public Evidence.NavigationResult open(Evidence.NavigationTarget requestedTarget,
                                                  Evidence.Scope requestedScope) {
                return new Evidence.NavigationResult(Evidence.NavigationStatus.OPENED, "opened");
            }
        };
        var wrapper = new ClientAssistant.ReflectiveProvider(new ClientAssistant.OptionalStatus("jei", "jei",
                "[19.27.0.340,20)", "19.57.0.450", true, true, "supported"), () -> delegate);

        var status = wrapper.status();
        var wrapped = wrapper.query(new Evidence.Query(Evidence.Operation.SEARCH, new Evidence.JsonObject(Map.of()),
                1, null, null), scope).toCompletableFuture().join();

        assertEquals("19.57.0.450", status.version());
        assertTrue(status.detail().contains("[19.27.0.340,20)"));
        assertEquals(Evidence.Coverage.PARTIAL, wrapped.coverage());
        assertEquals(Evidence.Outcome.PARTIAL, wrapped.outcome());
        assertEquals("next", wrapped.nextCursor());
        assertEquals("query detail", wrapped.detail());
        assertEquals(1, wrapped.records().size());
        var wrappedRecord = wrapped.records().getFirst();
        assertEquals(new Evidence.Source(scope, "jei", "19.57.0.450", "recipe-handle", 7, Instant.EPOCH,
                Evidence.Activity.ACTIVE, Evidence.Coverage.PARTIAL, Evidence.Authority.VIEWER, List.of("source")),
                wrappedRecord.source());
        assertEquals(record.kind(), wrappedRecord.kind());
        assertEquals(record.title(), wrappedRecord.title());
        assertEquals(record.payload(), wrappedRecord.payload());
        assertEquals(record.targets(), wrappedRecord.targets());
        assertEquals(record.recipePayload(), wrappedRecord.recipePayload());
    }

    @Test
    void privateWorldIdentityIsOpaqueAndStableWhileKeepingItsLabel() {
        ClientAssistant.CopiedWorld first = new ClientAssistant.CopiedWorld(
                "private:C:/instances/fixture/saves/Test World", "Test World", true,
                "C:/instances/fixture/saves/Test World/serverconfig");
        ClientAssistant.CopiedWorld same = new ClientAssistant.CopiedWorld(
                "private:C:/instances/fixture/saves/Test World", "Test World", true,
                "C:/instances/fixture/saves/Test World/serverconfig");
        ClientAssistant.CopiedWorld other = new ClientAssistant.CopiedWorld(
                "private:C:/instances/fixture/saves/Other World", "Other World", true,
                "C:/instances/fixture/saves/Other World/serverconfig");

        ChatContracts.WorldIdentity firstIdentity = ClientAssistant.identityFor(first);

        assertEquals(firstIdentity, ClientAssistant.identityFor(same));
        assertNotEquals(firstIdentity.worldOrServer(), ClientAssistant.identityFor(other).worldOrServer());
        assertEquals("Test World", firstIdentity.readableLabel());
        assertFalse(firstIdentity.worldOrServer().contains("C:/instances"));
        assertFalse(firstIdentity.worldOrServer().contains("Test World"));
    }

    @Test
    void scopeSeparatesSessionResourcesPlayerTeamAndProviderRuntimeWithoutRawTeamToken() {
        UUID session = UUID.fromString("11111111-1111-1111-1111-111111111111");
        Evidence.Scope first = ClientAssistant.scopeFor("world-id", session, 4, "en_us",
                "player-id", "team-token", 7);
        Evidence.Scope same = ClientAssistant.scopeFor("world-id", session, 4, "en_us",
                "player-id", "team-token", 7);
        Evidence.Scope changed = ClientAssistant.scopeFor("world-id", session, 5, "fr_fr",
                "other-player", "other-team-token", 8);

        assertEquals(first, same);
        assertNotEquals(first, changed);
        assertTrue(first.worldSession().contains(session.toString()));
        assertTrue(first.resourcesLocale().contains("4"));
        assertTrue(first.resourcesLocale().contains("en_us"));
        assertTrue(first.playerTeam().startsWith("player-id:"));
        assertFalse(first.playerTeam().contains("team-token"));
        assertEquals("runtime-7", first.providerRuntime());
    }

    @Test
    void jeiRuntimeLossAdvancesTheObservedRuntimeGeneration() {
        long before = PackSageJeiPlugin.runtimeGeneration();

        new PackSageJeiPlugin().onRuntimeUnavailable();

        assertTrue(PackSageJeiPlugin.runtimeGeneration() > before);
    }
}
