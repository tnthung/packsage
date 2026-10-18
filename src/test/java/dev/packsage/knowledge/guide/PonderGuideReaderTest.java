package dev.packsage.knowledge.guide;

import dev.packsage.knowledge.Evidence;
import dev.packsage.knowledge.Toolbox;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
                entry("c", "Other 3"), entry("d", "Other 4"), entry("e", "Other 5")));
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
        for (var index = 0; index < 8; index++) source.entries.add(entry("provider_" + index,
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
    void fragmentResolutionUsesFreshCaptionsAndRejectsReplacedRegistration() {
        var source = new FixtureSource();
        source.entries.add(entry("a", "Old title", "old caption"));
        var reader = new PonderGuideReader(source);
        var candidate = reader.candidates(null, 1, new Toolbox.DocumentBudget()).candidates().getFirst();
        source.captions.put("a", List.of("new caption"));

        assertTrue(candidate.title().contains("Old title"));
        assertTrue(reader.readFragment(candidate.id(), null, 1024, new Toolbox.DocumentBudget()).text().contains("Component: create:shaft"));
        var resolved = reader.resolve(candidate.id(), 1024, new Toolbox.DocumentBudget());

        assertTrue(resolved.fragment().text().contains("new caption"));
        assertTrue(resolved.fragment().text().contains("create:scene_a"));
        assertEquals(2, source.compiles.get());
        source.entries.set(0, entry("replacement", "Replacement", "replacement caption"));
        var failure = assertThrows(Toolbox.Failure.class,
                () -> reader.resolve(candidate.id(), 1024, new Toolbox.DocumentBudget()));
        assertEquals(Toolbox.Code.INVALID_REFERENCE, failure.code());
    }

    @Test
    void navigationRejectsRemovedRegistrationAndOffThreadOpen() {
        var source = new FixtureSource();
        source.entries.add(entry("a", "Title"));
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

    private static PonderGuideReader.Entry entry(String identity, String title, String... captions) {
        return entryForComponent(identity, "create:shaft", title, captions);
    }

    private static PonderGuideReader.Entry entryForComponent(String identity, String component, String title, String... captions) {
        var result = new PonderGuideReader.Entry(identity, component, "create", "create:shared",
                List.of("create:rotation"));
        TITLES.put(identity, title);
        SCENE_IDS.put(identity, "create:scene_" + identity);
        CAPTION_DEFAULTS.put(identity, List.of(captions));
        return result;
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

        @Override public Iterable<PonderGuideReader.Entry> entries() { return entries; }
        @Override public PonderGuideReader.SceneText compile(PonderGuideReader.Entry entry) {
            compiles.incrementAndGet();
            return new PonderGuideReader.SceneText(TITLES.get(entry.identity()), SCENE_IDS.get(entry.identity()), "Header",
                    captions.getOrDefault(entry.identity(), CAPTION_DEFAULTS.get(entry.identity())));
        }
        @Override public boolean isClientThread() { return clientThread; }
        @Override public void openComponent(String component) { opens.incrementAndGet(); }
    }
}
