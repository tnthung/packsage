package dev.packsage.model.chatgpt;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.packsage.chat.ChatContracts;
import dev.packsage.knowledge.Evidence;
import dev.packsage.model.GroundingInstructions;
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
import static org.junit.jupiter.api.Assertions.assertSame;
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
    void requestsExplicitlyAllowParallelToolCalls() {
        ModelBackend.Request request = new ModelBackend.Request("gpt-5-mini", "Answer from evidence.", List.of(),
                List.of(new ModelBackend.ToolDefinition("search_pack", "Search", "{}")),
                ModelBackend.SearchMode.DISABLED, "parallel-1", 512);

        JsonObject body = JsonParser.parseString(ChatGptBackend.requestBody(request)).getAsJsonObject();

        assertTrue(body.has("parallel_tool_calls"));
        assertTrue(body.get("parallel_tool_calls").getAsBoolean());
    }

    @Test
    void reasoningEffortIsOmittedByDefaultAndSerializedWhenSelected() {
        ModelBackend.Request defaultRequest = new ModelBackend.Request("gpt-5-mini", "Answer.", List.of(), List.of(),
                ModelBackend.SearchMode.DISABLED, "reasoning-default", 512);
        ModelBackend.Request highRequest = new ModelBackend.Request("gpt-5-mini", "Answer.", List.of(), List.of(),
                ModelBackend.SearchMode.DISABLED, "reasoning-high", 512, ModelBackend.ReasoningEffort.HIGH);
        JsonObject defaultBody = JsonParser.parseString(ChatGptBackend.requestBody(defaultRequest)).getAsJsonObject();
        JsonObject highBody = JsonParser.parseString(ChatGptBackend.requestBody(highRequest)).getAsJsonObject();
        assertFalse(defaultBody.has("reasoning"));
        assertEquals("high", highBody.getAsJsonObject("reasoning").get("effort").getAsString());
    }

    @Test
    void historicalAssistantTextUsesOutputTextWireType() {
        Evidence.Scope scope = new Evidence.Scope("world", "resources", "team", "runtime", 1);
        ChatContracts.Turn historical = new ChatContracts.Turn(new ChatContracts.RequestId("history-wire"),
                "old question", "old answer", List.of(new ModelBackend.ToolCall("old-call", "lookup", "{}"),
                        new ModelBackend.ToolResult("old-call", "{\"secret\":true}", false),
                        new ModelBackend.AssistantOutput("msg_known",
                        ModelBackend.OutputStatus.COMPLETED, ModelBackend.OutputPhase.FINAL_ANSWER,
                        List.of(new ModelBackend.OutputText("old answer", List.of())))),
                new ChatContracts.Context("old", null, scope), List.of(),
                new ChatContracts.Outcome(ChatContracts.OutcomeStatus.COMPLETED, null), 1);
        ChatContracts.Submission submission = new ChatContracts.Submission(new ChatContracts.RequestId("wire"),
                new ChatContracts.WorldIdentity("world", "World"), scope, "new question", false,
                new ChatContracts.HistoryLimits(10, 12_000), new ModelBackend.ModelInfo("gpt-5-mini", null, null, null, null),
                ModelBackend.SearchMode.DISABLED, 512);
        ModelBackend.Request request = GroundingInstructions.build(new ChatContracts.PreparedRequest(submission,
                new ChatContracts.HistorySelection(List.of(historical), 1),
                new ChatContracts.CapturedContext(new ChatContracts.Context("fresh", null, scope), new byte[0]),
                new ChatContracts.EvidenceSelection(List.of(), List.of())));

        JsonArray input = JsonParser.parseString(ChatGptBackend.requestBody(request)).getAsJsonObject().getAsJsonArray("input");
        assertEquals(4, input.size());
        assertEquals("user", input.get(0).getAsJsonObject().get("role").getAsString());
        assertEquals("input_text", input.get(0).getAsJsonObject().getAsJsonArray("content").get(0).getAsJsonObject().get("type").getAsString());
        assertEquals("old question", input.get(0).getAsJsonObject().getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString());
        assertEquals("message", input.get(1).getAsJsonObject().get("type").getAsString());
        assertEquals("msg_known", input.get(1).getAsJsonObject().get("id").getAsString());
        assertEquals("completed", input.get(1).getAsJsonObject().get("status").getAsString());
        assertEquals("assistant", input.get(1).getAsJsonObject().get("role").getAsString());
        assertEquals("output_text", input.get(1).getAsJsonObject().getAsJsonArray("content").get(0).getAsJsonObject().get("type").getAsString());
        assertEquals("old answer", input.get(1).getAsJsonObject().getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString());
        assertTrue(input.get(1).getAsJsonObject().getAsJsonArray("content").get(0).getAsJsonObject().getAsJsonArray("annotations").isEmpty());
        assertEquals("user", input.get(2).getAsJsonObject().get("role").getAsString());
        assertTrue(input.get(2).getAsJsonObject().getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString().contains("fresh"));
        assertEquals("user", input.get(3).getAsJsonObject().get("role").getAsString());
        assertEquals(1, input.get(3).getAsJsonObject().getAsJsonArray("content").size());
        assertEquals("input_text", input.get(3).getAsJsonObject().getAsJsonArray("content").get(0).getAsJsonObject().get("type").getAsString());
        assertEquals("new question", input.get(3).getAsJsonObject().getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString());
        assertFalse(input.toString().contains("old-call"));
        assertFalse(input.toString().contains("secret"));
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
    void typedProviderOutputSerializesLosslesslyForStatelessReplay() {
        ModelBackend.Request request = new ModelBackend.Request("gpt-5-mini", "Continue.", List.of(
                new ModelBackend.AssistantOutput("msg_1", ModelBackend.OutputStatus.COMPLETED, ModelBackend.OutputPhase.FINAL_ANSWER,
                        List.of(new ModelBackend.OutputText("source", List.of(new ModelBackend.UrlCitation(0, 6, "Official", "https://example.test"))),
                                new ModelBackend.Refusal("fixture refusal"))),
                new ModelBackend.WebSearchCall("ws_1", ModelBackend.WebSearchStatus.COMPLETED,
                        new ModelBackend.SearchAction("fixture", List.of("fixture", "fixture official"),
                                List.of(new ModelBackend.WebSearchSource("https://example.test"))))),
                List.of(), ModelBackend.SearchMode.DISABLED, "request-replay", 512);

        JsonArray input = JsonParser.parseString(ChatGptBackend.requestBody(request)).getAsJsonObject().getAsJsonArray("input");

        assertEquals("message", input.get(0).getAsJsonObject().get("type").getAsString());
        assertEquals("final_answer", input.get(0).getAsJsonObject().get("phase").getAsString());
        JsonObject citation = input.get(0).getAsJsonObject().getAsJsonArray("content").get(0).getAsJsonObject()
                .getAsJsonArray("annotations").get(0).getAsJsonObject();
        assertEquals(0, citation.get("start_index").getAsInt());
        assertEquals(6, citation.get("end_index").getAsInt());
        assertEquals("https://example.test", citation.get("url").getAsString());
        assertEquals("refusal", input.get(0).getAsJsonObject().getAsJsonArray("content").get(1).getAsJsonObject().get("type").getAsString());
        assertEquals("web_search_call", input.get(1).getAsJsonObject().get("type").getAsString());
        assertEquals("search", input.get(1).getAsJsonObject().getAsJsonObject("action").get("type").getAsString());
        assertEquals(2, input.get(1).getAsJsonObject().getAsJsonObject("action").getAsJsonArray("queries").size());
        assertEquals("https://example.test", input.get(1).getAsJsonObject().getAsJsonObject("action")
                .getAsJsonArray("sources").get(0).getAsJsonObject().get("url").getAsString());
    }

    @Test
    void webSearchOutputWithoutActionCompletesAndReplaysWithoutInventedData() {
        Path credentials = temporaryDirectory.resolve("credentials.json");
        ChatGptAuth auth = new ChatGptAuth(credentials, signedOutFlow());
        auth.saveCredential(credential("access-1", "refresh-1", Long.MAX_VALUE));
        CapturingHttpClient client = new CapturingHttpClient(List.of(
                "data: {\"type\":\"response.output_item.done\",\"output_index\":0,\"sequence_number\":1,\"item\":{\"id\":\"ws_1\",\"type\":\"web_search_call\",\"status\":\"failed\"}}\n\n"
                        + "data: {\"type\":\"response.completed\",\"sequence_number\":2,\"response\":{\"status\":\"completed\"}}\n\n"));
        List<ModelBackend.Event> events = new ArrayList<>();

        new ChatGptBackend(auth, client, Runnable::run).start(new ModelBackend.Request("gpt-5-mini", "Answer from evidence.",
                List.of(new ModelBackend.Message(ModelBackend.Role.USER, List.of(new ModelBackend.TextContent("question")))),
                List.of(), ModelBackend.SearchMode.DISABLED, "actionless-search", 512), events::add);

        ModelBackend.Event completed = events.stream().filter(event -> event.kind() == ModelBackend.EventKind.COMPLETED).findFirst().orElseThrow();
        assertEquals(List.of(new ModelBackend.WebSearchCall("ws_1", ModelBackend.WebSearchStatus.FAILED, null)), completed.replayItems());
        JsonObject replayed = JsonParser.parseString(ChatGptBackend.requestBody(new ModelBackend.Request("gpt-5-mini", "Continue.",
                completed.replayItems(), List.of(), ModelBackend.SearchMode.DISABLED, "actionless-search-replay", 512)))
                .getAsJsonObject().getAsJsonArray("input").get(0).getAsJsonObject();
        assertFalse(replayed.has("action"));
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
    void modelCatalogReadsOptionalTokenMetadataAndLeavesMissingValuesUnknown() {
        ModelBackend.Capabilities capabilities = ChatGptBackend.capabilitiesFromCatalog("""
                {"models":[
                  {"slug":"bounded","visibility":"list","context_window":8192,"max_output_tokens":1024},
                  {"slug":"unknown","visibility":"list"}
                ]}
                """, null);

        assertEquals(8192, capabilities.modelCatalog().get(0).contextTokens());
        assertEquals(1024, capabilities.modelCatalog().get(0).outputTokens());
        assertNull(capabilities.modelCatalog().get(1).contextTokens());
        assertNull(capabilities.modelCatalog().get(1).outputTokens());
        assertEquals(8192, capabilities.contextTokens());
        assertEquals(1024, capabilities.outputTokens());
    }

    @Test
    void acceptedImageAndSearchBecomeVerifiedWithoutInventingTokenLimits() {
        ChatGptAuth auth = new ChatGptAuth(temporaryDirectory.resolve("credentials.json"), signedOutFlow());
        auth.saveCredential(credential("access-1", "refresh-1", Long.MAX_VALUE));
        CapturingHttpClient client = new CapturingHttpClient(
                "{\"models\":[{\"slug\":\"gpt-route\",\"visibility\":\"list\",\"display_name\":\"GPT Route\"}]}", List.of(
                "data: {\"type\":\"response.output_item.done\",\"output_index\":0,\"sequence_number\":1,\"item\":{\"id\":\"msg_image\",\"type\":\"message\",\"status\":\"completed\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"image accepted\",\"annotations\":[]}]}}\n\n"
                        + "data: {\"type\":\"response.completed\",\"sequence_number\":2,\"response\":{\"status\":\"completed\"}}\n\n",
                "data: {\"type\":\"response.output_item.done\",\"output_index\":0,\"sequence_number\":1,\"item\":{\"id\":\"ws_route\",\"type\":\"web_search_call\",\"status\":\"completed\",\"action\":{\"type\":\"search\",\"queries\":[\"fixture\"]}}}\n\n"
                        + "data: {\"type\":\"response.completed\",\"sequence_number\":2,\"response\":{\"status\":\"completed\"}}\n\n"));
        ChatGptBackend backend = new ChatGptBackend(auth, client, Runnable::run);

        ModelBackend.Capabilities unknown = backend.capabilities().toCompletableFuture().join();
        backend.start(new ModelBackend.Request("gpt-route", "Describe the image.",
                List.of(new ModelBackend.Message(ModelBackend.Role.USER, List.of(new ModelBackend.TextContent("Describe it"),
                        new ModelBackend.ImageContent("data:image/png;base64,ZmFrZQ==")))), List.of(), ModelBackend.SearchMode.DISABLED,
                "image-capability", 512), ignored -> { });
        ModelBackend.Capabilities imageVerified = backend.capabilities().toCompletableFuture().join();
        backend.start(new ModelBackend.Request("gpt-route", "Search.",
                List.of(new ModelBackend.Message(ModelBackend.Role.USER, List.of(new ModelBackend.TextContent("Search")))),
                List.of(), ModelBackend.SearchMode.EXPLICIT_REQUEST, "search-capability", 512), ignored -> { });
        ModelBackend.Capabilities bothVerified = backend.capabilities().toCompletableFuture().join();

        assertEquals("GPT Route", unknown.modelCatalog().getFirst().displayName());
        assertNull(unknown.imageInput());
        assertNull(unknown.hostedSearch());
        assertEquals(true, imageVerified.imageInput());
        assertNull(imageVerified.hostedSearch());
        assertEquals(true, bothVerified.imageInput());
        assertEquals(true, bothVerified.hostedSearch());
        assertNull(bothVerified.contextTokens());
        assertNull(bothVerified.outputTokens());
    }

    @Test
    void explicitlyRejectedImageIsOmittedFromLaterRequests() {
        ChatGptAuth auth = new ChatGptAuth(temporaryDirectory.resolve("credentials.json"), signedOutFlow());
        auth.saveCredential(credential("access-1", "refresh-1", Long.MAX_VALUE));
        CapturingHttpClient client = new CapturingHttpClient(List.of(
                "data: {\"type\":\"response.failed\",\"sequence_number\":1,\"response\":{\"status\":\"failed\",\"error\":{\"code\":\"subscription_sharing_unsupported_capability\",\"message\":\"image input is unavailable\",\"param\":\"input\"}}}\n\n",
                "data: {\"type\":\"response.completed\",\"sequence_number\":1,\"response\":{\"status\":\"completed\"}}\n\n"));
        ChatGptBackend backend = new ChatGptBackend(auth, client, Runnable::run);
        ModelBackend.Request image = new ModelBackend.Request("gpt-route", "Describe the image.",
                List.of(new ModelBackend.Message(ModelBackend.Role.USER, List.of(new ModelBackend.TextContent("Describe it"),
                        new ModelBackend.ImageContent("data:image/png;base64,ZmFrZQ==")))), List.of(), ModelBackend.SearchMode.DISABLED,
                "image-1", 512);

        backend.start(image, ignored -> { });
        backend.start(new ModelBackend.Request(image.modelId(), image.instructions(), image.input(), image.tools(), image.searchMode(),
                "image-2", image.outputBudget()), ignored -> { });

        JsonObject fallback = JsonParser.parseString(requestBody(client.requests.get(1))).getAsJsonObject();
        assertEquals(1, fallback.getAsJsonArray("input").get(0).getAsJsonObject().getAsJsonArray("content").size());
        assertFalse(fallback.toString().contains("input_image"));
        assertTrue(fallback.get("instructions").getAsString().contains("screenshot was omitted"));
    }

    @Test
    void unsupportedReasoningCapabilityDoesNotPoisonLaterImageOrSearchRequests() {
        ChatGptAuth auth = new ChatGptAuth(temporaryDirectory.resolve("credentials.json"), signedOutFlow());
        auth.saveCredential(credential("access-1", "refresh-1", Long.MAX_VALUE));
        CapturingHttpClient client = new CapturingHttpClient(List.of(400, 200), List.of(
                "{\"error\":{\"code\":\"subscription_sharing_unsupported_capability\",\"message\":\"reasoning effort with web search is unavailable\",\"param\":\"reasoning.effort\"}}",
                "data: {\"type\":\"response.completed\",\"sequence_number\":1,\"response\":{\"status\":\"completed\"}}\n\n"));
        ChatGptBackend backend = new ChatGptBackend(auth, client, Runnable::run);
        ModelBackend.Request first = new ModelBackend.Request("gpt-route", "Answer.", List.of(new ModelBackend.Message(ModelBackend.Role.USER,
                List.of(new ModelBackend.TextContent("Search")))),
                List.of(), ModelBackend.SearchMode.EXPLICIT_REQUEST, "reasoning-failure", 512, ModelBackend.ReasoningEffort.HIGH);
        List<ModelBackend.Event> events = new ArrayList<>();
        backend.start(first, events::add);
        backend.start(new ModelBackend.Request("gpt-route", "Answer.", List.of(new ModelBackend.Message(ModelBackend.Role.USER,
                List.of(new ModelBackend.TextContent("Search"), new ModelBackend.ImageContent("data:image/png;base64,ZmFrZQ==")))),
                List.of(), ModelBackend.SearchMode.EXPLICIT_REQUEST, "after-reasoning", 512), ignored -> { });

        assertEquals(1, events.stream().filter(event -> event.kind() == ModelBackend.EventKind.FAILED).count());
        assertEquals(0, events.stream().filter(event -> event.kind() == ModelBackend.EventKind.RETRYING).count());
        JsonObject second = JsonParser.parseString(requestBody(client.requests.get(1))).getAsJsonObject();
        assertTrue(second.toString().contains("input_image"));
        assertTrue(second.toString().contains("web_search"));
    }

    @Test
    void streamedUnsupportedReasoningCapabilityDoesNotPoisonLaterRequests() {
        ChatGptAuth auth = new ChatGptAuth(temporaryDirectory.resolve("credentials.json"), signedOutFlow());
        auth.saveCredential(credential("access-1", "refresh-1", Long.MAX_VALUE));
        CapturingHttpClient client = new CapturingHttpClient(List.of(
                "data: {\"type\":\"response.failed\",\"sequence_number\":1,\"response\":{\"status\":\"failed\",\"error\":{\"code\":\"subscription_sharing_unsupported_capability\",\"message\":\"reasoning effort with image input is unavailable\",\"param\":\"reasoning.effort\"}}}\n\n",
                "data: {\"type\":\"response.completed\",\"sequence_number\":1,\"response\":{\"status\":\"completed\"}}\n\n"));
        ChatGptBackend backend = new ChatGptBackend(auth, client, Runnable::run);
        ModelBackend.Request first = new ModelBackend.Request("gpt-route", "Answer.", List.of(new ModelBackend.Message(ModelBackend.Role.USER,
                List.of(new ModelBackend.TextContent("Search"), new ModelBackend.ImageContent("data:image/png;base64,ZmFrZQ==")))),
                List.of(), ModelBackend.SearchMode.DISABLED, "stream-reasoning-failure", 512, ModelBackend.ReasoningEffort.HIGH);
        List<ModelBackend.Event> firstEvents = new ArrayList<>();
        backend.start(first, firstEvents::add);
        List<ModelBackend.Event> events = new ArrayList<>();
        backend.start(new ModelBackend.Request("gpt-route", "Answer.", List.of(new ModelBackend.Message(ModelBackend.Role.USER,
                List.of(new ModelBackend.TextContent("Search"), new ModelBackend.ImageContent("data:image/png;base64,ZmFrZQ==")))),
                List.of(), ModelBackend.SearchMode.EXPLICIT_REQUEST, "after-stream-reasoning", 512), events::add);

        assertEquals(1, firstEvents.stream().filter(event -> event.kind() == ModelBackend.EventKind.FAILED).count());
        assertEquals(0, firstEvents.stream().filter(event -> event.kind() == ModelBackend.EventKind.RETRYING).count());
        assertEquals(1, events.stream().filter(event -> event.kind() == ModelBackend.EventKind.COMPLETED).count());
        JsonObject second = JsonParser.parseString(requestBody(client.requests.get(1))).getAsJsonObject();
        assertTrue(second.toString().contains("input_image"));
        assertTrue(second.toString().contains("web_search"));
    }

    @Test
    void unsupportedToolsWhenSearchWasDisabledDoesNotDisableLaterSearch() {
        ChatGptAuth auth = new ChatGptAuth(temporaryDirectory.resolve("credentials.json"), signedOutFlow());
        auth.saveCredential(credential("access-1", "refresh-1", Long.MAX_VALUE));
        CapturingHttpClient client = new CapturingHttpClient(List.of(
                "data: {\"type\":\"response.failed\",\"sequence_number\":1,\"response\":{\"status\":\"failed\",\"error\":{\"code\":\"unsupported_capability\",\"message\":\"tools unavailable\",\"param\":\"tools\"}}}\n\n",
                "data: {\"type\":\"response.completed\",\"sequence_number\":1,\"response\":{\"status\":\"completed\"}}\n\n"));
        ChatGptBackend backend = new ChatGptBackend(auth, client, Runnable::run);
        ModelBackend.Request first = new ModelBackend.Request("gpt-route", "Answer.", List.of(), List.of(),
                ModelBackend.SearchMode.DISABLED, "disabled-tools", 512);
        backend.start(first, ignored -> { });
        backend.start(new ModelBackend.Request("gpt-route", "Answer.", List.of(), List.of(),
                ModelBackend.SearchMode.EXPLICIT_REQUEST, "after-disabled-tools", 512), ignored -> { });
        assertTrue(requestBody(client.requests.get(1)).contains("web_search"));
    }

    @Test
    void unsupportedInputWhenImageWasAbsentDoesNotDisableLaterImage() {
        ChatGptAuth auth = new ChatGptAuth(temporaryDirectory.resolve("credentials.json"), signedOutFlow());
        auth.saveCredential(credential("access-1", "refresh-1", Long.MAX_VALUE));
        CapturingHttpClient client = new CapturingHttpClient(List.of(
                "data: {\"type\":\"response.failed\",\"sequence_number\":1,\"response\":{\"status\":\"failed\",\"error\":{\"code\":\"unsupported_capability\",\"message\":\"input unavailable\",\"param\":\"input\"}}}\n\n",
                "data: {\"type\":\"response.completed\",\"sequence_number\":1,\"response\":{\"status\":\"completed\"}}\n\n"));
        ChatGptBackend backend = new ChatGptBackend(auth, client, Runnable::run);
        backend.start(new ModelBackend.Request("gpt-route", "Answer.", List.of(), List.of(),
                ModelBackend.SearchMode.DISABLED, "absent-input", 512), ignored -> { });
        backend.start(new ModelBackend.Request("gpt-route", "Answer.", List.of(new ModelBackend.Message(ModelBackend.Role.USER,
                List.of(new ModelBackend.ImageContent("data:image/png;base64,ZmFrZQ==")))), List.of(),
                ModelBackend.SearchMode.DISABLED, "after-absent-input", 512), ignored -> { });
        assertTrue(requestBody(client.requests.get(1)).contains("input_image"));
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
    void browserOpenFailureDoesNotWedgeTheNextSignInAttempt() {
        AtomicInteger opens = new AtomicInteger();
        CompletableFuture<URI> retried = new CompletableFuture<>();
        ChatGptAuth auth = new ChatGptAuth(temporaryDirectory.resolve("credentials.json"), new StringHttpClient(500, "unused"), uri -> {
            if (opens.incrementAndGet() == 1) throw new IllegalStateException("browser failed");
            retried.complete(uri);
        });

        assertEquals(ModelBackend.AuthStatus.FAILED, auth.signIn().toCompletableFuture().join().status());
        CompletionStage<ModelBackend.AuthState> second = auth.signIn();

        assertTrue(java.net.URLDecoder.decode(retried.join().getRawQuery(), StandardCharsets.UTF_8)
                .contains("redirect_uri=http://127.0.0.1:"));
        auth.cancelSignIn();
        assertEquals(ModelBackend.AuthStatus.SIGNED_OUT, second.toCompletableFuture().join().status());
        assertEquals(2, opens.get());
    }

    @Test
    void restoreSessionReportsSavedCredentialWithoutOpeningBrowser() {
        Path credentials = temporaryDirectory.resolve("credentials.json");
        new ChatGptAuth(credentials, signedOutFlow()).saveCredential(credential("access-1", "refresh-1", 0));
        AtomicInteger opens = new AtomicInteger();
        ChatGptAuth auth = new ChatGptAuth(credentials, new StringHttpClient(500, "unused"), uri -> opens.incrementAndGet());

        ModelBackend.AuthState restored = new ChatGptBackend(auth, new StringHttpClient(500, "unused"), Runnable::run)
                .restoreSession().toCompletableFuture().join();

        assertEquals(ModelBackend.AuthStatus.SIGNED_IN, restored.status());
        assertEquals(0, opens.get());
    }

    @Test
    void restoreSessionStaysSignedOutWithoutUsableCredential() throws Exception {
        Path missing = temporaryDirectory.resolve("missing.json");
        assertEquals(ModelBackend.AuthStatus.SIGNED_OUT, new ChatGptBackend(new ChatGptAuth(missing, signedOutFlow()),
                new StringHttpClient(500, "unused"), Runnable::run).restoreSession().toCompletableFuture().join().status());

        Path garbage = temporaryDirectory.resolve("garbage.json");
        Files.writeString(garbage, "not json");
        assertEquals(ModelBackend.AuthStatus.SIGNED_OUT, new ChatGptBackend(new ChatGptAuth(garbage, signedOutFlow()),
                new StringHttpClient(500, "unused"), Runnable::run).restoreSession().toCompletableFuture().join().status());
    }

    @Test
    void rejectedAuthenticationIsATypedFailureButServerErrorsAreNot() {
        Path credentials = temporaryDirectory.resolve("credentials.json");
        new ChatGptAuth(credentials, signedOutFlow()).saveCredential(credential("access-1", "refresh-1", Long.MAX_VALUE));
        for (int status : new int[]{401, 403}) {
            ChatGptBackend backend = new ChatGptBackend(new ChatGptAuth(credentials, new StringHttpClient(status, "{}"), uri -> { }),
                    new StringHttpClient(status, "{}"), Runnable::run);
            java.util.concurrent.CompletionException failure = assertThrows(java.util.concurrent.CompletionException.class,
                    () -> backend.capabilities().toCompletableFuture().join());
            assertTrue(failure.getCause() instanceof ModelBackend.AuthRejectedException);
        }
        ChatGptBackend unavailable = new ChatGptBackend(new ChatGptAuth(credentials, new StringHttpClient(503, "{}"), uri -> { }),
                new StringHttpClient(503, "{}"), Runnable::run);
        java.util.concurrent.CompletionException failure = assertThrows(java.util.concurrent.CompletionException.class,
                () -> unavailable.capabilities().toCompletableFuture().join());
        assertFalse(failure.getCause() instanceof ModelBackend.AuthRejectedException);

        Path expired = temporaryDirectory.resolve("expired.json");
        new ChatGptAuth(expired, signedOutFlow()).saveCredential(credential("access-1", "refresh-1", 0));
        ChatGptAuth refreshing = new ChatGptAuth(expired, new StringHttpClient(401, "{\"error\":\"refresh_token_expired\"}"), uri -> { });
        java.util.concurrent.CompletionException refresh = assertThrows(java.util.concurrent.CompletionException.class,
                () -> refreshing.accessToken().toCompletableFuture().join());
        assertTrue(refresh.getCause() instanceof ModelBackend.AuthRejectedException);
        assertEquals("refresh-1", refreshing.credential().refreshToken());
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
                        + "data: {\"type\":\"response.output_item.done\",\"output_index\":1,\"sequence_number\":2,\"item\":{\"id\":\"fc_a\",\"type\":\"function_call\",\"call_id\":\"call_a\",\"namespace\":\"packsage\",\"name\":\"read_evidence\",\"arguments\":\"{\\\"id\\\":\\\"a\\\"}\",\"status\":\"completed\"}}\n\n"
                        + "data: {\"type\":\"response.output_item.done\",\"output_index\":2,\"sequence_number\":3,\"item\":{\"id\":\"fc_b\",\"type\":\"function_call\",\"call_id\":\"call_b\",\"namespace\":\"packsage\",\"name\":\"read_evidence\",\"arguments\":\"{\\\"id\\\":\\\"b\\\"}\",\"status\":\"completed\"}}\n\n"
                        + "data: {\"type\":\"response.completed\",\"sequence_number\":4,\"response\":{\"status\":\"completed\"}}\n\n",
                "data: {\"type\":\"response.output_text.delta\",\"sequence_number\":1,\"delta\":\"finished\"}\n\n"
                        + "data: {\"type\":\"response.output_item.done\",\"output_index\":0,\"sequence_number\":2,\"item\":{\"id\":\"ws_1\",\"type\":\"web_search_call\",\"status\":\"completed\",\"action\":{\"type\":\"search\",\"query\":\"fixture\",\"queries\":[\"fixture\"],\"sources\":[{\"type\":\"url\",\"url\":\"https://example.test\"}]}}}\n\n"
                        + "data: {\"type\":\"response.output_item.done\",\"output_index\":1,\"sequence_number\":3,\"item\":{\"id\":\"msg_1\",\"type\":\"message\",\"status\":\"completed\",\"role\":\"assistant\",\"phase\":\"final_answer\",\"content\":[{\"type\":\"output_text\",\"text\":\"finished\",\"annotations\":[{\"type\":\"url_citation\",\"start_index\":0,\"end_index\":8,\"title\":\"Official\",\"url\":\"https://example.test\"}]}]}}\n\n"
                        + "data: {\"type\":\"response.completed\",\"sequence_number\":4,\"response\":{\"status\":\"completed\"}}\n\n"));
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
        assertEquals("packsage", continuation.get(2).getAsJsonObject().get("namespace").getAsString());
        assertEquals("packsage", continuation.get(3).getAsJsonObject().get("namespace").getAsString());
        assertEquals("call_a", continuation.get(4).getAsJsonObject().get("call_id").getAsString());
        assertEquals("call_b", continuation.get(5).getAsJsonObject().get("call_id").getAsString());
        assertTrue(events.stream().anyMatch(event -> event.kind() == ModelBackend.EventKind.TEXT_DELTA && "finished".equals(event.text())));
        assertEquals(1, events.stream().filter(event -> event.kind() == ModelBackend.EventKind.COMPLETED).count());
        ModelBackend.Event completed = events.stream().filter(event -> event.kind() == ModelBackend.EventKind.COMPLETED).findFirst().orElseThrow();
        assertEquals(List.of(
                new ModelBackend.EncryptedReasoning("rs_1", "encrypted-fixture", "[]"),
                new ModelBackend.ToolCall("call_a", "packsage", "read_evidence", "{\"id\":\"a\"}"),
                new ModelBackend.ToolCall("call_b", "packsage", "read_evidence", "{\"id\":\"b\"}"),
                new ModelBackend.ToolResult("call_a", "{\"value\":\"a\"}", false),
                new ModelBackend.ToolResult("call_b", "{\"value\":\"b\"}", false),
                new ModelBackend.WebSearchCall("ws_1", ModelBackend.WebSearchStatus.COMPLETED,
                        new ModelBackend.SearchAction("fixture", List.of("fixture"),
                                List.of(new ModelBackend.WebSearchSource("https://example.test")))),
                new ModelBackend.AssistantOutput("msg_1", ModelBackend.OutputStatus.COMPLETED, ModelBackend.OutputPhase.FINAL_ANSWER,
                        List.of(new ModelBackend.OutputText("finished", List.of(
                                new ModelBackend.UrlCitation(0, 8, "Official", "https://example.test")))))
        ), completed.replayItems());
    }

    private static final String TOOL_CALL_RESPONSE =
            "data: {\"type\":\"response.output_item.done\",\"output_index\":0,\"sequence_number\":1,\"item\":{\"id\":\"fc_a\",\"type\":\"function_call\",\"call_id\":\"call_a\",\"namespace\":\"packsage\",\"name\":\"search_pack\",\"arguments\":\"{}\",\"status\":\"completed\"}}\n\n"
                    + "data: {\"type\":\"response.completed\",\"sequence_number\":2,\"response\":{\"status\":\"completed\"}}\n\n";
    private static final String TWO_TOOL_CALL_RESPONSE =
            "data: {\"type\":\"response.output_item.done\",\"output_index\":0,\"sequence_number\":1,\"item\":{\"id\":\"fc_a\",\"type\":\"function_call\",\"call_id\":\"call_a\",\"namespace\":\"packsage\",\"name\":\"search_pack\",\"arguments\":\"{}\",\"status\":\"completed\"}}\n\n"
                    + "data: {\"type\":\"response.output_item.done\",\"output_index\":1,\"sequence_number\":2,\"item\":{\"id\":\"fc_b\",\"type\":\"function_call\",\"call_id\":\"call_b\",\"namespace\":\"packsage\",\"name\":\"read_evidence\",\"arguments\":\"{}\",\"status\":\"completed\"}}\n\n"
                    + "data: {\"type\":\"response.completed\",\"sequence_number\":3,\"response\":{\"status\":\"completed\"}}\n\n";
    private static final String MAX_MESSAGES_RESPONSE =
            "data: {\"type\":\"response.output_item.done\",\"output_index\":0,\"sequence_number\":1,\"item\":{\"id\":\"ws_loop\",\"type\":\"web_search_call\",\"status\":\"completed\"}}\n\n"
                    + "data: {\"type\":\"response.incomplete\",\"sequence_number\":2,\"response\":{\"status\":\"incomplete\",\"incomplete_details\":{\"reason\":\"max_messages\"},\"output\":[{\"id\":\"ws_loop\",\"type\":\"web_search_call\",\"status\":\"completed\"}]}}\n\n";
    private static final String ANSWER_RESPONSE =
            "data: {\"type\":\"response.output_text.delta\",\"sequence_number\":1,\"delta\":\"local answer\"}\n\n"
                    + "data: {\"type\":\"response.output_item.done\",\"output_index\":0,\"sequence_number\":2,\"item\":{\"id\":\"msg_1\",\"type\":\"message\",\"status\":\"completed\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"local answer\",\"annotations\":[]}]}}\n\n"
                    + "data: {\"type\":\"response.completed\",\"sequence_number\":3,\"response\":{\"status\":\"completed\"}}\n\n";

    private ModelBackend.Request searchRequest(String requestId, ModelBackend.SearchMode searchMode) {
        return new ModelBackend.Request("gpt-5-mini", "Answer from evidence.",
                List.of(new ModelBackend.Message(ModelBackend.Role.USER, List.of(new ModelBackend.TextContent("question")))),
                List.of(new ModelBackend.ToolDefinition("search_pack", "Search", "{\"type\":\"object\"}")), searchMode, requestId, 512);
    }

    private ChatGptAuth signedInAuth() {
        ChatGptAuth auth = new ChatGptAuth(temporaryDirectory.resolve("credentials.json"), signedOutFlow());
        auth.saveCredential(credential("access-1", "refresh-1", Long.MAX_VALUE));
        return auth;
    }

    private static boolean offersWebSearch(JsonObject body) {
        if (!body.has("tools")) return false;
        for (var tool : body.getAsJsonArray("tools")) {
            if ("web_search".equals(tool.getAsJsonObject().get("type").getAsString())) return true;
        }
        return false;
    }

    @Test
    void maxMessagesIncompleteWithWebSearchRetriesOnceWithoutWebSearchAndKeepsPairs() {
        CapturingHttpClient client = new CapturingHttpClient(List.of(TOOL_CALL_RESPONSE, MAX_MESSAGES_RESPONSE, ANSWER_RESPONSE));
        List<ModelBackend.Event> events = new ArrayList<>();

        ModelBackend.Exchange exchange = new ChatGptBackend(signedInAuth(), client, Runnable::run)
                .start(searchRequest("retry-1", ModelBackend.SearchMode.AUTOMATIC), events::add);
        exchange.submit(new ModelBackend.ToolResult("call_a", "{\"found\":true}", false));

        assertEquals(3, client.requests.size());
        JsonObject incomplete = JsonParser.parseString(requestBody(client.requests.get(1))).getAsJsonObject();
        JsonObject retry = JsonParser.parseString(requestBody(client.requests.get(2))).getAsJsonObject();
        assertTrue(offersWebSearch(incomplete));
        assertFalse(offersWebSearch(retry));
        assertTrue(retry.has("tools"));
        assertEquals(incomplete.getAsJsonArray("input"), retry.getAsJsonArray("input"));
        assertTrue(retry.get("instructions").getAsString().contains("web lookup was cut short"));
        assertEquals(1, events.stream().filter(event -> event.kind() == ModelBackend.EventKind.RETRYING).count());
        assertFalse(events.stream().anyMatch(event -> event.kind() == ModelBackend.EventKind.FAILED));
        ModelBackend.Event completed = events.stream().filter(event -> event.kind() == ModelBackend.EventKind.COMPLETED)
                .findFirst().orElseThrow();
        assertEquals(List.of(new ModelBackend.ToolCall("call_a", "packsage", "search_pack", "{}"),
                new ModelBackend.ToolResult("call_a", "{\"found\":true}", false),
                new ModelBackend.AssistantOutput("msg_1", ModelBackend.OutputStatus.COMPLETED, null,
                        List.of(new ModelBackend.OutputText("local answer", List.of())))), completed.replayItems());
    }

    @Test
    void maxMessagesAfterAClientCallDoesNotPublishTheIncompleteCallBeforeRetry() {
        String incompleteClientCall =
                "data: {\"type\":\"response.output_item.done\",\"output_index\":0,\"sequence_number\":1,\"item\":{\"id\":\"fc_b\",\"type\":\"function_call\",\"call_id\":\"call_b\",\"namespace\":\"packsage\",\"name\":\"read_evidence\",\"arguments\":\"{}\",\"status\":\"completed\"}}\n\n"
                        + "data: {\"type\":\"response.incomplete\",\"sequence_number\":2,\"response\":{\"status\":\"incomplete\",\"incomplete_details\":{\"reason\":\"max_messages\"},\"output\":[{\"id\":\"fc_b\",\"type\":\"function_call\",\"call_id\":\"call_b\",\"namespace\":\"packsage\",\"name\":\"read_evidence\",\"arguments\":\"{}\",\"status\":\"completed\"}]}}\n\n";
        CapturingHttpClient client = new CapturingHttpClient(List.of(TOOL_CALL_RESPONSE, incompleteClientCall, ANSWER_RESPONSE));
        List<ModelBackend.Event> events = new ArrayList<>();

        ModelBackend.Exchange exchange = new ChatGptBackend(signedInAuth(), client, Runnable::run)
                .start(searchRequest("retry-client-call", ModelBackend.SearchMode.AUTOMATIC), events::add);
        exchange.submit(new ModelBackend.ToolResult("call_a", "{\"found\":true}", false));

        assertEquals(3, client.requests.size());
        JsonObject incomplete = JsonParser.parseString(requestBody(client.requests.get(1))).getAsJsonObject();
        JsonObject retry = JsonParser.parseString(requestBody(client.requests.get(2))).getAsJsonObject();
        assertEquals(incomplete.getAsJsonArray("input"), retry.getAsJsonArray("input"));
        assertTrue(incomplete.getAsJsonArray("input").toString().contains("call_a"));
        assertFalse(incomplete.getAsJsonArray("input").toString().contains("call_b"));
        assertFalse(offersWebSearch(retry));
        assertFalse(retry.has("tool_choice"));
        assertTrue(events.stream().noneMatch(event -> event.kind() == ModelBackend.EventKind.CLIENT_TOOL_CALL
                && event.toolCall() != null && event.toolCall().callId().equals("call_b")));
        assertEquals(1, events.stream().filter(event -> event.kind() == ModelBackend.EventKind.RETRYING).count());
        assertFalse(events.stream().anyMatch(event -> event.kind() == ModelBackend.EventKind.FAILED));
        assertTrue(events.stream().anyMatch(event -> event.kind() == ModelBackend.EventKind.COMPLETED));
        ModelBackend.Event completed = events.stream().filter(event -> event.kind() == ModelBackend.EventKind.COMPLETED)
                .findFirst().orElseThrow();
        assertEquals(List.of(new ModelBackend.ToolCall("call_a", "packsage", "search_pack", "{}"),
                new ModelBackend.ToolResult("call_a", "{\"found\":true}", false),
                new ModelBackend.AssistantOutput("msg_1", ModelBackend.OutputStatus.COMPLETED, null,
                        List.of(new ModelBackend.OutputText("local answer", List.of())))), completed.replayItems());
    }

    @Test
    void cancellingFirstPublishedCallSuppressesLaterCallsAndContinuation() {
        List<ModelBackend.Event> events = new ArrayList<>();
        java.util.concurrent.atomic.AtomicReference<ModelBackend.Exchange> holder = new java.util.concurrent.atomic.AtomicReference<>();
        ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        CapturingHttpClient client = new CapturingHttpClient(List.of(TWO_TOOL_CALL_RESPONSE));
        holder.set(new ChatGptBackend(signedInAuth(), client, tasks::add)
                .start(searchRequest("cancel-buffered-calls", ModelBackend.SearchMode.DISABLED), event -> {
                    events.add(event);
                    if (event.kind() == ModelBackend.EventKind.CLIENT_TOOL_CALL) holder.get().cancel();
                }));
        while (!tasks.isEmpty()) tasks.removeFirst().run();

        assertEquals(1, client.requests.size());
        assertEquals(List.of("call_a"), events.stream().filter(event -> event.kind() == ModelBackend.EventKind.CLIENT_TOOL_CALL)
                .map(event -> event.toolCall().callId()).toList());
        assertEquals(1, events.stream().filter(event -> event.kind() == ModelBackend.EventKind.CLIENT_TOOL_CALL).count());
        assertTrue(events.stream().noneMatch(event -> event.kind() == ModelBackend.EventKind.COMPLETED
                || event.kind() == ModelBackend.EventKind.FAILED));
    }

    @Test
    void secondMaxMessagesIncompleteFailsWithTheProviderReasonWithoutAnotherRetry() {
        CapturingHttpClient client = new CapturingHttpClient(List.of(MAX_MESSAGES_RESPONSE, MAX_MESSAGES_RESPONSE, ANSWER_RESPONSE));
        List<ModelBackend.Event> events = new ArrayList<>();

        new ChatGptBackend(signedInAuth(), client, Runnable::run)
                .start(searchRequest("retry-twice", ModelBackend.SearchMode.EXPLICIT_REQUEST), events::add);

        assertEquals(2, client.requests.size());
        assertFalse(JsonParser.parseString(requestBody(client.requests.get(1))).getAsJsonObject().has("tool_choice"));
        assertEquals(1, events.stream().filter(event -> event.kind() == ModelBackend.EventKind.RETRYING).count());
        List<ModelBackend.Event> failures = events.stream().filter(event -> event.kind() == ModelBackend.EventKind.FAILED).toList();
        assertEquals(1, failures.size());
        assertTrue(failures.getFirst().detail().startsWith("response incomplete: max_messages"), failures.getFirst().detail());
    }

    @Test
    void serviceUnavailableRetriesOnceAndRecoversBeforeDispatchingTools() {
        CapturingHttpClient client = new CapturingHttpClient(List.of(503, 200), List.of(
                "{\"error\":{\"message\":\"busy\"}}",
                "data: {\"type\":\"response.completed\",\"sequence_number\":1,\"response\":{\"status\":\"completed\"}}\n\n"));
        List<ModelBackend.Event> events = new ArrayList<>();

        new ChatGptBackend(signedInAuth(), client, Runnable::run)
                .start(searchRequest("retry-503", ModelBackend.SearchMode.DISABLED), events::add);

        assertEquals(2, client.requests.size());
        assertEquals(1, events.stream().filter(event -> event.kind() == ModelBackend.EventKind.RETRYING).count());
        assertEquals(1, events.stream().filter(event -> event.kind() == ModelBackend.EventKind.COMPLETED).count());
        assertEquals(0, events.stream().filter(event -> event.kind() == ModelBackend.EventKind.FAILED).count());
        assertEquals(requestBody(client.requests.get(0)), requestBody(client.requests.get(1)));
    }

    @Test
    void repeatedServiceUnavailableFailsAfterTheSingleBoundedRetry() {
        CapturingHttpClient client = new CapturingHttpClient(List.of(503, 503), List.of(
                "{\"error\":{\"message\":\"busy\"}}", "{\"error\":{\"message\":\"still busy\"}}"));
        List<ModelBackend.Event> events = new ArrayList<>();

        new ChatGptBackend(signedInAuth(), client, Runnable::run)
                .start(searchRequest("retry-503-exhausted", ModelBackend.SearchMode.DISABLED), events::add);

        assertEquals(2, client.requests.size());
        assertEquals(1, events.stream().filter(event -> event.kind() == ModelBackend.EventKind.RETRYING).count());
        assertEquals(1, events.stream().filter(event -> event.kind() == ModelBackend.EventKind.FAILED).count());
    }

    @Test
    void serviceUnavailableContinuationReusesTheCompletedToolResultWithoutRepeatingTheCall() {
        CapturingHttpClient client = new CapturingHttpClient(List.of(200, 503, 200), List.of(
                TOOL_CALL_RESPONSE, "{\"error\":{\"message\":\"busy\"}}", ANSWER_RESPONSE));
        List<ModelBackend.Event> events = new ArrayList<>();
        ModelBackend.Exchange exchange = new ChatGptBackend(signedInAuth(), client, Runnable::run)
                .start(searchRequest("retry-503-continuation", ModelBackend.SearchMode.DISABLED), events::add);
        exchange.submit(new ModelBackend.ToolResult("call_a", "{\"found\":true}", false));

        assertEquals(3, client.requests.size());
        assertEquals(requestBody(client.requests.get(1)), requestBody(client.requests.get(2)));
        assertTrue(requestBody(client.requests.get(2)).contains("function_call_output"));
        assertEquals(1, events.stream().filter(event -> event.kind() == ModelBackend.EventKind.CLIENT_TOOL_CALL).count());
        assertEquals(1, events.stream().filter(event -> event.kind() == ModelBackend.EventKind.RETRYING).count());
        var completed = events.stream().filter(event -> event.kind() == ModelBackend.EventKind.COMPLETED).findFirst().orElseThrow();
        assertEquals(1, completed.replayItems().stream().filter(item -> item instanceof ModelBackend.ToolCall).count());
        assertEquals(1, completed.replayItems().stream().filter(item -> item instanceof ModelBackend.ToolResult).count());
    }

    @Test
    void cancellingWhenServiceUnavailableRetryIsAnnouncedSuppressesTheRetryRequest() {
        CapturingHttpClient client = new CapturingHttpClient(List.of(503), List.of("{\"error\":{\"message\":\"busy\"}}"));
        List<ModelBackend.Event> events = new ArrayList<>();
        java.util.concurrent.atomic.AtomicReference<ModelBackend.Exchange> holder = new java.util.concurrent.atomic.AtomicReference<>();
        ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean();

        holder.set(new ChatGptBackend(signedInAuth(), client, tasks::add)
                .start(searchRequest("retry-503-cancel", ModelBackend.SearchMode.DISABLED), event -> {
                    events.add(event);
                    if (event.kind() == ModelBackend.EventKind.RETRYING) { holder.get().cancel(); cancelled.set(true); }
                }));
        while (!tasks.isEmpty()) tasks.remove().run();

        assertTrue(cancelled.get());
        assertEquals(1, client.requests.size());
        assertEquals(1, events.stream().filter(event -> event.kind() == ModelBackend.EventKind.RETRYING).count());
        assertEquals(0, events.stream().filter(event -> event.kind() == ModelBackend.EventKind.FAILED).count());
    }

    @Test
    void otherIncompleteReasonsAndSearchlessRequestsFailWithoutRetry() {
        String maxOutput = MAX_MESSAGES_RESPONSE.replace("max_messages", "max_output_tokens");
        CapturingHttpClient client = new CapturingHttpClient(List.of(maxOutput, MAX_MESSAGES_RESPONSE, ANSWER_RESPONSE));
        ChatGptBackend backend = new ChatGptBackend(signedInAuth(), client, Runnable::run);
        List<ModelBackend.Event> otherReason = new ArrayList<>();
        List<ModelBackend.Event> searchless = new ArrayList<>();

        backend.start(searchRequest("other-reason", ModelBackend.SearchMode.AUTOMATIC), otherReason::add);
        backend.start(searchRequest("searchless", ModelBackend.SearchMode.DISABLED), searchless::add);

        assertEquals(2, client.requests.size());
        assertTrue(otherReason.getLast().detail().startsWith("response incomplete: max_output_tokens"), otherReason.getLast().detail());
        assertTrue(searchless.getLast().detail().startsWith("response incomplete: max_messages"), searchless.getLast().detail());
        assertTrue(otherReason.stream().noneMatch(event -> event.kind() == ModelBackend.EventKind.RETRYING));
        assertTrue(searchless.stream().noneMatch(event -> event.kind() == ModelBackend.EventKind.RETRYING));
    }

    @Test
    void cancellingWhenTheRetryIsAnnouncedPreventsTheRetryRequest() {
        CapturingHttpClient client = new CapturingHttpClient(List.of(TOOL_CALL_RESPONSE, MAX_MESSAGES_RESPONSE, ANSWER_RESPONSE));
        List<ModelBackend.Event> events = new ArrayList<>();
        java.util.concurrent.atomic.AtomicReference<ModelBackend.Exchange> holder = new java.util.concurrent.atomic.AtomicReference<>();

        holder.set(new ChatGptBackend(signedInAuth(), client, Runnable::run).start(searchRequest("retry-cancel",
                ModelBackend.SearchMode.AUTOMATIC), event -> {
            events.add(event);
            if (event.kind() == ModelBackend.EventKind.RETRYING) holder.get().cancel();
        }));
        holder.get().submit(new ModelBackend.ToolResult("call_a", "{}", false));

        assertEquals(2, client.requests.size());
        assertTrue(events.stream().noneMatch(event -> event.kind() == ModelBackend.EventKind.COMPLETED
                || event.kind() == ModelBackend.EventKind.FAILED));
    }

    @Test
    void unsupportedProviderOutputFailsInsteadOfEnteringDurableReplay() {
        ChatGptAuth auth = new ChatGptAuth(temporaryDirectory.resolve("credentials.json"), signedOutFlow());
        auth.saveCredential(credential("access-1", "refresh-1", Long.MAX_VALUE));
        CapturingHttpClient client = new CapturingHttpClient(List.of(
                "data: {\"type\":\"response.output_item.done\",\"output_index\":0,\"sequence_number\":1,\"item\":{\"id\":\"ci_1\",\"type\":\"code_interpreter_call\",\"status\":\"completed\"}}\n\n"
                        + "data: {\"type\":\"response.completed\",\"sequence_number\":2,\"response\":{\"status\":\"completed\"}}\n\n"));
        List<ModelBackend.Event> events = new ArrayList<>();

        new ChatGptBackend(auth, client, Runnable::run).start(new ModelBackend.Request("gpt-5-mini", "Answer from evidence.",
                List.of(new ModelBackend.Message(ModelBackend.Role.USER, List.of(new ModelBackend.TextContent("question")))),
                List.of(), ModelBackend.SearchMode.DISABLED, "unsupported-output", 512), events::add);

        assertEquals(1, events.stream().filter(event -> event.kind() == ModelBackend.EventKind.FAILED).count());
        assertTrue(events.stream().filter(event -> event.kind() == ModelBackend.EventKind.FAILED).findFirst().orElseThrow().detail()
                .contains("unsupported output item type"));
        assertFalse(events.stream().anyMatch(event -> event.kind() == ModelBackend.EventKind.COMPLETED));
    }

    @Test
    void streamErrorInsideASuccessfulResponseKeepsTheResponseRequestId() {
        ChatGptAuth auth = new ChatGptAuth(temporaryDirectory.resolve("credentials.json"), signedOutFlow());
        auth.saveCredential(credential("access-1", "refresh-1", Long.MAX_VALUE));
        CapturingHttpClient client = new CapturingHttpClient(List.of(
                "data: {\"type\":\"error\",\"sequence_number\":1,\"error\":{\"code\":\"server_error\",\"message\":\"Stream broke.\"}}\n\n"));
        client.streamRequestId = "req_header_1";
        List<ModelBackend.Event> events = new ArrayList<>();

        new ChatGptBackend(auth, client, Runnable::run).start(new ModelBackend.Request("gpt-5-mini", "Answer from evidence.",
                List.of(new ModelBackend.Message(ModelBackend.Role.USER, List.of(new ModelBackend.TextContent("question")))),
                List.of(), ModelBackend.SearchMode.DISABLED, "stream-error", 512), events::add);

        String detail = events.stream().filter(event -> event.kind() == ModelBackend.EventKind.FAILED).findFirst().orElseThrow().detail();
        assertTrue(detail.startsWith("Response error: server_error: Stream broke."), detail);
        assertTrue(detail.contains("[request req_header_1]"), detail);
        assertFalse(detail.contains("access-1"), detail);
    }

    @Test
    void rejectedHostedSearchFallsBackLocallyWithoutRepeatingTheUnsupportedTool() {
        ChatGptAuth auth = new ChatGptAuth(temporaryDirectory.resolve("credentials.json"), signedOutFlow());
        auth.saveCredential(credential("access-1", "refresh-1", Long.MAX_VALUE));
        CapturingHttpClient client = new CapturingHttpClient(List.of(
                "data: {\"type\":\"response.failed\",\"sequence_number\":1,\"response\":{\"status\":\"failed\",\"error\":{\"code\":\"subscription_sharing_unsupported_capability\",\"message\":\"web search is unavailable\",\"param\":\"tools\"}}}\n\n",
                "data: {\"type\":\"response.completed\",\"sequence_number\":1,\"response\":{\"status\":\"completed\"}}\n\n"));
        ChatGptBackend backend = new ChatGptBackend(auth, client, Runnable::run);
        ModelBackend.Request search = new ModelBackend.Request("gpt-5-mini", "Answer from evidence.",
                List.of(new ModelBackend.Message(ModelBackend.Role.USER, List.of(new ModelBackend.TextContent("Search current information")))),
                List.of(), ModelBackend.SearchMode.EXPLICIT_REQUEST, "search-1", 512);

        backend.start(search, ignored -> { });
        backend.start(new ModelBackend.Request(search.modelId(), search.instructions(), search.input(), search.tools(), search.searchMode(),
                "search-2", search.outputBudget()), ignored -> { });

        JsonObject fallback = JsonParser.parseString(requestBody(client.requests.get(1))).getAsJsonObject();
        assertFalse(fallback.has("tools"));
        assertFalse(fallback.has("tool_choice"));
        assertTrue(fallback.get("instructions").getAsString().contains("previously rejected"));
        assertTrue(fallback.get("instructions").getAsString().contains("current public lookup is unavailable"));
    }

    @Test
    void signedOutAccountCannotRestoreAStaleModelCatalog() {
        ChatGptAuth auth = new ChatGptAuth(temporaryDirectory.resolve("credentials.json"), signedOutFlow());
        auth.saveCredential(credential("access-1", "refresh-1", Long.MAX_VALUE));
        DeferredStringHttpClient client = new DeferredStringHttpClient();
        ChatGptBackend backend = new ChatGptBackend(auth, client, Runnable::run);

        CompletionStage<ModelBackend.Capabilities> loading = backend.capabilities();
        backend.signOut();
        client.response.complete(new StringResponse(client.request, 200,
                "{\"models\":[{\"slug\":\"stale-model\",\"visibility\":\"list\",\"display_name\":\"Stale\"}]}"));

        RuntimeException failure = assertThrows(RuntimeException.class, () -> loading.toCompletableFuture().join());
        assertTrue(failure instanceof java.util.concurrent.CancellationException
                || failure.getCause() instanceof java.util.concurrent.CancellationException);
        assertFalse(backend.selectModel("stale-model"));
    }

    @Test
    void backendExposesConfirmedRemoteRevocationOutcome() {
        Path credentials = temporaryDirectory.resolve("credentials.json");
        RevocationHttpClient revocation = new RevocationHttpClient(200);
        ChatGptAuth auth = new ChatGptAuth(credentials, revocation, ignored -> { });
        auth.saveCredential(credential("access-1", "refresh-1", Long.MAX_VALUE));
        ChatGptBackend backend = new ChatGptBackend(auth, new StringHttpClient(500, "unused"), Runnable::run);

        backend.signOut();

        assertEquals("Signed out and remote revocation was confirmed", backend.signOutStatus().toCompletableFuture().join());
        assertEquals(List.of(ChatGptAuth.ISSUER + "/.well-known/openid-configuration", "https://auth.openai.test/revoke"),
                revocation.requests.stream().map(request -> request.uri().toString()).toList());
        assertFalse(Files.exists(credentials));
    }

    @Test
    void signOutRetainsCredentialUntilTransientRevocationFailureIsRetried() {
        Path credentials = temporaryDirectory.resolve("credentials.json");
        RevocationHttpClient revocation = new RevocationHttpClient(503, 200);
        ChatGptAuth auth = new ChatGptAuth(credentials, revocation, ignored -> { });
        auth.saveCredential(credential("access-1", "refresh-1", Long.MAX_VALUE));

        CompletionStage<String> outcome = auth.signOut();

        assertNull(auth.credential());
        assertTrue(Files.exists(credentials));
        assertEquals("Signed out and remote revocation was confirmed", outcome.toCompletableFuture().join());
        assertEquals(3, revocation.requests.size());
        assertFalse(Files.exists(credentials));
    }

    @Test
    void oldSignOutCannotDeleteCredentialSavedByANewerSession() {
        Path credentials = temporaryDirectory.resolve("credentials.json");
        RevocationHttpClient revocation = new RevocationHttpClient(503, 200);
        ChatGptAuth auth = new ChatGptAuth(credentials, revocation, ignored -> { });
        auth.saveCredential(credential("access-1", "refresh-1", Long.MAX_VALUE));

        CompletionStage<String> oldSignOut = auth.signOut();
        ChatGptAuth.Credential replacement = credential("access-2", "refresh-2", Long.MAX_VALUE);
        auth.saveCredential(replacement);

        assertEquals("Sign-out outcome belongs to an inactive session", oldSignOut.toCompletableFuture().join());
        assertEquals(replacement, auth.credential());
        assertEquals(replacement, new ChatGptAuth(credentials, signedOutFlow()).credential());
    }

    @Test
    void repeatedSignOutDoesNotAbandonTheOriginalRevocation() {
        Path credentials = temporaryDirectory.resolve("credentials.json");
        RevocationHttpClient revocation = new RevocationHttpClient(503, 200);
        ChatGptAuth auth = new ChatGptAuth(credentials, revocation, ignored -> { });
        auth.saveCredential(credential("access-1", "refresh-1", Long.MAX_VALUE));
        ChatGptBackend backend = new ChatGptBackend(auth, new StringHttpClient(500, "unused"), Runnable::run);

        backend.signOut();
        CompletionStage<String> original = backend.signOutStatus();
        backend.signOut();

        assertSame(original, backend.signOutStatus());
        assertEquals("Signed out and remote revocation was confirmed", original.toCompletableFuture().join());
        assertFalse(Files.exists(credentials));
    }

    @Test
    void signOutRetriesTransientDiscoveryFailureBeforeClearingCredential() {
        Path credentials = temporaryDirectory.resolve("credentials.json");
        RevocationHttpClient revocation = new RevocationHttpClient(List.of(503, 200), List.of(200));
        ChatGptAuth auth = new ChatGptAuth(credentials, revocation, ignored -> { });
        auth.saveCredential(credential("access-1", "refresh-1", Long.MAX_VALUE));

        CompletionStage<String> outcome = auth.signOut();

        assertTrue(Files.exists(credentials));
        assertEquals("Signed out and remote revocation was confirmed", outcome.toCompletableFuture().join());
        assertEquals(List.of(ChatGptAuth.ISSUER + "/.well-known/openid-configuration",
                        ChatGptAuth.ISSUER + "/.well-known/openid-configuration", "https://auth.openai.test/revoke"),
                revocation.requests.stream().map(request -> request.uri().toString()).toList());
        assertFalse(Files.exists(credentials));
    }

    @Test
    void backendExposesUnconfirmedRemoteRevocationOutcome() {
        Path credentials = temporaryDirectory.resolve("credentials.json");
        RevocationHttpClient revocation = new RevocationHttpClient(503, 503, 503);
        ChatGptAuth auth = new ChatGptAuth(credentials, revocation, ignored -> { });
        auth.saveCredential(credential("access-1", "refresh-1", Long.MAX_VALUE));
        ChatGptBackend backend = new ChatGptBackend(auth, new StringHttpClient(500, "unused"), Runnable::run);

        backend.signOut();

        assertEquals("Signed out locally; remote revocation was not confirmed", backend.signOutStatus().toCompletableFuture().join());
        assertEquals(4, revocation.requests.size());
        assertFalse(Files.exists(credentials));
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
        private final ArrayDeque<Integer> statuses;
        private final String catalog;
        private final List<HttpRequest> requests = new ArrayList<>();
        private String streamRequestId;

        private CapturingHttpClient(List<String> responses) {
            this((String) null, responses);
        }

        private CapturingHttpClient(String catalog, List<String> responses) {
            this.catalog = catalog;
            this.responses = new ArrayDeque<>(responses);
            this.statuses = new ArrayDeque<>();
            responses.forEach(ignored -> statuses.add(200));
        }

        private CapturingHttpClient(List<Integer> statuses, List<String> responses) {
            this.catalog = null;
            this.responses = new ArrayDeque<>(responses);
            this.statuses = new ArrayDeque<>(statuses);
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
            if (catalog != null && request.uri().getPath().endsWith("/models")) {
                return CompletableFuture.completedFuture((HttpResponse<T>) new StringResponse(request, 200, catalog));
            }
            String body = responses.removeFirst();
            return CompletableFuture.completedFuture((HttpResponse<T>) new InputStreamResponse(request, body, streamRequestId,
                    statuses.removeFirst()));
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

    private static final class DeferredStringHttpClient extends HttpClient {
        private final CompletableFuture<HttpResponse<String>> response = new CompletableFuture<>();
        private HttpRequest request;

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
            this.request = request;
            return (CompletableFuture<HttpResponse<T>>) (CompletableFuture<?>) response;
        }
        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler, HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            return sendAsync(request, responseBodyHandler);
        }
    }

    private static final class RevocationHttpClient extends HttpClient {
        private final ArrayDeque<Integer> discoveryStatuses;
        private final ArrayDeque<Integer> revocationStatuses;
        private final List<HttpRequest> requests = new ArrayList<>();

        private RevocationHttpClient(Integer... revocationStatuses) {
            this(List.of(200), List.of(revocationStatuses));
        }

        private RevocationHttpClient(List<Integer> discoveryStatuses, List<Integer> revocationStatuses) {
            this.discoveryStatuses = new ArrayDeque<>(discoveryStatuses);
            this.revocationStatuses = new ArrayDeque<>(revocationStatuses);
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
            return CompletableFuture.completedFuture((HttpResponse<T>) new StringResponse(request,
                    request.uri().toString().endsWith("/.well-known/openid-configuration")
                            ? discoveryStatuses.removeFirst() : revocationStatuses.removeFirst(),
                    request.uri().toString().endsWith("/.well-known/openid-configuration")
                            ? "{\"revocation_endpoint\":\"https://auth.openai.test/revoke\"}" : "{}"));
        }
        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler, HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            return sendAsync(request, responseBodyHandler);
        }
    }

    private record InputStreamResponse(HttpRequest request, String content, String requestId, int status) implements HttpResponse<ByteArrayInputStream> {
        @Override public int statusCode() { return status; }
        @Override public Optional<HttpResponse<ByteArrayInputStream>> previousResponse() { return Optional.empty(); }
        @Override public HttpHeaders headers() {
            return HttpHeaders.of(requestId == null ? Map.of() : Map.of("x-request-id", List.of(requestId)), (name, value) -> true);
        }
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
