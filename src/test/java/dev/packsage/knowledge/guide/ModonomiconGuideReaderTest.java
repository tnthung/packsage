package dev.packsage.knowledge.guide;

import com.klikli_dev.modonomicon.book.Book;
import com.klikli_dev.modonomicon.book.BookCategory;
import com.klikli_dev.modonomicon.book.BookEntryParent;
import com.klikli_dev.modonomicon.book.BookIcon;
import com.klikli_dev.modonomicon.book.BookTextHolder;
import com.klikli_dev.modonomicon.book.ResolvedBookEntryParent;
import com.klikli_dev.modonomicon.book.conditions.BookAdvancementCondition;
import com.klikli_dev.modonomicon.book.conditions.BookAndCondition;
import com.klikli_dev.modonomicon.book.conditions.BookCategoryHasVisibleEntriesCondition;
import com.klikli_dev.modonomicon.book.conditions.BookCondition;
import com.klikli_dev.modonomicon.book.conditions.BookEntryReadCondition;
import com.klikli_dev.modonomicon.book.conditions.BookEntryUnlockedCondition;
import com.klikli_dev.modonomicon.book.conditions.BookFalseCondition;
import com.klikli_dev.modonomicon.book.conditions.BookModLoadedCondition;
import com.klikli_dev.modonomicon.book.conditions.BookNoneCondition;
import com.klikli_dev.modonomicon.book.conditions.BookOrCondition;
import com.klikli_dev.modonomicon.book.conditions.BookTrueCondition;
import com.klikli_dev.modonomicon.book.entries.BookContentEntry;
import com.klikli_dev.modonomicon.book.entries.BookEntry;
import com.klikli_dev.modonomicon.book.entries.BookEntry.BookEntryData;
import com.klikli_dev.modonomicon.book.page.BookPage;
import com.klikli_dev.modonomicon.book.page.BookRecipePage;
import com.klikli_dev.modonomicon.book.page.BookSpotlightPage;
import com.klikli_dev.modonomicon.book.page.BookTextPage;
import com.klikli_dev.modonomicon.bookstate.BookStatesSaveData;
import com.klikli_dev.modonomicon.bookstate.BookUnlockStateManager;
import com.klikli_dev.modonomicon.bookstate.BookUnlockStates;
import com.klikli_dev.modonomicon.data.BookDataManager;
import com.mojang.datafixers.util.Either;
import dev.packsage.knowledge.Evidence;
import dev.packsage.knowledge.KnowledgeProvider;
import dev.packsage.knowledge.Toolbox;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.Registries;
import net.minecraft.locale.Language;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModonomiconGuideReaderTest {
    private static final Evidence.Scope SCOPE = new Evidence.Scope("world", "en_us", "team", "runtime", 1);

    @Test
    void factoryExposesTheLogicalProviderAndVerifiedRuntimeVersion() {
        KnowledgeProvider provider = ModonomiconGuideReader.forMinecraft(() -> SCOPE, Runnable::run, "1.120.7");

        assertEquals("modonomicon", provider.id());
        assertEquals("modonomicon", provider.metadata().id());
        assertEquals(java.util.List.of("guide"), provider.metadata().documentTypes());
        assertEquals(KnowledgeProvider.Availability.AVAILABLE, provider.status().availability());
        assertEquals("1.120.7", provider.status().version());
    }

    @Test
    void malformedEntryIdentityIsRejectedBeforeReadingTheLoadedBookRegistry() {
        var reader = new ModonomiconGuideReader();

        var failure = assertThrows(dev.packsage.knowledge.Toolbox.Failure.class,
                () -> reader.readFragment("invalid", null, 256, new dev.packsage.knowledge.Toolbox.DocumentBudget()));

        assertEquals(dev.packsage.knowledge.Toolbox.Code.INVALID_REFERENCE, failure.code());
    }

    @Test
    void malformedFragmentPositionIsRejectedBeforeReadingTheLoadedBookRegistry() {
        var reader = new ModonomiconGuideReader();

        var failure = assertThrows(dev.packsage.knowledge.Toolbox.Failure.class,
                () -> reader.readFragment("test:book|test:category|test:entry", "bad", 256,
                        new dev.packsage.knowledge.Toolbox.DocumentBudget()));

        assertEquals(dev.packsage.knowledge.Toolbox.Code.INVALID_CURSOR, failure.code());
    }

    @Test
    void localizedBookTextFragmentsRetainOverlapAndAdvanceAcrossFields() {
        var holder = new BookTextHolder("packsage.fixture.long_text");
        var source = holder.getKey() + "x".repeat(600);
        var first = ModonomiconGuideReader.textFragment(source, 0, 0, 0, 1, 512,
                new Toolbox.DocumentBudget(), false);
        var second = ModonomiconGuideReader.textFragment(source, 0, 0, 512, 1, 512,
                new Toolbox.DocumentBudget(), false);
        var title = ModonomiconGuideReader.textFragment("Title", 0, 0, 0, 1, 256,
                new Toolbox.DocumentBudget(), false);
        var body = ModonomiconGuideReader.textFragment("Body", 0, 1, 0, 1, 256,
                new Toolbox.DocumentBudget(), false);

        assertEquals("0:0:512", first.nextPosition());
        assertEquals(GuideDocumentReader.FRAGMENT_OVERLAP_CHARS, second.overlapChars());
        assertEquals(512, second.text().length());
        assertEquals("0:0:513", second.nextPosition());
        assertTrue(second.text().startsWith(source.substring(1, 512)));
        assertEquals("0:1:0", title.nextPosition());
        assertNull(body.nextPosition());
    }

    @Test
    void textFragmentFailsInsteadOfReturningAnUnadvanceableEmptyPage() {
        var budget = new Toolbox.DocumentBudget();
        assertTrue(budget.bytes(budget.remainingBytes()));

        var failure = assertThrows(Toolbox.Failure.class, () -> ModonomiconGuideReader.textFragment(
                "x", 0, 0, 0, 1, 1, budget, false));

        assertEquals(Toolbox.Code.EXTRACTION_FAILED, failure.code());
    }

    @Test
    void literalAndTranslatedBookTextHoldersResolveWithoutRenderingWholeComponents() {
        var language = new FixtureLanguage(Map.of("packsage.fixture.translation", "Translated fixture text"));
        var literal = ModonomiconGuideReader.localizedText(new BookTextHolder(Component.literal("Literal fixture text")), language);
        var translatedString = ModonomiconGuideReader.localizedText(new BookTextHolder("packsage.fixture.translation"), language);
        var translatedComponent = ModonomiconGuideReader.localizedText(
                new BookTextHolder(Component.translatable("packsage.fixture.translation")), language);
        var missing = ModonomiconGuideReader.localizedText(new BookTextHolder("packsage.fixture.missing"), language);

        assertEquals("Literal fixture text", literal.text());
        assertEquals("Translated fixture text", translatedString.text());
        assertEquals("Translated fixture text", translatedComponent.text());
        assertEquals("packsage.fixture.missing", missing.text());
        assertTrue(missing.partial());
    }

    @Test
    void nativeSpotlightsAndRecipeSlotsShareTheirSourceFragmentWithText() throws Exception {
        try (var fixture = new ModonomiconFixture()) {
            var lockedText = new BookTextPage(new BookTextHolder("Locked page title"), new BookTextHolder("Locked page body"),
                    false, true, null, null);
            var hiddenSpotlight = spotlight(new ItemStack(Items.DIAMOND));
            var stackSpotlight = spotlight(new ItemStack(Items.BREAD));
            var tagSpotlight = new BookSpotlightPage(new BookTextHolder("Tag spotlight"), new BookTextHolder(""),
                    Either.right(Ingredient.of(TagKey.create(Registries.ITEM, ResourceLocation.parse("fixture:spotlight_items")))),
                    null, null);
            var recipePage = new FixtureRecipePage(ResourceLocation.parse("fixture:first_recipe"),
                    ResourceLocation.parse("fixture:missing_recipe"));
            var textPage = new BookTextPage(new BookTextHolder("Relationship title"),
                    new BookTextHolder("Relationship body"), false, true, null, null);
            var entry = fixture.entry("fixture:entry", "Relationship entry",
                    List.of(lockedText, hiddenSpotlight, stackSpotlight, tagSpotlight, recipePage, textPage), null,
                    List.of(), new BookIcon(new ItemStack(Items.DIAMOND)), false, false);
            fixture.unlock(entry, List.of(2, 3, 4, 5));

            var fragment = new ModonomiconGuideReader("1.120.7").readFragment(fixture.identity(entry), null, 1024,
                    new Toolbox.DocumentBudget());
            var items = relationshipValues(fragment, "items");
            var recipes = relationshipValues(fragment, "recipes");
            var itemIds = items.stream().map(value -> value.values().get("itemId"))
                    .filter(Evidence.JsonString.class::isInstance).map(Evidence.JsonString.class::cast)
                    .map(Evidence.JsonString::value).toList();
            var tagIds = items.stream().map(value -> value.values().get("tagId"))
                    .filter(Evidence.JsonString.class::isInstance).map(Evidence.JsonString.class::cast)
                    .map(Evidence.JsonString::value).toList();
            var recipeIds = recipes.stream().map(value -> value.values().get("recipeId"))
                    .filter(Evidence.JsonString.class::isInstance).map(Evidence.JsonString.class::cast)
                    .map(Evidence.JsonString::value).toList();

            assertEquals(List.of("minecraft:bread"), itemIds, "entry icons and locked pages are not associations");
            assertEquals(List.of("fixture:spotlight_items"), tagIds);
            assertEquals(List.of("fixture:first_recipe", "fixture:missing_recipe"), recipeIds,
                    "both declared recipe slots are references even when a recipe is absent");
            assertTrue(recipes.stream().allMatch(value -> value.values().containsKey("recipeType")));
            assertTrue(items.stream().anyMatch(value -> "page:2".equals(jsonString(value, "sourcePosition"))));
            assertTrue(items.stream().anyMatch(value -> "page:3".equals(jsonString(value, "sourcePosition"))));
            assertTrue(items.stream().allMatch(value -> "spotlight".equals(jsonString(value, "kind"))));
            assertTrue(recipes.stream().allMatch(value -> "page:4".equals(jsonString(value, "sourcePosition"))));
            assertEquals("Relationship title", fragment.text());
        }
    }

    @Test
    void nativePrerequisiteTreeKeepsParentEdgesAndScopedConditionsSeparate() throws Exception {
        try (var fixture = new ModonomiconFixture()) {
            var page = new BookTextPage(new BookTextHolder("Condition title"), new BookTextHolder("Condition body"),
                    false, true, null,
                    new BookAdvancementCondition(Component.literal("page"), ResourceLocation.parse("fixture:page_advancement")));
            var rootCondition = new BookAndCondition(Component.literal("and"), new BookCondition[]{
                    new BookTrueCondition(),
                    new BookOrCondition(Component.literal("or"), new BookCondition[]{
                            new BookFalseCondition(Component.literal("false")),
                            new BookEntryUnlockedCondition(Component.literal("unlocked"), ResourceLocation.parse("fixture:prerequisite")),
                            new BookNoneCondition()
                    }),
                    new BookAdvancementCondition(Component.literal("advancement"), ResourceLocation.parse("fixture:advancement")),
                    new BookEntryReadCondition(Component.literal("read"), ResourceLocation.parse("fixture:read_entry")),
                    new BookModLoadedCondition(Component.literal("mod"), "fixturemod"),
                    new BookCategoryHasVisibleEntriesCondition(Component.literal("category"), ResourceLocation.parse("fixture:category")),
                    new OpaqueCondition()
            });
            var parent = fixture.entry("fixture:parent", "Parent", List.of(), new BookTrueCondition(), List.of(),
                    null, false, false);
            var secondParent = fixture.entry("fixture:second_parent", "Second parent", List.of(), new BookTrueCondition(),
                    List.of(), null, false, false);
            var entry = fixture.entry("fixture:condition_entry", "Condition entry", List.of(page), rootCondition,
                    List.of(parent, secondParent), null, false, false);
            fixture.categoryCondition(new BookModLoadedCondition(Component.literal("category"), "categorymod"));
            fixture.unlock(parent, List.of());
            fixture.unlock(secondParent, List.of());
            fixture.unlock(entry, List.of(0));

            var fragment = new ModonomiconGuideReader("1.120.7").readFragment(fixture.identity(entry), null, 1024,
                    new Toolbox.DocumentBudget());
            var prerequisites = relationshipValues(fragment, "prerequisites");
            var conditionValues = prerequisites.stream().filter(value -> "condition".equals(jsonString(value, "kind"))).toList();
            var childCounts = conditionValues.stream().map(value -> jsonNumber(value, "childCount")).filter(java.util.Objects::nonNull).toList();
            var conditionPaths = conditionValues.stream().map(value -> value.values().get("path")).toList();
            assertTrue(prerequisites.stream().anyMatch(value -> "parent".equals(jsonString(value, "kind"))
                    && "fixture:parent".equals(jsonString(value, "entryId"))));
            assertTrue(prerequisites.stream().anyMatch(value -> "parent".equals(jsonString(value, "kind"))
                    && "fixture:second_parent".equals(jsonString(value, "entryId"))));
            assertTrue(prerequisites.stream().anyMatch(value -> "condition".equals(jsonString(value, "kind"))
                    && "fixture:opaque_condition".equals(jsonString(value, "type"))));
            assertTrue(prerequisites.stream().anyMatch(value -> "fixture:advancement".equals(jsonString(value, "advancementId"))));
            assertTrue(prerequisites.stream().anyMatch(value -> "fixture:read_entry".equals(jsonString(value, "entryId"))));
            assertTrue(prerequisites.stream().anyMatch(value -> "fixturemod".equals(jsonString(value, "modId"))));
            assertTrue(prerequisites.stream().anyMatch(value -> "fixture:category".equals(jsonString(value, "categoryId"))));
            assertTrue(prerequisites.stream().anyMatch(value -> "fixture:page_advancement".equals(jsonString(value, "advancementId"))
                    && "page".equals(jsonString(value, "scope"))));
            assertTrue(prerequisites.stream().anyMatch(value -> "category".equals(jsonString(value, "scope"))
                    && "categorymod".equals(jsonString(value, "modId"))));
            assertTrue(prerequisites.stream().anyMatch(value -> "entry".equals(jsonString(value, "scope"))
                    && "UNLOCKED".equals(jsonString(value, "state"))));
            assertTrue(childCounts.contains(7));
            assertTrue(childCounts.contains(3));
            assertEquals(conditionPaths.size(), new HashSet<>(conditionPaths).size());
            assertTrue(conditionPaths.stream().allMatch(path -> path != null));
            assertEquals("entry", jsonString(conditionValues.stream()
                    .filter(value -> "fixture:advancement".equals(jsonString(value, "advancementId")))
                    .findFirst().orElseThrow(), "scope"));
            var prerequisiteGroup = (Evidence.JsonObject) fragment.relationships().values().get("prerequisites");
            assertTrue(List.of("PARTIAL", "OPAQUE").contains(jsonString(prerequisiteGroup, "coverage")));
            assertTrue(jsonString(prerequisiteGroup, "detail") != null && !jsonString(prerequisiteGroup, "detail").isBlank()
                    && jsonString(prerequisiteGroup, "detail").length() <= 2048);
        }
    }

    @Test
    void visibleLockedEntriesExposePrerequisitesButNeverLockedPagesAndHiddenEntriesStayUnavailable() throws Exception {
        try (var fixture = new ModonomiconFixture()) {
            var visibleParent = fixture.entry("fixture:visible_parent", "Visible parent", List.of(), new BookTrueCondition(),
                    List.of(), null, false, false);
            var lockedParent = fixture.entry("fixture:locked_parent", "Locked parent", List.of(), new BookTrueCondition(),
                    List.of(), null, false, false);
            var lockedPage = new BookTextPage(new BookTextHolder("Locked title"), new BookTextHolder("Secret body"),
                    false, true, null, null);
            var spotlight = spotlight(new ItemStack(Items.NETHER_STAR));
            var visibleLocked = fixture.entry("fixture:visible_locked", "Visible locked", List.of(lockedPage, spotlight),
                    new BookEntryReadCondition(Component.literal("read"), ResourceLocation.parse("fixture:prerequisite")),
                    List.of(visibleParent, lockedParent), null, false, true);
            var hidden = fixture.entry("fixture:hidden", "Hidden title", List.of(
                    new BookTextPage(new BookTextHolder("Hidden title"), new BookTextHolder("Hidden body"), false, true, null, null)),
                    new BookTrueCondition(), List.of(lockedParent), null, false, true);
            fixture.unlock(visibleParent, List.of());

            var candidates = new ModonomiconGuideReader("1.120.7").candidates(null, 16, new Toolbox.DocumentBudget());
            assertTrue(candidates.candidates().stream().anyMatch(candidate -> candidate.id().equals(fixture.identity(visibleLocked))));
            assertTrue(candidates.candidates().stream().noneMatch(candidate -> candidate.id().equals(fixture.identity(hidden))));

            var fragment = new ModonomiconGuideReader("1.120.7").readFragment(fixture.identity(visibleLocked), null, 1024,
                    new Toolbox.DocumentBudget());
            assertEquals("", fragment.text());
            assertTrue(relationshipValues(fragment, "items").isEmpty());
            assertTrue(relationshipValues(fragment, "recipes").isEmpty());
            assertTrue(relationshipValues(fragment, "prerequisites").stream().anyMatch(value ->
                    "fixture:visible_parent".equals(jsonString(value, "entryId"))));
            var lockedFailure = assertThrows(Toolbox.Failure.class,
                    () -> new ModonomiconGuideReader("1.120.7").readFragment(fixture.identity(hidden), null, 1024,
                            new Toolbox.DocumentBudget()));
            assertEquals(Toolbox.Code.INVALID_REFERENCE, lockedFailure.code());
        }
    }

    @Test
    void nativeSpotlightRelationshipsStopAtTheFragmentBoundAndResumeFromTheReturnedPosition() throws Exception {
        try (var fixture = new ModonomiconFixture()) {
            var pages = new ArrayList<BookPage>();
            var unlockedPages = new ArrayList<Integer>();
            for (var index = 0; index < 18; index++) {
                pages.add(spotlight(new ItemStack(Items.BREAD)));
                unlockedPages.add(index);
            }
            var entry = fixture.entry("fixture:many_spotlights", "Many spotlights", pages,
                    new BookTrueCondition(), List.of(), null, false, false);
            fixture.unlock(entry, unlockedPages);
            var reader = new ModonomiconGuideReader("1.120.7");

            var first = reader.readFragment(fixture.identity(entry), null, 1024, new Toolbox.DocumentBudget());
            var firstValues = relationshipValues(first, "items");
            assertTrue(firstValues.size() < 18);
            assertTrue(relationshipValues(first, "items").size() + relationshipValues(first, "recipes").size()
                    + relationshipValues(first, "prerequisites").size() <= 16);
            assertEquals("PARTIAL", jsonString((Evidence.JsonObject) first.relationships().values().get("items"), "coverage"));
            assertTrue(first.nextPosition() != null && !first.nextPosition().isBlank());
            var second = reader.readFragment(fixture.identity(entry), first.nextPosition(), 1024, new Toolbox.DocumentBudget());
            var allValues = new ArrayList<Evidence.JsonObject>(firstValues);
            allValues.addAll(relationshipValues(second, "items"));
            var sourcePositions = allValues.stream().map(value -> jsonString(value, "sourcePosition")).toList();

            assertEquals(18, sourcePositions.size());
            assertEquals(18, new HashSet<>(sourcePositions).size());
            assertEquals("COMPLETE", jsonString((Evidence.JsonObject) second.relationships().values().get("items"), "coverage"));
            assertNull(second.nextPosition());
        }
    }

    private static BookSpotlightPage spotlight(ItemStack stack) {
        return new BookSpotlightPage(new BookTextHolder("Spotlight"), new BookTextHolder(""), Either.left(stack), null, null);
    }

    private static List<Evidence.JsonObject> relationshipValues(GuideDocumentReader.Fragment fragment, String group) {
        var groupValue = fragment.relationships().values().get(group);
        if (!(groupValue instanceof Evidence.JsonObject relationshipGroup)
                || !(relationshipGroup.values().get("values") instanceof Evidence.JsonArray values)) return List.of();
        return values.values().stream().filter(Evidence.JsonObject.class::isInstance)
                .map(Evidence.JsonObject.class::cast).toList();
    }

    private static String jsonString(Evidence.JsonObject value, String key) {
        return value.values().get(key) instanceof Evidence.JsonString string ? string.value() : null;
    }

    private static Integer jsonNumber(Evidence.JsonObject value, String key) {
        return value.values().get(key) instanceof Evidence.JsonNumber number ? number.value().intValue() : null;
    }

    private static final class ModonomiconFixture implements AutoCloseable {
        private static final ResourceLocation BOOK_ID = ResourceLocation.parse("fixture:book");
        private static final ResourceLocation CATEGORY_ID = ResourceLocation.parse("fixture:category");
        private static final UUID PLAYER_ID = UUID.fromString("00000000-0000-0000-0000-000000000111");
        private final Minecraft fakeMinecraft;
        private final Object previousMinecraft;
        private final BookDataManager bookManager = BookDataManager.get();
        private final Map<ResourceLocation, Book> previousBooks = new LinkedHashMap<>(bookManager.getBooks());
        private final boolean previousLoaded = bookManager.isLoaded();
        private final boolean previousBuilt = bookManager.areBooksBuilt();
        private final BookUnlockStateManager unlockManager = BookUnlockStateManager.get();
        private final BookStatesSaveData previousSaveData = unlockManager.saveData;
        private final BookUnlockStates unlockStates = new BookUnlockStates();
        private final Book book;
        private final BookCategory category;

        private ModonomiconFixture() throws Exception {
            var unsafeField = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            var unsafe = unsafeField.get(null);
            var allocateInstance = unsafe.getClass().getMethod("allocateInstance", Class.class);
            fakeMinecraft = (Minecraft) allocateInstance.invoke(unsafe, Minecraft.class);
            var fakePlayer = (LocalPlayer) allocateInstance.invoke(unsafe, LocalPlayer.class);
            var minecraftInstance = Minecraft.class.getDeclaredField("instance");
            minecraftInstance.setAccessible(true);
            previousMinecraft = minecraftInstance.get(null);
            var gameThread = Minecraft.class.getDeclaredField("gameThread");
            gameThread.setAccessible(true);
            gameThread.set(fakeMinecraft, Thread.currentThread());
            var playerField = Minecraft.class.getDeclaredField("player");
            playerField.setAccessible(true);
            playerField.set(fakeMinecraft, fakePlayer);
            var uuidField = Entity.class.getDeclaredField("uuid");
            uuidField.setAccessible(true);
            uuidField.set(fakePlayer, PLAYER_ID);

            book = (Book) allocateInstance.invoke(unsafe, Book.class);
            setField(Book.class, "id", book, BOOK_ID);
            setField(Book.class, "categories", book, new LinkedHashMap<ResourceLocation, BookCategory>());
            setField(Book.class, "entries", book, new LinkedHashMap<ResourceLocation, BookEntry>());
            category = (BookCategory) allocateInstance.invoke(unsafe, BookCategory.class);
            setField(BookCategory.class, "id", category, CATEGORY_ID);
            setField(BookCategory.class, "book", category, book);
            setField(BookCategory.class, "entries", category, new LinkedHashMap<ResourceLocation, BookEntry>());
            book.getCategories().put(CATEGORY_ID, category);

            var unlockData = new BookStatesSaveData();
            unlockData.unlockStates.put(PLAYER_ID, unlockStates);
            unlockStates.unlockedCategories.put(BOOK_ID, new HashSet<>(Set.of(CATEGORY_ID)));
            unlockManager.saveData = unlockData;
            try {
                bookManager.getBooks().clear();
                bookManager.getBooks().put(BOOK_ID, book);
                setField(BookDataManager.class, "loaded", bookManager, true);
                setField(BookDataManager.class, "booksBuilt", bookManager, true);
                minecraftInstance.set(null, fakeMinecraft);
            } catch (Exception failure) {
                close();
                throw failure;
            }
        }

        private BookContentEntry entry(String id, String name, List<BookPage> pages, BookCondition condition,
                                       List<BookContentEntry> parents, BookIcon icon, boolean hideWhileLocked,
                                       boolean showWhenAnyParentUnlocked) throws Exception {
            var parentIds = parents.stream().map(parent -> (BookEntryParent) new BookEntryParent(parent.getId())).toList();
            var data = new BookEntryData(CATEGORY_ID, parentIds, 0, 0, name, "Fixture description", icon, 0, 0,
                    condition, hideWhileLocked, showWhenAnyParentUnlocked, 0);
            var entry = new BookContentEntry(ResourceLocation.parse(id), data, null, new ArrayList<>(pages));
            setField(BookEntry.class, "book", entry, book);
            setField(BookEntry.class, "category", entry, category);
            setField(BookEntry.class, "parents", entry, parents.stream().map(parent -> new ResolvedBookEntryParent(
                    new BookEntryParent(parent.getId()), parent)).toList());
            category.getEntries().put(entry.getId(), entry);
            book.getEntries().put(entry.getId(), entry);
            for (var index = 0; index < pages.size(); index++) pages.get(index).build(null, entry, index);
            return entry;
        }

        private void unlock(BookContentEntry entry, List<Integer> pages) {
            unlockStates.unlockedEntries.computeIfAbsent(BOOK_ID, ignored -> new HashSet<>()).add(entry.getId());
            unlockStates.unlockedPages.computeIfAbsent(BOOK_ID, ignored -> new LinkedHashMap<>())
                    .put(entry.getId(), new HashSet<>(pages));
        }

        private void categoryCondition(BookCondition condition) throws Exception {
            setField(BookCategory.class, "condition", category, condition);
        }

        private String identity(BookContentEntry entry) {
            return BOOK_ID + "|" + CATEGORY_ID + "|" + entry.getId();
        }

        private static void setField(Class<?> owner, String name, Object target, Object value) throws Exception {
            Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        }

        @Override
        public void close() throws Exception {
            var minecraftInstance = Minecraft.class.getDeclaredField("instance");
            minecraftInstance.setAccessible(true);
            minecraftInstance.set(null, previousMinecraft);
            bookManager.getBooks().clear();
            bookManager.getBooks().putAll(previousBooks);
            setField(BookDataManager.class, "loaded", bookManager, previousLoaded);
            setField(BookDataManager.class, "booksBuilt", bookManager, previousBuilt);
            unlockManager.saveData = previousSaveData;
        }
    }

    private static final class FixtureRecipePage extends BookRecipePage<Recipe<?>> {
        private FixtureRecipePage(ResourceLocation first, ResourceLocation second) {
            super(RecipeType.CRAFTING, new BookTextHolder("First"), first, new BookTextHolder("Second"), second,
                    new BookTextHolder("Recipe page"), null, null);
        }

        @Override public ResourceLocation getType() { return ResourceLocation.parse("modonomicon:fixture_recipe"); }

        @Override protected ItemStack getRecipeOutput(Level level, RecipeHolder<Recipe<?>> recipe) { return ItemStack.EMPTY; }

        @Override public boolean matchesQuery(String query) { return false; }
    }

    private static final class OpaqueCondition extends BookCondition {
        private OpaqueCondition() { super(Component.literal("custom")); }

        @Override public ResourceLocation getType() { return ResourceLocation.parse("fixture:opaque_condition"); }

        @Override public void toNetwork(RegistryFriendlyByteBuf buffer) { }

        @Override public boolean test(com.klikli_dev.modonomicon.book.conditions.context.BookConditionContext context,
                                      Player player) {
            throw new AssertionError("relationship extraction must not evaluate custom conditions");
        }
    }

    private static final class FixtureLanguage extends Language {
        private final Map<String, String> translations;

        private FixtureLanguage(Map<String, String> translations) {
            this.translations = Map.copyOf(translations);
        }

        @Override public String getOrDefault(String key, String fallback) { return translations.getOrDefault(key, fallback); }
        @Override public boolean has(String key) { return translations.containsKey(key); }
        @Override public boolean isDefaultRightToLeft() { return false; }
        @Override public FormattedCharSequence getVisualOrder(FormattedText text) { return FormattedCharSequence.EMPTY; }
    }
}
