package dev.packsage.knowledge.patchouli;

import dev.packsage.knowledge.Evidence;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PatchouliProviderTest {
    private static final Evidence.Scope SCOPE = new Evidence.Scope("world", "zh_tw", "team", "patchouli-1", 1);

    @Test
    void loadedEntryKeepsPageIdentityMappingsAndOpaqueCustomContent() throws Exception {
        var text = new PatchouliProvider.PageRecord(0, "text", new Evidence.JsonObject(Map.of()),
                "Localized text", List.of(), List.of(), true, Evidence.DecodingStatus.VERIFIED);
        var custom = new PatchouliProvider.PageRecord(1, "fixture:custom", new Evidence.JsonObject(Map.of(
                "payload", new Evidence.JsonString("retained"))), null, List.of("minecraft:stone"),
                List.of("fixture:recipe"), true, Evidence.DecodingStatus.OPAQUE);
        var entry = new PatchouliProvider.EntryRecord("fixture:book", "fixture:book/intro", "Introduction",
                List.of(text, custom), true, false, false, false, List.of("minecraft:stone@1"));
        var snapshot = new PatchouliProvider.Snapshot(SCOPE, 1,
                List.of(new PatchouliProvider.BookRecord("fixture:book", "Fixture", List.of(entry),
                        List.of("patchouli:loaded-entry:fixture:book/intro"))), ignored -> { });
        var provider = new PatchouliProvider(() -> snapshot, Runnable::run);
        provider.refresh();

        var result = provider.query(new Evidence.Query(Evidence.Operation.SEARCH,
                new Evidence.JsonObject(Map.of("text", new Evidence.JsonString("localized"))), 10, null, null), SCOPE)
                .toCompletableFuture().join();

        assertEquals(1, result.records().size());
        assertEquals("fixture:book/intro", result.records().getFirst().source().sourceHandle());
        assertEquals(Evidence.Coverage.OPAQUE, result.records().getFirst().source().coverage());
        assertTrue(result.records().getFirst().payload().values().containsKey("recipeMappings"));
    }

    @Test
    void navigationRequiresCurrentScopeAndVisibleLoadedEntry() {
        var opened = new AtomicReference<PatchouliProvider.Navigation>();
        var page = new PatchouliProvider.PageRecord(0, "text", new Evidence.JsonObject(Map.of()), "text",
                List.of(), List.of(), true, Evidence.DecodingStatus.VERIFIED);
        var entry = new PatchouliProvider.EntryRecord("fixture:book", "fixture:book/intro", "Intro",
                List.of(page), true, false, false, false, List.of());
        var snapshot = new PatchouliProvider.Snapshot(SCOPE, 1,
                List.of(new PatchouliProvider.BookRecord("fixture:book", "Fixture", List.of(entry), List.of())), opened::set);
        var provider = new PatchouliProvider(() -> snapshot, Runnable::run);
        provider.refresh();

        var target = new Evidence.NavigationTarget(SCOPE, PatchouliProvider.ID, "fixture:book#fixture:book/intro#0",
                "fixture:book#fixture:book/intro");
        assertEquals(Evidence.NavigationStatus.OPENED, provider.open(target, SCOPE).status());
        assertEquals("fixture:book/intro", opened.get().entryId());
        assertEquals(Evidence.NavigationStatus.STALE, provider.open(target,
                new Evidence.Scope("other", "zh_tw", "team", "patchouli-1", 1)).status());
    }
}
