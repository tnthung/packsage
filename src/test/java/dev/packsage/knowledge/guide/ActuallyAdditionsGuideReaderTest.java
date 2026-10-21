package dev.packsage.knowledge.guide;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.packsage.knowledge.Evidence;
import dev.packsage.knowledge.Toolbox;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import vazkii.patchouli.api.PatchouliConfigAccess;
import vazkii.patchouli.client.book.BookEntry;
import vazkii.patchouli.client.book.page.PageText;
import vazkii.patchouli.common.base.PatchouliConfig;
import vazkii.patchouli.common.book.Book;
import vazkii.patchouli.xplat.XplatModContainer;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActuallyAdditionsGuideReaderTest {
    @Test
    void registersTheObservedPatchouliBookletAsAnActuallyAdditionsGuide() {
        var scope = new Evidence.Scope("world", "en_us", "solo", "client", 1);
        var provider = ActuallyAdditionsGuideReader.forMinecraft(() -> scope, Runnable::run, "1.3.27");

        assertEquals("actuallyadditions", provider.id());
        assertEquals("1.3.27", provider.status().version());
        assertTrue(provider.metadata().documentTypes().contains("guide"));
    }

    @Test
    void readsCurrentPatchouliTextAcrossBoundedFragments() throws Exception {
        Field configField = PatchouliConfig.class.getDeclaredField("access");
        configField.setAccessible(true);
        var originalConfig = configField.get(null);
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
                @Override public String getId() { return "actuallyadditions"; }
                @Override public String getName() { return "Actually Additions"; }
                @Override public Path getPath(String ignored) { return Path.of("."); }
                @Override public List<Path> getRootPaths() { return List.of(Path.of(".")); }
            }, ResourceLocation.parse("actuallyadditions:booklet"), false);
            var entryJson = new JsonObject();
            entryJson.addProperty("category", "actuallyadditions:category");
            entryJson.addProperty("name", "Fixture page");
            entryJson.addProperty("icon", "fixture:textures/item/book.png");
            entryJson.add("pages", new JsonArray());
            var entryId = ResourceLocation.parse("actuallyadditions:booklet/fixture");
            BookEntry entry;
            try {
                entry = (BookEntry) BookEntry.class.getConstructor(JsonObject.class, ResourceLocation.class,
                        Book.class, String.class).newInstance(entryJson, entryId, book, null);
            } catch (NoSuchMethodException newerConstructor) {
                entry = (BookEntry) BookEntry.class.getConstructor(JsonObject.class, ResourceLocation.class,
                        Book.class, String.class, HolderLookup.Provider.class).newInstance(entryJson, entryId, book, null,
                        RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
            }
            entry.updateLockStatus();
            var page = new PageText();
            page.sourceObject = new JsonObject();
            page.sourceObject.addProperty("type", "patchouli:text");
            page.sourceObject.addProperty("text", "a".repeat(128));
            Field realPages = BookEntry.class.getDeclaredField("realPages");
            realPages.setAccessible(true);
            realPages.set(entry, List.of(page));
            var secondEntryId = ResourceLocation.parse("actuallyadditions:booklet/second");
            BookEntry secondEntry;
            try {
                secondEntry = (BookEntry) BookEntry.class.getConstructor(JsonObject.class, ResourceLocation.class,
                        Book.class, String.class).newInstance(entryJson.deepCopy(), secondEntryId, book, null);
            } catch (NoSuchMethodException newerConstructor) {
                secondEntry = (BookEntry) BookEntry.class.getConstructor(JsonObject.class, ResourceLocation.class,
                        Book.class, String.class, HolderLookup.Provider.class).newInstance(entryJson.deepCopy(), secondEntryId,
                        book, null, RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
            }
            secondEntry.updateLockStatus();
            var contents = new vazkii.patchouli.client.book.BookContents(book, com.google.common.collect.ImmutableMap.of(),
                    com.google.common.collect.ImmutableMap.of(entryId, entry, secondEntryId, secondEntry), com.google.common.collect.ImmutableMap.of(), null);
            Field bookContents = Book.class.getDeclaredField("contents");
            bookContents.setAccessible(true);
            bookContents.set(book, contents);
            var reader = new ActuallyAdditionsGuideReader(() -> book);
            var found = reader.candidates(null, 1, new Toolbox.DocumentBudget());

            assertEquals(List.of("actuallyadditions:booklet/fixture"), found.candidates().stream().map(GuideDocumentReader.Candidate::id).toList());
            var first = reader.readFragment(entryId.toString(), null, 16, new Toolbox.DocumentBudget());
            assertEquals(16, first.text().length());
            assertTrue(first.nextPosition() != null);
            page.sourceObject.addProperty("text", "a".repeat(16) + "Changed text" + "b".repeat(100));
            var next = reader.readFragment(entryId.toString(), first.nextPosition(), 64, new Toolbox.DocumentBudget());

            assertTrue(next.text().length() <= 64);
            assertTrue(next.overlapChars() > 0);
            assertTrue(next.text().contains("Changed text"));

            var exhausted = new Toolbox.DocumentBudget();
            assertTrue(exhausted.bytes(96 * 1024 - 128));
            var deferred = reader.candidates(null, 1, exhausted);
            assertTrue(deferred.candidates().isEmpty());
            assertTrue(deferred.more());
            var firstPage = reader.candidates(deferred.nextPosition(), 1, new Toolbox.DocumentBudget());
            var secondPage = reader.candidates(firstPage.nextPosition(), 1, new Toolbox.DocumentBudget());
            assertEquals(List.of(entryId.toString()), firstPage.candidates().stream().map(GuideDocumentReader.Candidate::id).toList());
            assertEquals(List.of(secondEntryId.toString()), secondPage.candidates().stream().map(GuideDocumentReader.Candidate::id).toList());

            var craftingPage = new vazkii.patchouli.client.book.page.PageCrafting();
            craftingPage.sourceObject = new JsonObject();
            craftingPage.sourceObject.addProperty("type", "patchouli:crafting");
            craftingPage.sourceObject.addProperty("recipe", "fixture:recipe");
            var followingPage = new PageText();
            followingPage.sourceObject = new JsonObject();
            followingPage.sourceObject.addProperty("type", "patchouli:text");
            followingPage.sourceObject.addProperty("text", "text after crafting page");
            realPages.set(entry, List.of(page, craftingPage, followingPage));
            var emptyPage = reader.readFragment(entryId.toString(), "1,0,0,0", 64, new Toolbox.DocumentBudget());
            assertEquals("", emptyPage.text());
            assertEquals("2,0,0,0", emptyPage.nextPosition());
            var afterEmptyPage = reader.readFragment(entryId.toString(), emptyPage.nextPosition(), 64, new Toolbox.DocumentBudget());
            assertEquals("text after crafting page", afterEmptyPage.text());

            var boundedBudget = new Toolbox.DocumentBudget();
            assertTrue(boundedBudget.bytes(96 * 1024 - entryId.toString().length() * 6 - 128 - 2048));
            var failure = assertThrows(Toolbox.Failure.class,
                    () -> reader.readFragment(entryId.toString(), "0,0,0,0", 64, boundedBudget));
            assertTrue(boundedBudget.exhausted());
            assertTrue(failure.getMessage().contains("Patchouli continuation cannot advance within the read budget"));

            var seekBudget = new Toolbox.DocumentBudget();
            assertTrue(seekBudget.bytes(96 * 1024 - entryId.toString().length() * 6 - 128 - 2048));
            var seekFailure = assertThrows(Toolbox.Failure.class,
                    () -> reader.readFragment(entryId.toString(), "1,0,0,0", 64, seekBudget));
            assertEquals(Toolbox.Code.EXTRACTION_FAILED, seekFailure.code());
            assertTrue(seekBudget.exhausted());

            var outOfRangeFailure = assertThrows(Toolbox.Failure.class,
                    () -> reader.readFragment(entryId.toString(), "4,0,0,0", 64, new Toolbox.DocumentBudget()));
            assertEquals(Toolbox.Code.INVALID_CURSOR, outOfRangeFailure.code());
        } finally {
            configField.set(null, originalConfig);
        }
    }

    private static final Evidence.Scope SCOPE = new Evidence.Scope("world", "en_us", "solo", "client", 1);
    private static final String INTRO = "actuallyadditions:intro";

    @Test
    void navigationTargetsUseEntryIdentity() {
        var reader = new ActuallyAdditionsGuideReader(() -> FakeBook.of(new FakeEntry(INTRO, false, false)), "1.3.26",
                new RecordingOpener());

        assertEquals(List.of(new Evidence.NavigationTarget(SCOPE, "actuallyadditions", INTRO, INTRO)),
                reader.navigationTargets(SCOPE, new GuideDocumentReader.Candidate(INTRO, "Intro", "0|" + INTRO,
                        List.of(), Evidence.Coverage.COMPLETE)));
    }

    @Test
    void savedEntryResolvesAfterContentsRebuild() {
        var current = new java.util.concurrent.atomic.AtomicReference<Object>(FakeBook.of(new FakeEntry(INTRO, false, false)));
        var opener = new RecordingOpener();
        var reader = new ActuallyAdditionsGuideReader(current::get, "1.3.26", opener);
        var saved = new Evidence.NavigationTarget(SCOPE, "actuallyadditions", "old", INTRO);
        current.set(FakeBook.of(new FakeEntry(INTRO, false, false)));

        var refreshed = reader.refreshNavigation(saved, SCOPE);

        assertEquals(new Evidence.NavigationTarget(SCOPE, "actuallyadditions", INTRO, INTRO), refreshed);
        assertEquals(Evidence.NavigationStatus.OPENED, reader.open(refreshed, SCOPE).status());
        assertEquals(List.of(List.of("actuallyadditions:booklet", INTRO, 0)), opener.calls);
    }

    @Test
    void removedEntryIsUnavailableWithoutNativeCall() {
        var current = new java.util.concurrent.atomic.AtomicReference<Object>(FakeBook.of(new FakeEntry(INTRO, false, false)));
        var opener = new RecordingOpener();
        var reader = new ActuallyAdditionsGuideReader(current::get, "1.3.26", opener);
        var refreshed = reader.refreshNavigation(new Evidence.NavigationTarget(SCOPE, "actuallyadditions", "old", INTRO), SCOPE);
        current.set(FakeBook.of(new FakeEntry("actuallyadditions:other", false, false)));

        assertNull(reader.refreshNavigation(refreshed, SCOPE));
        assertEquals(Evidence.NavigationStatus.UNAVAILABLE, reader.open(refreshed, SCOPE).status());
        current.set(null);
        assertEquals(Evidence.NavigationStatus.UNAVAILABLE, reader.open(refreshed, SCOPE).status());
        current.set(new FakeBook(new FakeContents(true, java.util.Map.of())));
        assertEquals(Evidence.NavigationStatus.UNAVAILABLE, reader.open(refreshed, SCOPE).status());
        assertEquals(List.of(), opener.calls);
    }

    @Test
    void refreshRejectsMalformedWrongProviderHiddenAndLockedEntries() {
        var opener = new RecordingOpener();
        var reader = new ActuallyAdditionsGuideReader(() -> FakeBook.of(new FakeEntry(INTRO, false, false),
                new FakeEntry("actuallyadditions:hidden", true, false), new FakeEntry("actuallyadditions:locked", false, true)),
                "1.3.26", opener);

        for (var targetId : List.of("not a resource id", "actuallyadditions:booklet#" + INTRO + "#0", INTRO + "#0",
                "actuallyadditions:hidden", "actuallyadditions:locked", "actuallyadditions:missing")) {
            assertNull(reader.refreshNavigation(new Evidence.NavigationTarget(SCOPE, "actuallyadditions", "old", targetId), SCOPE),
                    targetId);
        }
        assertNull(reader.refreshNavigation(new Evidence.NavigationTarget(SCOPE, "patchouli", "old", INTRO), SCOPE));
        assertEquals(List.of(), opener.calls);
    }

    @Test
    void openReportsStaleScopeOffThreadChangedHandleAndError() {
        var opener = new RecordingOpener();
        var reader = new ActuallyAdditionsGuideReader(() -> FakeBook.of(new FakeEntry(INTRO, false, false)), "1.3.26", opener);
        var current = new Evidence.NavigationTarget(SCOPE, "actuallyadditions", INTRO, INTRO);

        assertEquals(Evidence.NavigationStatus.STALE, reader.open(current,
                new Evidence.Scope("other", "en_us", "solo", "client", 1)).status());
        opener.clientThread = false;
        assertEquals(Evidence.NavigationStatus.UNSUPPORTED, reader.open(current, SCOPE).status());
        opener.clientThread = true;
        assertEquals(Evidence.NavigationStatus.STALE, reader.open(new Evidence.NavigationTarget(SCOPE, "actuallyadditions",
                "actuallyadditions:other", INTRO), SCOPE).status());
        assertEquals(List.of(), opener.calls);
        opener.failure = new NullPointerException("entry");
        assertEquals(Evidence.NavigationStatus.ERROR, reader.open(current, SCOPE).status());
    }

    private static final class RecordingOpener implements ActuallyAdditionsGuideReader.EntryOpener {
        private final List<List<Object>> calls = new java.util.ArrayList<>();
        private boolean clientThread = true;
        private RuntimeException failure;

        @Override public boolean isClientThread() { return clientThread; }

        @Override public void openEntry(ResourceLocation book, ResourceLocation entry, int page) {
            if (failure != null) throw failure;
            calls.add(List.of(book.toString(), entry.toString(), page));
        }
    }

    public record FakeBook(FakeContents contents) {
        static FakeBook of(FakeEntry... entries) {
            var map = new java.util.LinkedHashMap<ResourceLocation, FakeEntry>();
            for (var entry : entries) map.put(entry.getId(), entry);
            return new FakeBook(new FakeContents(false, map));
        }

        public FakeContents getContents() { return contents; }
    }

    public static final class FakeContents {
        public final java.util.Map<ResourceLocation, FakeEntry> entries;
        private final boolean errored;

        FakeContents(boolean errored, java.util.Map<ResourceLocation, FakeEntry> entries) {
            this.errored = errored;
            this.entries = entries;
        }

        public boolean isErrored() { return errored; }
    }

    public static final class FakeEntry {
        private final ResourceLocation id;
        private final boolean hidden;
        private final boolean locked;

        FakeEntry(String id, boolean hidden, boolean locked) {
            this.id = ResourceLocation.parse(id);
            this.hidden = hidden;
            this.locked = locked;
        }

        public ResourceLocation getId() { return id; }

        public boolean shouldHide() { return hidden; }

        public boolean isLocked() { return locked; }
    }
}
