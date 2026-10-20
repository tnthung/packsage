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
import guideme.libs.mdast.mdx.model.MdxJsxFlowElement;
import guideme.libs.mdast.mdx.model.MdxJsxTextElement;
import guideme.libs.mdast.model.MdAstRoot;
import guideme.libs.mdast.model.MdAstText;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuideMeGuideReaderTest {
    private static final ResourceLocation GUIDE_ID = ResourceLocation.parse("fixture:book");
    private static final ResourceLocation PAGE_ID = ResourceLocation.parse("fixture:entry");

    @AfterEach
    void clearGuides() {
        Guides.setForTest(List.of());
        RecipeCompiler.setTagNamesForTest(Set.of("Recipe", "RecipeFor", "RecipesFor"));
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

        do {
            var fragment = reader.readFragment("fixture:book#fixture:entry", position, 64,
                    new Toolbox.DocumentBudget());
            actual.append(fragment.text());
            position = fragment.nextPosition();
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
        assertFalse("minecraft:diamond".equals(((Evidence.JsonString) item.values().get("itemId")).value()));
        assertFalse(reader.sourceIdentity(new Evidence.Scope("world", "en_us", "team", "runtime", 1))
                .equals(reader.sourceIdentity(new Evidence.Scope("world", "fr_fr", "team", "runtime", 1))));
    }

    @Test
    void nativeRecipeTagsPreserveTheirDistinctIdentitiesAndExpressionAttributesStayOpaque() {
        RecipeCompiler.setTagNamesForTest(Set.of("Recipe", "RecipeFor", "RecipesFor"));
        var root = new MdAstRoot();
        root.add(new MdxJsxFlowElement("Recipe", List.of(MdxJsxAttribute.string("id", "fixture:iron_recipe"))));
        var inlineParent = new MdxJsxFlowElement("div", List.of());
        inlineParent.add(new MdxJsxTextElement("RecipeFor", List.of(
                MdxJsxAttribute.string("id", "minecraft:diamond"),
                MdxJsxAttribute.string("fallbackText", "Diamond recipe"))));
        root.add(inlineParent);
        root.add(new MdxJsxFlowElement("RecipesFor", List.of(MdxJsxAttribute.string("id", "minecraft:bread"))));
        root.add(new MdxJsxFlowElement("Recipe", List.of(MdxJsxAttribute.expression("id", new Object()))));
        var page = new ParsedGuidePage(PAGE_ID, "en_us", root);
        Guides.setForTest(List.of(new FixtureGuide(GUIDE_ID, List.of(page))));

        var fragment = new GuideMeGuideReader("21.1.19").readFragment("fixture:book#fixture:entry", null, 1024,
                new Toolbox.DocumentBudget());
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
            var fragment = reader.readFragment("fixture:book#fixture:entry", position, 128,
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
