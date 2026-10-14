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
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActuallyAdditionsGuideReaderTest {
    @Test
    void registersThePinnedPatchouliBookletAsAnActuallyAdditionsGuide() {
        var scope = new Evidence.Scope("world", "en_us", "solo", "client", 1);
        var provider = ActuallyAdditionsGuideReader.forMinecraft(() -> scope, Runnable::run);

        assertEquals("actuallyadditions", provider.id());
        assertEquals("1.3.26", provider.status().version());
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
        } finally {
            configField.set(null, originalConfig);
        }
    }
}
