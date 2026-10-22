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
