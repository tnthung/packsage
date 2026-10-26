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
        // Only the disposable copy is changed; select the same body for cold and explicit-ID opening.
        Files.writeString(directory.resolve("latest"), selected);
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
        java.util.function.Supplier<AssistantRuntime> createRuntime = () -> new AssistantRuntime(store, backend, current -> new GameplayContextCapture(
                () -> { throw new AssertionError("no gameplay capture during opening"); },
                ignored -> new GameplayContextCapture.CapturedFrame("image/png", new byte[]{1}, Instant.EPOCH),
                Runnable::run, current), Runnable::run, Runnable::run,
                current -> new KnowledgeService(List.of(), current), GroundingInstructions::historyTextBudget,
                GroundingInstructions::build, ignored -> 512, ignored -> { }, ignored -> { });
        var runtime = createRuntime.get();
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
        List<ChatContracts.Turn> turns = screen.view().conversation().turns();
        screen.prepareConversation();
        long prepared = System.nanoTime();
        long glyphs = font.measuredGlyphs;
        screen.prepareConversation();
        long repeated = System.nanoTime();
        assertEquals(glyphs, font.measuredGlyphs, "idle frames must reuse measured text");
        while (!queue.isEmpty()) queue.remove().run();
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
                        + "catalog-task=%.1fms, actual-preparation=%.1fms, repeat=%.3fms, glyphs=%d, sidebar=%.1fms/%d rows, "
                        + "next-ten=%.1fms, initial-chats=%d, selected-bytes=%d, turns=%d, startup-tokenizer=%.1fms%n",
                (widgets - start) / 1e6, (attached - start) / 1e6, (prepared - start) / 1e6,
                catalogNanos / 1e6, (prepared - attached) / 1e6, (repeated - prepared) / 1e6, glyphs,
                (sidebarEnd - sidebarStart) / 1e6, rows, (backfillEnd - backfillStart) / 1e6,
                catalog.join().sessions().size(), Files.size(selectedFile), turns.size(), warmupNanos / 1e6);
        assertTrue(prepared - start < TimeUnit.MILLISECONDS.toNanos(300), "opening and CPU render preparation exceeded 300 ms");

        // The actual CHAT_SCREEN route, including client dispatches and scoped lookup, without HTTP or GPU drawing.
        // Prime a different selection so the first explicit-ID request must read the selected body.
        String other = catalog.join().sessions().stream().filter(session -> !session.id().equals(selected))
                .findFirst().orElseThrow().id();
        Files.writeString(directory.resolve("latest"), other);
        var bridgeRuntime = createRuntime.get();
        bridgeRuntime.enterWorld(world, scope);
        while (!bridgeRuntime.sessionReady()) queue.remove().run();
        class Client extends net.minecraft.client.Minecraft {
            Client() { super(null); }
            @Override public void execute(Runnable task) { task.run(); }
        }
        var unsafeField = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        var unsafe = unsafeField.get(null);
        var client = (Client) unsafe.getClass().getMethod("allocateInstance", Class.class).invoke(unsafe, Client.class);
        client.level = (net.minecraft.client.multiplayer.ClientLevel) unsafe.getClass().getMethod("allocateInstance", Class.class)
                .invoke(unsafe, net.minecraft.client.multiplayer.ClientLevel.class);
        var opened = new java.util.concurrent.atomic.AtomicReference<AssistantScreen>();
        var adapter = new dev.packsage.debug.MinecraftDebugAdapter(client, bridgeRuntime, root.resolve("unused-settings"), kind -> {
            var next = bridgeRuntime.openScreen();
            next.width = 960;
            next.height = 600;
            ScreenTestHost.initialize(next);
            try { fontField.set(next, new ScreenPreparationFont()); }
            catch (IllegalAccessException failure) { throw new AssertionError(failure); }
            opened.set(next);
            client.screen = next;
            return new com.google.gson.JsonObject();
        }, new dev.packsage.debug.DebugRequestLog());
        var body = new com.google.gson.JsonObject();
        body.addProperty("open", true);
        body.addProperty("conversationId", selected);
        try (var worker = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            for (int attempt = 0; attempt < 2; attempt++) {
                long bridgeStart = System.nanoTime();
                var response = worker.submit(() -> adapter.execute(dev.packsage.debug.DebugRouter.Operation.CHAT_SCREEN, body));
                while (!response.isDone()) {
                    Runnable task = queue.poll(1, TimeUnit.MILLISECONDS);
                    if (task != null) task.run();
                }
                long shown = System.nanoTime();
                assertTrue(response.get().get("open").getAsBoolean());
                while (!bridgeRuntime.sessionReady()) queue.remove().run();
                long ready = System.nanoTime();
                assertEquals(selected, opened.get().view().conversation().id());
                opened.get().prepareConversation();
                long rendered = System.nanoTime();
                System.out.printf("ACTUAL BRIDGE attempt=%d response=%.1fms, ready=%.1fms, prepared=%.1fms%n", attempt,
                        (shown - bridgeStart) / 1e6, (ready - bridgeStart) / 1e6, (rendered - bridgeStart) / 1e6);
                assertTrue(rendered - bridgeStart < TimeUnit.MILLISECONDS.toNanos(300), "explicit-ID preparation exceeded 300 ms");
            }
        } finally {
            Files.writeString(directory.resolve("latest"), selected);
        }
    }
}
