package dev.packsage.knowledge.guide;

import com.klikli_dev.modonomicon.book.Book;
import com.klikli_dev.modonomicon.book.BookCategory;
import com.klikli_dev.modonomicon.book.BookTextHolder;
import com.klikli_dev.modonomicon.book.entries.BookEntry;
import com.klikli_dev.modonomicon.book.page.BookPage;
import com.klikli_dev.modonomicon.book.page.BookTextPage;
import com.klikli_dev.modonomicon.bookstate.BookUnlockStateManager;
import com.klikli_dev.modonomicon.data.BookDataManager;
import dev.packsage.knowledge.Evidence;
import dev.packsage.knowledge.KnowledgeProvider;
import dev.packsage.knowledge.Toolbox;
import net.minecraft.client.Minecraft;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.ComponentContents;
import net.minecraft.network.chat.contents.PlainTextContents;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/** Bounded reads from the currently loaded Modonomicon books. */
public final class ModonomiconGuideReader implements GuideDocumentReader {
    private final String readerVersion;
    private static final String SEPARATOR = "|";

    record LocalizedText(String text, boolean partial) { }

    ModonomiconGuideReader() { this("unknown"); }

    ModonomiconGuideReader(String version) { this.readerVersion = version; }

    public static KnowledgeProvider forMinecraft(Supplier<Evidence.Scope> currentScope, Executor clientExecutor, String actualVersion) {
        return new GuideDocumentProvider(new ModonomiconGuideReader(actualVersion), currentScope, clientExecutor);
    }

    @Override public String providerId() { return "modonomicon"; }

    @Override public String version() { return readerVersion; }

    @Override public String sourceIdentity(Evidence.Scope scope) {
        var manager = BookDataManager.get();
        var books = manager.getBooks();
        return Integer.toHexString(scope.hashCode()) + ":" + System.identityHashCode(books) + ":"
                + manager.isLoaded() + ":" + manager.areBooksBuilt() + ":" + books.size();
    }

    @Override public CandidatePage candidates(String afterPosition, int limit, Toolbox.DocumentBudget budget) {
        if (limit < 1) throw new IllegalArgumentException("candidate limit must be positive");
        var manager = BookDataManager.get();
        if (!manager.isLoaded() || !manager.areBooksBuilt()) throw new Toolbox.Failure(Toolbox.Code.UNAVAILABLE,
                "Modonomicon books are not loaded and built");
        var player = Minecraft.getInstance().player;
        if (player == null) throw new Toolbox.Failure(Toolbox.Code.UNAVAILABLE, "Modonomicon visibility requires a local player");
        var unlocks = BookUnlockStateManager.get();
        var candidates = new java.util.ArrayList<Candidate>();
        var afterFound = afterPosition == null;
        var more = false;
        String nextPosition = afterPosition;
        var coverage = Evidence.Coverage.COMPLETE;
        for (Book book : manager.getBooks().values()) {
            if (!budget.value()) {
                if (!afterFound || nextPosition == null || nextPosition.equals(afterPosition)) throw new Toolbox.Failure(
                        Toolbox.Code.EXTRACTION_FAILED, "Modonomicon book traversal cannot advance within its value budget");
                return new CandidatePage(candidates, nextPosition, true, Evidence.Coverage.PARTIAL,
                        "Modonomicon book traversal reached its live source budget");
            }
            var bookPosition = "@book|" + book.getId();
            if (!afterFound) {
                nextPosition = bookPosition;
                if (bookPosition.equals(afterPosition)) afterFound = true;
            }
            nextPosition = bookPosition;
            for (BookCategory category : book.getCategories().values()) {
                if (!budget.value()) {
                    if (!afterFound || nextPosition.equals(afterPosition)) throw new Toolbox.Failure(
                            Toolbox.Code.EXTRACTION_FAILED, "Modonomicon category traversal cannot advance within its value budget");
                    return new CandidatePage(candidates, nextPosition, true, Evidence.Coverage.PARTIAL,
                            "Modonomicon category traversal reached its live source budget");
                }
                var categoryPosition = "@category|" + book.getId() + "|" + category.getId();
                if (!afterFound) {
                    nextPosition = categoryPosition;
                    if (categoryPosition.equals(afterPosition)) afterFound = true;
                }
                nextPosition = categoryPosition;
                for (BookEntry entry : category.getEntries().values()) {
                    if (!budget.value()) {
                        if (!afterFound || nextPosition.equals(afterPosition)) throw new Toolbox.Failure(
                                Toolbox.Code.EXTRACTION_FAILED, "Modonomicon entry traversal cannot advance within its value budget");
                        return new CandidatePage(candidates, nextPosition, true, Evidence.Coverage.PARTIAL,
                                "Modonomicon entry traversal reached its live source budget");
                    }
                    var id = book.getId() + SEPARATOR + category.getId() + SEPARATOR + entry.getId();
                    if (!afterFound) {
                        nextPosition = id;
                        if (id.equals(afterPosition)) afterFound = true;
                        continue;
                    }
                    nextPosition = id;
                    if (!unlocks.isUnlockedFor(player, category) || !unlocks.isUnlockedFor(player, entry)) continue;
                    if (!budget.bytes(id.length() * 6 + 2048)) {
                        if (candidates.isEmpty()) throw new Toolbox.Failure(Toolbox.Code.EXTRACTION_FAILED,
                                "Modonomicon candidate metadata cannot fit the remaining byte budget");
                        return new CandidatePage(candidates, candidates.getLast().sourcePosition(), true,
                                Evidence.Coverage.PARTIAL, "Modonomicon candidate metadata reached its byte budget");
                    }
                    var title = entry.getName();
                    var candidateCoverage = Evidence.Coverage.COMPLETE;
                    if (title.length() > 256) {
                        title = title.substring(0, 256);
                        coverage = Evidence.Coverage.PARTIAL;
                        candidateCoverage = Evidence.Coverage.PARTIAL;
                    }
                    if (candidates.size() == limit) {
                        more = true;
                        nextPosition = candidates.getLast().sourcePosition();
                        return new CandidatePage(candidates, nextPosition, more, coverage, null);
                    }
                    candidates.add(new Candidate(id, title, id, List.of("modonomicon:book:" + book.getId(),
                            "modonomicon:category:" + category.getId()), candidateCoverage));
                }
            }
        }
        if (!afterFound && afterPosition != null) throw new Toolbox.Failure(Toolbox.Code.INVALID_CURSOR,
                "Modonomicon continuation entry is no longer loaded");
        return new CandidatePage(candidates, nextPosition, more, coverage, null);
    }

    @Override public Fragment readFragment(String id, String fragmentPosition, int maxChars, Toolbox.DocumentBudget budget) {
        if (maxChars < 1) throw new IllegalArgumentException("fragment size must be positive");
        var parts = id.split("\\|", -1);
        if (parts.length != 3) throw new Toolbox.Failure(Toolbox.Code.INVALID_REFERENCE, "invalid Modonomicon entry identity");
        final ResourceLocation bookId;
        final ResourceLocation categoryId;
        final ResourceLocation entryId;
        try {
            bookId = ResourceLocation.parse(parts[0]);
            categoryId = ResourceLocation.parse(parts[1]);
            entryId = ResourceLocation.parse(parts[2]);
        } catch (RuntimeException invalidId) {
            throw new Toolbox.Failure(Toolbox.Code.INVALID_REFERENCE, "invalid Modonomicon entry identity");
        }
        int pageIndex = 0;
        int fieldIndex = 0;
        int textOffset = 0;
        if (fragmentPosition != null) {
            var position = fragmentPosition.split(":", -1);
            try {
                if (position.length != 3) throw new NumberFormatException();
                pageIndex = Integer.parseInt(position[0]);
                fieldIndex = Integer.parseInt(position[1]);
                textOffset = Integer.parseInt(position[2]);
                if (pageIndex < 0 || fieldIndex < 0 || fieldIndex > 1 || textOffset < 0) throw new NumberFormatException();
            } catch (NumberFormatException invalidPosition) {
                throw new Toolbox.Failure(Toolbox.Code.INVALID_CURSOR, "invalid Modonomicon text position");
            }
        }
        var manager = BookDataManager.get();
        var book = manager.isLoaded() && manager.areBooksBuilt() ? manager.getBook(bookId) : null;
        var player = Minecraft.getInstance().player;
        if (book == null || player == null) throw new Toolbox.Failure(Toolbox.Code.UNAVAILABLE, "Modonomicon book or player is unavailable");
        var category = book.getCategory(categoryId);
        var entry = category == null ? null : category.getEntry(entryId);
        var unlocks = BookUnlockStateManager.get();
        if (entry == null || !unlocks.isUnlockedFor(player, category) || !unlocks.isUnlockedFor(player, entry)) {
            throw new Toolbox.Failure(Toolbox.Code.INVALID_REFERENCE, "Modonomicon entry is missing or currently locked");
        }
        var pages = entry.getPages();
        if (fragmentPosition != null && pageIndex >= pages.size()) throw new Toolbox.Failure(Toolbox.Code.INVALID_CURSOR,
                "Modonomicon page no longer exists; restart the lookup");
        var partial = false;
        for (; pageIndex < pages.size(); pageIndex++, fieldIndex = 0, textOffset = 0) {
            if (!budget.value()) throw new Toolbox.Failure(Toolbox.Code.EXTRACTION_FAILED,
                    "Modonomicon page traversal reached its value budget");
            BookPage page = pages.get(pageIndex);
            if (!unlocks.isUnlockedFor(player, page)) continue;
            if (!(page instanceof BookTextPage textPage)) {
                partial = true;
                continue;
            }
            for (; fieldIndex < 2; fieldIndex++, textOffset = 0) {
                var holder = fieldIndex == 0 ? textPage.getTitle() : textPage.getText();
                if (holder == null || !holder.hasComponent() && holder.isEmpty()) continue;
                if (!budget.value() || !budget.bytes(64)) throw new Toolbox.Failure(Toolbox.Code.EXTRACTION_FAILED,
                        "Modonomicon text extraction reached its read budget");
                var localized = localizedText(holder, Language.getInstance());
                if (localized.text() == null) {
                    partial = true;
                    continue;
                }
                return textFragment(localized.text(), pageIndex, fieldIndex, textOffset, pages.size(), maxChars, budget,
                        partial || localized.partial());
            }
        }
        if (pageIndex == 0 && pages.isEmpty()) throw new Toolbox.Failure(Toolbox.Code.INVALID_REFERENCE,
                "Modonomicon entry has no currently unlocked pages");
        return new Fragment("", pages.size() + ":0:0", 0, null,
                partial ? Evidence.Coverage.PARTIAL : Evidence.Coverage.COMPLETE,
                partial ? "non-text Modonomicon pages remain unread" : null);
    }

    @Override public ResolvedDocument resolve(String id, int maxChars, Toolbox.DocumentBudget budget) {
        var parts = id.split("\\|", -1);
        if (parts.length != 3) throw new Toolbox.Failure(Toolbox.Code.INVALID_REFERENCE, "invalid Modonomicon entry identity");
        final ResourceLocation bookId;
        final ResourceLocation categoryId;
        final ResourceLocation entryId;
        try {
            bookId = ResourceLocation.parse(parts[0]);
            categoryId = ResourceLocation.parse(parts[1]);
            entryId = ResourceLocation.parse(parts[2]);
        } catch (RuntimeException invalidId) {
            throw new Toolbox.Failure(Toolbox.Code.INVALID_REFERENCE, "invalid Modonomicon entry identity");
        }
        var manager = BookDataManager.get();
        if (!budget.value()) throw new Toolbox.Failure(Toolbox.Code.EXTRACTION_FAILED,
                "Modonomicon evidence resolution reached its value budget");
        var player = Minecraft.getInstance().player;
        var book = manager.isLoaded() && manager.areBooksBuilt() ? manager.getBook(bookId) : null;
        var category = book == null ? null : book.getCategory(categoryId);
        var entry = category == null ? null : category.getEntry(entryId);
        var unlocks = BookUnlockStateManager.get();
        if (entry == null || player == null || !unlocks.isUnlockedFor(player, category)
                || !unlocks.isUnlockedFor(player, entry)) {
            throw new Toolbox.Failure(Toolbox.Code.INVALID_REFERENCE, "Modonomicon entry is unavailable or locked");
        }
        if (!budget.bytes(id.length() * 6 + 2048)) throw new Toolbox.Failure(Toolbox.Code.EXTRACTION_FAILED,
                "Modonomicon candidate metadata exceeds its read budget");
        var title = entry.getName();
        var candidateCoverage = title.length() > 256 ? Evidence.Coverage.PARTIAL : Evidence.Coverage.COMPLETE;
        if (title.length() > 256) title = title.substring(0, 256);
        var candidate = new Candidate(id, title, id, List.of("modonomicon:book:" + bookId,
                "modonomicon:category:" + categoryId), candidateCoverage);
        return new ResolvedDocument(candidate, readFragment(id, null, maxChars, budget));
    }

    static Fragment textFragment(String text, int pageIndex, int fieldIndex, int textOffset, int pageCount,
                                 int maxChars, Toolbox.DocumentBudget budget, boolean sourcePartial) {
        if (maxChars < 1 || pageIndex < 0 || fieldIndex < 0 || fieldIndex > 1 || textOffset < 0
                || pageCount < 0) throw new Toolbox.Failure(Toolbox.Code.INVALID_CURSOR, "invalid Modonomicon text position");
        if (textOffset > text.length()) throw new Toolbox.Failure(Toolbox.Code.INVALID_CURSOR,
                "Modonomicon text changed; restart the lookup");
        var requestedOverlap = textOffset == 0 ? 0 : Math.min(GuideDocumentReader.FRAGMENT_OVERLAP_CHARS, textOffset);
        var overlap = Math.min(requestedOverlap, maxChars - 1);
        var start = textOffset - overlap;
        var overlapPartial = overlap < requestedOverlap;
        if (start > 0 && Character.isLowSurrogate(text.charAt(start))) {
            start++;
            overlap--;
            overlapPartial = true;
        }
        var count = Math.min(text.length() - textOffset, Math.min(maxChars - overlap,
                Math.max(0, (budget.remainingBytes() - 32) / 12)));
        if (count == 0 && textOffset < text.length()) throw new Toolbox.Failure(Toolbox.Code.EXTRACTION_FAILED,
                "Modonomicon text cannot advance within the remaining byte budget");
        var end = textOffset + count;
        if (end < text.length() && end > textOffset && Character.isHighSurrogate(text.charAt(end - 1))) end--;
        if (end == textOffset && textOffset < text.length()) throw new Toolbox.Failure(Toolbox.Code.EXTRACTION_FAILED,
                "Modonomicon text cannot advance at the requested fragment size");
        if (!budget.bytes((end - start) * 12 + 32)) throw new Toolbox.Failure(Toolbox.Code.EXTRACTION_FAILED,
                "Modonomicon text reached its byte budget");
        var value = text.substring(start, end);
        var nextPosition = end < text.length() ? pageIndex + ":" + fieldIndex + ":" + end
                : fieldIndex == 0 ? pageIndex + ":1:0" : pageIndex + 1 + ":0:0";
        var hasNext = end < text.length() || fieldIndex == 0 || pageIndex + 1 < pageCount;
        var partial = sourcePartial || overlapPartial;
        return new Fragment(value, pageIndex + ":" + fieldIndex + ":" + start, overlap,
                hasNext ? nextPosition : null, partial ? Evidence.Coverage.PARTIAL : Evidence.Coverage.COMPLETE,
                sourcePartial ? "component-backed Modonomicon fields remain unread" : overlapPartial
                        ? "fragment overlap was shortened to preserve a text boundary" : null);
    }

    static LocalizedText localizedText(BookTextHolder holder, Language language) {
        if (holder == null) return new LocalizedText(null, true);
        if (!holder.hasComponent()) {
            var key = holder.getKey();
            if (key == null) return new LocalizedText(null, true);
            var text = language.getOrDefault(key, key);
            return new LocalizedText(text == null ? key : text, text == null || !language.has(key));
        }
        var component = holder.getComponent();
        if (component == null) return new LocalizedText(null, true);
        ComponentContents contents = component.getContents();
        if (contents instanceof PlainTextContents.LiteralContents literal) {
            return new LocalizedText(literal.text(), !component.getSiblings().isEmpty());
        }
        if (contents instanceof TranslatableContents translatable && translatable.getArgs().length == 0) {
            var key = translatable.getKey();
            if (key == null) return new LocalizedText(null, true);
            var fallback = translatable.getFallback();
            var translated = language.getOrDefault(key, fallback == null ? key : fallback);
            return new LocalizedText(translated == null ? fallback == null ? key : fallback : translated,
                    !component.getSiblings().isEmpty() || !language.has(key));
        }
        return new LocalizedText(null, true);
    }
}
