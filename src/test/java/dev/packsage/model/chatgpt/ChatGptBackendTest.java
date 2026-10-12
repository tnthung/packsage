package dev.packsage.model.chatgpt;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.packsage.model.ModelBackend;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
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
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatGptBackendTest {
    @TempDir Path temporaryDirectory;

    @Test
    void requestUsesStreamingWithoutStorageAndSendsHistoryInInput() {
        ModelBackend.Request request = new ModelBackend.Request(
                "gpt-5-mini",
                "Answer from installed evidence.",
                List.of(new ModelBackend.Message(ModelBackend.Role.USER, List.of(new ModelBackend.TextContent("What is this?")))),
                List.of(new ModelBackend.ToolDefinition("read_evidence", "Read local evidence", "{}")),
                ModelBackend.SearchMode.EXPLICIT_ONLY,
                "request-1",
                512);

        JsonObject body = JsonParser.parseString(ChatGptBackend.requestBody(request)).getAsJsonObject();

        assertEquals("gpt-5-mini", body.get("model").getAsString());
        assertFalse(body.get("store").getAsBoolean());
        assertTrue(body.get("stream").getAsBoolean());
        assertEquals("message", body.getAsJsonArray("input").get(0).getAsJsonObject().get("type").getAsString());
        assertEquals("user", body.getAsJsonArray("input").get(0).getAsJsonObject().get("role").getAsString());
        assertEquals("What is this?", body.getAsJsonArray("input").get(0).getAsJsonObject().getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString());
        assertTrue(body.get("instructions").getAsString().startsWith(request.instructions()));
        assertFalse(body.has("previous_response_id"));
        assertFalse(body.has("max_output_tokens"));
        assertEquals("namespace", body.getAsJsonArray("tools").get(0).getAsJsonObject().get("type").getAsString());
        assertEquals("packsage", body.getAsJsonArray("tools").get(0).getAsJsonObject().get("name").getAsString());
        assertEquals("read_evidence", body.getAsJsonArray("tools").get(0).getAsJsonObject().getAsJsonArray("tools").get(0).getAsJsonObject().get("name").getAsString());
    }

    @Test
    void searchPolicyKeepsExplicitOnlyAvailableAndForcesExplicitPerSend() {
        ModelBackend.Request explicitOnly = new ModelBackend.Request("gpt-5-mini", "Answer from evidence.",
                List.of(new ModelBackend.Message(ModelBackend.Role.USER, List.of(new ModelBackend.TextContent("Explain this")))), List.of(),
                ModelBackend.SearchMode.EXPLICIT_ONLY, "request-1", 512);
        ModelBackend.Request explicitRequest = new ModelBackend.Request("gpt-5-mini", "Answer from evidence.",
                List.of(new ModelBackend.Message(ModelBackend.Role.USER, List.of(new ModelBackend.TextContent("Search for current documentation")))), List.of(),
                ModelBackend.SearchMode.EXPLICIT_REQUEST, "request-2", 512);

        JsonObject explicitOnlyBody = JsonParser.parseString(ChatGptBackend.requestBody(explicitOnly)).getAsJsonObject();
        JsonObject explicitRequestBody = JsonParser.parseString(ChatGptBackend.requestBody(explicitRequest)).getAsJsonObject();

        assertEquals("web_search", explicitOnlyBody.getAsJsonArray("tools").get(0).getAsJsonObject().get("type").getAsString());
        assertTrue(explicitOnlyBody.get("instructions").getAsString().contains("Use web search only when the player explicitly asks"));
        assertTrue(explicitOnlyBody.get("instructions").getAsString().contains("avoid private world, chat, and player details"));
        assertFalse(explicitOnlyBody.has("tool_choice"));
        assertEquals("allowed_tools", explicitRequestBody.getAsJsonObject("tool_choice").get("type").getAsString());
        assertEquals("required", explicitRequestBody.getAsJsonObject("tool_choice").get("mode").getAsString());
        assertEquals("web_search", explicitRequestBody.getAsJsonObject("tool_choice").getAsJsonArray("tools").get(0).getAsJsonObject().get("type").getAsString());
    }

    @Test
    void imageInputUsesADataUrlAndNeverAnAssetId() {
        ModelBackend.Request request = new ModelBackend.Request("gpt-5-mini", "Describe supplied evidence.",
                List.of(new ModelBackend.Message(ModelBackend.Role.USER, List.of(
                        new ModelBackend.TextContent("Describe the screenshot"),
                        new ModelBackend.ImageContent("data:image/png;base64,ZmFrZS1wbmc=")))),
                List.of(), ModelBackend.SearchMode.DISABLED, "request-image", 512);

        JsonObject body = JsonParser.parseString(ChatGptBackend.requestBody(request)).getAsJsonObject();
        JsonObject image = body.getAsJsonArray("input").get(0).getAsJsonObject().getAsJsonArray("content").get(1).getAsJsonObject();

        assertEquals("input_image", image.get("type").getAsString());
        assertEquals("data:image/png;base64,ZmFrZS1wbmc=", image.get("image_url").getAsString());
        assertEquals("auto", image.get("detail").getAsString());
        ModelBackend.Request invalid = new ModelBackend.Request("gpt-5-mini", "Describe supplied evidence.",
                List.of(new ModelBackend.Message(ModelBackend.Role.USER, List.of(new ModelBackend.ImageContent("asset-id")))),
                List.of(), ModelBackend.SearchMode.DISABLED, "request-invalid-image", 512);
        assertThrows(IllegalArgumentException.class, () -> ChatGptBackend.requestBody(invalid));
    }

    @Test
    void modelCatalogPreservesDocumentedNameOrderAndSelectsFirstVisibleModel() {
        String catalog = """
                {"models":[
                  {"slug":"hidden","visibility":"unlisted","display_name":"Hidden"},
                  {"slug":"gpt-5-mini","visibility":"list","display_name":"GPT 5 mini"},
                  {"slug":"gpt-5","visibility":"list","display_name":"GPT 5"}
                ]}
                """;

        ModelBackend.Capabilities capabilities = ChatGptBackend.capabilitiesFromCatalog(catalog, null);

        assertEquals(List.of(
                new ModelBackend.ModelInfo("gpt-5-mini", "GPT 5 mini", null, null, null, null),
                new ModelBackend.ModelInfo("gpt-5", "GPT 5", null, null, null, null)), capabilities.modelCatalog());
        assertEquals("gpt-5-mini", capabilities.selectedModelId());
        assertNull(capabilities.imageInput());
        assertNull(capabilities.hostedSearch());
    }

    @Test
    void mapsRouteFailuresWithoutPretendingInferenceIsAvailable() {
        assertEquals(ModelBackend.AuthStatus.FAILED, ChatGptBackend.authStatusForHttpFailure(403, "subscription_sharing_user_not_eligible"));
        assertEquals(ModelBackend.AuthStatus.EXPIRED, ChatGptBackend.authStatusForHttpFailure(401, "invalid_token"));
        assertEquals(ModelBackend.AuthStatus.FAILED, ChatGptBackend.authStatusForHttpFailure(429, "usage_limit"));
    }

    @Test
    void validSignedIdTokenWithNumericExpiryAndArrayAudienceIsAccepted() throws Exception {
        SignedToken fixture = signedToken(header -> { }, claims -> { });

        assertEquals("fixture-subject", ChatGptAuth.validateIdToken(fixture.token(), fixture.jwks(), "oaiapp_fixture", "fixture-nonce", Instant.ofEpochSecond(1_900_000_000L)));
    }

    @Test
    void idTokenRejectsWrongOrMissingSecurityClaims() throws Exception {
        SignedToken wrongAlgorithm = signedToken(header -> header.addProperty("alg", "HS256"), claims -> { });
        SignedToken missingKid = signedToken(header -> header.remove("kid"), claims -> { });
        SignedToken wrongIssuer = signedToken(header -> { }, claims -> claims.addProperty("iss", "https://example.test"));
        SignedToken wrongAudience = signedToken(header -> { }, claims -> claims.addProperty("aud", "another-client"));
        SignedToken expired = signedToken(header -> { }, claims -> claims.addProperty("exp", 1_800_000_000L));
        SignedToken wrongNonce = signedToken(header -> { }, claims -> claims.addProperty("nonce", "other-nonce"));
        SignedToken missingSubject = signedToken(header -> { }, claims -> claims.remove("sub"));
        SignedToken valid = signedToken(header -> { }, claims -> { });
        int signatureStart = valid.token().lastIndexOf('.') + 1;
        String badSignature = valid.token().substring(0, signatureStart)
                + (valid.token().charAt(signatureStart) == 'A' ? 'B' : 'A') + valid.token().substring(signatureStart + 1);

        assertThrows(IllegalArgumentException.class, () -> ChatGptAuth.validateIdToken(wrongAlgorithm.token(), wrongAlgorithm.jwks(), "oaiapp_fixture", "fixture-nonce", Instant.ofEpochSecond(1_900_000_000L)));
        assertThrows(IllegalArgumentException.class, () -> ChatGptAuth.validateIdToken(missingKid.token(), missingKid.jwks(), "oaiapp_fixture", "fixture-nonce", Instant.ofEpochSecond(1_900_000_000L)));
        assertThrows(IllegalArgumentException.class, () -> ChatGptAuth.validateIdToken(wrongIssuer.token(), wrongIssuer.jwks(), "oaiapp_fixture", "fixture-nonce", Instant.ofEpochSecond(1_900_000_000L)));
        assertThrows(IllegalArgumentException.class, () -> ChatGptAuth.validateIdToken(wrongAudience.token(), wrongAudience.jwks(), "oaiapp_fixture", "fixture-nonce", Instant.ofEpochSecond(1_900_000_000L)));
        assertThrows(IllegalArgumentException.class, () -> ChatGptAuth.validateIdToken(expired.token(), expired.jwks(), "oaiapp_fixture", "fixture-nonce", Instant.ofEpochSecond(1_900_000_000L)));
        assertThrows(IllegalArgumentException.class, () -> ChatGptAuth.validateIdToken(wrongNonce.token(), wrongNonce.jwks(), "oaiapp_fixture", "fixture-nonce", Instant.ofEpochSecond(1_900_000_000L)));
        assertThrows(IllegalArgumentException.class, () -> ChatGptAuth.validateIdToken(missingSubject.token(), missingSubject.jwks(), "oaiapp_fixture", "fixture-nonce", Instant.ofEpochSecond(1_900_000_000L)));
        assertThrows(IllegalArgumentException.class, () -> ChatGptAuth.validateIdToken(badSignature, valid.jwks(), "oaiapp_fixture", "fixture-nonce", Instant.ofEpochSecond(1_900_000_000L)));
    }

    @Test
    void credentialRoundTripsAcrossRestartWithEveryField() {
        Path credentials = temporaryDirectory.resolve("credentials.json");
        ChatGptAuth.Credential expected = credential("access-1", "refresh-1", Long.MAX_VALUE);
        ChatGptAuth auth = new ChatGptAuth(credentials, signedOutFlow());

        auth.saveCredential(expected);

        assertEquals(expected, new ChatGptAuth(credentials, signedOutFlow()).credential());
    }

    @Test
    void concurrentAccessTokenCallsShareOneRotatingRefresh() {
        Path credentials = temporaryDirectory.resolve("credentials.json");
        AtomicInteger refreshes = new AtomicInteger();
        CompletableFuture<ChatGptAuth.Credential> refresh = new CompletableFuture<>();
        ChatGptAuth auth = new ChatGptAuth(credentials, new ChatGptAuth.SignInFlow() {
            @Override public CompletionStage<ModelBackend.AuthState> start(ChatGptAuth ignored, CompletableFuture<ModelBackend.AuthState> cancellation) {
                return CompletableFuture.completedFuture(new ModelBackend.AuthState(ModelBackend.AuthStatus.SIGNED_OUT, null, null));
            }
            @Override public CompletionStage<ChatGptAuth.Credential> refresh(ChatGptAuth.Credential current) {
                refreshes.incrementAndGet();
                return refresh;
            }
        });
        auth.saveCredential(credential("access-1", "refresh-1", 0));

        CompletionStage<String> first = auth.accessToken();
        CompletionStage<String> second = auth.accessToken();

        assertEquals(1, refreshes.get());
        refresh.complete(credential("access-2", "refresh-2", Long.MAX_VALUE));
        assertEquals("access-2", first.toCompletableFuture().join());
        assertEquals("access-2", second.toCompletableFuture().join());
        assertEquals("refresh-2", auth.credential().refreshToken());
    }

    @Test
    void signOutDuringRefreshCannotRestoreCredentials() {
        Path credentials = temporaryDirectory.resolve("credentials.json");
        CompletableFuture<ChatGptAuth.Credential> refresh = new CompletableFuture<>();
        ChatGptAuth auth = new ChatGptAuth(credentials, new ChatGptAuth.SignInFlow() {
            @Override public CompletionStage<ModelBackend.AuthState> start(ChatGptAuth ignored, CompletableFuture<ModelBackend.AuthState> cancellation) {
                return CompletableFuture.completedFuture(new ModelBackend.AuthState(ModelBackend.AuthStatus.SIGNED_OUT, null, null));
            }
            @Override public CompletionStage<ChatGptAuth.Credential> refresh(ChatGptAuth.Credential current) {
                return refresh;
            }
        });
        auth.saveCredential(credential("access-1", "refresh-1", 0));
        CompletionStage<String> access = auth.accessToken();

        auth.signOut();
        refresh.complete(credential("access-2", "refresh-2", Long.MAX_VALUE));
        access.handle((ignored, failure) -> null).toCompletableFuture().join();

        assertNull(auth.credential());
        assertFalse(Files.exists(credentials));
    }

    @Test
    void terminalRefreshRejectionClearsTheUnusableCredential() throws IOException {
        Path credentials = temporaryDirectory.resolve("credentials.json");
        ChatGptAuth auth = new ChatGptAuth(credentials, new StringHttpClient(400,
                new String(ChatGptBackendTest.class.getResourceAsStream("/chatgpt/oauth-expired-refresh.json").readAllBytes(), StandardCharsets.UTF_8)), ignored -> { });
        auth.saveCredential(credential("access-1", "refresh-1", 0));

        auth.accessToken().handle((ignored, failure) -> null).toCompletableFuture().join();

        assertNull(auth.credential());
        assertFalse(Files.exists(credentials));
        assertTrue(auth.lastDetail().contains("sign in again"));
    }

    @Test
    void cancellingSignInCompletesTheExactAttemptWithoutLateSuccess() {
        CompletableFuture<ModelBackend.AuthState> remote = new CompletableFuture<>();
        AtomicInteger cancellations = new AtomicInteger();
        ChatGptAuth auth = new ChatGptAuth(temporaryDirectory.resolve("credentials.json"), new ChatGptAuth.SignInFlow() {
            @Override public CompletionStage<ModelBackend.AuthState> start(ChatGptAuth ignored, CompletableFuture<ModelBackend.AuthState> cancellation) {
                return remote;
            }
            @Override public void cancel() { cancellations.incrementAndGet(); }
        });

        CompletionStage<ModelBackend.AuthState> attempt = auth.signIn();
        auth.cancelSignIn();
        remote.complete(new ModelBackend.AuthState(ModelBackend.AuthStatus.SIGNED_IN, "late", "late"));

        assertEquals(ModelBackend.AuthStatus.SIGNED_OUT, attempt.toCompletableFuture().join().status());
        assertEquals(1, cancellations.get());
        assertNull(auth.credential());
    }

    @Test
    void hostIdIsDurableBeforeTheBrowserOpens() {
        Path credentials = temporaryDirectory.resolve("credentials.json");
        CompletableFuture<URI> opened = new CompletableFuture<>();
        ChatGptAuth auth = new ChatGptAuth(credentials, new StringHttpClient(500, "unused"), uri -> {
            assertTrue(Files.exists(credentials.resolveSibling("credentials.json.host-id")));
            opened.complete(uri);
        });

        CompletionStage<ModelBackend.AuthState> attempt = auth.signIn();

        String authorizeQuery = java.net.URLDecoder.decode(opened.join().getRawQuery(), StandardCharsets.UTF_8);
        assertTrue(authorizeQuery.contains("redirect_uri=http://127.0.0.1:"));
        assertTrue(authorizeQuery.contains("/auth/callback"));
        auth.cancelSignIn();
        assertEquals(ModelBackend.AuthStatus.SIGNED_OUT, attempt.toCompletableFuture().join().status());
    }

    @Test
    void returningCallbackRejectsADifferentClientId() throws Exception {
        Path credentials = temporaryDirectory.resolve("credentials.json");
        new ChatGptAuth(credentials, signedOutFlow()).saveCredential(credential("access-1", "refresh-1", Long.MAX_VALUE));
        CompletableFuture<URI> opened = new CompletableFuture<>();
        ChatGptAuth auth = new ChatGptAuth(credentials, new StringHttpClient(500, "unused"), opened::complete);

        CompletionStage<ModelBackend.AuthState> attempt = auth.signIn();
        Map<String, String> values = new java.util.HashMap<>();
        for (String pair : opened.join().getRawQuery().split("&")) {
            String[] parts = pair.split("=", 2);
            values.put(java.net.URLDecoder.decode(parts[0], StandardCharsets.UTF_8), java.net.URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
        }
        HttpResponse<String> callback = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(values.get("redirect_uri")
                + "?code=fixture-code&state=" + java.net.URLEncoder.encode(values.get("state"), StandardCharsets.UTF_8)
                + "&client_id=oaiapp_other")).GET().build(), HttpResponse.BodyHandlers.ofString());

        assertEquals(200, callback.statusCode());
        assertEquals(ModelBackend.AuthStatus.FAILED, attempt.toCompletableFuture().join().status());
        assertTrue(auth.lastDetail().contains("different client id"));
        assertEquals("oaiapp_fixture", auth.credential().clientId());
    }

    @Test
    void statelessToolContinuationReplaysOutputAndPairsAllResultsOnce() {
        Path credentials = temporaryDirectory.resolve("credentials.json");
        ChatGptAuth auth = new ChatGptAuth(credentials, signedOutFlow());
        auth.saveCredential(credential("access-1", "refresh-1", Long.MAX_VALUE));
        CapturingHttpClient client = new CapturingHttpClient(List.of(
                "data: {\"type\":\"response.output_item.done\",\"output_index\":0,\"sequence_number\":1,\"item\":{\"id\":\"rs_1\",\"type\":\"reasoning\",\"encrypted_content\":\"encrypted-fixture\",\"summary\":[]}}\n\n"
                        + "data: {\"type\":\"response.output_item.done\",\"output_index\":1,\"sequence_number\":2,\"item\":{\"id\":\"fc_a\",\"type\":\"function_call\",\"call_id\":\"call_a\",\"name\":\"read_evidence\",\"arguments\":\"{\\\"id\\\":\\\"a\\\"}\",\"status\":\"completed\"}}\n\n"
                        + "data: {\"type\":\"response.output_item.done\",\"output_index\":2,\"sequence_number\":3,\"item\":{\"id\":\"fc_b\",\"type\":\"function_call\",\"call_id\":\"call_b\",\"name\":\"read_evidence\",\"arguments\":\"{\\\"id\\\":\\\"b\\\"}\",\"status\":\"completed\"}}\n\n"
                        + "data: {\"type\":\"response.completed\",\"sequence_number\":4,\"response\":{\"status\":\"completed\"}}\n\n",
                "data: {\"type\":\"response.output_text.delta\",\"sequence_number\":1,\"delta\":\"finished\"}\n\n"
                        + "data: {\"type\":\"response.output_item.done\",\"output_index\":0,\"sequence_number\":2,\"item\":{\"id\":\"msg_1\",\"type\":\"message\",\"status\":\"completed\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"finished\",\"annotations\":[]}]}}\n\n"
                        + "data: {\"type\":\"response.completed\",\"sequence_number\":3,\"response\":{\"status\":\"completed\"}}\n\n"));
        List<ModelBackend.Event> events = new ArrayList<>();
        ModelBackend.Request request = new ModelBackend.Request("gpt-5-mini", "Answer from evidence.",
                List.of(new ModelBackend.Message(ModelBackend.Role.USER, List.of(new ModelBackend.TextContent("question")))),
                List.of(new ModelBackend.ToolDefinition("read_evidence", "Read evidence", "{\"type\":\"object\"}")),
                ModelBackend.SearchMode.DISABLED, "request-1", 512);

        ModelBackend.Exchange exchange = new ChatGptBackend(auth, client, Runnable::run).start(request, events::add);
        exchange.submit(new ModelBackend.ToolResult("call_b", "{\"value\":\"b\"}", false));
        assertEquals(1, client.requests.size());
        exchange.submit(new ModelBackend.ToolResult("call_a", "{\"value\":\"a\"}", false));

        assertEquals(2, client.requests.size());
        JsonArray continuation = JsonParser.parseString(requestBody(client.requests.get(1))).getAsJsonObject().getAsJsonArray("input");
        assertEquals("message", continuation.get(0).getAsJsonObject().get("type").getAsString());
        assertEquals("reasoning", continuation.get(1).getAsJsonObject().get("type").getAsString());
        assertEquals("encrypted-fixture", continuation.get(1).getAsJsonObject().get("encrypted_content").getAsString());
        assertEquals("call_a", continuation.get(2).getAsJsonObject().get("call_id").getAsString());
        assertEquals("call_b", continuation.get(3).getAsJsonObject().get("call_id").getAsString());
        assertEquals("call_a", continuation.get(4).getAsJsonObject().get("call_id").getAsString());
        assertEquals("call_b", continuation.get(5).getAsJsonObject().get("call_id").getAsString());
        assertTrue(events.stream().anyMatch(event -> event.kind() == ModelBackend.EventKind.TEXT_DELTA && "finished".equals(event.text())));
        assertEquals(1, events.stream().filter(event -> event.kind() == ModelBackend.EventKind.COMPLETED).count());
        ModelBackend.Event completed = events.stream().filter(event -> event.kind() == ModelBackend.EventKind.COMPLETED).findFirst().orElseThrow();
        assertEquals(List.of(
                new ModelBackend.EncryptedReasoning("rs_1", "encrypted-fixture", "[]"),
                new ModelBackend.ToolCall("call_a", "read_evidence", "{\"id\":\"a\"}"),
                new ModelBackend.ToolCall("call_b", "read_evidence", "{\"id\":\"b\"}"),
                new ModelBackend.OpaqueHostedOutput("message", "{\"id\":\"msg_1\",\"type\":\"message\",\"status\":\"completed\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"finished\",\"annotations\":[]}]}")
        ), completed.replayItems());
    }

    @Test
    void pkceChallengeAndDirectScopeAreDeterministicAndStrict() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", ChatGptAuth.codeChallenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"));
        assertTrue(ChatGptAuth.hasDirectScope(List.of("openid", ChatGptAuth.REQUIRED_SCOPE)));
        assertFalse(ChatGptAuth.hasDirectScope(List.of("openid", "resource.invoke")));
    }

    private static ChatGptAuth.Credential credential(String accessToken, String refreshToken, long expiry) {
        return new ChatGptAuth.Credential(ChatGptAuth.ISSUER, "fixture-subject", "oaiapp_fixture", "urn:uuid:00000000-0000-4000-8000-000000000001", "id-token", accessToken, refreshToken, expiry, List.of("openid", ChatGptAuth.REQUIRED_SCOPE));
    }

    private static ChatGptAuth.SignInFlow signedOutFlow() {
        return (ignored, cancellation) -> CompletableFuture.completedFuture(new ModelBackend.AuthState(ModelBackend.AuthStatus.SIGNED_OUT, null, null));
    }

    private static byte[] unsigned(byte[] value) {
        if (value.length > 1 && value[0] == 0) return java.util.Arrays.copyOfRange(value, 1, value.length);
        return value;
    }

    private static SignedToken signedToken(Consumer<JsonObject> headerChange, Consumer<JsonObject> claimsChange) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        RSAPublicKey publicKey = (RSAPublicKey) pair.getPublic();
        JsonObject header = JsonParser.parseString("{\"alg\":\"RS256\",\"kid\":\"fixture-key\",\"typ\":\"JWT\"}").getAsJsonObject();
        headerChange.accept(header);
        JsonObject claims = new JsonObject();
        claims.addProperty("iss", ChatGptAuth.ISSUER);
        JsonArray audience = new JsonArray();
        audience.add("other-client");
        audience.add("oaiapp_fixture");
        claims.add("aud", audience);
        claims.addProperty("exp", 2_000_000_000L);
        claims.addProperty("nonce", "fixture-nonce");
        claims.addProperty("sub", "fixture-subject");
        claimsChange.accept(claims);
        String encodedHeader = Base64.getUrlEncoder().withoutPadding().encodeToString(header.toString().getBytes(StandardCharsets.UTF_8));
        String encodedClaims = Base64.getUrlEncoder().withoutPadding().encodeToString(claims.toString().getBytes(StandardCharsets.UTF_8));
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(pair.getPrivate());
        signer.update((encodedHeader + "." + encodedClaims).getBytes(StandardCharsets.US_ASCII));
        String token = encodedHeader + "." + encodedClaims + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign());
        JsonObject key = new JsonObject();
        key.addProperty("kty", "RSA");
        key.addProperty("use", "sig");
        key.addProperty("alg", "RS256");
        key.addProperty("kid", "fixture-key");
        key.addProperty("n", Base64.getUrlEncoder().withoutPadding().encodeToString(unsigned(publicKey.getModulus().toByteArray())));
        key.addProperty("e", Base64.getUrlEncoder().withoutPadding().encodeToString(unsigned(publicKey.getPublicExponent().toByteArray())));
        JsonArray keys = new JsonArray();
        keys.add(key);
        JsonObject jwks = new JsonObject();
        jwks.add("keys", keys);
        return new SignedToken(token, jwks.toString());
    }

    private record SignedToken(String token, String jwks) {
    }

    private static String requestBody(HttpRequest request) {
        HttpRequest.BodyPublisher publisher = request.bodyPublisher().orElseThrow();
        CompletableFuture<byte[]> body = new CompletableFuture<>();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        publisher.subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
            @Override public void onNext(ByteBuffer item) {
                byte[] chunk = new byte[item.remaining()];
                item.get(chunk);
                bytes.writeBytes(chunk);
            }
            @Override public void onError(Throwable throwable) { body.completeExceptionally(throwable); }
            @Override public void onComplete() { body.complete(bytes.toByteArray()); }
        });
        return new String(body.join(), StandardCharsets.UTF_8);
    }

    private static final class CapturingHttpClient extends HttpClient {
        private final ArrayDeque<String> responses;
        private final List<HttpRequest> requests = new ArrayList<>();

        private CapturingHttpClient(List<String> responses) {
            this.responses = new ArrayDeque<>(responses);
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
        @Override public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) throws IOException { throw new UnsupportedOperationException(); }
        @SuppressWarnings("unchecked")
        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
            requests.add(request);
            String body = responses.removeFirst();
            return CompletableFuture.completedFuture((HttpResponse<T>) new InputStreamResponse(request, body));
        }
        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler, HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            return sendAsync(request, responseBodyHandler);
        }
    }

    private static final class StringHttpClient extends HttpClient {
        private final int status;
        private final String response;

        private StringHttpClient(int status, String response) {
            this.status = status;
            this.response = response;
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
        @Override public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) throws IOException { throw new UnsupportedOperationException(); }
        @SuppressWarnings("unchecked")
        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
            return CompletableFuture.completedFuture((HttpResponse<T>) new StringResponse(request, status, response));
        }
        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler, HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            return sendAsync(request, responseBodyHandler);
        }
    }

    private record InputStreamResponse(HttpRequest request, String content) implements HttpResponse<ByteArrayInputStream> {
        @Override public int statusCode() { return 200; }
        @Override public Optional<HttpResponse<ByteArrayInputStream>> previousResponse() { return Optional.empty(); }
        @Override public HttpHeaders headers() { return HttpHeaders.of(Map.of(), (name, value) -> true); }
        @Override public ByteArrayInputStream body() { return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)); }
        @Override public Optional<SSLSession> sslSession() { return Optional.empty(); }
        @Override public URI uri() { return request.uri(); }
        @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
    }

    private record StringResponse(HttpRequest request, int statusCode, String body) implements HttpResponse<String> {
        @Override public Optional<HttpResponse<String>> previousResponse() { return Optional.empty(); }
        @Override public HttpHeaders headers() { return HttpHeaders.of(Map.of(), (name, value) -> true); }
        @Override public Optional<SSLSession> sslSession() { return Optional.empty(); }
        @Override public URI uri() { return request.uri(); }
        @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
    }
}
