package dev.packsage.knowledge.guide;

import com.klikli_dev.modonomicon.book.Book;
import com.klikli_dev.modonomicon.book.BookCategory;
import com.klikli_dev.modonomicon.book.BookTextHolder;
import com.klikli_dev.modonomicon.book.ResolvedBookEntryParent;
import com.klikli_dev.modonomicon.book.conditions.BookAndCondition;
import com.klikli_dev.modonomicon.book.conditions.BookCondition;
import com.klikli_dev.modonomicon.book.conditions.BookOrCondition;
import com.klikli_dev.modonomicon.book.entries.BookEntry;
import com.klikli_dev.modonomicon.book.page.BookPage;
import com.klikli_dev.modonomicon.book.page.BookRecipePage;
import com.klikli_dev.modonomicon.book.page.BookSpotlightPage;
import com.klikli_dev.modonomicon.book.page.BookTextPage;
import com.klikli_dev.modonomicon.bookstate.BookUnlockStateManager;
import com.klikli_dev.modonomicon.data.BookDataManager;
import com.mojang.datafixers.util.Either;
import dev.packsage.knowledge.Evidence;
import dev.packsage.knowledge.KnowledgeProvider;
import dev.packsage.knowledge.Toolbox;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.ComponentContents;
import net.minecraft.network.chat.contents.PlainTextContents;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeType;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/** Bounded reads from the currently loaded Modonomicon books. */
public class ModonomiconGuideReader implements GuideDocumentReader {
    private static final String SEPARATOR = "|";
    private static final int MAX_RELATIONSHIP_RECORDS = 16;
    private static final int MAX_FRAGMENT_BYTES = 8 * 1024;
    private static final int MAX_RELATIONSHIP_BYTES = MAX_FRAGMENT_BYTES - 1024;
    private static final int ITEMS_OPAQUE = 1;
    private static final int RECIPES_OPAQUE = 2;
    private static final int PREREQUISITES_OPAQUE = 4;
    private final String readerVersion;

    record LocalizedText(String text, boolean partial) { }
    protected record SpotlightValue(ItemStack stack, Ingredient ingredient) { }

    ModonomiconGuideReader() { this("unknown"); }

    ModonomiconGuideReader(String version) { this.readerVersion = version; }

    public static KnowledgeProvider forMinecraft(Supplier<Evidence.Scope> currentScope, Executor clientExecutor, String actualVersion) {
        var version = new DefaultArtifactVersion(actualVersion);
        GuideDocumentReader reader = version.compareTo(new DefaultArtifactVersion("1.101.0")) < 0
                ? new ModonomiconLegacyGuideReader(actualVersion)
                : version.compareTo(new DefaultArtifactVersion("1.102.0")) < 0
                ? new ModonomiconIntermediateGuideReader(actualVersion) : new ModonomiconGuideReader(actualVersion);
        return new GuideDocumentProvider(reader, currentScope, clientExecutor);
    }

    protected SpotlightValue spotlightValue(BookSpotlightPage page) {
        try {
            var item = page.getClass().getMethod("getItem").invoke(page);
            if (item instanceof Ingredient ingredient) return new SpotlightValue(null, ingredient);
            if (item instanceof Either<?, ?> either) return either.map(
                    stack -> stack instanceof ItemStack itemStack ? new SpotlightValue(itemStack, null) : null,
                    value -> value instanceof Ingredient ingredient ? new SpotlightValue(null, ingredient) : null);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unreadableSpotlight) {
            return null;
        }
        return null;
    }

    protected String entryDisplayState(BookEntry entry, Player player) {
        try {
            return String.valueOf(entry.getClass().getMethod("getEntryDisplayState", Player.class).invoke(entry, player));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailableDisplayState) {
            return "HIDDEN";
        }
    }

    protected static String legacyEntryDisplayState(BookEntry entry, Player player) {
        var unlocks = BookUnlockStateManager.get();
        var entryUnlocked = unlocks.isUnlockedFor(player, entry);
        var anyParentUnlocked = false;
        var allParentsUnlocked = true;
        for (var parent : entry.getParents()) {
            if (!(parent instanceof ResolvedBookEntryParent resolved) || resolved.getEntry() == null) return "HIDDEN";
            var parentUnlocked = unlocks.isUnlockedFor(player, resolved.getEntry());
            anyParentUnlocked |= parentUnlocked;
            allParentsUnlocked &= parentUnlocked;
        }
        if (entry.showWhenAnyParentUnlocked() ? !anyParentUnlocked : !allParentsUnlocked) return "HIDDEN";
        if (!entryUnlocked) return entry.hideWhileLocked() ? "HIDDEN" : "LOCKED";
        return "UNLOCKED";
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
                    if (!unlocks.isUnlockedFor(player, category)) continue;
                    var displayState = entryDisplayState(entry, player);
                    if ("HIDDEN".equals(displayState)) continue;
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
        var cursor = FragmentCursor.parse(fragmentPosition);
        var pageIndex = cursor.pageIndex();
        var fieldIndex = cursor.fieldIndex();
        var textOffset = cursor.textOffset();
        var manager = BookDataManager.get();
        var book = manager.isLoaded() && manager.areBooksBuilt() ? manager.getBook(bookId) : null;
        var player = Minecraft.getInstance().player;
        if (book == null || player == null) throw new Toolbox.Failure(Toolbox.Code.UNAVAILABLE, "Modonomicon book or player is unavailable");
        var category = book.getCategory(categoryId);
        var entry = category == null ? null : category.getEntry(entryId);
        var unlocks = BookUnlockStateManager.get();
        if (entry == null || category == null || !unlocks.isUnlockedFor(player, category))
            throw new Toolbox.Failure(Toolbox.Code.INVALID_REFERENCE, "Modonomicon entry is missing or currently locked");
        var displayState = entryDisplayState(entry, player);
        if ("HIDDEN".equals(displayState)) throw new Toolbox.Failure(Toolbox.Code.INVALID_REFERENCE,
                "Modonomicon entry is missing or currently locked");
        var pages = entry.getPages();
        if (fragmentPosition != null && !cursor.textDone() && pageIndex >= pages.size()) throw new Toolbox.Failure(Toolbox.Code.INVALID_CURSOR,
                "Modonomicon page no longer exists; restart the lookup");
        if (!"LOCKED".equals(displayState) && pages.isEmpty()) throw new Toolbox.Failure(Toolbox.Code.INVALID_REFERENCE,
                "Modonomicon entry has no currently unlocked pages");
        var relationships = new RelationshipCollector(cursor.relationshipPosition(), cursor.opaqueMask(), cursor.partialMask(), budget);
        if (!"done".equals(cursor.relationshipPosition()))
            readRelationships(entry, category, pages, player, displayState, unlocks, relationships);
        var relationshipObject = relationships.jsonObject();
        var relationshipBytes = relationships.jsonBytes();
        if (!budget.bytes(relationshipBytes)) throw new Toolbox.Failure(Toolbox.Code.EXTRACTION_FAILED,
                "Modonomicon relationships exceed the live byte budget");
        var partial = false;
        var text = "";
        var textSourcePosition = pageIndex + ":" + fieldIndex + ":" + textOffset;
        var textNextPosition = cursor.textDone() || "LOCKED".equals(displayState) ? null : textSourcePosition;
        var textDone = cursor.textDone() || "LOCKED".equals(displayState);
        var overlapChars = 0;
        var textCoverage = Evidence.Coverage.COMPLETE;
        String textDetail = null;
        var foundTextFragment = false;
        var textMaxChars = Math.max(0, Math.min(maxChars, (MAX_FRAGMENT_BYTES - relationshipBytes - 256) / 4));
        if (textMaxChars > 0 && !textDone && !"LOCKED".equals(displayState)
                && !relationships.valueBudgetExhausted()) {
            for (; pageIndex < pages.size(); pageIndex++, fieldIndex = 0, textOffset = 0) {
                if (!budget.value()) throw new Toolbox.Failure(Toolbox.Code.EXTRACTION_FAILED,
                        "Modonomicon page traversal reached its value budget");
                BookPage page = pages.get(pageIndex);
                if (!unlocks.isUnlockedFor(player, page)) {
                    partial = true;
                    continue;
                }
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
                    var textFragment = textFragment(localized.text(), pageIndex, fieldIndex, textOffset, pages.size(),
                            textMaxChars, budget, partial || localized.partial());
                    text = textFragment.text();
                    textSourcePosition = textFragment.sourcePosition();
                    textNextPosition = textFragment.nextPosition();
                    overlapChars = textFragment.overlapChars();
                    textCoverage = textFragment.coverage();
                    textDetail = textFragment.detail();
                    textDone = textNextPosition == null;
                    foundTextFragment = true;
                    break;
                }
                if (foundTextFragment || textNextPosition == null) break;
            }
            if (pageIndex >= pages.size()) {
                textNextPosition = null;
                textDone = true;
            }
        }
        var nextRelationship = relationships.nextPosition();
        var nextPosition = nextRelationship != null ? FragmentCursor.encode(nextRelationship, relationships.opaqueMask(),
                relationships.partialMask(), textDone ? "done" : textNextPosition)
                : textNextPosition != null ? FragmentCursor.encode("done", relationships.opaqueMask(),
                relationships.partialMask(), textNextPosition) : null;
        var relationshipPartial = relationships.coverage() != Evidence.Coverage.COMPLETE;
        var coverage = relationshipPartial || textCoverage != Evidence.Coverage.COMPLETE || partial
                ? Evidence.Coverage.PARTIAL : Evidence.Coverage.COMPLETE;
        var detail = relationships.detail() != null ? relationships.detail() : textDetail != null ? textDetail
                : partial ? "locked or non-text Modonomicon pages remain unread" : null;
        return new Fragment(text, textSourcePosition, overlapChars, nextPosition, coverage, detail, relationshipObject);
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
        if (entry == null || category == null || player == null || !unlocks.isUnlockedFor(player, category)
                || "HIDDEN".equals(entryDisplayState(entry, player))) {
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

    private void readRelationships(BookEntry entry, BookCategory category, List<BookPage> pages, Player player,
                                   String displayState, BookUnlockStateManager unlocks,
                                   RelationshipCollector collector) {
        var position = collector.position();
        collector.record("entry:state", "prerequisites", () -> {
            var values = new LinkedHashMap<String, Evidence.JsonValue>();
            values.put("kind", new Evidence.JsonString("state"));
            values.put("scope", new Evidence.JsonString("entry"));
            values.put("state", new Evidence.JsonString(displayState));
            values.put("sourcePosition", new Evidence.JsonString("entry"));
            return new Evidence.JsonObject(values);
        });
        if (collector.stopped()) return;
        collector.record("category:state", "prerequisites", () -> {
            var values = new LinkedHashMap<String, Evidence.JsonValue>();
            values.put("kind", new Evidence.JsonString("state"));
            values.put("scope", new Evidence.JsonString("category"));
            values.put("state", new Evidence.JsonString("UNLOCKED"));
            values.put("sourcePosition", new Evidence.JsonString("category"));
            return new Evidence.JsonObject(values);
        });
        if (collector.stopped()) return;
        var parents = entry.getParents();
        var parentStart = position != null && position.kind() == RelationshipKind.PARENT ? position.index()
                : position == null || position.kind() == RelationshipKind.ENTRY_STATE
                || position.kind() == RelationshipKind.CATEGORY_STATE ? 0 : parents.size();
        if (position != null && position.kind() == RelationshipKind.PARENT && parentStart >= parents.size())
            throw new Toolbox.Failure(Toolbox.Code.INVALID_CURSOR,
                "Modonomicon parent relationship no longer exists");
        for (var index = parentStart; index < parents.size(); index++) {
            var parentIndex = index;
            var parent = parents.get(index);
            collector.record("entry:parent:" + index, "prerequisites", () -> {
                var values = new LinkedHashMap<String, Evidence.JsonValue>();
                values.put("kind", new Evidence.JsonString("parent"));
                values.put("scope", new Evidence.JsonString("entry"));
                var parentId = resourceId(parent.getEntryId());
                if (parentId != null) values.put("entryId", new Evidence.JsonString(parentId));
                else collector.markOpaque("prerequisites", "a Modonomicon parent ID exceeded the identity bound");
                values.put("sourcePosition", new Evidence.JsonString("parent:" + parentIndex));
                return new Evidence.JsonObject(values);
            });
            if (collector.stopped()) return;
        }

        if (position == null || position.kind() == RelationshipKind.ENTRY_STATE
                || position.kind() == RelationshipKind.CATEGORY_STATE || position.kind() == RelationshipKind.PARENT) {
            readCondition(entry.getCondition(), "entry", "root", 0, null, collector);
        } else if (position.kind() == RelationshipKind.CONDITION && "entry".equals(position.scope())) {
            readCondition(entry.getCondition(), "entry", "root", 0, position.conditionPath(), collector);
            if (!collector.started()) {
                collector.verifyResume();
                return;
            }
        }
        if (collector.stopped()) return;

        if (position == null || position.kind() == RelationshipKind.ENTRY_STATE
                || position.kind() == RelationshipKind.CATEGORY_STATE || position.kind() == RelationshipKind.PARENT
                || position.kind() == RelationshipKind.CONDITION && "entry".equals(position.scope())) {
            readCondition(category.getCondition(), "category", "root", 0, null, collector);
        } else if (position.kind() == RelationshipKind.CONDITION && "category".equals(position.scope())) {
            readCondition(category.getCondition(), "category", "root", 0, position.conditionPath(), collector);
            if (!collector.started()) {
                collector.verifyResume();
                return;
            }
        }
        if (collector.stopped()) return;
        if ("LOCKED".equals(displayState)) {
            collector.verifyResume();
            return;
        }

        var pageStart = position != null && position.isPagePosition() ? position.pageIndex() : 0;
        if (pageStart > pages.size() || position != null && position.isPagePosition() && pageStart == pages.size())
            throw new Toolbox.Failure(Toolbox.Code.INVALID_CURSOR, "Modonomicon page relationship no longer exists");
        for (var pageIndex = pageStart; pageIndex < pages.size(); pageIndex++) {
            var currentPageIndex = pageIndex;
            var page = pages.get(pageIndex);
            var pageSource = "page:" + currentPageIndex + ":start";
            collector.pageGroupsApplicable = true;
            var pageStateDecision = collector.begin(pageSource, "prerequisites");
            if (pageStateDecision == RelationshipCollector.Decision.STOP) return;
            boolean pageUnlocked;
            if (pageStateDecision == RelationshipCollector.Decision.PROCESS) {
                pageUnlocked = unlocks.isUnlockedFor(player, page);
                var values = new LinkedHashMap<String, Evidence.JsonValue>();
                values.put("kind", new Evidence.JsonString("state"));
                values.put("scope", new Evidence.JsonString("page"));
                values.put("state", new Evidence.JsonString(pageUnlocked ? "UNLOCKED" : "LOCKED"));
                values.put("sourcePosition", new Evidence.JsonString("page:" + currentPageIndex));
                if (!collector.append(pageSource, "prerequisites", new Evidence.JsonObject(values))) return;
            } else {
                if (!collector.chargeTraversal(pageSource, "prerequisites")) return;
                pageUnlocked = unlocks.isUnlockedFor(player, page);
            }
            if (!pageUnlocked) {
                collector.markPartial("items", "locked Modonomicon pages were not inspected", true);
                collector.markPartial("recipes", "locked Modonomicon pages were not inspected", true);
                collector.markPartial("prerequisites", "locked page conditions were not inspected", true);
                if (position != null && position.pageIndex() == currentPageIndex
                        && position.kind() != RelationshipKind.PAGE_START) {
                    collector.verifyResume();
                    return;
                }
                continue;
            }
            if (position == null || !position.isPagePosition() || currentPageIndex > position.pageIndex()
                    || position.kind() == RelationshipKind.PAGE_START
                    || position.kind() == RelationshipKind.CONDITION) {
                var conditionPath = position != null && position.kind() == RelationshipKind.CONDITION
                        && position.pageIndex() == currentPageIndex ? position.conditionPath() : null;
                readCondition(page.getCondition(), "page", "page:" + currentPageIndex, 0,
                        conditionPath, collector);
                if (position != null && position.kind() == RelationshipKind.CONDITION
                        && position.pageIndex() == currentPageIndex && !collector.started()) {
                    collector.verifyResume();
                    return;
                }
                if (collector.stopped()) return;
            }
            if (page.getClass() == BookSpotlightPage.class) {
                SpotlightValue selected;
                try {
                    selected = spotlightValue((BookSpotlightPage) page);
                } catch (RuntimeException | LinkageError unreadableSpotlight) {
                    selected = null;
                }
                if (selected == null) {
                    collector.markOpaque("items", "a Modonomicon spotlight value could not be decoded");
                } else if (selected.stack() != null) {
                    var stack = selected.stack();
                    if (!stack.isEmpty()) {
                        var source = "page:" + currentPageIndex + ":spotlight:0";
                        var decision = collector.begin(source, "items");
                        if (decision == RelationshipCollector.Decision.STOP) return;
                        if (decision == RelationshipCollector.Decision.PROCESS) {
                            var itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
                            var id = resourceId(itemId);
                            var values = new LinkedHashMap<String, Evidence.JsonValue>();
                            values.put("kind", new Evidence.JsonString("spotlight"));
                            values.put("sourcePosition", new Evidence.JsonString("page:" + currentPageIndex));
                            if (id == null) {
                                collector.markOpaque("items", "a Modonomicon spotlight item has no bounded registry identity");
                                values.put("type", new Evidence.JsonString("opaque"));
                            } else values.put("itemId", new Evidence.JsonString(id));
                            collector.append(source, "items", new Evidence.JsonObject(values));
                        }
                    }
                } else if (selected.ingredient() != null) {
                    var alternativeStart = position != null && position.kind() == RelationshipKind.SPOTLIGHT
                            && position.pageIndex() == currentPageIndex ? position.index() : 0;
                    readIngredient(selected.ingredient(), currentPageIndex, alternativeStart, collector);
                } else {
                    collector.markOpaque("items", "a Modonomicon spotlight value could not be decoded");
                }
            } else if (page instanceof BookRecipePage<?> recipePage) {
                var recipeStart = position != null && position.kind() == RelationshipKind.RECIPE
                        && position.pageIndex() == currentPageIndex ? position.index() : 0;
                readRecipePage(recipePage, currentPageIndex, recipeStart, collector);
            } else if (page.getClass() != BookTextPage.class) {
                collector.markOpaque("items", "a custom Modonomicon page may declare unread item relationships");
                collector.markOpaque("recipes", "a custom Modonomicon page may declare unread recipe relationships");
            }
            if (position != null && position.pageIndex() == currentPageIndex
                    && (position.kind() == RelationshipKind.SPOTLIGHT || position.kind() == RelationshipKind.RECIPE)
                    && !collector.started()) {
                collector.verifyResume();
                return;
            }
            if (collector.stopped()) return;
        }
        collector.finishPages();
        collector.verifyResume();
    }

    private static void readIngredient(Ingredient ingredient, int pageIndex, int alternativeStart,
                                      RelationshipCollector collector) {
        if (ingredient.getClass() != Ingredient.class) {
            collector.markOpaque("items", "a custom Modonomicon ingredient remains opaque");
            return;
        }
        if (!collector.chargeExtraction("page:" + pageIndex + ":spotlight:" + alternativeStart)) return;
        Ingredient.Value[] alternatives;
        try {
            alternatives = ingredient.getValues();
        } catch (RuntimeException | LinkageError unreadableValues) {
            collector.markOpaque("items", "a Modonomicon ingredient alternatives list could not be read");
            return;
        }
        if (alternatives.length == 0) {
            collector.markOpaque("items", "a Modonomicon ingredient has no readable alternatives");
            return;
        }
        if (alternativeStart > alternatives.length) return;
        for (var alternativeIndex = alternativeStart; alternativeIndex < alternatives.length; alternativeIndex++) {
            var alternative = alternatives[alternativeIndex];
            var source = "page:" + pageIndex + ":spotlight:" + alternativeIndex;
            var decision = collector.begin(source, "items");
            if (decision == RelationshipCollector.Decision.STOP) return;
            if (decision == RelationshipCollector.Decision.SKIP) continue;
            var values = new LinkedHashMap<String, Evidence.JsonValue>();
            values.put("kind", new Evidence.JsonString("spotlight"));
            values.put("sourcePosition", new Evidence.JsonString("page:" + pageIndex));
            if (alternative instanceof Ingredient.ItemValue itemValue) {
                var itemId = resourceId(BuiltInRegistries.ITEM.getKey(itemValue.item().getItem()));
                if (itemId == null) {
                    collector.markOpaque("items", "a Modonomicon ingredient item has no bounded registry identity");
                    values.put("type", new Evidence.JsonString("opaque"));
                } else values.put("itemId", new Evidence.JsonString(itemId));
            } else if (alternative instanceof Ingredient.TagValue tagValue) {
                var tagId = tagValue.tag() == null ? null : resourceId(tagValue.tag().location());
                if (tagId == null) {
                    collector.markOpaque("items", "a Modonomicon ingredient tag has no bounded registry identity");
                    values.put("type", new Evidence.JsonString("opaque"));
                } else values.put("tagId", new Evidence.JsonString(tagId));
            } else {
                collector.markOpaque("items", "a Modonomicon ingredient alternative type is unsupported");
                values.put("type", new Evidence.JsonString("opaque"));
            }
            if (!collector.append(source, "items", new Evidence.JsonObject(values))) return;
        }
    }

    private static void readRecipePage(BookRecipePage<?> page, int pageIndex, int slotStart,
                                       RelationshipCollector collector) {
        try {
            var first = (ResourceLocation) declaredField(BookRecipePage.class, "recipeId1", page);
            var second = (ResourceLocation) declaredField(BookRecipePage.class, "recipeId2", page);
            var recipeType = (RecipeType<?>) declaredField(BookRecipePage.class, "recipeType", page);
            var typeId = recipeType == null ? null : BuiltInRegistries.RECIPE_TYPE.getKey(recipeType);
            if (slotStart > 2) return;
            for (var slot = slotStart; slot < 2; slot++) {
                var recipeId = slot == 0 ? first : second;
                if (recipeId == null) continue;
                var source = "page:" + pageIndex + ":recipe:" + slot;
                var decision = collector.begin(source, "recipes");
                if (decision == RelationshipCollector.Decision.STOP) return;
                if (decision == RelationshipCollector.Decision.SKIP) continue;
                var values = new LinkedHashMap<String, Evidence.JsonValue>();
                values.put("kind", new Evidence.JsonString("recipe"));
                values.put("sourcePosition", new Evidence.JsonString("page:" + pageIndex));
                var recipeIdentity = resourceId(recipeId);
                if (recipeIdentity != null) values.put("recipeId", new Evidence.JsonString(recipeIdentity));
                else {
                    collector.markOpaque("recipes", "a Modonomicon recipe ID exceeded the identity bound");
                    values.put("type", new Evidence.JsonString("opaque"));
                }
                var recipeTypeIdentity = resourceId(typeId);
                if (recipeTypeIdentity != null) values.put("recipeType", new Evidence.JsonString(recipeTypeIdentity));
                else {
                    collector.markOpaque("recipes", "a Modonomicon recipe type has no registry identity");
                    values.putIfAbsent("type", new Evidence.JsonString("opaque"));
                }
                if (!collector.append(source, "recipes", new Evidence.JsonObject(values))) return;
            }
        } catch (ReflectiveOperationException | RuntimeException unreadableRecipePage) {
            collector.markOpaque("recipes", "a Modonomicon recipe page declaration could not be read");
        }
    }

    private static void readCondition(BookCondition condition, String scope, String path, int depth,
                                      List<Integer> seekPath, RelationshipCollector collector) {
        if (condition == null || collector.stopped()) return;
        var source = "condition:" + scope + ":" + path;
        if (seekPath != null && !collector.started() && depth < seekPath.size()) {
            if (!collector.chargeConditionSeek()) return;
            BookCondition[] children = null;
            if (condition.getClass() == BookAndCondition.class) children = ((BookAndCondition) condition).children();
            else if (condition.getClass() == BookOrCondition.class) children = ((BookOrCondition) condition).children();
            var childIndex = seekPath.get(depth);
            if (children == null || childIndex >= children.length) return;
            readCondition(children[childIndex], scope, path + "." + childIndex, depth + 1, seekPath, collector);
            if (collector.stopped()) return;
            for (var siblingIndex = childIndex + 1; siblingIndex < children.length; siblingIndex++) {
                readCondition(children[siblingIndex], scope, path + "." + siblingIndex, depth + 1, null, collector);
                if (collector.stopped()) return;
            }
            return;
        }
        var decision = collector.begin(source, "prerequisites");
        if (decision == RelationshipCollector.Decision.STOP) return;
        if (decision == RelationshipCollector.Decision.SKIP) return;
        BookCondition[] children = null;
        var className = condition.getClass().getName();
        if (condition.getClass() == BookAndCondition.class) children = ((BookAndCondition) condition).children();
        else if (condition.getClass() == BookOrCondition.class) children = ((BookOrCondition) condition).children();
        var values = new LinkedHashMap<String, Evidence.JsonValue>();
        values.put("kind", new Evidence.JsonString("condition"));
        values.put("scope", new Evidence.JsonString(scope));
        values.put("path", new Evidence.JsonString(scope + ":" + path));
        values.put("sourcePosition", new Evidence.JsonString(source));
        ResourceLocation type = null;
        try {
            type = condition.getType();
        } catch (RuntimeException | LinkageError unreadableType) {
            collector.markOpaque("prerequisites", "a Modonomicon condition type could not be read");
        }
        var typeId = resourceId(type);
        if (typeId == null) collector.markOpaque("prerequisites", "a Modonomicon condition type has no bounded identity");
        values.put("type", new Evidence.JsonString(typeId == null ? "unknown" : typeId));
        if (children != null) values.put("childCount", new Evidence.JsonNumber(java.math.BigDecimal.valueOf(children.length)));
        var fieldName = className.equals("com.klikli_dev.modonomicon.book.conditions.BookAdvancementCondition") ? "advancementId"
                : className.equals("com.klikli_dev.modonomicon.book.conditions.BookEntryReadCondition")
                || className.equals("com.klikli_dev.modonomicon.book.conditions.BookEntryUnlockedCondition") ? "entryId"
                : className.equals("com.klikli_dev.modonomicon.book.conditions.BookModLoadedCondition") ? "modId"
                : className.equals("com.klikli_dev.modonomicon.book.conditions.BookCategoryHasEntriesCondition")
                || className.equals("com.klikli_dev.modonomicon.book.conditions.BookCategoryHasVisibleEntriesCondition")
                ? "categoryId" : null;
        if (fieldName != null) {
            try {
                var target = declaredField(condition.getClass(), fieldName, condition);
                var targetText = target instanceof ResourceLocation id ? resourceId(id)
                        : target instanceof String text && text.length() <= 256 ? text : null;
                if (targetText != null) values.put(fieldName, new Evidence.JsonString(targetText));
                else collector.markOpaque("prerequisites", "a Modonomicon condition target was unavailable");
            } catch (ReflectiveOperationException | RuntimeException unreadableTarget) {
                collector.markOpaque("prerequisites", "a Modonomicon condition target was unavailable");
            }
        } else if (children == null && !className.equals("com.klikli_dev.modonomicon.book.conditions.BookTrueCondition")
                && !className.equals("com.klikli_dev.modonomicon.book.conditions.BookFalseCondition")
                && !className.equals("com.klikli_dev.modonomicon.book.conditions.BookNoneCondition")) {
            collector.markOpaque("prerequisites", "a custom Modonomicon condition remains opaque");
        }
        if (!collector.append(source, "prerequisites", new Evidence.JsonObject(values))) return;
        if (children == null || children.length == 0) return;
        if (depth >= 64) {
            collector.markOpaque("prerequisites", "a Modonomicon condition tree exceeded depth 64");
            return;
        }
        for (var childIndex = 0; childIndex < children.length; childIndex++) {
            readCondition(children[childIndex], scope, path + "." + childIndex, depth + 1, null, collector);
            if (collector.stopped()) return;
        }
    }

    private static Object declaredField(Class<?> owner, String name, Object target) throws ReflectiveOperationException {
        Field field = owner.getDeclaredField(name);
        if (!field.trySetAccessible()) throw new IllegalAccessException(name);
        return field.get(target);
    }

    private static String resourceId(ResourceLocation value) {
        if (value == null || value.getNamespace().length() > 128 || value.getPath().length() > 256) return null;
        return value.getNamespace() + ":" + value.getPath();
    }

    private record FragmentCursor(String relationshipPosition, int opaqueMask, int partialMask,
                                  int pageIndex, int fieldIndex,
                                  int textOffset, boolean textDone) {
        private static FragmentCursor parse(String value) {
            if (value == null) return new FragmentCursor(null, 0, 0, 0, 0, 0, false);
            if (value.length() > 2048) throw new Toolbox.Failure(Toolbox.Code.INVALID_CURSOR,
                    "Modonomicon fragment position exceeds its metadata bound");
            var encoded = value.split("\\|", -1);
            try {
                if (encoded.length == 5 && "v3".equals(encoded[0])) {
                    var relationship = "^".equals(encoded[1]) ? null : encoded[1];
                    var opaqueMask = Integer.parseInt(encoded[2]);
                    var partialMask = Integer.parseInt(encoded[3]);
                    if ("done".equals(encoded[4])) {
                        if (opaqueMask < 0 || (opaqueMask & ~7) != 0 || partialMask < 0 || (partialMask & ~7) != 0)
                            throw new NumberFormatException();
                        return new FragmentCursor(relationship, opaqueMask, partialMask, 0, 0, 0, true);
                    }
                    var text = encoded[4].split(":", -1);
                    if (text.length != 3) throw new NumberFormatException();
                    var pageIndex = Integer.parseInt(text[0]);
                    var fieldIndex = Integer.parseInt(text[1]);
                    var textOffset = Integer.parseInt(text[2]);
                    if (opaqueMask < 0 || (opaqueMask & ~7) != 0 || partialMask < 0 || (partialMask & ~7) != 0
                            || pageIndex < 0 || fieldIndex < 0 || fieldIndex > 1 || textOffset < 0)
                        throw new NumberFormatException();
                    return new FragmentCursor(relationship, opaqueMask, partialMask, pageIndex, fieldIndex, textOffset, false);
                }
                if (encoded.length == 4 && "v2".equals(encoded[0])) {
                    var relationship = "^".equals(encoded[1]) ? null : encoded[1];
                    var opaqueMask = Integer.parseInt(encoded[2]);
                    if ("done".equals(encoded[3])) {
                        if (opaqueMask < 0 || (opaqueMask & ~7) != 0) throw new NumberFormatException();
                        return new FragmentCursor(relationship, opaqueMask, 0, 0, 0, 0, true);
                    }
                    var text = encoded[3].split(":", -1);
                    if (text.length != 3) throw new NumberFormatException();
                    var pageIndex = Integer.parseInt(text[0]);
                    var fieldIndex = Integer.parseInt(text[1]);
                    var textOffset = Integer.parseInt(text[2]);
                    if (opaqueMask < 0 || (opaqueMask & ~7) != 0 || pageIndex < 0 || fieldIndex < 0 || fieldIndex > 1
                            || textOffset < 0) throw new NumberFormatException();
                    return new FragmentCursor(relationship, opaqueMask, 0, pageIndex, fieldIndex, textOffset, false);
                }
                var text = value.split(":", -1);
                if (text.length != 3) throw new NumberFormatException();
                var pageIndex = Integer.parseInt(text[0]);
                var fieldIndex = Integer.parseInt(text[1]);
                var textOffset = Integer.parseInt(text[2]);
                if (pageIndex < 0 || fieldIndex < 0 || fieldIndex > 1 || textOffset < 0) throw new NumberFormatException();
                return new FragmentCursor("done", 0, 0, pageIndex, fieldIndex, textOffset, false);
            } catch (NumberFormatException invalidPosition) {
                throw new Toolbox.Failure(Toolbox.Code.INVALID_CURSOR, "invalid Modonomicon fragment position");
            }
        }

        private static String encode(String relationshipPosition, int opaqueMask, int partialMask, String textPosition) {
            return "v3|" + (relationshipPosition == null ? "^" : relationshipPosition) + "|" + opaqueMask + "|"
                    + partialMask + "|" + textPosition;
        }
    }

    private enum RelationshipKind { DONE, ENTRY_STATE, CATEGORY_STATE, PARENT, CONDITION, PAGE_START, SPOTLIGHT, RECIPE }

    private record RelationshipPosition(RelationshipKind kind, String scope, int pageIndex, int index,
                                        List<Integer> conditionPath) {
        private static RelationshipPosition parse(String source) {
            if (source == null) return null;
            if (source.length() > 1024) throw new Toolbox.Failure(Toolbox.Code.INVALID_CURSOR,
                    "Modonomicon relationship position exceeds its metadata bound");
            if ("done".equals(source)) return new RelationshipPosition(RelationshipKind.DONE, null, -1, -1, List.of());
            if ("entry:state".equals(source)) return new RelationshipPosition(RelationshipKind.ENTRY_STATE, null, -1, -1, List.of());
            if ("category:state".equals(source)) return new RelationshipPosition(RelationshipKind.CATEGORY_STATE, null, -1, -1, List.of());
            try {
                if (source.startsWith("entry:parent:")) {
                    var encodedIndex = source.substring("entry:parent:".length());
                    var index = Integer.parseInt(encodedIndex);
                    if (index < 0 || !Integer.toString(index).equals(encodedIndex)) throw new NumberFormatException();
                    return new RelationshipPosition(RelationshipKind.PARENT, null, -1, index, List.of());
                }
                if (source.startsWith("condition:")) {
                    var encodedCondition = source.substring("condition:".length());
                    var scopeDelimiter = encodedCondition.indexOf(':');
                    if (scopeDelimiter < 1) throw new NumberFormatException();
                    var scope = encodedCondition.substring(0, scopeDelimiter);
                    var encodedPath = encodedCondition.substring(scopeDelimiter + 1);
                    var pageIndex = -1;
                    var conditionPath = new ArrayList<Integer>();
                    if ("page".equals(scope)) {
                        var pagePathParts = encodedPath.split("\\.", -1);
                        if (pagePathParts.length == 0 || pagePathParts.length > 65
                                || !pagePathParts[0].startsWith("page:")) throw new NumberFormatException();
                        var encodedPage = pagePathParts[0].substring("page:".length());
                        pageIndex = Integer.parseInt(encodedPage);
                        if (pageIndex < 0 || !Integer.toString(pageIndex).equals(encodedPage)) throw new NumberFormatException();
                        for (var pathIndex = 1; pathIndex < pagePathParts.length; pathIndex++) {
                            var childIndex = Integer.parseInt(pagePathParts[pathIndex]);
                            if (childIndex < 0 || !Integer.toString(childIndex).equals(pagePathParts[pathIndex]))
                                throw new NumberFormatException();
                            conditionPath.add(childIndex);
                        }
                    } else {
                        if (!"entry".equals(scope) && !"category".equals(scope)) throw new NumberFormatException();
                        var pathParts = encodedPath.split("\\.", -1);
                        if (pathParts.length == 0 || pathParts.length > 65 || !"root".equals(pathParts[0]))
                            throw new NumberFormatException();
                        for (var pathIndex = 1; pathIndex < pathParts.length; pathIndex++) {
                            var childIndex = Integer.parseInt(pathParts[pathIndex]);
                            if (childIndex < 0 || !Integer.toString(childIndex).equals(pathParts[pathIndex]))
                                throw new NumberFormatException();
                            conditionPath.add(childIndex);
                        }
                    }
                    return new RelationshipPosition(RelationshipKind.CONDITION, scope, pageIndex, -1,
                            List.copyOf(conditionPath));
                }
                if (source.startsWith("page:")) {
                    var parts = source.split(":", -1);
                    if (parts.length < 3 || parts.length > 4) throw new NumberFormatException();
                    var pageIndex = Integer.parseInt(parts[1]);
                    if (pageIndex < 0 || !Integer.toString(pageIndex).equals(parts[1])) throw new NumberFormatException();
                    if (parts.length == 3 && "start".equals(parts[2]))
                        return new RelationshipPosition(RelationshipKind.PAGE_START, null, pageIndex, -1, List.of());
                    if (parts.length == 4 && ("spotlight".equals(parts[2]) || "recipe".equals(parts[2]))) {
                        var index = Integer.parseInt(parts[3]);
                        if (index < 0 || !Integer.toString(index).equals(parts[3])) throw new NumberFormatException();
                        return new RelationshipPosition("spotlight".equals(parts[2])
                                ? RelationshipKind.SPOTLIGHT : RelationshipKind.RECIPE, null, pageIndex, index, List.of());
                    }
                }
            } catch (NumberFormatException invalidPosition) {
                throw new Toolbox.Failure(Toolbox.Code.INVALID_CURSOR, "invalid Modonomicon relationship position");
            }
            throw new Toolbox.Failure(Toolbox.Code.INVALID_CURSOR, "invalid Modonomicon relationship position");
        }

        private boolean isPagePosition() {
            return kind == RelationshipKind.PAGE_START || kind == RelationshipKind.SPOTLIGHT
                    || kind == RelationshipKind.RECIPE || kind == RelationshipKind.CONDITION && pageIndex >= 0;
        }
    }

    private static final class RelationshipCollector {
        private enum Decision { SKIP, PROCESS, STOP }

        private final Toolbox.DocumentBudget budget;
        private final String resumePosition;
        private final RelationshipPosition position;
        private final boolean startsAtBeginning;
        private final Map<String, List<Evidence.JsonObject>> values = new LinkedHashMap<>();
        private final Map<String, Evidence.Coverage> coverage = new LinkedHashMap<>();
        private final Map<String, String> details = new LinkedHashMap<>();
        private String nextPosition;
        private boolean started;
        private boolean stopped;
        private boolean pageGroupsApplicable;
        private int opaqueMask;
        private int partialMask;
        private int outputRecords;
        private int relationshipBytes;
        private boolean valueBudgetExhausted;

        private RelationshipCollector(String resumePosition, int opaqueMask, int partialMask,
                                      Toolbox.DocumentBudget budget) {
            this.resumePosition = resumePosition;
            this.position = RelationshipPosition.parse(resumePosition);
            this.startsAtBeginning = resumePosition == null;
            this.started = startsAtBeginning;
            this.opaqueMask = opaqueMask;
            this.partialMask = partialMask;
            this.budget = budget;
            if ((opaqueMask & ITEMS_OPAQUE) != 0) markOpaque("items", "earlier Modonomicon item declarations remain opaque");
            if ((opaqueMask & RECIPES_OPAQUE) != 0) markOpaque("recipes", "earlier Modonomicon recipe declarations remain opaque");
            if ((opaqueMask & PREREQUISITES_OPAQUE) != 0) markOpaque("prerequisites", "earlier Modonomicon prerequisite declarations remain opaque");
            if ((partialMask & ITEMS_OPAQUE) != 0) markPartial("items", "earlier Modonomicon item pages remain unread", true);
            if ((partialMask & RECIPES_OPAQUE) != 0) markPartial("recipes", "earlier Modonomicon recipe pages remain unread", true);
            if ((partialMask & PREREQUISITES_OPAQUE) != 0) markPartial("prerequisites", "earlier Modonomicon page conditions remain unread", true);
        }

        private Decision begin(String position, String group) {
            if (stopped) return Decision.STOP;
            if (!started) {
                if (!position.equals(resumePosition)) return Decision.SKIP;
                started = true;
            }
            if (outputRecords >= MAX_RELATIONSHIP_RECORDS) {
                nextPosition = position;
                stopped = true;
                markPartial(group, "Modonomicon relationship output reached its 16-record fragment bound", false);
                markUnfinishedGroups(group);
                return Decision.STOP;
            }
            if (!budget.value()) {
                nextPosition = position;
                stopped = true;
                valueBudgetExhausted = true;
                markPartial(group, "Modonomicon relationship extraction reached its value budget", false);
                return Decision.STOP;
            }
            return Decision.PROCESS;
        }

        private boolean record(String position, String group, Supplier<Evidence.JsonObject> value) {
            var decision = begin(position, group);
            return decision != Decision.PROCESS || append(position, group, value.get());
        }

        private boolean append(String position, String group, Evidence.JsonObject value) {
            var groupValues = values.computeIfAbsent(group, ignored -> new ArrayList<>());
            groupValues.add(value);
            var previousBytes = relationshipBytes;
            relationshipBytes = encodedBytes(jsonObject());
            if (relationshipBytes > MAX_RELATIONSHIP_BYTES || relationshipBytes > budget.remainingBytes()) {
                groupValues.removeLast();
                relationshipBytes = previousBytes;
                nextPosition = position;
                stopped = true;
                markPartial(group, "Modonomicon relationship output reached its byte bound", false);
                return false;
            }
            outputRecords++;
            return true;
        }

        private boolean chargeTraversal(String position, String group) {
            if (stopped) return false;
            if (!budget.value()) {
                nextPosition = started ? position : resumePosition;
                stopped = true;
                valueBudgetExhausted = true;
                markPartial(group, "Modonomicon page visibility reached its value budget", false);
                return false;
            }
            return true;
        }

        private boolean chargeConditionSeek() {
            if (stopped) return false;
            if (!budget.value()) {
                nextPosition = resumePosition;
                stopped = true;
                valueBudgetExhausted = true;
                markPartial("prerequisites", "Modonomicon condition seeking reached its value budget", false);
                return false;
            }
            return true;
        }

        private boolean chargeExtraction(String position) {
            if (budget.value()) return true;
            nextPosition = position;
            stopped = true;
            valueBudgetExhausted = true;
            markPartial("items", "Modonomicon ingredient extraction reached its value budget", false);
            return false;
        }

        private void markPartial(String group, String detail, boolean persist) {
            if (coverage.get(group) == Evidence.Coverage.OPAQUE) return;
            coverage.put(group, Evidence.Coverage.PARTIAL);
            details.put(group, detail);
            if (persist) partialMask |= switch (group) {
                case "items" -> ITEMS_OPAQUE;
                case "recipes" -> RECIPES_OPAQUE;
                default -> PREREQUISITES_OPAQUE;
            };
        }

        private void markOpaque(String group, String detail) {
            coverage.put(group, Evidence.Coverage.OPAQUE);
            details.put(group, detail);
            var groupMask = switch (group) {
                case "items" -> ITEMS_OPAQUE;
                case "recipes" -> RECIPES_OPAQUE;
                default -> PREREQUISITES_OPAQUE;
            };
            opaqueMask |= groupMask;
            partialMask &= ~groupMask;
        }

        private void markUnfinishedGroups(String group) {
            if ("prerequisites".equals(group)) {
                markPartial("items", "Modonomicon relationship continuation has not reached item pages", false);
                markPartial("recipes", "Modonomicon relationship continuation has not reached recipe pages", false);
            } else {
                markPartial("items", "Modonomicon item declarations continue in a later fragment", false);
                markPartial("recipes", "Modonomicon recipe declarations continue in a later fragment", false);
            }
        }

        private void finishPages() {
            if (startsAtBeginning && !stopped && pageGroupsApplicable) {
                values.computeIfAbsent("items", ignored -> new ArrayList<>());
                values.computeIfAbsent("recipes", ignored -> new ArrayList<>());
            }
            if (!stopped && resumePosition != null && !started)
                throw new Toolbox.Failure(Toolbox.Code.INVALID_CURSOR, "Modonomicon relationship source position no longer exists");
        }

        private void verifyResume() {
            if (!stopped && resumePosition != null && !started)
                throw new Toolbox.Failure(Toolbox.Code.INVALID_CURSOR, "Modonomicon relationship source position no longer exists");
        }

        private Evidence.JsonObject jsonObject() {
            var groups = new LinkedHashMap<String, Evidence.JsonValue>();
            for (var group : List.of("items", "recipes", "prerequisites")) {
                if (!values.containsKey(group) && !coverage.containsKey(group)) continue;
                var groupValue = new LinkedHashMap<String, Evidence.JsonValue>();
                var groupCoverage = coverage.getOrDefault(group, Evidence.Coverage.COMPLETE);
                groupValue.put("coverage", new Evidence.JsonString(groupCoverage.name()));
                var groupRecords = values.getOrDefault(group, List.of()).stream().map(value -> (Evidence.JsonValue) value).toList();
                groupValue.put("values", new Evidence.JsonArray(groupRecords));
                if (groupCoverage != Evidence.Coverage.COMPLETE)
                    groupValue.put("detail", new Evidence.JsonString(details.getOrDefault(group,
                            "Modonomicon relationship data remains incomplete")));
                groups.put(group, new Evidence.JsonObject(groupValue));
            }
            return new Evidence.JsonObject(groups);
        }

        private int encodedBytes(Evidence.JsonObject value) {
            return Evidence.serialize(value).getBytes(StandardCharsets.UTF_8).length;
        }

        private int jsonBytes() { return relationshipBytes = encodedBytes(jsonObject()); }

        private Evidence.Coverage coverage() {
            return coverage.values().stream().anyMatch(value -> value != Evidence.Coverage.COMPLETE)
                    ? Evidence.Coverage.PARTIAL : Evidence.Coverage.COMPLETE;
        }

        private String detail() {
            return details.values().stream().findFirst().orElse(null);
        }

        private RelationshipPosition position() { return position; }

        private String nextPosition() { return nextPosition; }

        private int opaqueMask() { return opaqueMask; }

        private int partialMask() { return partialMask; }

        private boolean stopped() { return stopped; }

        private boolean started() { return started; }

        private boolean valueBudgetExhausted() { return valueBudgetExhausted; }
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
