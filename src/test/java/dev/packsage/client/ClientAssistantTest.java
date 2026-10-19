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
    void optionalProviderForwardsSavedDestinationResolutionToTheCurrentDelegate() {
        var scope = new Evidence.Scope("new-session", "new-resources", "player", "runtime-2", 2);
        var saved = new Evidence.NavigationTarget(new Evidence.Scope("old-session", "old-resources", "player",
                "runtime-1", 1), "ponder", "old-registration", "create:mechanical_press");
        var current = new Evidence.NavigationTarget(scope, "ponder", "new-registration", "create:mechanical_press");
        var resolutions = new java.util.concurrent.atomic.AtomicInteger();
        var delegate = new java.util.concurrent.atomic.AtomicReference<KnowledgeProvider>(new KnowledgeProvider() {
            @Override public String id() { return "ponder"; }
            @Override public ProviderStatus status() { return new ProviderStatus(Availability.AVAILABLE, "fixture", null); }
            @Override public java.util.concurrent.CompletionStage<Evidence.Result> query(Evidence.Query query, Evidence.Scope requestedScope) {
                return CompletableFuture.completedFuture(new Evidence.Result(List.of(), Evidence.Coverage.COMPLETE,
                        Evidence.Outcome.COMPLETE, null));
            }
            @Override public java.util.concurrent.CompletionStage<Evidence.NavigationTarget> refreshNavigation(
                    Evidence.NavigationTarget target, Evidence.Scope requestedScope) {
                assertEquals(saved, target);
                assertEquals(scope, requestedScope);
                resolutions.incrementAndGet();
                return CompletableFuture.completedFuture(current);
            }
            @Override public Evidence.NavigationResult open(Evidence.NavigationTarget target, Evidence.Scope requestedScope) {
                assertEquals(current, target);
                assertEquals(scope, requestedScope);
                return new Evidence.NavigationResult(Evidence.NavigationStatus.OPENED, "current viewer");
            }
        });
        var wrapper = new ClientAssistant.ReflectiveProvider(new ClientAssistant.OptionalStatus("ponder", "ponder",
                "[0.8.1,1.1)", "1.0.82+mc1.21.1", true, true, "supported"), delegate::get);
        var service = new dev.packsage.knowledge.KnowledgeService(List.of(wrapper), () -> scope);

        assertEquals(current, service.refreshNavigation(saved, scope).toCompletableFuture().join());
        assertEquals(1, resolutions.get());
        assertEquals(Evidence.NavigationStatus.OPENED, service.open(current, scope).status());
        delegate.set(null);
        assertEquals(null, service.refreshNavigation(saved, scope).toCompletableFuture().join());
        assertEquals(1, resolutions.get());
    }

    @Test
    void unavailableGuideAdaptersKeepTheirDiscoveryMetadata() {
        for (String id : List.of("modonomicon", "guideme", "immersiveengineering", "actuallyadditions", "relics", "artifacts", "ponder")) {
            var wrapper = new ClientAssistant.ReflectiveProvider(new ClientAssistant.OptionalStatus(id, id,
                    "[1]", "unloaded", false, false, "not installed"), () -> null);
            assertEquals(List.of("guide"), wrapper.metadata().documentTypes(), id);
            assertEquals(id, wrapper.metadata().id());
            assertFalse(wrapper.enabled());
        }
    }

    @Test
    void optionalMinecraftFactoryLoadsTwoArgumentProvidersAndVersionedGuideProviders() {
        var scope = new Evidence.Scope("world", "en_us", "player", "runtime", 1);
        var ftb = ClientAssistant.optionalMinecraftProvider(new ClientAssistant.OptionalStatus("ftb_quests", "ftbquests",
                "[2101.1.36,2101.2)", "2101.1.36", true, true, "supported"),
                "dev.packsage.knowledge.ftb.FtbProvider", "forMinecraft", () -> scope, Runnable::run);
        var patchouli = ClientAssistant.optionalMinecraftProvider(new ClientAssistant.OptionalStatus("patchouli", "patchouli",
                "[1.21.1-92,1.21.1-94)", "1.21.1-93-NEOFORGE", true, true, "supported"),
                "dev.packsage.knowledge.patchouli.PatchouliProvider", "forMinecraft", () -> scope, Runnable::run);
        var modonomicon = ClientAssistant.optionalMinecraftProvider(new ClientAssistant.OptionalStatus("modonomicon", "modonomicon",
                ClientAssistant.MODONOMICON_VERSION, "1.120.7", true, true, "supported"),
                "dev.packsage.knowledge.guide.ModonomiconGuideReader", "forMinecraft", () -> scope, Runnable::run);

        assertTrue(ftb instanceof ClientAssistant.ReflectiveProvider);
        assertTrue(patchouli instanceof ClientAssistant.ReflectiveProvider);
        assertTrue(modonomicon instanceof ClientAssistant.ReflectiveProvider);
    }

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
    void bundledPonderVersionsPassTheConfiguredApiGateAndRejectOtherMinecraftBuilds() {
        assertTrue(ClientAssistant.versionMatches(ClientAssistant.PONDER_VERSION, "0.8.1"));
        assertTrue(ClientAssistant.versionMatches(ClientAssistant.PONDER_VERSION, "0.9.23"));
        assertTrue(ClientAssistant.versionMatches(ClientAssistant.PONDER_VERSION, "1.0.43"));
        assertTrue(ClientAssistant.versionMatches(ClientAssistant.PONDER_VERSION, "1.0.79+mc1.21.1"));
        assertTrue(ClientAssistant.versionMatches(ClientAssistant.PONDER_VERSION, "1.0.81+mc1.21.1"));
        assertTrue(ClientAssistant.versionMatches(ClientAssistant.PONDER_VERSION, "1.0.82+mc1.21.1"));
        assertTrue(ClientAssistant.versionMatches(ClientAssistant.PONDER_VERSION, "1.0.87+mc1.21.1"));
        assertTrue(ClientAssistant.versionMatches(ClientAssistant.PONDER_VERSION, "1.0.99+mc1.21.1"));
        assertFalse(ClientAssistant.versionMatches(ClientAssistant.PONDER_VERSION, "0.8.0"));
        assertFalse(ClientAssistant.versionMatches(ClientAssistant.PONDER_VERSION, "1.1.0+mc1.21.1"));
        assertFalse(ClientAssistant.versionMatches(ClientAssistant.PONDER_VERSION, "1.0.82+mc1.21.2"));
        assertFalse(ClientAssistant.versionMatches(ClientAssistant.PONDER_VERSION, "1.0.82+mc1.20.1"));
        assertFalse(ClientAssistant.versionMatches(ClientAssistant.PONDER_VERSION, "1.0.82+mc1.21.1-extra"));
    }

    @Test
    void guideAdapterCompatibilityRangesAcceptCurrentAndBoundaryReleases() {
        assertTrue(ClientAssistant.versionMatches(ClientAssistant.MODONOMICON_VERSION, "1.120.7"));
        assertTrue(ClientAssistant.versionMatches(ClientAssistant.MODONOMICON_VERSION, "1.98.1"));
        assertTrue(ClientAssistant.versionMatches(ClientAssistant.MODONOMICON_VERSION, "1.119.9"));
        assertFalse(ClientAssistant.versionMatches(ClientAssistant.MODONOMICON_VERSION, "1.98.0"));
        assertFalse(ClientAssistant.versionMatches(ClientAssistant.MODONOMICON_VERSION, "2.0.0"));

        assertTrue(ClientAssistant.versionMatches(ClientAssistant.GUIDEME_VERSION, "21.1.0"));
        assertTrue(ClientAssistant.versionMatches(ClientAssistant.GUIDEME_VERSION, "21.1.19"));
        assertFalse(ClientAssistant.versionMatches(ClientAssistant.GUIDEME_VERSION, "21.0.99"));
        assertFalse(ClientAssistant.versionMatches(ClientAssistant.GUIDEME_VERSION, "21.2.0"));

        assertTrue(ClientAssistant.versionMatches(ClientAssistant.IMMERSIVE_ENGINEERING_VERSION, "12.0.0-182"));
        assertTrue(ClientAssistant.versionMatches(ClientAssistant.IMMERSIVE_ENGINEERING_VERSION, "12.4.2-194"));
        assertFalse(ClientAssistant.versionMatches(ClientAssistant.IMMERSIVE_ENGINEERING_VERSION, "11.9.9"));
        assertFalse(ClientAssistant.versionMatches(ClientAssistant.IMMERSIVE_ENGINEERING_VERSION, "13.0.0"));

        assertTrue(ClientAssistant.versionMatches(ClientAssistant.ACTUALLY_ADDITIONS_VERSION, "1.3.0"));
        assertTrue(ClientAssistant.versionMatches(ClientAssistant.ACTUALLY_ADDITIONS_VERSION, "1.3.26"));
        assertFalse(ClientAssistant.versionMatches(ClientAssistant.ACTUALLY_ADDITIONS_VERSION, "1.2.99"));
        assertFalse(ClientAssistant.versionMatches(ClientAssistant.ACTUALLY_ADDITIONS_VERSION, "1.4.0"));

        assertTrue(ClientAssistant.versionMatches(ClientAssistant.RELICS_VERSION, "0.12.8"));
        assertTrue(ClientAssistant.versionMatches(ClientAssistant.RELICS_VERSION, "0.12.9"));
        assertFalse(ClientAssistant.versionMatches(ClientAssistant.RELICS_VERSION, "0.12.7"));
        assertFalse(ClientAssistant.versionMatches(ClientAssistant.RELICS_VERSION, "0.13.0"));

        assertTrue(ClientAssistant.versionMatches(ClientAssistant.ARTIFACTS_VERSION, "13.2.5"));
        assertTrue(ClientAssistant.versionMatches(ClientAssistant.ARTIFACTS_VERSION, "13.2.3"));
        assertTrue(ClientAssistant.versionMatches(ClientAssistant.ARTIFACTS_VERSION, "13.99.99"));
        assertFalse(ClientAssistant.versionMatches(ClientAssistant.ARTIFACTS_VERSION, "13.2.2"));
        assertFalse(ClientAssistant.versionMatches(ClientAssistant.ARTIFACTS_VERSION, "14.0.0"));

        assertFalse(ClientAssistant.versionMatches(ClientAssistant.MODONOMICON_VERSION, "1.120.7+mc1.20.1"));
        assertFalse(ClientAssistant.versionMatches(ClientAssistant.ARTIFACTS_VERSION, "13.2.5-FORGE"));
        assertFalse(ClientAssistant.versionMatches(ClientAssistant.RELICS_VERSION, "unknown"));
    }

    @Test
    void curiosCompatibilityRangeAcceptsItsMinecraftSuffixAndRejectsOtherBuilds() {
        assertTrue(ClientAssistant.versionMatches("[9.2.0,10.0.0)", "9.2.0+1.21.1"));
        assertTrue(ClientAssistant.versionMatches("[9.2.0,10.0.0)", "9.5.1+1.21.1"));
        assertFalse(ClientAssistant.versionMatches("[9.2.0,10.0.0)", "9.1.9+1.21.1"));
        assertFalse(ClientAssistant.versionMatches("[9.2.0,10.0.0)", "10.0.0+1.21.1"));
        assertFalse(ClientAssistant.versionMatches("[9.2.0,10.0.0)", "9.5.1+1.21.2"));
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
            @Override public Metadata metadata() { return new Metadata("jei", List.of(), true, List.of(), Map.of()); }
            @Override public java.util.concurrent.CompletionStage<Evidence.Result> searchRecipe(dev.packsage.model.PackSageTools.ReadRequest request, Context context) {
                return CompletableFuture.completedFuture(result);
            }
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

        assertTrue(wrapper.enabled());
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
        var registry = new dev.packsage.knowledge.AdapterRegistry();
        registry.register(wrapper);
        assertTrue(registry.metadata().getFirst().recipes());
        assertEquals(wrapped, registry.dispatch(new dev.packsage.model.PackSageTools.ReadRequest("search_recipe",
                new Evidence.JsonObject(Map.of("filter", new Evidence.JsonObject(Map.of("provider", new Evidence.JsonString("jei"))))), scope),
                new dev.packsage.knowledge.Toolbox.Context(() -> scope, Map.of(), registry, new dev.packsage.knowledge.Toolbox.DocumentBudget(), null))
                .toCompletableFuture().join());
    }

    @Test
    void providerEnablementIgnoresCoverageAndDoesNotQueryOrReadStatus() {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        KnowledgeProvider disabled = new KnowledgeProvider() {
            public String id() { return "jei"; }
            public boolean enabled() { return false; }
            public ProviderStatus status() { calls.incrementAndGet(); throw new AssertionError("status must not be read"); }
            public java.util.concurrent.CompletionStage<Evidence.Result> query(Evidence.Query query, Evidence.Scope scope) {
                calls.incrementAndGet(); throw new AssertionError("query must not run");
            }
            public Evidence.NavigationResult open(Evidence.NavigationTarget target, Evidence.Scope scope) {
                throw new AssertionError("navigation must not run");
            }
        };
        var wrapper = new ClientAssistant.ReflectiveProvider(new ClientAssistant.OptionalStatus("jei", "jei",
                "[19.27.0.340,20)", "19.57.0.450", true, true, "supported"), () -> disabled);
        var unavailable = new ClientAssistant.ReflectiveProvider(new ClientAssistant.OptionalStatus("jei", "jei",
                "range", "unknown", true, false, "not loaded"), () -> {
            calls.incrementAndGet();
            return disabled;
        });
        var failed = new ClientAssistant.ReflectiveProvider(new ClientAssistant.OptionalStatus("jei", "jei",
                "range", "unknown", true, true, "supported"), () -> { throw new LinkageError("missing class"); });

        assertFalse(wrapper.enabled());
        assertFalse(unavailable.enabled());
        assertFalse(failed.enabled());
        assertEquals(0, calls.get());
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
