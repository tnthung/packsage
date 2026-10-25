package dev.packsage.client;

import dev.packsage.chat.ChatContracts;
import dev.packsage.chat.AssistantRuntime;
import dev.packsage.chat.ConversationStore;
import dev.packsage.knowledge.KnowledgeService;
import dev.packsage.knowledge.Evidence;
import dev.packsage.model.GroundingInstructions;
import dev.packsage.model.ModelBackend;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertAll;

class ClientAssistantTest {
    @Test
    void productionNoteAnchorsFollowSessionMirrorsAndRejectLoadingAndMissingDimensions(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        var unsafeField = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        var unsafe = unsafeField.get(null);
        var server = (net.minecraft.client.server.IntegratedServer) unsafe.getClass().getMethod("allocateInstance", Class.class)
                .invoke(unsafe, net.minecraft.client.server.IntegratedServer.class);
        var running = net.minecraft.server.MinecraftServer.class.getDeclaredField("running");
        running.setAccessible(true);
        running.set(server, true);
        var level = (net.minecraft.server.level.ServerLevel) unsafe.getClass().getMethod("allocateInstance", Class.class)
                .invoke(unsafe, net.minecraft.server.level.ServerLevel.class);
        var levels = net.minecraft.server.MinecraftServer.class.getDeclaredField("levels");
        levels.setAccessible(true);
        levels.set(server, Map.of(net.minecraft.world.level.Level.OVERWORLD, level));
        var client = (net.minecraft.client.Minecraft) unsafe.getClass().getMethod("allocateInstance", Class.class)
                .invoke(unsafe, net.minecraft.client.Minecraft.class);
        for (var entry : Map.of("singleplayerServer", server, "gameDirectory", directory.toFile()).entrySet()) {
            var field = net.minecraft.client.Minecraft.class.getDeclaredField(entry.getKey());
            field.setAccessible(true);
            field.set(client, entry.getValue());
        }
        var assistant = (ClientAssistant) unsafe.getClass().getMethod("allocateInstance", Class.class).invoke(unsafe, ClientAssistant.class);
        var worker = java.util.concurrent.Executors.newSingleThreadExecutor();
        var journal = new dev.packsage.journal.Journal(Runnable::run);
        var work = new dev.packsage.world.ServerWork(System::nanoTime);
        var scope = new Evidence.Scope("world", "resources", "team", "runtime", 1);
        var place = new dev.packsage.world.BlockPlace("minecraft:overworld", 40, 70, -8);
        try {
            journal.open(directory.resolve("first.db"), () -> 400);
            journal.write(store -> null).toCompletableFuture().get();
            journal.notes().put(new dev.packsage.journal.Note("n0000abcd", "old farm", "words", List.of(), place, 1, 1, 1, null, null));
            journal.write(store -> { store.putNote(journal.notes().get("n0000abcd")); return null; }).toCompletableFuture().get();
            for (var entry : Map.of("client", client, "journal", journal, "workerExecutor", worker,
                    "clientExecutor", (Executor) Runnable::run, "serverWork", work).entrySet()) {
                var field = ClientAssistant.class.getDeclaredField(entry.getKey());
                field.setAccessible(true);
                field.set(assistant, entry.getValue());
            }
            var knowledgeMethod = ClientAssistant.class.getDeclaredMethod("knowledge", java.util.function.Supplier.class);
            knowledgeMethod.setAccessible(true);
            var knowledge = (KnowledgeService) knowledgeMethod.invoke(assistant, (java.util.function.Supplier<Evidence.Scope>) () -> scope);
            var providers = KnowledgeService.class.getDeclaredField("providers");
            providers.setAccessible(true);
            var worldTools = (dev.packsage.world.WorldTools) ((List<?>) providers.get(knowledge)).stream()
                    .filter(dev.packsage.world.WorldTools.class::isInstance).findFirst().orElseThrow();
            var old = worldTools.resolveAnchor(new dev.packsage.world.Anchor.OfNote("n0000abcd")).toCompletableFuture();
            while (!old.isDone()) work.tick();
            assertEquals(place, old.get());
            journal.close().get();
            journal.open(directory.resolve("first.db"), () -> 500);
            journal.write(store -> null).toCompletableFuture().get();
            assertEquals(place, journal.notes().place("n0000abcd"));
            journal.notes().put(new dev.packsage.journal.Note("n00001234", "new farm", "words", List.of(), place, 2, 2, 2, null, null));
            var current = worldTools.resolveAnchor(new dev.packsage.world.Anchor.OfNote("n00001234")).toCompletableFuture();
            while (!current.isDone()) work.tick();
            journal.notes().remove("n0000abcd");
            var forgotten = worldTools.resolveAnchor(new dev.packsage.world.Anchor.OfNote("n0000abcd")).toCompletableFuture();
            while (!forgotten.isDone()) work.tick();
            journal.notes().put(new dev.packsage.journal.Note("n00005678", "lost dimension", "words", List.of(),
                    new dev.packsage.world.BlockPlace("fixture:removed", 1, 2, 3), 3, 3, 3, null, null));
            var removed = worldTools.resolveAnchor(new dev.packsage.world.Anchor.OfNote("n00005678")).toCompletableFuture();
            while (!removed.isDone()) work.tick();
            var blocked = new CompletableFuture<Void>();
            journal.write(store -> { blocked.join(); return null; });
            var closing = journal.close();
            journal.open(directory.resolve("third.db"), () -> 600);
            var opening = worldTools.resolveAnchor(new dev.packsage.world.Anchor.OfNote("n00001234")).toCompletableFuture();
            try {
                while (!opening.isDone()) work.tick();
                assertAll(
                        () -> assertEquals(place, current.get()),
                        () -> {
                            var failure = org.junit.jupiter.api.Assertions.assertThrows(java.util.concurrent.ExecutionException.class, forgotten::get);
                            assertEquals(dev.packsage.knowledge.Toolbox.Code.UNAVAILABLE,
                                    ((dev.packsage.knowledge.Toolbox.Failure) failure.getCause()).code());
                            assertTrue(failure.getCause().getMessage().contains("has no saved place"));
                        },
                        () -> assertTrue(org.junit.jupiter.api.Assertions.assertThrows(java.util.concurrent.ExecutionException.class, removed::get)
                                .getCause().getMessage().contains("dimension fixture:removed is not loaded")),
                        () -> assertTrue(org.junit.jupiter.api.Assertions.assertThrows(java.util.concurrent.ExecutionException.class, opening::get)
                                .getCause().getMessage().contains("still opening")));
            } finally {
                blocked.complete(null);
                closing.get();
            }
        } finally {
            journal.close().get();
            worker.shutdownNow();
        }
    }

    @Test
    void backgroundFailurePushesTerminalSessionActivityWithoutRefreshingOnTokens() throws Exception {
        Executor direct = Runnable::run;
        var model = new ModelBackend.ModelInfo("fixture-model", "Fixture Model", true, true, 16_000, 2_048);
        class RuntimeBackend implements ModelBackend {
            private final List<Request> requests = new ArrayList<>();
            private final Map<String, Consumer<Event>> eventConsumers = new LinkedHashMap<>();

            @Override
            public CompletionStage<AuthState> signIn() {
                return CompletableFuture.completedFuture(new AuthState(AuthStatus.SIGNED_IN, "fixture", null));
            }

            @Override
            public void cancelSignIn() { }

            @Override
            public void signOut() { }

            @Override
            public CompletionStage<Capabilities> capabilities() {
                return CompletableFuture.completedFuture(new Capabilities(List.of(model), model.id(), model.imageInput(),
                        model.hostedSearch(), model.contextTokens(), model.outputTokens()));
            }

            @Override
            public Exchange start(Request request, Consumer<Event> events) {
                requests.add(request);
                eventConsumers.put(request.requestId(), events);
                return new Exchange() {
                    @Override public void submit(ToolResult result) { }
                    @Override public boolean steer(Message message) { return true; }
                    @Override public void cancel() { }
                };
            }
        }

        var backend = new RuntimeBackend();
        var runtime = new AssistantRuntime(new ConversationStore(Files.createTempDirectory("packsage-sidebar-activity"), direct),
                backend, current -> new GameplayContextCapture(
                () -> new GameplayContextCapture.Observation(null, null, "overworld", "0,0,0", "plains", "0", "unavailable"),
                ignored -> new GameplayContextCapture.CapturedFrame("image/png", new byte[0], Instant.EPOCH), direct, current),
                direct, direct, current -> new KnowledgeService(List.of(), current), ChatContracts.Turn::tokenCount,
                GroundingInstructions::build, ignored -> 512, ignored -> { }, ignored -> { });
        runtime.enterWorld(new ChatContracts.WorldIdentity("runtime-world", "Runtime World"),
                new Evidence.Scope("world-session", "en_us", "player", "runtime", 1));
        runtime.signIn();
        var screen = runtime.openScreen();
        screen.refreshSessions(runtime.sessions().toCompletableFuture().join());

        runtime.send("Background A question", false, false);
        String requestA = backend.requests.getLast().requestId();
        String sessionA = runtime.view().conversation().id();
        backend.eventConsumers.get(requestA).accept(new ModelBackend.Event(ModelBackend.EventKind.TEXT_DELTA, "A streamed text", null,
                List.of(), null));
        String tokenDeltaLastMessage = screen.sessionRows().stream().filter(row -> row.session() != null
                && row.session().id().equals(sessionA)).findFirst().orElseThrow().session().lastMessage();

        runtime.newChat();
        String sessionB = runtime.view().conversation().id();
        runtime.send("Selected B question", false, false);
        String requestB = backend.requests.getLast().requestId();
        backend.eventConsumers.get(requestB).accept(new ModelBackend.Event(ModelBackend.EventKind.TEXT_DELTA, "B remains active", null,
                List.of(), null));
        boolean backgroundActiveBeforeFailure = screen.sessionRows().stream().filter(row -> row.session() != null
                && row.session().id().equals(sessionA)).findFirst().orElseThrow().session().active();
        boolean selectedActiveBeforeFailure = screen.sessionRows().stream().filter(row -> row.session() != null
                && row.session().id().equals(sessionB)).findFirst().orElseThrow().session().active();

        backend.eventConsumers.get(requestA).accept(new ModelBackend.Event(ModelBackend.EventKind.FAILED, null, null, List.of(), "fixture failure"));
        ChatContracts.SessionSummary backgroundAfterFailure = screen.sessionRows().stream().filter(row -> row.session() != null
                && row.session().id().equals(sessionA)).findFirst().orElseThrow().session();
        ChatContracts.SessionSummary selectedAfterFailure = screen.sessionRows().stream().filter(row -> row.session() != null
                && row.session().id().equals(sessionB)).findFirst().orElseThrow().session();

        assertAll(
                () -> assertEquals("", tokenDeltaLastMessage, "streaming tokens do not reload the sidebar catalog"),
                () -> assertTrue(backgroundActiveBeforeFailure),
                () -> assertTrue(selectedActiveBeforeFailure),
                () -> assertFalse(backgroundAfterFailure.active(), "a background terminal update refreshes its sidebar row"),
                () -> assertTrue(selectedAfterFailure.active()),
                () -> assertEquals(sessionB, runtime.view().conversation().id()),
                () -> assertTrue(runtime.view().active()));
    }

    @Test
    void privateWorldIdentityIsOpaqueAndStableWhileKeepingItsLabel() {
        ClientAssistant.CopiedWorld first = new ClientAssistant.CopiedWorld(
                "private:C:/instances/fixture/saves/Test World", "Test World", true,
                "C:/instances/fixture/saves/Test World/serverconfig");
        ClientAssistant.CopiedWorld same = new ClientAssistant.CopiedWorld(
                "private:C:/instances/fixture/saves/Test World", "Test World", true,
                "C:/instances/fixture/saves/Test World/serverconfig");
        ClientAssistant.CopiedWorld other = new ClientAssistant.CopiedWorld(
                "private:C:/instances/fixture/saves/Other World", "Other World", true,
                "C:/instances/fixture/saves/Other World/serverconfig");

        ChatContracts.WorldIdentity firstIdentity = ClientAssistant.identityFor(first);

        assertEquals(firstIdentity, ClientAssistant.identityFor(same));
        assertNotEquals(firstIdentity.worldOrServer(), ClientAssistant.identityFor(other).worldOrServer());
        assertEquals("Test World", firstIdentity.readableLabel());
        assertFalse(firstIdentity.worldOrServer().contains("C:/instances"));
        assertFalse(firstIdentity.worldOrServer().contains("Test World"));
    }

    @Test
    void scopeSeparatesSessionResourcesPlayerAndProviderRuntime() {
        UUID session = UUID.fromString("11111111-1111-1111-1111-111111111111");
        Evidence.Scope first = ClientAssistant.scopeFor("world-id", session, 4, "en_us",
                "player-id", 7);
        Evidence.Scope same = ClientAssistant.scopeFor("world-id", session, 4, "en_us",
                "player-id", 7);
        Evidence.Scope changed = ClientAssistant.scopeFor("world-id", session, 5, "fr_fr",
                "other-player", 8);

        assertEquals(first, same);
        assertNotEquals(first, changed);
        assertTrue(first.worldSession().contains(session.toString()));
        assertTrue(first.resourcesLocale().contains("4"));
        assertTrue(first.resourcesLocale().contains("en_us"));
        assertEquals("player-id", first.playerTeam());
        assertEquals("unknown", ClientAssistant.scopeFor("world-id", session, 4, "en_us", " ", 7).playerTeam());
        assertEquals("runtime-7", first.providerRuntime());
    }
}
