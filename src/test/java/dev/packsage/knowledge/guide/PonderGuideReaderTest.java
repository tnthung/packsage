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
        source.entries.add(new PonderGuideReader.Entry("a", "create:shaft", "create", "create:shared",
                List.of("create:rotation"), "First Scene", "create:first", List.of("first caption")));
        source.entries.add(new PonderGuideReader.Entry("b", "create:gearbox", "create", "create:shared",
                List.of("create:rotation"), "Second Scene", "create:second", List.of("second caption")));
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
    void fragmentResolutionUsesFreshCaptionsAndRejectsReplacedRegistration() {
        var source = new FixtureSource();
        source.entries.add(entry("a", "Old title", "old caption"));
        var reader = new PonderGuideReader(source);
        var candidate = reader.candidates(null, 1, new Toolbox.DocumentBudget()).candidates().getFirst();
        source.captions.put("a", List.of("new caption"));

        var resolved = reader.resolve(candidate.id(), 1024, new Toolbox.DocumentBudget());

        assertTrue(resolved.fragment().text().contains("new caption"));
        assertTrue(resolved.fragment().text().contains("create:scene_a"));
        assertEquals(2, source.compiles.get());
        source.entries.set(0, entry("a", "Replacement", "replacement caption"));
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
        return new PonderGuideReader.Entry(identity, "create:shaft", "create", "create:shared",
                List.of("create:rotation"), title, "create:scene_" + identity, List.of(captions));
    }

    private static final class FixtureSource implements PonderGuideReader.Source {
        final List<PonderGuideReader.Entry> entries = new ArrayList<>();
        final java.util.Map<String, List<String>> captions = new java.util.HashMap<>();
        final AtomicInteger compiles = new AtomicInteger();
        final AtomicInteger opens = new AtomicInteger();
        boolean clientThread = true;

        @Override public List<PonderGuideReader.Entry> entries() { return List.copyOf(entries); }
        @Override public PonderGuideReader.SceneText compile(PonderGuideReader.Entry entry) {
            compiles.incrementAndGet();
            return new PonderGuideReader.SceneText(entry.title(), entry.sceneId(), "Header",
                    captions.getOrDefault(entry.identity(), entry.captions()));
        }
        @Override public boolean isClientThread() { return clientThread; }
        @Override public void openComponent(String component) { opens.incrementAndGet(); }
    }
}
