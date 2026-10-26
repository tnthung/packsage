package dev.packsage.client;

import com.google.gson.JsonParser;
import dev.packsage.chat.AssistantRuntime;
import dev.packsage.chat.ChatContracts;
import dev.packsage.chat.ConversationStore;
import dev.packsage.knowledge.Evidence;
import dev.packsage.knowledge.KnowledgeService;
import dev.packsage.model.GroundingInstructions;
import dev.packsage.model.ModelBackend;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Opt-in CPU opening profile. Supply only a disposable COPY of an instance's packsage directory. */
class ChatOpeningProfileTest {
    @Test
    void copiedPlayerDataOpeningProfile() throws Exception {
        String copiedData = System.getenv("PACKSAGE_OPENING_DATA");
        assumeTrue(copiedData != null);
        Path root = Path.of(copiedData).toAbsolutePath().normalize();
        assumeTrue(root.startsWith(Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()),
                "benchmark data must be a temporary copy");
        Path directory = root.resolve("PackSage_Adapter_Filter_Live_Test-e341466b58d81012");
        String selected = System.getenv("PACKSAGE_OPENING_CONVERSATION") == null
                ? Files.readString(directory.resolve("latest")).trim() : System.getenv("PACKSAGE_OPENING_CONVERSATION");
        Path selectedFile;
        try (var files = Files.list(directory)) {
            selectedFile = files.filter(file -> file.getFileName().toString().startsWith(selected)
                    && file.toString().endsWith(".jsonl")).findFirst().orElseThrow();
        }
        com.google.gson.JsonObject header;
        try (var reader = Files.newBufferedReader(selectedFile)) {
            header = JsonParser.parseString(reader.readLine()).getAsJsonObject();
        }
        var world = new ChatContracts.WorldIdentity(header.get("world").getAsString(), header.get("label").getAsString());
        var scope = new Evidence.Scope(world.worldOrServer(), "en_us", "player", "profile", 0);
        var queue = new LinkedBlockingQueue<Runnable>();
        var catalogQueue = new LinkedBlockingQueue<Runnable>();
        var store = new ConversationStore(root, queue::add, catalogQueue::add);
        var backend = (ModelBackend) java.lang.reflect.Proxy.newProxyInstance(ModelBackend.class.getClassLoader(),
                new Class<?>[]{ModelBackend.class}, (proxy, method, arguments) -> {
                    throw new AssertionError("opening must not call a model: " + method.getName());
                });
        var runtime = new AssistantRuntime(store, backend, current -> new GameplayContextCapture(
                () -> { throw new AssertionError("no gameplay capture during opening"); },
                ignored -> new GameplayContextCapture.CapturedFrame("image/png", new byte[]{1}, Instant.EPOCH),
                Runnable::run, current), Runnable::run, Runnable::run,
                current -> new KnowledgeService(List.of(), current), GroundingInstructions::historyTextBudget,
                GroundingInstructions::build, ignored -> 512, ignored -> { }, ignored -> { });
        // ClientAssistant now does this on its worker during startup, before the open key can be pressed.
        long warmupStart = System.nanoTime();
        GroundingInstructions.historyTokenCounts(new ChatContracts.Turn(new ChatContracts.RequestId("tokenizer-warmup"),
                "", "", List.of(), new ChatContracts.Context("", null, scope), List.of(),
                new ChatContracts.Outcome(ChatContracts.OutcomeStatus.COMPLETED, null), 0));
        long warmupNanos = System.nanoTime() - warmupStart;
        long start = System.nanoTime();
        // Reproduce a sidebar refresh already queued when the world/selected chat begins loading.
        var catalog = store.sessionPage(world, false, 0, runtime.settings().sessionsBeforeCollapse()).toCompletableFuture();
        runtime.enterWorld(world, scope);
        var screen = runtime.openScreen();
        screen.width = 960;
        screen.height = 600;
        ScreenTestHost.initialize(screen);
        var font = new ScreenPreparationFont();
        var fontField = net.minecraft.client.gui.screens.Screen.class.getDeclaredField("font");
        fontField.setAccessible(true);
        fontField.set(screen, font);
        long widgets = System.nanoTime();
        while (screen.view().conversation().turns().isEmpty()) {
            Runnable task = queue.poll(30, TimeUnit.SECONDS);
            if (task == null) throw new AssertionError("selected chat never appeared");
            task.run();
            assertFalse(screen.view().errors().stream().anyMatch(error -> error.contains("could not be opened")),
                    screen.view().errors().toString());
        }
        long attached = System.nanoTime();
        assertEquals(selected, screen.view().conversation().id());
        long historyStart = System.nanoTime();
        runtime.historyPreview();
        long historyEnd = System.nanoTime();
        long tokenNanos = 0;
        long layoutNanos = 0;
        List<ChatContracts.Turn> turns = screen.view().conversation().turns();
        for (var turn : turns.subList(Math.max(0, turns.size() - 4), turns.size())) {
            long tokenStart = System.nanoTime();
            GroundingInstructions.historyTokenCounts(turn);
            tokenNanos += System.nanoTime() - tokenStart;
            long layoutStart = System.nanoTime();
            font.split(net.minecraft.network.chat.Component.literal(turn.question()), 340);
            for (var segment : AssistantScreen.answerSegments(turn.answer(), turn.steeringMessages())) {
                var lines = ScreenText.inlineLines(segment.text(), turn.navigationTargets(), List.of(),
                        turn.sources(), ScreenText.sourceLabels(turn));
                screen.messageLayout(lines, 340);
                screen.messageLayout(lines, 340);
            }
            layoutNanos += System.nanoTime() - layoutStart;
        }
        long prepared = System.nanoTime();
        System.out.printf("SCREEN BASELINE glyph-visits=%d%n", font.measuredGlyphs);
        while (!queue.isEmpty()) queue.remove().run();
        long lookupStart = System.nanoTime();
        var allSessions = runtime.sessions().toCompletableFuture();
        while (!allSessions.isDone()) catalogQueue.remove().run();
        var chosen = allSessions.join().stream().filter(session -> session.id().equals(selected)
                && session.world().equals(world)).findFirst().orElseThrow();
        long lookupEnd = System.nanoTime();
        runtime.openSession(chosen);
        while (!runtime.sessionReady()) queue.remove().run();
        long reopenEnd = System.nanoTime();
        System.out.printf("BRIDGE BASELINE all-session-lookup=%.1fms, reopen=%.1fms, sessions=%d%n",
                (lookupEnd - lookupStart) / 1e6, (reopenEnd - lookupEnd) / 1e6, allSessions.join().size());
        long catalogStart = System.nanoTime();
        while (!catalog.isDone()) catalogQueue.remove().run();
        long catalogNanos = System.nanoTime() - catalogStart;
        screen.refreshSessions(catalog.join().sessions());
        long sidebarStart = System.nanoTime();
        int rows = screen.sessionRows().size();
        long sidebarEnd = System.nanoTime();
        var summaries = store.sessionPage(world, false, catalog.join().sessions().size(), 10).toCompletableFuture();
        long backfillStart = System.nanoTime();
        while (!summaries.isDone()) catalogQueue.remove().run();
        long backfillEnd = System.nanoTime();
        System.out.printf("PLAYER OPEN widgets=%.1fms, selected=%.1fms, selected+render-preparation=%.1fms, "
                        + "catalog-task=%.1fms, history=%.1fms, turn-tokens=%.1fms, layout=%.1fms, sidebar=%.1fms/%d rows, "
                        + "next-ten=%.1fms, initial-chats=%d, selected-bytes=%d, turns=%d, startup-tokenizer=%.1fms%n",
                (widgets - start) / 1e6, (attached - start) / 1e6, (prepared - start) / 1e6,
                catalogNanos / 1e6, (historyEnd - historyStart) / 1e6, tokenNanos / 1e6, layoutNanos / 1e6,
                (sidebarEnd - sidebarStart) / 1e6, rows, (backfillEnd - backfillStart) / 1e6,
                catalog.join().sessions().size(), Files.size(selectedFile), turns.size(), warmupNanos / 1e6);
        assertTrue(prepared - start < TimeUnit.MILLISECONDS.toNanos(500), "opening and CPU render preparation exceeded 500 ms");
    }
}
