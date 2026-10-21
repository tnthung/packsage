package dev.packsage.knowledge.guide;

import dev.packsage.knowledge.AdapterRegistry;
import dev.packsage.knowledge.Evidence;
import dev.packsage.knowledge.Toolbox;
import dev.packsage.model.PackSageTools;
import guideme.FixtureGuide;
import guideme.Guides;
import guideme.compiler.Frontmatter;
import guideme.compiler.ParsedGuidePage;
import guideme.compiler.tags.RecipeCompiler;
import guideme.libs.mdast.mdx.model.MdxJsxAttribute;
import guideme.libs.mdast.mdx.model.MdxJsxExpressionAttribute;
import guideme.libs.mdast.mdx.model.MdxJsxFlowElement;
import guideme.libs.mdast.mdx.model.MdxJsxTextElement;
import guideme.libs.mdast.model.MdAstHeading;
import guideme.libs.mdast.model.MdAstRoot;
import guideme.libs.mdast.model.MdAstParent;
import guideme.libs.mdast.model.MdAstText;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuideMeGuideReaderTest {
    private static final ResourceLocation GUIDE_ID = ResourceLocation.parse("fixture:book");
    private static final ResourceLocation PAGE_ID = ResourceLocation.parse("fixture:entry");

    private static final Evidence.Scope SCOPE = new Evidence.Scope("world", "en_us", "team", "runtime", 1);
    private static final String PAGE_TARGET = "fixture:book#fixture:entry";
    private static final String ANCHOR_TARGET = "fixture:book#fixture:entry#charging-items";

    @AfterEach
    void clearGuides() {
        Guides.setForTest(List.of());
        RecipeCompiler.setTagNamesForTest(Set.of("Recipe", "RecipeFor", "RecipesFor"));
    }

    @Test
    void anchorForMatchesGuideMeNormalization() {
        assertEquals("charging-items", GuideMeGuideReader.anchorFor("  Charging  Items "));
        assertEquals("a-b", GuideMeGuideReader.anchorFor("A\t\nB"));
    }

    @Test
    void recordsOfferPageTargetAndCitedHeadingAnchor() {
        installHeadingPage(new MdAstText("Charging Items"));
        var reader = new GuideMeGuideReader("21.1.19", new RecordingOpener());
        var candidate = candidate();

        assertEquals(List.of(new Evidence.NavigationTarget(SCOPE, "guideme", PAGE_TARGET, PAGE_TARGET),
                        new Evidence.NavigationTarget(SCOPE, "guideme", PAGE_TARGET, ANCHOR_TARGET)),
                reader.navigationTargets(SCOPE, candidate, fragment("l:2:0")));
        assertEquals(List.of(new Evidence.NavigationTarget(SCOPE, "guideme", PAGE_TARGET, PAGE_TARGET),
                        new Evidence.NavigationTarget(SCOPE, "guideme", PAGE_TARGET, ANCHOR_TARGET)),
                reader.navigationTargets(SCOPE, candidate, fragment("l:1.0:3")));
        assertEquals(List.of(new Evidence.NavigationTarget(SCOPE, "guideme", PAGE_TARGET, PAGE_TARGET)),
                reader.navigationTargets(SCOPE, candidate, fragment("l:0:0")));
        assertEquals(List.of(new Evidence.NavigationTarget(SCOPE, "guideme", PAGE_TARGET, PAGE_TARGET)),
                reader.navigationTargets(SCOPE, candidate, new GuideDocumentReader.Fragment("", PAGE_TARGET + "|first",
                        0, null, Evidence.Coverage.COMPLETE, null)));
    }

    @Test
    void formattedHeadingYieldsNoAnchorTarget() {
        installHeadingPage(new MdAstText("Charging "), new MdxJsxTextElement("ItemLink", List.of()));
        var reader = new GuideMeGuideReader("21.1.19", new RecordingOpener());

        assertEquals(List.of(new Evidence.NavigationTarget(SCOPE, "guideme", PAGE_TARGET, PAGE_TARGET)),
                reader.navigationTargets(SCOPE, candidate(), fragment("l:2:0")));
    }

    @Test
    void headingWithHashYieldsNoUnopenableAnchorTarget() {
        installHeadingPage(new MdAstText("Tier #2"));
        var reader = new GuideMeGuideReader("21.1.19", new RecordingOpener());

        assertEquals(List.of(new Evidence.NavigationTarget(SCOPE, "guideme", PAGE_TARGET, PAGE_TARGET)),
                reader.navigationTargets(SCOPE, candidate(), fragment("l:2:0")));
    }

    @Test
    void savedPageAndAnchorResolveAfterGuideReload() {
        installHeadingPage(new MdAstText("Charging Items"));
        var opener = new RecordingOpener();
        opener.anchors.add("charging-items");
        var reader = new GuideMeGuideReader("21.1.19", opener);
        var savedPage = new Evidence.NavigationTarget(SCOPE, "guideme", "old", PAGE_TARGET);
        var savedAnchor = new Evidence.NavigationTarget(SCOPE, "guideme", "old", ANCHOR_TARGET);
        installHeadingPage(new MdAstText("Charging Items"));

        var page = reader.refreshNavigation(savedPage, SCOPE);
        var anchor = reader.refreshNavigation(savedAnchor, SCOPE);

        assertEquals(new Evidence.NavigationTarget(SCOPE, "guideme", PAGE_TARGET, PAGE_TARGET), page);
        assertEquals(new Evidence.NavigationTarget(SCOPE, "guideme", PAGE_TARGET, ANCHOR_TARGET), anchor);
        assertEquals(Evidence.NavigationStatus.OPENED, reader.open(page, SCOPE).status());
        assertEquals(Evidence.NavigationStatus.OPENED, reader.open(anchor, SCOPE).status());
        assertEquals(List.of(java.util.Arrays.asList("fixture:book", "fixture:entry", null),
                java.util.Arrays.asList("fixture:book", "fixture:entry", "charging-items")), opener.calls);
    }

    @Test
    void missingAnchorLeavesPageTargetWorking() {
        installHeadingPage(new MdAstText("Charging Items"));
        var opener = new RecordingOpener();
        var reader = new GuideMeGuideReader("21.1.19", opener);
        var anchor = new Evidence.NavigationTarget(SCOPE, "guideme", PAGE_TARGET, ANCHOR_TARGET);

        assertNull(reader.refreshNavigation(anchor, SCOPE));
        assertEquals(Evidence.NavigationStatus.UNAVAILABLE, reader.open(anchor, SCOPE).status());
        assertEquals(List.of(), opener.calls);
        assertEquals(Evidence.NavigationStatus.OPENED, reader.open(
                new Evidence.NavigationTarget(SCOPE, "guideme", PAGE_TARGET, PAGE_TARGET), SCOPE).status());
    }

    @Test
    void refreshRejectsMalformedWrongProviderMissingGuideAndPage() {
        installHeadingPage(new MdAstText("Charging Items"));
        var opener = new RecordingOpener();
        opener.anchors.add("charging-items");
        var reader = new GuideMeGuideReader("21.1.19", opener);

        for (var targetId : List.of("fixture:book#fixture:entry#charging-items#extra", "bad id#fixture:entry",
                "fixture:book#", "fixture:book#fixture:entry#", "fixture:book", "fixture:missing#fixture:entry",
                "fixture:book#fixture:missing")) {
            assertNull(reader.refreshNavigation(new Evidence.NavigationTarget(SCOPE, "guideme", "old", targetId), SCOPE), targetId);
        }
        assertNull(reader.refreshNavigation(new Evidence.NavigationTarget(SCOPE, "modonomicon", "old", PAGE_TARGET), SCOPE));
        assertEquals(List.of(), opener.calls);
    }

    @Test
    void openReportsStaleScopeOffThreadChangedHandleAndError() {
        installHeadingPage(new MdAstText("Charging Items"));
        var opener = new RecordingOpener();
        var reader = new GuideMeGuideReader("21.1.19", opener);
        var current = new Evidence.NavigationTarget(SCOPE, "guideme", PAGE_TARGET, PAGE_TARGET);

        assertEquals(Evidence.NavigationStatus.STALE, reader.open(current,
                new Evidence.Scope("other", "en_us", "team", "runtime", 1)).status());
        opener.clientThread = false;
        assertEquals(Evidence.NavigationStatus.UNSUPPORTED, reader.open(current, SCOPE).status());
        opener.clientThread = true;
        assertEquals(Evidence.NavigationStatus.STALE, reader.open(
                new Evidence.NavigationTarget(SCOPE, "guideme", "fixture:book#fixture:other", PAGE_TARGET), SCOPE).status());
        assertEquals(List.of(), opener.calls);
        opener.failure = new IllegalStateException("screen failed");
        assertEquals(Evidence.NavigationStatus.ERROR, reader.open(current, SCOPE).status());
    }

    /** Installs a page shaped as [intro text, heading(children), body text]. */
    private static void installHeadingPage(guideme.libs.mdast.model.MdAstNode... headingChildren) {
        var root = new MdAstRoot();
        root.add(new MdAstText("Intro"));
        var heading = new MdAstHeading();
        heading.depth = 2;
        for (var child : headingChildren) heading.add(child);
        root.add(heading);
        root.add(new MdAstText("Body"));
        Guides.setForTest(List.of(new FixtureGuide(GUIDE_ID, List.of(new ParsedGuidePage(PAGE_ID, "en_us", root)))));
    }

    private static GuideDocumentReader.Candidate candidate() {
        return new GuideDocumentReader.Candidate(PAGE_TARGET, "fixture:entry", PAGE_TARGET + "|base", List.of(),
                Evidence.Coverage.COMPLETE);
    }

    private static GuideDocumentReader.Fragment fragment(String textPosition) {
        return new GuideDocumentReader.Fragment("text", PAGE_TARGET + "|" + textPosition, 0, null,
                Evidence.Coverage.COMPLETE, null);
    }

    private static final class RecordingOpener implements GuideMeGuideReader.PageOpener {
        private final List<List<String>> calls = new java.util.ArrayList<>();
        private final Set<String> anchors = new HashSet<>();
        private boolean clientThread = true;
        private RuntimeException failure;

        @Override public boolean isClientThread() { return clientThread; }

        @Override public boolean anchorExists(ResourceLocation guide, ResourceLocation page, String anchor) {
            return anchors.contains(anchor);
        }

        @Override public void openGuide(ResourceLocation guide, ResourceLocation page, String anchor) {
            if (failure != null) throw failure;
            calls.add(java.util.Arrays.asList(guide.toString(), page.toString(), anchor));
        }
    }

    @Test
    void candidatesExposeCurrentParsedPagesAndContinueAfterTheLastPosition() {
        var first = new ParsedGuidePage(PAGE_ID, "en_us", new MdAstRoot());
        var second = new ParsedGuidePage(ResourceLocation.parse("fixture:second"), "en_us", new MdAstRoot());
        Guides.setForTest(List.of(new FixtureGuide(GUIDE_ID, List.of(first, second))));
        var reader = new GuideMeGuideReader();

        var page = reader.candidates(null, 1, new Toolbox.DocumentBudget());
        var continued = reader.candidates(page.nextPosition(), 1, new Toolbox.DocumentBudget());

        assertEquals("fixture:book#fixture:entry", page.candidates().getFirst().id());
        assertEquals("en_us", page.candidates().getFirst().provenance().getLast().substring("guideme:language:".length()));
        assertTrue(page.more());
        assertEquals("fixture:book#fixture:second", continued.candidates().getFirst().id());
        assertFalse(continued.more());
    }

    @Test
    void exhaustedCandidateBudgetPreservesProgressAndFailsWhenReseekingCannotAdvance() {
        var basePages = new java.util.ArrayList<ParsedGuidePage>();
        var developmentPages = new java.util.ArrayList<ParsedGuidePage>();
        for (int index = 0; index < 2100; index++) {
            var id = ResourceLocation.parse("fixture:page_" + index);
            basePages.add(new ParsedGuidePage(id, "en_us", new MdAstRoot()));
            developmentPages.add(new ParsedGuidePage(id, "en_us", new MdAstRoot()));
        }
        Guides.setForTest(List.of(new FixtureGuide(GUIDE_ID, basePages, developmentPages)));
        var reader = new GuideMeGuideReader();

        var exhausted = reader.candidates(null, 1, new Toolbox.DocumentBudget());

        assertTrue(exhausted.candidates().isEmpty());
        assertNotNull(exhausted.nextPosition());
        assertTrue(exhausted.more());
        assertEquals(Evidence.Coverage.PARTIAL, exhausted.coverage());
        var failure = org.junit.jupiter.api.Assertions.assertThrows(Toolbox.Failure.class,
                () -> reader.candidates(exhausted.nextPosition(), 1, new Toolbox.DocumentBudget()));
        assertEquals(Toolbox.Code.EXTRACTION_FAILED, failure.code());
    }

    @Test
    void exhaustedMetadataByteBudgetLeavesFirstCandidateAvailableToRetry() {
        var page = new ParsedGuidePage(PAGE_ID, "en_us", new MdAstRoot());
        Guides.setForTest(List.of(new FixtureGuide(GUIDE_ID, List.of(page))));
        var reader = new GuideMeGuideReader();
        var exhaustedBudget = new Toolbox.DocumentBudget();
        assertTrue(exhaustedBudget.bytes(96 * 1024 - 200));

        var exhausted = reader.candidates(null, 1, exhaustedBudget);
        var retried = reader.candidates(exhausted.nextPosition(), 1, new Toolbox.DocumentBudget());

        assertTrue(exhausted.candidates().isEmpty());
        assertTrue(exhausted.more());
        assertEquals("fixture:book#fixture:entry", retried.candidates().getFirst().id());
    }

    @Test
    void longAstTextContinuesWithBoundedOverlapAndFreshPageResolution() {
        String source = "needle-".repeat(200);
        var root = new MdAstRoot();
        root.add(new MdAstText(source));
        var page = new ParsedGuidePage(PAGE_ID, "en_us", root);
        Guides.setForTest(List.of(new FixtureGuide(GUIDE_ID, List.of(page))));
        var reader = new GuideMeGuideReader();

        var first = reader.readFragment("fixture:book#fixture:entry", null, 600, new Toolbox.DocumentBudget());
        var second = reader.readFragment("fixture:book#fixture:entry", first.nextPosition(), 600, new Toolbox.DocumentBudget());

        assertEquals(source.substring(0, 600), first.text());
        assertNotNull(first.nextPosition());
        assertEquals(511, second.overlapChars());
        assertEquals(600, second.text().length());
        assertEquals(source.substring(89, 689), second.text());
        assertEquals(Evidence.Coverage.COMPLETE, second.coverage());
    }

    @Test
    void childPathContinuationReadsBeyondThePerCallTraversalBudget() {
        var root = new MdAstRoot();
        var expected = new StringBuilder();
        for (int index = 0; index < 2200; index++) {
            String value = "page-" + index + ";";
            expected.append(value);
            root.add(new MdAstText(value));
        }
        var page = new ParsedGuidePage(PAGE_ID, "en_us", root);
        Guides.setForTest(List.of(new FixtureGuide(GUIDE_ID, List.of(page))));
        var reader = new GuideMeGuideReader();
        String position = null;
        var actual = new StringBuilder();
        var returnedPositions = new HashSet<String>();
        int pages = 0;

        do {
            var fragment = reader.readFragment("fixture:book#fixture:entry", position, 64,
                    new Toolbox.DocumentBudget());
            actual.append(fragment.text());
            position = fragment.nextPosition();
            pages++;
            assertTrue(pages <= 4096, "the full AST should advance within the bounded page count");
            assertTrue(position == null || returnedPositions.add(position), "continuations must advance");
        } while (position != null);

        assertEquals(expected.toString(), actual.toString());
    }

    @Test
    void malformedPageIdentityIsRejectedBeforeOptionalLookup() {
        var failure = org.junit.jupiter.api.Assertions.assertThrows(Toolbox.Failure.class,
                () -> new GuideMeGuideReader().readFragment("bad-page-id", null, 128, new Toolbox.DocumentBudget()));

        assertEquals(Toolbox.Code.INVALID_REFERENCE, failure.code());
    }

    @Test
    void currentPageItemIdsResolveRelativeIdsAndKeepInvalidDeclarationsPartial() {
        var pageId = ResourceLocation.parse("minecraft:entry");
        var page = new ParsedGuidePage(pageId, "en_us", new MdAstRoot(), new Frontmatter(Map.of(
                "item_ids", List.of("diamond", "minecraft:bread", "fixture:missing", 17))));
        Guides.setForTest(List.of(new FixtureGuide(GUIDE_ID, List.of(page))));

        var fragment = new GuideMeGuideReader().readFragment("fixture:book#minecraft:entry", null, 1024,
                new Toolbox.DocumentBudget());
        var group = (Evidence.JsonObject) fragment.relationships().values().get("items");
        var values = (Evidence.JsonArray) group.values().get("values");

        assertEquals("PARTIAL", ((Evidence.JsonString) group.values().get("coverage")).value());
        assertEquals(List.of("minecraft:diamond", "minecraft:bread"), values.values().stream()
                .filter(Evidence.JsonObject.class::isInstance).map(Evidence.JsonObject.class::cast)
                .map(value -> (Evidence.JsonString) value.values().get("itemId"))
                .map(Evidence.JsonString::value).toList());
        assertTrue(values.values().stream().filter(Evidence.JsonObject.class::isInstance)
                .map(Evidence.JsonObject.class::cast).allMatch(value ->
                        "associated".equals(((Evidence.JsonString) value.values().get("kind")).value())));
    }

    @Test
    void emptyItemIdsAreCompleteAndDevelopmentOverridesSupplyCurrentMetadata() {
        var emptyPage = new ParsedGuidePage(PAGE_ID, "en_us", new MdAstRoot(), new Frontmatter(Map.of("item_ids", List.of())));
        Guides.setForTest(List.of(new FixtureGuide(GUIDE_ID, List.of(emptyPage))));
        var emptyFragment = new GuideMeGuideReader().readFragment("fixture:book#fixture:entry", null, 1024,
                new Toolbox.DocumentBudget());
        var emptyGroup = (Evidence.JsonObject) emptyFragment.relationships().values().get("items");
        assertEquals("COMPLETE", ((Evidence.JsonString) emptyGroup.values().get("coverage")).value());
        assertTrue(((Evidence.JsonArray) emptyGroup.values().get("values")).values().isEmpty());

        var base = new ParsedGuidePage(PAGE_ID, "en_us", new MdAstRoot(), new Frontmatter(Map.of(
                "item_ids", List.of("minecraft:diamond"))));
        var development = new ParsedGuidePage(PAGE_ID, "fr_fr", new MdAstRoot(), new Frontmatter(Map.of(
                "item_ids", List.of("minecraft:bread"))));
        var guide = new FixtureGuide(GUIDE_ID, List.of(base), List.of(development));
        Guides.setForTest(List.of(guide));
        var reader = new GuideMeGuideReader();
        var candidates = reader.candidates(null, 8, new Toolbox.DocumentBudget());
        var fragment = reader.readFragment("fixture:book#fixture:entry", null, 1024, new Toolbox.DocumentBudget());
        var itemGroup = (Evidence.JsonObject) fragment.relationships().values().get("items");
        var item = (Evidence.JsonObject) ((Evidence.JsonArray) itemGroup.values().get("values")).values().getFirst();

        assertEquals("fr_fr", candidates.candidates().getFirst().provenance().getLast()
                .substring("guideme:language:".length()));
        assertEquals("minecraft:bread", ((Evidence.JsonString) item.values().get("itemId")).value());
        assertFalse(reader.sourceIdentity(new Evidence.Scope("world", "en_us", "team", "runtime", 1))
                .equals(reader.sourceIdentity(new Evidence.Scope("world", "fr_fr", "team", "runtime", 1))));
    }

    @Test
    void nativeRecipeTagsAndSpreadAttributesPreserveItemsAndTextWithoutExecutingExpressions() {
        RecipeCompiler.setTagNamesForTest(Set.of("Recipe", "RecipeFor", "RecipesFor"));
        var root = new MdAstRoot();
        root.add(new MdAstText("GuideME text survives native expression attributes"));
        root.add(new MdxJsxFlowElement("Recipe", List.of(MdxJsxAttribute.string("id", "fixture:iron_recipe"),
                new MdxJsxExpressionAttribute())));
        var inlineParent = new MdxJsxFlowElement("div", List.of());
        inlineParent.add(new MdxJsxTextElement("RecipeFor", List.of(
                MdxJsxAttribute.string("id", "minecraft:diamond"),
                MdxJsxAttribute.string("fallbackText", "Diamond recipe"),
                MdxJsxAttribute.expression("label", "unread-expression"))));
        root.add(inlineParent);
        root.add(new MdxJsxFlowElement("RecipesFor", List.of(MdxJsxAttribute.string("id", "minecraft:bread"),
                new MdxJsxExpressionAttribute())));
        root.add(new MdxJsxFlowElement("Recipe", List.of(MdxJsxAttribute.expression("id", "unread-expression"))));
        var page = new ParsedGuidePage(PAGE_ID, "en_us", root, new Frontmatter(Map.of(
                "item_ids", List.of("minecraft:emerald"))));
        Guides.setForTest(List.of(new FixtureGuide(GUIDE_ID, List.of(page))));

        var fragment = new GuideMeGuideReader("21.1.19").readFragment("fixture:book#fixture:entry", null, 1024,
                new Toolbox.DocumentBudget());
        assertNotNull(fragment.relationships().values().get("recipes"),
                "native spread attributes must not discard the page's recipe relationships");
        assertNotNull(fragment.relationships().values().get("items"),
                "native spread attributes must not discard parsed page item associations");
        assertTrue(fragment.text().contains("GuideME text survives native expression attributes"));
        var group = (Evidence.JsonObject) fragment.relationships().values().get("recipes");
        var values = ((Evidence.JsonArray) group.values().get("values")).values().stream()
                .filter(Evidence.JsonObject.class::isInstance).map(Evidence.JsonObject.class::cast).toList();
        var recipe = values.stream().filter(value -> "Recipe".equals(((Evidence.JsonString) value.values().get("tag")).value()))
                .filter(value -> value.values().containsKey("recipeId")).findFirst().orElseThrow();
        var recipeFor = values.stream().filter(value -> "RecipeFor".equals(((Evidence.JsonString) value.values().get("tag")).value()))
                .findFirst().orElseThrow();
        var recipesFor = values.stream().filter(value -> "RecipesFor".equals(((Evidence.JsonString) value.values().get("tag")).value()))
                .findFirst().orElseThrow();

        assertEquals("fixture:iron_recipe", ((Evidence.JsonString) recipe.values().get("recipeId")).value());
        assertFalse(recipe.values().containsKey("itemId"), "recipe identities are not output selectors");
        assertEquals("minecraft:diamond", ((Evidence.JsonString) recipeFor.values().get("itemId")).value());
        assertFalse(recipeFor.values().containsKey("recipeId"), "output selectors are not recipe identities");
        assertEquals("Diamond recipe", ((Evidence.JsonString) recipeFor.values().get("fallbackText")).value());
        assertEquals("minecraft:bread", ((Evidence.JsonString) recipesFor.values().get("itemId")).value());
        assertEquals("PARTIAL", ((Evidence.JsonString) group.values().get("coverage")).value());
        assertTrue(group.values().containsKey("detail"));
        var itemGroup = (Evidence.JsonObject) fragment.relationships().values().get("items");
        assertEquals("minecraft:emerald", ((Evidence.JsonString) ((Evidence.JsonObject)
                ((Evidence.JsonArray) itemGroup.values().get("values")).values().getFirst()).values().get("itemId")).value());
    }

    @Test
    void recipesForCapabilityFollowsTheActiveNativeTagSet() {
        RecipeCompiler.setTagNamesForTest(Set.of("Recipe", "RecipeFor"));
        var root = new MdAstRoot();
        root.add(new MdxJsxFlowElement("RecipesFor", List.of(MdxJsxAttribute.string("id", "minecraft:bread"))));
        Guides.setForTest(List.of(new FixtureGuide(GUIDE_ID,
                List.of(new ParsedGuidePage(PAGE_ID, "en_us", root)))));

        var floor = new GuideMeGuideReader("21.1.0").readFragment("fixture:book#fixture:entry", null, 1024,
                new Toolbox.DocumentBudget());
        var floorGroup = (Evidence.JsonObject) floor.relationships().values().get("recipes");
        assertTrue(((Evidence.JsonArray) floorGroup.values().get("values")).values().isEmpty());
        assertEquals("PARTIAL", ((Evidence.JsonString) floorGroup.values().get("coverage")).value());

        RecipeCompiler.setTagNamesForTest(Set.of("Recipe", "RecipeFor", "RecipesFor"));
        var current = new GuideMeGuideReader("21.1.19").readFragment("fixture:book#fixture:entry", null, 1024,
                new Toolbox.DocumentBudget());
        var currentGroup = (Evidence.JsonObject) current.relationships().values().get("recipes");
        assertEquals("RecipesFor", ((Evidence.JsonString) ((Evidence.JsonObject)
                ((Evidence.JsonArray) currentGroup.values().get("values")).values().getFirst()).values().get("tag")).value());
        RecipeCompiler.setTagNamesForTest(Set.of("Recipe", "RecipeFor", "RecipesFor"));
    }

    @Test
    void skippedDescendantsAtTheDepthLimitKeepRecipeCoveragePartial() {
        var root = new MdAstRoot();
        root.add(new MdxJsxFlowElement("RecipeFor", List.of(MdxJsxAttribute.string("id", "minecraft:diamond"))));
        MdAstParent current = root;
        for (int depth = 0; depth < 64; depth++) {
            var nested = new MdAstParent() { };
            current.add(nested);
            current = nested;
        }
        current.add(new MdxJsxFlowElement("RecipeFor", List.of(MdxJsxAttribute.string("id", "minecraft:bread"))));
        Guides.setForTest(List.of(new FixtureGuide(GUIDE_ID,
                List.of(new ParsedGuidePage(PAGE_ID, "en_us", root)))));
        var reader = new GuideMeGuideReader();
        String position = null;
        GuideDocumentReader.Fragment fragment;
        int pages = 0;

        do {
            fragment = reader.readFragment("fixture:book#fixture:entry", position, 128,
                    new Toolbox.DocumentBudget());
            position = fragment.nextPosition();
            pages++;
            assertTrue(pages <= 32, "the bounded depth case should reach a terminal continuation");
        } while (position != null);

        var group = (Evidence.JsonObject) fragment.relationships().values().get("recipes");
        assertEquals("PARTIAL", ((Evidence.JsonString) group.values().get("coverage")).value());
        assertTrue(group.values().containsKey("detail"));
    }

    @Test
    void localRelationshipByteLimitContinuesAtTheUnemittedRecipe() {
        var root = new MdAstRoot();
        for (int index = 0; index < 6; index++) {
            root.add(new MdxJsxFlowElement("RecipeFor", List.of(
                    MdxJsxAttribute.string("id", "minecraft:diamond"),
                    MdxJsxAttribute.string("fallbackText", "record-" + index + ";" + "x".repeat(1300)))));
        }
        Guides.setForTest(List.of(new FixtureGuide(GUIDE_ID,
                List.of(new ParsedGuidePage(PAGE_ID, "en_us", root)))));
        var reader = new GuideMeGuideReader();
        String position = null;
        var returnedPositions = new HashSet<String>();
        var records = new java.util.ArrayList<Evidence.JsonObject>();
        int pages = 0;

        do {
            var fragment = reader.readFragment("fixture:book#fixture:entry", position, 128,
                    new Toolbox.DocumentBudget());
            position = fragment.nextPosition();
            pages++;
            assertTrue(pages <= 8, "a locally full fragment must continue within a bounded number of pages");
            assertTrue(position == null || returnedPositions.add(position), "continuations must advance");
            var group = fragment.relationships().values().get("recipes");
            if (group instanceof Evidence.JsonObject object)
                ((Evidence.JsonArray) object.values().get("values")).values().stream()
                        .filter(Evidence.JsonObject.class::isInstance).map(Evidence.JsonObject.class::cast)
                        .forEach(records::add);
        } while (position != null);

        assertEquals(6, records.size());
        var sixth = records.stream().filter(record -> "record-5;".concat("x".repeat(1300)).equals(
                ((Evidence.JsonString) record.values().get("fallbackText")).value())).findFirst().orElseThrow();
        assertTrue(((Evidence.JsonString) sixth.values().get("sourcePosition")).value().startsWith("ast:"));
    }

    @Test
    void recordLimitIsPendingUntilAllValidPageAssociationsAreEmitted() {
        var itemIds = new java.util.ArrayList<String>();
        for (int index = 0; index < 17; index++) itemIds.add("minecraft:diamond");
        var page = new ParsedGuidePage(PAGE_ID, "en_us", new MdAstRoot(),
                new Frontmatter(Map.of("item_ids", itemIds)));
        Guides.setForTest(List.of(new FixtureGuide(GUIDE_ID, List.of(page))));
        var reader = new GuideMeGuideReader();
        String position = null;
        var returnedPositions = new HashSet<String>();
        var items = new java.util.ArrayList<Evidence.JsonObject>();
        GuideDocumentReader.Fragment terminal;
        int pages = 0;

        do {
            terminal = reader.readFragment("fixture:book#fixture:entry", position, 128,
                    new Toolbox.DocumentBudget());
            position = terminal.nextPosition();
            pages++;
            assertTrue(pages <= 8, "the remaining declaration should be emitted promptly");
            assertTrue(position == null || returnedPositions.add(position), "continuations must advance");
            var group = terminal.relationships().values().get("items");
            if (group instanceof Evidence.JsonObject object)
                ((Evidence.JsonArray) object.values().get("values")).values().stream()
                        .filter(Evidence.JsonObject.class::isInstance).map(Evidence.JsonObject.class::cast)
                        .forEach(items::add);
        } while (position != null);

        assertEquals(17, items.size());
        var group = (Evidence.JsonObject) terminal.relationships().values().get("items");
        assertEquals("COMPLETE", ((Evidence.JsonString) group.values().get("coverage")).value());
    }

    @Test
    void astRecipeCapContinuesThroughAllIdentitiesAndEndsWithCompleteCoverage() {
        RecipeCompiler.setTagNamesForTest(Set.of("Recipe", "RecipeFor", "RecipesFor"));
        var root = new MdAstRoot();
        for (int index = 0; index < 17; index++) {
            root.add(new MdxJsxFlowElement("Recipe", List.of(MdxJsxAttribute.string("id", "fixture:recipe_" + index))));
        }
        Guides.setForTest(List.of(new FixtureGuide(GUIDE_ID,
                List.of(new ParsedGuidePage(PAGE_ID, "en_us", root)))));
        var reader = new GuideMeGuideReader();
        var recipeIds = new HashSet<String>();
        var returnedPositions = new HashSet<String>();
        String position = null;
        GuideDocumentReader.Fragment terminal;
        int pages = 0;

        do {
            terminal = reader.readFragment("fixture:book#fixture:entry", position, 128,
                    new Toolbox.DocumentBudget());
            var group = terminal.relationships().values().get("recipes");
            assertNotNull(group, "each relationship fragment must retain its recipe group");
            ((Evidence.JsonArray) ((Evidence.JsonObject) group).values().get("values")).values().stream()
                    .filter(Evidence.JsonObject.class::isInstance).map(Evidence.JsonObject.class::cast)
                    .map(value -> (Evidence.JsonString) value.values().get("recipeId"))
                    .map(Evidence.JsonString::value).forEach(recipeIds::add);
            position = terminal.nextPosition();
            pages++;
            assertTrue(pages <= 8, "the AST declaration cap must advance within a bounded number of fragments");
            assertTrue(position == null || returnedPositions.add(position), "AST recipe continuations must advance");
        } while (position != null);

        assertEquals(17, recipeIds.size());
        for (int index = 0; index < 17; index++) assertTrue(recipeIds.contains("fixture:recipe_" + index));
        var terminalGroup = (Evidence.JsonObject) terminal.relationships().values().get("recipes");
        assertEquals("COMPLETE", ((Evidence.JsonString) terminalGroup.values().get("coverage")).value());
    }

    @Test
    void relationshipReadUsesTheCurrentlySelectedLocalizedPage() {
        var english = new ParsedGuidePage(PAGE_ID, "en_us", new MdAstRoot(), new Frontmatter(Map.of(
                "item_ids", List.of("minecraft:diamond"))));
        var french = new ParsedGuidePage(PAGE_ID, "fr_fr", new MdAstRoot(), new Frontmatter(Map.of(
                "item_ids", List.of("minecraft:bread"))));
        var reader = new GuideMeGuideReader();
        Guides.setForTest(List.of(new FixtureGuide(GUIDE_ID, List.of(english))));
        var englishRead = reader.readFragment("fixture:book#fixture:entry", null, 128,
                new Toolbox.DocumentBudget());
        Guides.setForTest(List.of(new FixtureGuide(GUIDE_ID, List.of(french))));
        var frenchRead = reader.readFragment("fixture:book#fixture:entry", null, 128,
                new Toolbox.DocumentBudget());

        var englishGroup = (Evidence.JsonObject) englishRead.relationships().values().get("items");
        var frenchGroup = (Evidence.JsonObject) frenchRead.relationships().values().get("items");
        assertEquals("minecraft:diamond", ((Evidence.JsonString) ((Evidence.JsonObject)
                ((Evidence.JsonArray) englishGroup.values().get("values")).values().getFirst()).values().get("itemId")).value());
        assertEquals("minecraft:bread", ((Evidence.JsonString) ((Evidence.JsonObject)
                ((Evidence.JsonArray) frenchGroup.values().get("values")).values().getFirst()).values().get("itemId")).value());
    }

    @Test
    void relationshipAndTextContinuationsRereadFreshMetadataAndAst() {
        var itemIds = new java.util.ArrayList<String>();
        for (int index = 0; index < 18; index++) itemIds.add("minecraft:diamond");
        var root = new MdAstRoot();
        root.add(new MdxJsxTextElement("RecipeFor", List.of(MdxJsxAttribute.string("id", "minecraft:bread"))));
        root.add(new MdAstText("page text ".repeat(120)));
        var page = new ParsedGuidePage(PAGE_ID, "en_us", root, new Frontmatter(Map.of("item_ids", itemIds)));
        Guides.setForTest(List.of(new FixtureGuide(GUIDE_ID, List.of(page))));
        var reader = new GuideMeGuideReader();
        String position = null;
        var fragments = new java.util.ArrayList<GuideDocumentReader.Fragment>();

        do {
            var fragment = reader.readFragment("fixture:book#fixture:entry", position, 600,
                    new Toolbox.DocumentBudget());
            fragments.add(fragment);
            position = fragment.nextPosition();
            assertTrue(fragments.size() <= 64, "bounded continuation must make progress");
        } while (position != null);

        var itemValues = fragments.stream().map(GuideDocumentReader.Fragment::relationships)
                .map(value -> value.values().get("items")).filter(Evidence.JsonObject.class::isInstance)
                .map(Evidence.JsonObject.class::cast).flatMap(group -> ((Evidence.JsonArray) group.values().get("values"))
                        .values().stream()).filter(Evidence.JsonObject.class::isInstance).map(Evidence.JsonObject.class::cast).toList();
        assertEquals(18, itemValues.size());
        assertTrue(fragments.stream().anyMatch(fragment -> fragment.text().contains("page text")));

        var replacementRoot = new MdAstRoot();
        replacementRoot.add(new MdAstText("fresh current text"));
        var replacement = new ParsedGuidePage(PAGE_ID, "en_us", replacementRoot, new Frontmatter(Map.of(
                "item_ids", List.of("minecraft:bread"))));
        Guides.setForTest(List.of(new FixtureGuide(GUIDE_ID, List.of(replacement))));
        var fresh = reader.readFragment("fixture:book#fixture:entry", null, 128, new Toolbox.DocumentBudget());
        var freshGroup = (Evidence.JsonObject) fresh.relationships().values().get("items");
        var freshItem = (Evidence.JsonObject) ((Evidence.JsonArray) freshGroup.values().get("values")).values().getFirst();

        assertEquals("minecraft:bread", ((Evidence.JsonString) freshItem.values().get("itemId")).value());
        assertTrue(fresh.text().contains("fresh current text"));
    }

    @Test
    void providerSearchFindsAnAssociatedItemAbsentFromThePageText() {
        var page = new ParsedGuidePage(PAGE_ID, "en_us", new MdAstRoot(), new Frontmatter(Map.of(
                "item_ids", List.of("minecraft:diamond"))));
        Guides.setForTest(List.of(new FixtureGuide(GUIDE_ID, List.of(page))));
        var scope = new Evidence.Scope("world", "en_us", "team", "runtime", 1);
        var registry = new AdapterRegistry();
        var provider = new GuideDocumentProvider(new GuideMeGuideReader(), () -> scope, Runnable::run);
        registry.register(provider);
        var context = new Toolbox.Context(() -> scope, Map.of(), registry, new Toolbox.DocumentBudget(), null);
        var arguments = new Evidence.JsonObject(Map.of(
                "query", new Evidence.JsonString("minecraft:diamond"),
                "filter", new Evidence.JsonObject(Map.of("provider", new Evidence.JsonString("guideme"),
                        "types", new Evidence.JsonArray(List.of(new Evidence.JsonString("guide")))))));

        var result = provider.searchDocument(new PackSageTools.ReadRequest("search_document", arguments, scope), context)
                .toCompletableFuture().join();

        assertEquals(1, result.records().size());
        assertEquals("", result.records().getFirst().payload().values().get("text") instanceof Evidence.JsonString text
                ? text.value() : null);
        assertTrue(result.records().getFirst().payload().values().containsKey("relationships"));
    }
}
