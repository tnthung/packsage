package dev.packsage.knowledge.patchouli;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.packsage.knowledge.Evidence;
import org.junit.jupiter.api.Test;
import net.minecraft.resources.ResourceLocation;
import vazkii.patchouli.api.PatchouliConfigAccess;
import vazkii.patchouli.client.book.BookEntry;
import vazkii.patchouli.common.base.PatchouliConfig;
import vazkii.patchouli.common.book.Book;
import vazkii.patchouli.xplat.XplatModContainer;

import java.nio.file.Path;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
        assertEquals("fixture:book#fixture:book/intro", result.records().getFirst().source().sourceHandle());
        assertEquals(Evidence.Coverage.OPAQUE, result.records().getFirst().source().coverage());
        assertTrue(result.records().getFirst().payload().values().containsKey("recipeMappings"));
        var pages = (Evidence.JsonArray) result.records().getFirst().payload().values().get("pages");
        var customPayload = (Evidence.JsonObject) pages.values().get(1);
        assertEquals(new Evidence.JsonString("retained"),
                ((Evidence.JsonObject) customPayload.values().get("source")).values().get("payload"));
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
                "fixture:book#fixture:book/intro#0");
        assertEquals(Evidence.NavigationStatus.OPENED, provider.open(target, SCOPE).status());
        assertEquals("fixture:book/intro", opened.get().entryId());
        assertEquals(Evidence.NavigationStatus.STALE, provider.open(target,
                new Evidence.Scope("other", "zh_tw", "team", "patchouli-1", 1)).status());
    }

    @Test
    void localizedPagePayloadAndNavigationKeepTheMatchingPage() {
        var first = new PatchouliProvider.PageRecord(0, "text", new Evidence.JsonObject(Map.of(
                "text", new Evidence.JsonString("first"))), "first", List.of(), List.of(), true,
                Evidence.DecodingStatus.VERIFIED);
        var later = new PatchouliProvider.PageRecord(1, "text", new Evidence.JsonObject(Map.of(
                "text", new Evidence.JsonString("later"))), "later", List.of(), List.of(), true,
                Evidence.DecodingStatus.VERIFIED);
        var opened = new AtomicReference<PatchouliProvider.Navigation>();
        var entry = new PatchouliProvider.EntryRecord("fixture:book", "fixture:book/intro", "Intro",
                List.of(first, later), true, false, false, false, List.of());
        var snapshot = new PatchouliProvider.Snapshot(SCOPE, 1,
                List.of(new PatchouliProvider.BookRecord("fixture:book", "Fixture", List.of(entry), List.of())),
                opened::set);
        var provider = new PatchouliProvider(() -> snapshot, Runnable::run);
        provider.refresh();

        var result = provider.query(new Evidence.Query(Evidence.Operation.SEARCH,
                new Evidence.JsonObject(Map.of("text", new Evidence.JsonString("later"))), 10, null, null), SCOPE)
                .toCompletableFuture().join();

        var payload = (Evidence.JsonArray) result.records().getFirst().payload().values().get("pages");
        var page = (Evidence.JsonObject) payload.values().stream().map(value -> (Evidence.JsonObject) value)
                .filter(value -> value.values().get("index").equals(new Evidence.JsonNumber(java.math.BigDecimal.ONE)))
                .findFirst().orElseThrow();
        assertEquals(new Evidence.JsonString("later"), page.values().get("text"));
        var target = result.records().getFirst().targets().stream()
                .filter(value -> value.targetId().endsWith("#1")).findFirst().orElseThrow();
        assertEquals(Evidence.NavigationStatus.OPENED, provider.open(target, SCOPE).status());
        assertEquals(1, opened.get().page());
    }

    @Test
    void gatedEntriesAndPagesStayOutOfSearchAndNavigation() {
        var lockedPage = new PatchouliProvider.PageRecord(1, "text", new Evidence.JsonObject(Map.of(
                "text", new Evidence.JsonString("secret page"))), "secret page", List.of(), List.of(), false,
                Evidence.DecodingStatus.VERIFIED);
        var visibleEntry = new PatchouliProvider.EntryRecord("fixture:book", "fixture:book/visible", "Visible",
                List.of(lockedPage), true, false, false, false, List.of());
        var lockedEntry = new PatchouliProvider.EntryRecord("fixture:book", "fixture:book/locked", "Secret entry",
                List.of(new PatchouliProvider.PageRecord(0, "text", new Evidence.JsonObject(Map.of()),
                        "secret entry", List.of(), List.of(), true, Evidence.DecodingStatus.VERIFIED)), false, true,
                true, true, List.of());
        var opened = new AtomicReference<PatchouliProvider.Navigation>();
        var snapshot = new PatchouliProvider.Snapshot(SCOPE, 1,
                List.of(new PatchouliProvider.BookRecord("fixture:book", "Fixture",
                        List.of(visibleEntry, lockedEntry), List.of())), opened::set);
        var provider = new PatchouliProvider(() -> snapshot, Runnable::run);
        provider.refresh();

        var result = provider.query(new Evidence.Query(Evidence.Operation.SEARCH,
                new Evidence.JsonObject(Map.of("text", new Evidence.JsonString("secret"))), 10, null, null), SCOPE)
                .toCompletableFuture().join();

        assertTrue(result.records().isEmpty());
        var lockedTarget = new Evidence.NavigationTarget(SCOPE, PatchouliProvider.ID,
                "fixture:book#fixture:book/visible#1", "fixture:book#fixture:book/visible#1");
        assertTrue(provider.open(lockedTarget, SCOPE).status() != Evidence.NavigationStatus.OPENED);
    }

    @Test
    void filteredCursorsAdvanceByScannedPosition() {
        var entries = new java.util.ArrayList<PatchouliProvider.EntryRecord>();
        for (var index = 0; index < 300; index++) {
            entries.add(new PatchouliProvider.EntryRecord("fixture:book", "fixture:book/entry" + index,
                    "Entry " + index, List.of(), true, false, false, false, List.of()));
        }
        entries.set(299, new PatchouliProvider.EntryRecord("fixture:book", "fixture:book/entry299", "needle",
                List.of(), true, false, false, false, List.of()));
        var snapshot = new PatchouliProvider.Snapshot(SCOPE, 1,
                List.of(new PatchouliProvider.BookRecord("fixture:book", "Fixture", entries, List.of())),
                ignored -> { });
        var provider = new PatchouliProvider(() -> snapshot, Runnable::run);
        provider.refresh();

        var first = provider.query(new Evidence.Query(Evidence.Operation.SEARCH,
                new Evidence.JsonObject(Map.of("text", new Evidence.JsonString("needle"))), 1, null, null), SCOPE)
                .toCompletableFuture().join();

        assertTrue(first.records().isEmpty());
        assertEquals("256", first.nextCursor());
        var second = provider.query(new Evidence.Query(Evidence.Operation.SEARCH,
                new Evidence.JsonObject(Map.of("text", new Evidence.JsonString("needle"))), 1, SCOPE,
                first.nextCursor()), SCOPE).toCompletableFuture().join();
        assertEquals(1, second.records().size());
        assertEquals("fixture:book#fixture:book/entry299", second.records().getFirst().source().sourceHandle());
        assertEquals(null, second.nextCursor());
        assertEquals(Evidence.Coverage.ERROR, provider.query(new Evidence.Query(Evidence.Operation.SEARCH,
                new Evidence.JsonObject(Map.of("text", new Evidence.JsonString("needle"))), 1, SCOPE, "-1"), SCOPE)
                .toCompletableFuture().join().coverage());
        assertEquals(Evidence.Coverage.ERROR, provider.query(new Evidence.Query(Evidence.Operation.SEARCH,
                new Evidence.JsonObject(Map.of("text", new Evidence.JsonString("needle"))), 1, SCOPE, "9999"), SCOPE)
                .toCompletableFuture().join().coverage());
    }

    @Test
    void captureFailureIsReturnedAsAnErrorResult() {
        var provider = new PatchouliProvider(() -> { throw new IllegalStateException("capture failed"); }, Runnable::run);

        var result = provider.query(new Evidence.Query(Evidence.Operation.SEARCH,
                new Evidence.JsonObject(Map.of("text", new Evidence.JsonString("anything"))), 1, null, null), SCOPE)
                .toCompletableFuture().join();

        assertEquals(Evidence.Coverage.ERROR, result.coverage());
        assertEquals(Evidence.Outcome.ERROR, result.outcome());
        assertTrue(result.detail() != null && result.detail().contains("capture failed"), result.detail());
        assertEquals(Evidence.Coverage.ERROR, provider.query(new Evidence.Query(Evidence.Operation.GET,
                new Evidence.JsonObject(Map.of()), 1, null, null), SCOPE).toCompletableFuture().join().coverage());
    }

    @Test
    void captureEntryAcceptsListenerEntriesWithoutResourceProvenance() throws Exception {
        var configField = PatchouliConfig.class.getDeclaredField("access");
        configField.setAccessible(true);
        var originalConfig = (PatchouliConfigAccess) configField.get(null);
        configField.set(null, new PatchouliConfigAccess() {
            @Override public boolean disableAdvancementLocking() { return true; }
            @Override public List<String> noAdvancementBooks() { return List.of(); }
            @Override public boolean testingMode() { return false; }
            @Override public String inventoryButtonBook() { return ""; }
            @Override public boolean useShiftForQuickLookup() { return false; }
            @Override public PatchouliConfigAccess.TextOverflowMode overflowMode() { return PatchouliConfigAccess.TextOverflowMode.OVERFLOW; }
            @Override public int quickLookupTime() { return 0; }
        });
        try {
            var bookJson = new JsonObject();
            bookJson.addProperty("name", "Fixture");
            bookJson.addProperty("use_resource_pack", true);
            var book = new Book(bookJson, new XplatModContainer() {
                @Override public String getId() { return "fixture"; }
                @Override public String getName() { return "Fixture"; }
                @Override public Path getPath(String ignored) { return Path.of("."); }
                @Override public List<Path> getRootPaths() { return List.of(Path.of(".")); }
            }, ResourceLocation.parse("fixture:book"), false);
            var entryJson = new JsonObject();
            entryJson.addProperty("category", "fixture:category");
            entryJson.addProperty("name", "Aura");
            entryJson.addProperty("icon", "fixture:textures/item/aura.png");
            entryJson.add("pages", new JsonArray());
            var entry = new BookEntry(entryJson, ResourceLocation.parse("fixture:book/aura"), book, null);
            entry.updateLockStatus();
            assertNull(entry.getAddedBy());

            Method captureEntry = PatchouliProvider.Snapshot.class.getDeclaredMethod("captureEntry", Book.class, BookEntry.class);
            captureEntry.setAccessible(true);
            var captured = (PatchouliProvider.EntryRecord) captureEntry.invoke(null, book, entry);

            assertEquals("fixture:book/aura", captured.id());
            assertEquals("Aura", captured.title());
            assertTrue(captured.visible());
            assertEquals(List.of(), captured.provenance());
            assertEquals(Evidence.Coverage.COMPLETE, captured.coverage(), captured.failure());
        } finally {
            configField.set(null, originalConfig);
        }
    }

    @Test
    void scopeMismatchReturnsAUsefulPartialDiagnostic() {
        var provider = new PatchouliProvider(() -> new PatchouliProvider.Snapshot(
                new Evidence.Scope("other-world", "zh_tw", "team", "patchouli-1", 1), 1, List.of(), ignored -> { }), Runnable::run);

        var result = provider.query(new Evidence.Query(Evidence.Operation.SEARCH,
                new Evidence.JsonObject(Map.of("text", new Evidence.JsonString("anything"))), 1, null, null), SCOPE)
                .toCompletableFuture().join();

        assertEquals(Evidence.Coverage.PARTIAL, result.coverage());
        assertEquals(Evidence.Outcome.UNKNOWN, result.outcome());
        assertTrue(result.detail() != null && result.detail().contains("scope"), result.detail());
    }

    @Test
    void failedBookCoverageDoesNotHideAnUnaffectedSiblingRecord() {
        var page = new PatchouliProvider.PageRecord(0, "text", new Evidence.JsonObject(Map.of()), "sibling",
                List.of(), List.of(), true, Evidence.DecodingStatus.VERIFIED);
        var sibling = new PatchouliProvider.EntryRecord("fixture:book", "fixture:book/sibling", "Sibling",
                List.of(page), true, false, false, false, List.of());
        var snapshot = new PatchouliProvider.Snapshot(SCOPE, 1,
                List.of(new PatchouliProvider.BookRecord("fixture:book", "Fixture", List.of(sibling), List.of(),
                        Evidence.Coverage.ERROR, "one entry failed")), ignored -> { }, Evidence.Coverage.ERROR,
                "one entry failed");
        var provider = new PatchouliProvider(() -> snapshot, Runnable::run);

        var result = provider.query(new Evidence.Query(Evidence.Operation.SEARCH,
                new Evidence.JsonObject(Map.of("text", new Evidence.JsonString("sibling"))), 1, null, null), SCOPE)
                .toCompletableFuture().join();

        assertEquals(1, result.records().size());
        assertEquals(Evidence.Coverage.ERROR, result.coverage());
    }

    @Test
    void refreshDispatchesCaptureAndInvalidatesOldTargets() throws Exception {
        var opened = new AtomicReference<PatchouliProvider.Navigation>();
        var firstEntry = new PatchouliProvider.EntryRecord("fixture:book", "fixture:book/intro", "Intro",
                List.of(new PatchouliProvider.PageRecord(0, "text", new Evidence.JsonObject(Map.of()), "text",
                        List.of(), List.of(), true, Evidence.DecodingStatus.VERIFIED)), true, false, false, false,
                List.of());
        var secondEntry = new PatchouliProvider.EntryRecord("fixture:book", "fixture:book/intro", "Reloaded",
                List.of(new PatchouliProvider.PageRecord(0, "text", new Evidence.JsonObject(Map.of()), "reloaded",
                        List.of(), List.of(), true, Evidence.DecodingStatus.VERIFIED)), true, false, false, false,
                List.of());
        var snapshots = new AtomicReference<>(new PatchouliProvider.Snapshot(SCOPE, 1,
                List.of(new PatchouliProvider.BookRecord("fixture:book", "Fixture", List.of(firstEntry), List.of())),
                opened::set));
        var provider = new PatchouliProvider(snapshots::get, Runnable::run);
        provider.refresh();
        var oldTarget = new Evidence.NavigationTarget(SCOPE, PatchouliProvider.ID,
                "fixture:book#fixture:book/intro#0@lifecycle=1", "fixture:book#fixture:book/intro#0");
        provider.invalidate();
        assertEquals(Evidence.NavigationStatus.STALE, provider.open(oldTarget, SCOPE).status());
        snapshots.set(new PatchouliProvider.Snapshot(SCOPE, 1,
                List.of(new PatchouliProvider.BookRecord("fixture:book", "Fixture", List.of(secondEntry), List.of())),
                opened::set));
        provider.refresh();

        assertEquals(Evidence.NavigationStatus.STALE, provider.open(oldTarget, SCOPE).status());
        assertEquals("Reloaded", provider.query(new Evidence.Query(Evidence.Operation.GET,
                new Evidence.JsonObject(Map.of("id", new Evidence.JsonString("fixture:book/intro"))), 1, null, null), SCOPE)
                .toCompletableFuture().join().records().getFirst().title());
    }

    @Test
    void navigationRejectsMismatchedMalformedOutOfRangeAndLockedHandles() {
        var pages = List.of(
                new PatchouliProvider.PageRecord(0, "text", new Evidence.JsonObject(Map.of()), "first",
                        List.of(), List.of(), true, Evidence.DecodingStatus.VERIFIED),
                new PatchouliProvider.PageRecord(1, "text", new Evidence.JsonObject(Map.of()), "locked",
                        List.of(), List.of(), false, Evidence.DecodingStatus.VERIFIED));
        var opened = new AtomicReference<PatchouliProvider.Navigation>();
        var entry = new PatchouliProvider.EntryRecord("fixture:book", "fixture:book/intro", "Intro", pages,
                true, false, false, false, List.of());
        var snapshot = new PatchouliProvider.Snapshot(SCOPE, 1,
                List.of(new PatchouliProvider.BookRecord("fixture:book", "Fixture", List.of(entry), List.of())),
                opened::set);
        var provider = new PatchouliProvider(() -> snapshot, Runnable::run);
        provider.refresh();

        assertTrue(provider.open(new Evidence.NavigationTarget(SCOPE, PatchouliProvider.ID,
                "other:book#other:entry#0", "fixture:book#fixture:book/intro#0"), SCOPE)
                .status() != Evidence.NavigationStatus.OPENED);
        assertTrue(provider.open(new Evidence.NavigationTarget(SCOPE, PatchouliProvider.ID,
                "fixture:book#fixture:book/intro#bad", "fixture:book#fixture:book/intro#bad"), SCOPE)
                .status() != Evidence.NavigationStatus.OPENED);
        assertTrue(provider.open(new Evidence.NavigationTarget(SCOPE, PatchouliProvider.ID,
                "fixture:book#fixture:book/intro#2", "fixture:book#fixture:book/intro#2"), SCOPE)
                .status() != Evidence.NavigationStatus.OPENED);
        assertTrue(provider.open(new Evidence.NavigationTarget(SCOPE, PatchouliProvider.ID,
                "fixture:book#fixture:book/intro#1", "fixture:book#fixture:book/intro#1"), SCOPE)
                .status() != Evidence.NavigationStatus.OPENED);
    }

    @Test
    void navigationRunsOnTheClientExecutor() {
        var opened = new AtomicReference<String>();
        ExecutorService client = Executors.newSingleThreadExecutor(r -> new Thread(r, "patchouli-client"));
        try {
            var page = new PatchouliProvider.PageRecord(0, "text", new Evidence.JsonObject(Map.of()), "text",
                    List.of(), List.of(), true, Evidence.DecodingStatus.VERIFIED);
            var entry = new PatchouliProvider.EntryRecord("fixture:book", "fixture:book/intro", "Intro",
                    List.of(page), true, false, false, false, List.of());
            var snapshot = new PatchouliProvider.Snapshot(SCOPE, 1,
                    List.of(new PatchouliProvider.BookRecord("fixture:book", "Fixture", List.of(entry), List.of())),
                    ignored -> opened.set(Thread.currentThread().getName()));
            var provider = new PatchouliProvider(() -> snapshot, client);
            provider.refresh();
            provider.query(new Evidence.Query(Evidence.Operation.SEARCH,
                    new Evidence.JsonObject(Map.of("text", new Evidence.JsonString("text"))), 1, null, null), SCOPE)
                    .toCompletableFuture().join();
            var target = new Evidence.NavigationTarget(SCOPE, PatchouliProvider.ID,
                    "fixture:book#fixture:book/intro#0", "fixture:book#fixture:book/intro#0");

            assertEquals(Evidence.NavigationStatus.OPENED, provider.open(target, SCOPE).status());
            assertEquals("patchouli-client", opened.get());
        } finally {
            client.shutdownNow();
        }
    }
}
