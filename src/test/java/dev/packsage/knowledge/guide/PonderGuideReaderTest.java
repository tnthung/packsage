package dev.packsage.knowledge.guide;

import dev.packsage.knowledge.Evidence;
import dev.packsage.knowledge.Toolbox;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PonderGuideReaderTest {
    private static final Evidence.Scope SCOPE = new Evidence.Scope("world", "en_us", "team", "runtime", 1);

    @Test
    void titleSearchIsBoundedAndKeepsSameSchematicRegistrationsDistinct() {
        var source = new FixtureSource();
        source.entries.add(entryForComponent("a", "create:shaft", "First Scene"));
        source.entries.add(entryForComponent("b", "create:gearbox", "Second Scene"));
        source.entries.addAll(List.of(
                entryForComponent("c", "create:shaft", "Other 3"), entryForComponent("d", "create:shaft", "Other 4"),
                entryForComponent("e", "create:shaft", "Other 5")));
        var reader = new PonderGuideReader(source);

        var page = reader.candidates(null, 8, new Toolbox.DocumentBudget());

        assertEquals(4, page.candidates().size());
        assertEquals(4, source.compiles.get());
        assertNotEquals(page.candidates().get(0).id(), page.candidates().get(1).id());
        assertTrue(page.more());
    }

    @Test
    void providerTitleSearchAndNoMatchEachCompileAtMostFourCandidates() {
        var source = new FixtureSource();
        for (var index = 0; index < 8; index++) source.entries.add(entryForComponent("provider_" + index, "create:shaft",
                index == 2 ? "Matching Scene" : "Other Scene"));
        var provider = new GuideDocumentProvider(new PonderGuideReader(source), () -> SCOPE, Runnable::run, 4);

        var match = provider.query(new Evidence.Query(Evidence.Operation.SEARCH,
                new Evidence.JsonObject(java.util.Map.of("query", new Evidence.JsonString("Matching Scene"))),
                16, null, null), SCOPE).toCompletableFuture().join();

        assertEquals(1, match.records().size());
        assertEquals(4, source.compiles.get());
        source.compiles.set(0);
        var noMatch = provider.query(new Evidence.Query(Evidence.Operation.SEARCH,
                new Evidence.JsonObject(java.util.Map.of("query", new Evidence.JsonString("absent"))),
                16, null, null), SCOPE).toCompletableFuture().join();
        assertTrue(noMatch.records().isEmpty());
        assertEquals(4, source.compiles.get());
    }

    @Test
    void combinedDiscoverySharesTheCallbackLimitAndContinuesOnTheNextToolCall() {
        var source = new FixtureSource();
        for (int index = 0; index < 12; index++) source.entries.add(entryForComponent("combined_" + index, "create:shaft", "Other scene"));
        var service = new dev.packsage.knowledge.KnowledgeService(List.of(new GuideDocumentProvider(
                new PonderGuideReader(source), () -> SCOPE, Runnable::run, 4)), () -> SCOPE);
        var first = service.execute(new dev.packsage.model.PackSageTools.ReadRequest("search_document",
                new Evidence.JsonObject(java.util.Map.of("query", new Evidence.JsonString("absent"))), SCOPE))
                .toCompletableFuture().join();
        assertEquals(4, source.compiles.get());
        assertEquals(Evidence.Coverage.PARTIAL, first.coverage());
        assertTrue(first.nextCursor() != null);
        service.execute(new dev.packsage.model.PackSageTools.ReadRequest("search_document", new Evidence.JsonObject(
                java.util.Map.of("query", new Evidence.JsonString("absent"), "cursor", new Evidence.JsonString(first.nextCursor()))),
                SCOPE)).toCompletableFuture().join();
        assertEquals(8, source.compiles.get());
    }

    @Test
    void fragmentResolutionUsesFreshCaptionsAndRejectsReplacedRegistration() {
        var source = new FixtureSource();
        source.entries.add(entryForComponent("a", "create:shaft", "Old title", "old caption"));
        var reader = new PonderGuideReader(source);
        var candidate = reader.candidates(null, 1, new Toolbox.DocumentBudget()).candidates().getFirst();
        source.captions.put("a", List.of("new caption"));

        assertTrue(candidate.title().contains("Old title"));
        assertTrue(reader.readFragment(candidate.id(), null, 1024, new Toolbox.DocumentBudget()).text().contains("Component: create:shaft"));
        var resolved = reader.resolve(candidate.id(), 1024, new Toolbox.DocumentBudget());

        assertTrue(resolved.fragment().text().contains("new caption"));
        assertTrue(resolved.fragment().text().contains("create:scene_a"));
        assertFalse(resolved.fragment().text().contains("Header:"));
        assertEquals(2, source.compiles.get());
        source.entries.set(0, entryForComponent("replacement", "create:shaft", "Replacement", "replacement caption"));
        var failure = assertThrows(Toolbox.Failure.class,
                () -> reader.resolve(candidate.id(), 1024, new Toolbox.DocumentBudget()));
        assertEquals(Toolbox.Code.INVALID_REFERENCE, failure.code());
    }

    @Test
    void freshEvidencePreservesTheObservedPonderRuntimeVersion() {
        var source = new FixtureSource();
        source.entries.add(entryForComponent("version", "create:shaft", "Title", "caption"));
        var result = new GuideDocumentProvider(new PonderGuideReader(source, "1.0.87+mc1.21.1"), () -> SCOPE,
                Runnable::run).query(new Evidence.Query(Evidence.Operation.GET, new Evidence.JsonObject(
                java.util.Map.of("id", new Evidence.JsonString("version"))), 1, null, null), SCOPE)
                .toCompletableFuture().join();
        assertEquals("1.0.87+mc1.21.1", result.records().getFirst().source().providerVersion());
    }

    @Test
    void navigationRejectsRemovedRegistrationAndOffThreadOpen() {
        var source = new FixtureSource();
        source.entries.add(entryForComponent("a", "create:shaft", "Title"));
        var reader = new PonderGuideReader(source);
        var candidate = reader.candidates(null, 1, new Toolbox.DocumentBudget()).candidates().getFirst();
        var target = new Evidence.NavigationTarget(SCOPE, "ponder", candidate.id(), "create:shaft");
        source.clientThread = false;

        assertEquals(Evidence.NavigationStatus.UNSUPPORTED, reader.open(target, SCOPE).status());
        source.clientThread = true;
        source.entries.clear();
        assertEquals(Evidence.NavigationStatus.STALE, reader.open(target, SCOPE).status());
        assertEquals(0, source.opens.get());
    }

    @Test
    void continuationKeepsTheFirstUnreturnedStoryboard() {
        var source = new FixtureSource();
        for (int index = 0; index < 7; index++) source.entries.add(entryForComponent("page_" + index, "create:shaft", "Scene " + index));
        var reader = new PonderGuideReader(source);
        var first = reader.candidates(null, 4, new Toolbox.DocumentBudget());
        var second = reader.candidates(first.nextPosition(), 4, new Toolbox.DocumentBudget());

        assertEquals(List.of("page_0", "page_1", "page_2", "page_3"),
                first.candidates().stream().map(GuideDocumentReader.Candidate::id).toList());
        assertEquals(List.of("page_4", "page_5", "page_6"),
                second.candidates().stream().map(GuideDocumentReader.Candidate::id).toList());
        assertEquals(7, source.compiles.get());
    }

    @Test
    void tinyMetadataFragmentsDoNotExceedTheirCharacterLimit() {
        var source = new FixtureSource();
        source.entries.add(entryForComponent("tiny", "create:shaft", "Title"));
        assertEquals(1, new PonderGuideReader(source).readFragment("tiny", null, 1,
                new Toolbox.DocumentBudget()).text().length());
        assertTrue(new PonderGuideReader(source).readFragment("tiny", null, "Component: create:shaft".length(),
                new Toolbox.DocumentBudget()).text().length() <= "Component: create:shaft".length());
    }

    @Test
    void captionOutputStaysBoundedAndNavigationOpensOnlyItsCurrentComponent() {
        var source = new FixtureSource();
        source.entries.add(entryForComponent("bounded", "create:shaft", "Title", "x".repeat(100_000), "later caption"));
        var reader = new PonderGuideReader(source);
        var resolved = reader.resolve("bounded", 120, new Toolbox.DocumentBudget());
        assertTrue(resolved.fragment().text().length() <= 120);
        assertTrue(resolved.fragment().text().contains("Caption 1:"));
        var target = reader.navigationTargets(SCOPE, resolved.candidate()).getFirst();
        assertEquals(Evidence.NavigationStatus.OPENED, reader.open(target, SCOPE).status());
        assertEquals("create:shaft", source.openedComponent);
        assertEquals(1, source.opens.get());
        assertEquals(Evidence.NavigationStatus.STALE, reader.open(new Evidence.NavigationTarget(SCOPE,
                "ponder", "bounded", "create:gearbox"), SCOPE).status());
        assertEquals(1, source.opens.get());
    }

    @Test
    void literalCaptionExtractionUsesNumericOrderAndBoundsCopiedText() {
        var captions = new java.util.HashMap<String, String>();
        captions.put("header", "Localized Scene");
        captions.put("text_10", "tenth caption");
        captions.put("text_2", "second caption");
        captions.put("text_1", "first caption");
        var extracted = PonderGuideReader.literalText(net.minecraft.resources.ResourceLocation.parse("fixture:scene"), captions);
        assertEquals(List.of("first caption", "second caption", "tenth caption"), extracted.captions());
        captions.put("text_1", "x".repeat(100_000));
        extracted = PonderGuideReader.literalText(net.minecraft.resources.ResourceLocation.parse("fixture:scene"), captions);
        assertTrue(extracted.captions().stream().mapToInt(String::length).sum() <= 16_384);
    }

    @Test
    void componentAndTagDisplayTextCanFindAStoryboard() {
        var source = new FixtureSource();
        source.entries.add(new PonderGuideReader.Entry("labels", "create:shaft", "create", "create:shared",
                List.of("create:rotation"), "Localized Shaft Rotation Components"));
        TITLES.put("labels", "Unrelated scene title");
        SCENE_IDS.put("labels", "create:labels");
        CAPTION_DEFAULTS.put("labels", List.of());
        var provider = new GuideDocumentProvider(new PonderGuideReader(source), () -> SCOPE, Runnable::run, 4);
        var result = provider.query(new Evidence.Query(Evidence.Operation.SEARCH,
                new Evidence.JsonObject(java.util.Map.of("query", new Evidence.JsonString("Localized Shaft"))),
                16, null, null), SCOPE).toCompletableFuture().join();
        assertEquals(1, result.records().size());
        assertEquals("labels", result.records().getFirst().source().sourceHandle());
    }

    private static PonderGuideReader.Entry entryForComponent(String identity, String component, String title, String... captions) {
        TITLES.put(identity, title);
        SCENE_IDS.put(identity, "create:scene_" + identity);
        CAPTION_DEFAULTS.put(identity, List.of(captions));
        return new PonderGuideReader.Entry(identity, component, "create", "create:shared", List.of("create:rotation"), "");
    }

    private static final java.util.Map<String, String> TITLES = new java.util.HashMap<>();
    private static final java.util.Map<String, String> SCENE_IDS = new java.util.HashMap<>();
    private static final java.util.Map<String, List<String>> CAPTION_DEFAULTS = new java.util.HashMap<>();

    private static final class FixtureSource implements PonderGuideReader.Source {
        final List<PonderGuideReader.Entry> entries = new ArrayList<>();
        final java.util.Map<String, List<String>> captions = new java.util.HashMap<>();
        final AtomicInteger compiles = new AtomicInteger();
        final AtomicInteger opens = new AtomicInteger();
        boolean clientThread = true;
        String openedComponent;

        @Override public Iterable<PonderGuideReader.Entry> entries() { return entries; }
        @Override public PonderGuideReader.SceneText compile(PonderGuideReader.Entry entry) {
            compiles.incrementAndGet();
            return new PonderGuideReader.SceneText(TITLES.get(entry.identity()), SCENE_IDS.get(entry.identity()), TITLES.get(entry.identity()),
                    captions.getOrDefault(entry.identity(), CAPTION_DEFAULTS.get(entry.identity())));
        }
        @Override public boolean isClientThread() { return clientThread; }
        @Override public void openComponent(String component) { openedComponent = component; opens.incrementAndGet(); }
    }
}
