package dev.packsage.chat;

import dev.packsage.client.GameplayContextCapture;
import dev.packsage.knowledge.Evidence;
import dev.packsage.knowledge.KnowledgeService;
import dev.packsage.model.ModelBackend;
import dev.packsage.model.chatgpt.ChatGptAuth;
import dev.packsage.model.chatgpt.ChatGptBackend;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RestoredSignInRealBackendTest {
    private static final Executor DIRECT = Runnable::run;
    private static final ChatContracts.WorldIdentity WORLD = new ChatContracts.WorldIdentity("world", "World");
    private static final Evidence.Scope SCOPE = new Evidence.Scope("world", "resources", "team", "runtime", 0);

    @TempDir Path temporaryDirectory;

    @Test
    void serverRejectedUnexpiredAccessTokenLeavesSignedInSoSignInRestarts() throws Exception {
        AtomicInteger opens = new AtomicInteger();
        var runtime = restoredRuntime(Long.MAX_VALUE, 401, "{\"error\":{\"message\":\"token revoked\",\"code\":\"invalid_api_key\"}}", opens);

        assertSignedOutThenSignInStartsBrowser(runtime, opens);
    }

    @Test
    void nonInvalidGrantRefreshFailureLeavesSignedInSoSignInRestarts() throws Exception {
        AtomicInteger opens = new AtomicInteger();
        var runtime = restoredRuntime(0, 401, "{\"error\":\"refresh_token_expired\"}", opens);

        assertSignedOutThenSignInStartsBrowser(runtime, opens);
    }

    @Test
    void serverErrorOnCatalogKeepsRestoredSessionSignedIn() throws Exception {
        AtomicInteger opens = new AtomicInteger();
        var runtime = restoredRuntime(Long.MAX_VALUE, 503, "{}", opens);

        assertEquals(ChatContracts.SignInState.SIGNED_IN, runtime.view().signIn());
        assertTrue(runtime.view().errors().stream().anyMatch(error -> error.contains("Model catalog unavailable")));
    }

    private static void assertSignedOutThenSignInStartsBrowser(AssistantRuntime runtime, AtomicInteger opens) {
        assertEquals(ChatContracts.SignInState.SIGNED_OUT, runtime.view().signIn(), runtime.view().errors().toString());
        assertTrue(runtime.view().errors().stream().anyMatch(error -> error.contains("Saved sign-in is no longer valid; sign in again")));
        assertTrue(runtime.view().errors().stream().noneMatch(error -> error.contains("access-1") || error.contains("refresh-1")));
        runtime.signIn();
        assertEquals(ChatContracts.SignInState.SIGNING_IN, runtime.view().signIn());
        assertEquals(1, opens.get());
    }

    private AssistantRuntime restoredRuntime(long expiry, int status, String body, AtomicInteger opens) throws Exception {
        Path credentials = temporaryDirectory.resolve("credentials.json");
        new ChatGptAuth(credentials, (ignored, cancellation) -> CompletableFuture.completedFuture(
                new ModelBackend.AuthState(ModelBackend.AuthStatus.SIGNED_OUT, null, null)))
                .saveCredential(new ChatGptAuth.Credential(ChatGptAuth.ISSUER, "fixture-subject", "oaiapp_fixture",
                        "urn:uuid:00000000-0000-4000-8000-000000000001", "id-token", "access-1", "refresh-1", expiry,
                        List.of("openid", ChatGptAuth.REQUIRED_SCOPE)));
        FixedHttpClient client = new FixedHttpClient(status, body);
        ChatGptBackend backend = new ChatGptBackend(new ChatGptAuth(credentials, client, uri -> opens.incrementAndGet()), client, DIRECT);
        var runtime = new AssistantRuntime(new ConversationStore(Files.createTempDirectory("packsage-restore-test"), DIRECT), backend,
                scope -> new GameplayContextCapture(
                        () -> new GameplayContextCapture.Observation("held", "target", "overworld", "1,2,3", "plains", "1", "block"),
                        ignored -> new GameplayContextCapture.CapturedFrame("image/png", new byte[]{1}, Instant.EPOCH), DIRECT, scope),
                DIRECT, DIRECT, current -> new KnowledgeService(List.of(), current,
                        ignored -> CompletableFuture.completedFuture(
                                new Evidence.Result(List.of(), Evidence.Coverage.UNAVAILABLE, Evidence.Outcome.UNKNOWN, null)),
                        (ignored, scopeValue) -> CompletableFuture.completedFuture(
                                new Evidence.Result(List.of(), Evidence.Coverage.UNAVAILABLE, Evidence.Outcome.UNKNOWN, null))),
                turn -> turn.tokenCount(), prepared -> {
                    throw new UnsupportedOperationException();
                }, model -> 512, ignored -> { }, ignored -> { });
        runtime.enterWorld(WORLD, SCOPE);
        runtime.restoreSession();
        return runtime;
    }

    private static final class FixedHttpClient extends HttpClient {
        private final int status;
        private final String body;

        private FixedHttpClient(int status, String body) {
            this.status = status;
            this.body = body;
        }

        @Override public Optional<CookieHandler> cookieHandler() { return Optional.empty(); }
        @Override public Optional<Duration> connectTimeout() { return Optional.of(Duration.ofSeconds(1)); }
        @Override public Redirect followRedirects() { return Redirect.NEVER; }
        @Override public Optional<ProxySelector> proxy() { return Optional.empty(); }
        @Override public SSLContext sslContext() { return null; }
        @Override public SSLParameters sslParameters() { return new SSLParameters(); }
        @Override public Optional<Authenticator> authenticator() { return Optional.empty(); }
        @Override public Version version() { return Version.HTTP_1_1; }
        @Override public Optional<Executor> executor() { return Optional.empty(); }
        @Override public WebSocket.Builder newWebSocketBuilder() { throw new UnsupportedOperationException(); }
        @Override public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) throws IOException {
            throw new UnsupportedOperationException();
        }
        @SuppressWarnings("unchecked")
        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            return CompletableFuture.completedFuture((HttpResponse<T>) new FixedResponse(request, status, body));
        }
        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler,
                                                                          HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            return sendAsync(request, handler);
        }
    }

    private record FixedResponse(HttpRequest request, int statusCode, String body) implements HttpResponse<String> {
        @Override public Optional<HttpResponse<String>> previousResponse() { return Optional.empty(); }
        @Override public HttpHeaders headers() { return HttpHeaders.of(Map.of(), (name, value) -> true); }
        @Override public Optional<SSLSession> sslSession() { return Optional.empty(); }
        @Override public URI uri() { return request.uri(); }
        @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
    }
}
