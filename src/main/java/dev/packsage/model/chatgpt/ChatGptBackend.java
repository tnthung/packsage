package dev.packsage.model.chatgpt;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.packsage.PackSageLog;
import dev.packsage.model.ModelBackend;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public final class ChatGptBackend implements ModelBackend {
    private static final URI MODELS_URI = URI.create("https://api.openai.com/v1/models");
    private static final URI RESPONSES_URI = URI.create("https://api.openai.com/v1/responses");
    private static final int MAX_ERROR_BYTES = 65_536;
    private final HttpClient client;
    private final Executor executor;
    private final ChatGptAuth auth;
    private final Object exchangeLock = new Object();
    private final Map<String, Boolean> verifiedImageInput = new LinkedHashMap<>();
    private final Map<String, Boolean> verifiedHostedSearch = new LinkedHashMap<>();
    private volatile Capabilities capabilities = new Capabilities(List.of(), null, null, null, null, null);
    private volatile CompletionStage<String> signOutStatus = CompletableFuture.completedFuture("Signed out");
    private ExchangeImpl activeExchange;
    private long accountEpoch;

    public ChatGptBackend(ChatGptAuth auth, HttpClient client, Executor executor) {
        this.auth = auth;
        this.client = client;
        this.executor = executor;
    }

    public ChatGptBackend(ChatGptAuth auth) {
        this(auth, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(), Executors.newCachedThreadPool());
    }

    @Override
    public CompletionStage<AuthState> signIn() {
        return auth.signIn().thenApply(state -> {
            if (state.status() == AuthStatus.SIGNED_IN) {
                ExchangeImpl previous;
                synchronized (exchangeLock) {
                    accountEpoch++;
                    verifiedImageInput.clear();
                    verifiedHostedSearch.clear();
                    previous = activeExchange;
                    activeExchange = null;
                    capabilities = new Capabilities(List.of(), null, null, null, null, null);
                }
                if (previous != null) previous.cancel();
            }
            return state;
        });
    }

    @Override
    public CompletionStage<AuthState> restoreSession() {
        ChatGptAuth.Credential saved = auth.credential();
        if (saved == null || !ChatGptAuth.hasDirectScope(saved.grantedScopes())
                || saved.refreshToken() == null || saved.refreshToken().isBlank()) {
            return CompletableFuture.completedFuture(new AuthState(AuthStatus.SIGNED_OUT, null, null));
        }
        return CompletableFuture.completedFuture(new AuthState(AuthStatus.SIGNED_IN, saved.subject(), "Restored saved ChatGPT sign-in"));
    }

    @Override public void cancelSignIn() { auth.cancelSignIn(); }

    @Override
    public void signOut() {
        ExchangeImpl previous;
        CompletableFuture<String> outcome = null;
        synchronized (exchangeLock) {
            accountEpoch++;
            verifiedImageInput.clear();
            verifiedHostedSearch.clear();
            previous = activeExchange;
            activeExchange = null;
            capabilities = new Capabilities(List.of(), null, null, null, null, null);
            if (signOutStatus.toCompletableFuture().isDone() || auth.credential() != null) {
                outcome = new CompletableFuture<>();
                signOutStatus = outcome;
            }
        }
        if (previous != null) previous.cancel();
        if (outcome != null) {
            CompletableFuture<String> result = outcome;
            try {
                auth.signOut().whenComplete((detail, failure) -> {
                    if (failure == null) result.complete(detail);
                    else result.completeExceptionally(failure);
                });
            } catch (RuntimeException failure) {
                result.completeExceptionally(failure);
            }
        }
    }

    public CompletionStage<String> signOutStatus() { return signOutStatus; }

    @Override
    public CompletionStage<Capabilities> capabilities() {
        final long epoch;
        synchronized (exchangeLock) { epoch = accountEpoch; }
        return auth.accessToken().thenCompose(token -> {
            HttpRequest request = HttpRequest.newBuilder(MODELS_URI).timeout(Duration.ofSeconds(20))
                    .header("Authorization", "Bearer " + token).GET().build();
            return client.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).thenApply(response -> {
                if (response.statusCode() / 100 != 2) {
                    String failure = httpFailure(response.statusCode(), response.body(), response.headers().firstValue("x-request-id").orElse(null));
                    if (response.statusCode() == 401 || response.statusCode() == 403) throw new AuthRejectedException(failure);
                    throw new IllegalStateException(failure);
                }
                synchronized (exchangeLock) {
                    if (accountEpoch != epoch) throw new java.util.concurrent.CancellationException("model catalog belongs to an inactive account session");
                    Capabilities found = capabilitiesFromCatalog(response.body(), capabilities.selectedModelId());
                    List<ModelInfo> models = new ArrayList<>();
                    for (ModelInfo model : found.modelCatalog()) {
                        models.add(new ModelInfo(model.id(), model.displayName(), verifiedImageInput.containsKey(model.id())
                                ? verifiedImageInput.get(model.id()) : model.imageInput(), verifiedHostedSearch.containsKey(model.id())
                                ? verifiedHostedSearch.get(model.id()) : model.hostedSearch(), model.contextTokens(), model.outputTokens()));
                    }
                    ModelInfo selected = models.stream().filter(model -> model.id().equals(found.selectedModelId())).findFirst().orElse(null);
                    capabilities = new Capabilities(models, selected == null ? null : selected.id(), selected == null ? null : selected.imageInput(),
                            selected == null ? null : selected.hostedSearch(), selected == null ? null : selected.contextTokens(), selected == null ? null : selected.outputTokens());
                    return capabilities;
                }
            });
        });
    }

    public boolean selectModel(String modelId) {
        synchronized (exchangeLock) {
            Capabilities current = capabilities;
            ModelInfo selected = current.modelCatalog().stream().filter(model -> model.id().equals(modelId)).findFirst().orElse(null);
            if (selected == null) return false;
            capabilities = new Capabilities(current.modelCatalog(), selected.id(), selected.imageInput(), selected.hostedSearch(), selected.contextTokens(), selected.outputTokens());
            return true;
        }
    }

    @Override
    public Exchange start(Request request, Consumer<Event> events) {
        Request effective = request;
        Boolean imageInput;
        Boolean hostedSearch;
        long requestAccountEpoch;
        synchronized (exchangeLock) {
            imageInput = verifiedImageInput.get(request.modelId());
            hostedSearch = verifiedHostedSearch.get(request.modelId());
            requestAccountEpoch = accountEpoch;
        }
        if (Boolean.FALSE.equals(imageInput)) {
            List<InputItem> input = new ArrayList<>();
            for (InputItem item : effective.input()) {
                if (item instanceof Message message) {
                    List<Content> content = message.content().stream().filter(part -> !(part instanceof ImageContent)).toList();
                    if (!content.isEmpty()) input.add(new Message(message.role(), content));
                } else {
                    input.add(item);
                }
            }
            effective = new Request(effective.modelId(), effective.instructions()
                    + " Image input was previously rejected for this account and model. The screenshot was omitted; state this when it affects the answer.",
                    input, effective.tools(), effective.searchMode(), effective.requestId(), effective.outputBudget());
        }
        if (request.searchMode() != SearchMode.DISABLED && Boolean.FALSE.equals(hostedSearch)) {
            effective = new Request(request.modelId(), effective.instructions()
                    + " Hosted web search was previously rejected for this account and model. Answer from local evidence and state that current public lookup is unavailable.",
                    effective.input(), request.tools(), SearchMode.DISABLED, request.requestId(), request.outputBudget());
        }
        ExchangeImpl exchange = new ExchangeImpl(effective, events, requestAccountEpoch);
        ExchangeImpl previous;
        boolean inactive;
        synchronized (exchangeLock) {
            inactive = accountEpoch != requestAccountEpoch;
            previous = activeExchange;
            if (!inactive) activeExchange = exchange;
        }
        if (inactive) {
            exchange.cancel();
            events.accept(new Event(EventKind.FAILED, null, null, List.of(), "request belongs to an inactive account session"));
            return exchange;
        }
        if (previous != null) previous.cancel();
        synchronized (exchangeLock) {
            if (activeExchange != exchange) return exchange;
        }
        exchange.issue(effective.input());
        return exchange;
    }

    private CompletableFuture<HttpResponse<InputStream>> send(Request request, List<InputItem> input) {
        return auth.accessToken().thenCompose(token -> {
            HttpRequest httpRequest = HttpRequest.newBuilder(RESPONSES_URI).timeout(Duration.ofSeconds(60))
                    .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody(request, input), StandardCharsets.UTF_8)).build();
            return client.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
        }).toCompletableFuture();
    }

    private void read(HttpResponse<InputStream> response, ExchangeImpl exchange, long generation, ChatGptStream stream) {
        InputStream input = response.body();
        if (!exchange.attachInput(generation, input)) {
            try { input.close(); } catch (IOException ignored) { }
            return;
        }
        try (input) {
            if (response.statusCode() / 100 != 2) {
                byte[] body = input.readNBytes(MAX_ERROR_BYTES + 1);
                String responseBody = new String(body, 0, Math.min(body.length, MAX_ERROR_BYTES), StandardCharsets.UTF_8);
                if (responseBody.contains("subscription_sharing_unsupported_capability") || responseBody.contains("unsupported_capability")) {
                    boolean searchFailure = exchange.request.searchMode() != SearchMode.DISABLED
                            && (!exchange.usesImage || responseBody.contains("web search") || responseBody.contains("\"param\":\"tools\""));
                    boolean imageFailure = exchange.usesImage && (exchange.request.searchMode() == SearchMode.DISABLED
                            || responseBody.contains("image") || responseBody.contains("\"param\":\"input\""));
                    recordCapabilities(exchange.request.modelId(), imageFailure ? false : null, searchFailure ? false : null, exchange.requestAccountEpoch);
                }
                String detail = httpFailure(response.statusCode(), responseBody,
                        response.headers().firstValue("x-request-id").orElse(null));
                if (body.length > MAX_ERROR_BYTES) detail += " [body truncated]";
                exchange.transportFailure(generation, detail);
                return;
            }
            exchange.recordResponse(generation, response.statusCode(), response.headers().firstValue("x-request-id").orElse(null));
            byte[] buffer = new byte[4096];
            int count;
            while (exchange.isCurrent(generation) && (count = input.read(buffer)) >= 0) {
                if (count > 0) stream.accept(java.util.Arrays.copyOf(buffer, count));
            }
            if (exchange.isCurrent(generation)) stream.complete();
        } catch (IOException error) {
            if (exchange.isCurrent(generation)) exchange.transportFailure(generation, "response stream failed: " + error.getMessage());
        } finally {
            exchange.detachInput(generation, input);
        }
    }

    static String requestBody(Request request) {
        return requestBody(request, request.input());
    }

    private static String requestBody(Request request, List<InputItem> input) {
        JsonObject body = new JsonObject();
        body.addProperty("model", request.modelId());
        String instructions = request.instructions();
        if (request.searchMode() == SearchMode.AUTOMATIC) {
            instructions += " Use web search when installed evidence is insufficient or current public information is needed. Build queries from relevant public names, IDs, and installed versions; avoid private world, chat, and player details.";
        } else if (request.searchMode() == SearchMode.EXPLICIT_ONLY) {
            instructions += " Use web search only when the player explicitly asks for web lookup in the current message. Build queries from relevant public names, IDs, and installed versions; avoid private world, chat, and player details.";
        } else if (request.searchMode() == SearchMode.EXPLICIT_REQUEST) {
            instructions += " The player explicitly requested web lookup for this message. Search using relevant public names, IDs, and installed versions; avoid private world, chat, and player details.";
        } else {
            instructions += " Hosted web search is unavailable for this request. Use local evidence and state that current public lookup was unavailable when it affects the answer.";
        }
        body.addProperty("instructions", instructions);
        JsonArray serializedInput = new JsonArray();
        for (InputItem item : input) serializedInput.add(serializeInput(item));
        body.add("input", serializedInput);
        body.addProperty("store", false);
        body.addProperty("stream", true);
        JsonArray tools = new JsonArray();
        if (!request.tools().isEmpty()) {
            JsonObject namespace = new JsonObject();
            namespace.addProperty("type", "namespace");
            namespace.addProperty("name", "packsage");
            namespace.addProperty("description", "Read-only tools for bounded PackSage installed evidence and gameplay context.");
            JsonArray functions = new JsonArray();
            for (ToolDefinition definition : request.tools()) {
                JsonObject function = new JsonObject();
                function.addProperty("type", "function");
                function.addProperty("name", definition.name());
                function.addProperty("description", definition.description());
                JsonElement parameters = JsonParser.parseString(definition.serializedSchema());
                if (!parameters.isJsonObject()) throw new IllegalArgumentException("tool parameters must be a JSON object");
                function.add("parameters", parameters);
                functions.add(function);
            }
            namespace.add("tools", functions);
            tools.add(namespace);
        }
        if (request.searchMode() != SearchMode.DISABLED) {
            JsonObject search = new JsonObject();
            search.addProperty("type", "web_search");
            tools.add(search);
        }
        if (!tools.isEmpty()) body.add("tools", tools);
        if (request.searchMode() == SearchMode.EXPLICIT_REQUEST) {
            JsonObject choice = new JsonObject();
            choice.addProperty("type", "allowed_tools");
            choice.addProperty("mode", "required");
            JsonArray allowed = new JsonArray();
            JsonObject search = new JsonObject();
            search.addProperty("type", "web_search");
            allowed.add(search);
            choice.add("tools", allowed);
            body.add("tool_choice", choice);
        }
        return body.toString();
    }

    private static JsonObject serializeInput(InputItem item) {
        JsonObject value = new JsonObject();
        if (item instanceof Message message) {
            if (message.role() == Role.SYSTEM) throw new IllegalArgumentException("system input messages are unsupported on the direct ChatGPT route; use instructions");
            value.addProperty("type", "message");
            value.addProperty("role", message.role() == Role.USER ? "user" : "assistant");
            JsonArray content = new JsonArray();
            for (Content part : message.content()) {
                JsonObject serialized = new JsonObject();
                if (part instanceof TextContent text) {
                    serialized.addProperty("type", "input_text");
                    serialized.addProperty("text", text.text());
                } else if (part instanceof ImageContent image) {
                    if (!image.dataUrl().startsWith("data:image/") || !image.dataUrl().contains(";base64,")) {
                        throw new IllegalArgumentException("image input must be a base64 image data URL");
                    }
                    try {
                        java.util.Base64.getDecoder().decode(image.dataUrl().substring(image.dataUrl().indexOf(";base64,") + 8));
                    } catch (IllegalArgumentException error) {
                        throw new IllegalArgumentException("image input contains invalid base64 data", error);
                    }
                    serialized.addProperty("type", "input_image");
                    serialized.addProperty("image_url", image.dataUrl());
                    serialized.addProperty("detail", "auto");
                }
                content.add(serialized);
            }
            value.add("content", content);
        } else if (item instanceof EncryptedReasoning reasoning) {
            value.addProperty("type", "reasoning");
            value.addProperty("id", reasoning.id());
            value.addProperty("encrypted_content", reasoning.encryptedContent());
            JsonElement summary = JsonParser.parseString(reasoning.serializedSummary());
            if (!summary.isJsonArray()) throw new IllegalArgumentException("reasoning summary must be a JSON array");
            value.add("summary", summary);
        } else if (item instanceof ToolCall call) {
            value.addProperty("type", "function_call");
            value.addProperty("call_id", call.callId());
            if (call.namespace() != null) value.addProperty("namespace", call.namespace());
            value.addProperty("name", call.name());
            value.addProperty("arguments", call.serializedArguments());
        } else if (item instanceof ToolResult result) {
            value.addProperty("type", "function_call_output");
            value.addProperty("call_id", result.callId());
            value.addProperty("output", result.serializedResult());
        } else if (item instanceof AssistantOutput output) {
            value.addProperty("id", output.id());
            value.addProperty("type", "message");
            value.addProperty("status", output.status().name().toLowerCase(Locale.ROOT));
            value.addProperty("role", "assistant");
            if (output.phase() != null) value.addProperty("phase", output.phase().name().toLowerCase(Locale.ROOT));
            JsonArray content = new JsonArray();
            for (OutputContent part : output.content()) {
                JsonObject serialized = new JsonObject();
                if (part instanceof OutputText text) {
                    serialized.addProperty("type", "output_text");
                    serialized.addProperty("text", text.text());
                    JsonArray annotations = new JsonArray();
                    for (UrlCitation citation : text.citations()) {
                        JsonObject annotation = new JsonObject();
                        annotation.addProperty("type", "url_citation");
                        annotation.addProperty("start_index", citation.startIndex());
                        annotation.addProperty("end_index", citation.endIndex());
                        annotation.addProperty("title", citation.title());
                        annotation.addProperty("url", citation.url());
                        annotations.add(annotation);
                    }
                    serialized.add("annotations", annotations);
                } else if (part instanceof Refusal refusal) {
                    serialized.addProperty("type", "refusal");
                    serialized.addProperty("refusal", refusal.refusal());
                }
                content.add(serialized);
            }
            value.add("content", content);
        } else if (item instanceof WebSearchCall call) {
            value.addProperty("id", call.id());
            value.addProperty("type", "web_search_call");
            value.addProperty("status", call.status().name().toLowerCase(Locale.ROOT));
            if (call.action() == null) return value;
            JsonObject action = new JsonObject();
            if (call.action() instanceof SearchAction search) {
                action.addProperty("type", "search");
                if (search.query() != null) action.addProperty("query", search.query());
                if (!search.queries().isEmpty()) {
                    JsonArray queries = new JsonArray();
                    search.queries().forEach(queries::add);
                    action.add("queries", queries);
                }
                if (!search.sources().isEmpty()) {
                    JsonArray sources = new JsonArray();
                    for (WebSearchSource source : search.sources()) {
                        JsonObject serialized = new JsonObject();
                        serialized.addProperty("type", "url");
                        serialized.addProperty("url", source.url());
                        sources.add(serialized);
                    }
                    action.add("sources", sources);
                }
            } else if (call.action() instanceof OpenPageAction open) {
                action.addProperty("type", "open_page");
                if (open.url() != null) action.addProperty("url", open.url());
            } else if (call.action() instanceof FindInPageAction find) {
                action.addProperty("type", "find_in_page");
                action.addProperty("url", find.url());
                action.addProperty("pattern", find.pattern());
            }
            value.add("action", action);
        }
        return value;
    }

    static ModelBackend.Capabilities capabilitiesFromCatalog(String catalog, String selectedModelId) {
        JsonObject root = JsonParser.parseString(catalog).getAsJsonObject();
        if (!root.has("models") || !root.get("models").isJsonArray()) return new Capabilities(List.of(), null, null, null, null, null);
        List<ModelInfo> models = new ArrayList<>();
        for (JsonElement value : root.getAsJsonArray("models")) {
            if (!value.isJsonObject()) continue;
            JsonObject model = value.getAsJsonObject();
            if (!model.has("visibility") || !"list".equals(model.get("visibility").getAsString())
                    || !model.has("slug") || model.get("slug").getAsString().isBlank()) continue;
            String id = model.get("slug").getAsString();
            String displayName = model.has("display_name") && !model.get("display_name").isJsonNull() ? model.get("display_name").getAsString() : id;
            models.add(new ModelInfo(id, displayName, null, null, null, null));
        }
        ModelInfo selected = models.stream().filter(model -> model.id().equals(selectedModelId)).findFirst().orElse(models.isEmpty() ? null : models.getFirst());
        return new Capabilities(models, selected == null ? null : selected.id(), selected == null ? null : selected.imageInput(),
                selected == null ? null : selected.hostedSearch(), selected == null ? null : selected.contextTokens(), selected == null ? null : selected.outputTokens());
    }

    static AuthStatus authStatusForHttpFailure(int status, String code) {
        if (status == 401) return AuthStatus.EXPIRED;
        if (status == 403 || status == 429 || code != null && code.contains("unsupported")) return AuthStatus.FAILED;
        return AuthStatus.UNKNOWN;
    }

    private void recordCapabilities(String modelId, Boolean imageInput, Boolean hostedSearch, long epoch) {
        if (imageInput == null && hostedSearch == null) return;
        synchronized (exchangeLock) {
            if (accountEpoch != epoch) return;
            if (imageInput != null) verifiedImageInput.put(modelId, imageInput);
            if (hostedSearch != null) verifiedHostedSearch.put(modelId, hostedSearch);
            Capabilities current = capabilities;
            List<ModelInfo> models = new ArrayList<>();
            for (ModelInfo model : current.modelCatalog()) {
                models.add(model.id().equals(modelId)
                        ? new ModelInfo(model.id(), model.displayName(), imageInput == null ? model.imageInput() : imageInput,
                        hostedSearch == null ? model.hostedSearch() : hostedSearch, model.contextTokens(), model.outputTokens()) : model);
            }
            ModelInfo selected = models.stream().filter(model -> model.id().equals(current.selectedModelId())).findFirst().orElse(null);
            capabilities = new Capabilities(models, selected == null ? null : selected.id(), selected == null ? null : selected.imageInput(),
                    selected == null ? null : selected.hostedSearch(), selected == null ? null : selected.contextTokens(), selected == null ? null : selected.outputTokens());
        }
    }

    private static String httpFailure(int status, String body, String headerRequestId) {
        String code = null;
        String message = null;
        String param = null;
        String requestId = headerRequestId;
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            JsonObject error = root.has("error") && root.get("error").isJsonObject() ? root.getAsJsonObject("error") : root;
            if (error.has("code") && !error.get("code").isJsonNull()) code = error.get("code").getAsString();
            if (error.has("message") && !error.get("message").isJsonNull()) message = error.get("message").getAsString();
            if (error.has("param") && !error.get("param").isJsonNull()) param = error.get("param").getAsString();
            if (requestId == null && root.has("request_id") && !root.get("request_id").isJsonNull()) requestId = root.get("request_id").getAsString();
        } catch (RuntimeException ignored) { }
        String detail = "HTTP " + status;
        if (code != null) detail += ": " + code;
        if (message != null) detail += " - " + message;
        if (param != null) detail += " (param " + param + ")";
        if (requestId != null) detail += " [request " + requestId + "]";
        return detail;
    }

    private final class ExchangeImpl implements Exchange {
        private final Request request;
        private final Consumer<Event> events;
        private final Object lock = new Object();
        private final long requestAccountEpoch;
        private final boolean usesImage;
        private final Set<String> staleCallIds = new LinkedHashSet<>();
        private long generation;
        private boolean cancelled;
        private boolean terminal;
        private boolean responseFinished;
        private boolean continuationStarted;
        private List<InputItem> currentInput = List.of();
        private final List<InputItem> durableReplay = new ArrayList<>();
        private List<JsonObject> outputItems = new ArrayList<>();
        private LinkedHashMap<String, ToolCall> calls = new LinkedHashMap<>();
        private Map<String, ToolResult> results = new LinkedHashMap<>();
        private CompletableFuture<HttpResponse<InputStream>> responseFuture;
        private InputStream responseInput;
        private ChatGptStream stream;
        private int responseStatus;
        private String responseRequestId;

        private ExchangeImpl(Request request, Consumer<Event> events, long requestAccountEpoch) {
            this.request = request;
            this.events = events;
            this.requestAccountEpoch = requestAccountEpoch;
            usesImage = request.input().stream().anyMatch(item -> item instanceof Message message
                    && message.content().stream().anyMatch(content -> content instanceof ImageContent));
        }

        private void issue(List<InputItem> input) {
            final long nextGeneration;
            final ChatGptStream nextStream;
            synchronized (lock) {
                if (cancelled || terminal) return;
                generation++;
                nextGeneration = generation;
                currentInput = List.copyOf(input);
                responseFinished = false;
                continuationStarted = false;
                outputItems = new ArrayList<>();
                calls = new LinkedHashMap<>();
                results = new LinkedHashMap<>();
                responseStatus = 0;
                responseRequestId = null;
                nextStream =new ChatGptStream(event -> handleEvent(nextGeneration, event), item -> captureOutput(nextGeneration, item));
                stream = nextStream;
                responseFuture = send(request, currentInput);
            }
            responseFuture.whenCompleteAsync((response, failure) -> {
                if (failure != null) {
                    Throwable cause = failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
                    transportFailure(nextGeneration, "request failed: " + cause.getMessage());
                } else {
                    read(response, this, nextGeneration, nextStream);
                }
            }, executor);
        }

        private void captureOutput(long eventGeneration, JsonObject item) {
            synchronized (lock) {
                if (isCurrentLocked(eventGeneration)) outputItems.add(item.deepCopy());
            }
        }

        private void handleEvent(long eventGeneration, Event event) {
            List<InputItem> continuation = null;
            boolean publish = false;
            Boolean imageCapability = null;
            Boolean searchCapability = null;
            Event publishedEvent = event;
            synchronized (lock) {
                if (!isCurrentLocked(eventGeneration)) return;
                if (event.kind() == EventKind.CLIENT_TOOL_CALL) {
                    ToolCall call = event.toolCall();
                    if (calls.putIfAbsent(call.callId(), call) != null) {
                        failLocked("duplicate function call id: " + call.callId());
                        return;
                    }
                    publish = true;
                } else if (event.kind() == EventKind.COMPLETED) {
                    responseFinished = true;
                    try {
                        List<InputItem> stepReplay = projectOutputLocked();
                        durableReplay.addAll(stepReplay);
                        if (usesImage) imageCapability = true;
                        if (stepReplay.stream().anyMatch(item -> item instanceof WebSearchCall)) searchCapability = true;
                        if (calls.isEmpty()) {
                            terminal = true;
                            publish = true;
                            publishedEvent = new Event(EventKind.COMPLETED, null, null, List.of(), durableReplay, null);
                        } else if (results.size() == calls.size()) {
                            continuation = continuationInputLocked(stepReplay);
                        }
                    } catch (RuntimeException error) {
                        failLocked("response output cannot be replayed: " + error.getMessage());
                        publish = true;
                        publishedEvent = new Event(EventKind.FAILED, null, null, List.of(), "response output cannot be replayed: " + error.getMessage());
                    }
                } else if (event.kind() == EventKind.FAILED) {
                    String detail = event.detail() == null ? "response failed without detail" : event.detail();
                    if (responseRequestId != null && !detail.contains("[request ")) detail += " [request " + responseRequestId + "]";
                    publishedEvent = new Event(EventKind.FAILED, null, null, List.of(), detail);
                    PackSageLog.LOGGER.warn("PackSage model response failed: HTTP {} request {}: {}", responseStatus,
                            responseRequestId == null ? "unknown" : responseRequestId, detail);
                    if (event.detail() != null
                            && (event.detail().contains("subscription_sharing_unsupported_capability")
                            || event.detail().contains("unsupported_capability"))) {
                        if (request.searchMode() != SearchMode.DISABLED
                                && (!usesImage || event.detail().contains("web search") || event.detail().contains("param tools"))) {
                            searchCapability = false;
                        }
                        if (usesImage && (request.searchMode() == SearchMode.DISABLED
                                || event.detail().contains("image") || event.detail().contains("param input"))) {
                            imageCapability = false;
                        }
                    }
                    terminal = true;
                    publish = true;
                } else {
                    publish = true;
                }
            }
            recordCapabilities(request.modelId(), imageCapability, searchCapability, requestAccountEpoch);
            if (publish) events.accept(publishedEvent);
            if (continuation != null) issue(continuation);
            if (publishedEvent.kind() == EventKind.COMPLETED || publishedEvent.kind() == EventKind.FAILED) clearIfFinished();
        }

        @Override
        public void submit(ToolResult result) {
            List<InputItem> continuation = null;
            String failure = null;
            synchronized (lock) {
                if (cancelled || terminal) return;
                if (staleCallIds.contains(result.callId())) return;
                if (!calls.containsKey(result.callId())) failure = "unknown function call id: " + result.callId();
                else if (results.putIfAbsent(result.callId(), result) != null) failure = "duplicate result for function call: " + result.callId();
                else if (responseFinished && results.size() == calls.size()) {
                    try {
                        continuation = continuationInputLocked(projectOutputLocked());
                    } catch (RuntimeException error) {
                        failure = "response output cannot be replayed: " + error.getMessage();
                    }
                }
                if (failure != null) failLocked(failure);
            }
            if (failure != null) {
                events.accept(new Event(EventKind.FAILED, null, null, List.of(), failure));
                clearIfFinished();
            } else if (continuation != null) {
                issue(continuation);
            }
        }

        private List<InputItem> projectOutputLocked() {
            List<InputItem> replay = new ArrayList<>();
            for (JsonObject item : outputItems) {
                if (!item.has("type")) throw new IllegalArgumentException("output item type is missing");
                String type = item.get("type").getAsString();
                if ("reasoning".equals(type)) {
                    if (!item.has("id") || !item.has("encrypted_content") || item.get("encrypted_content").isJsonNull()
                            || !item.has("summary") || !item.get("summary").isJsonArray()) {
                        throw new IllegalArgumentException("completed reasoning item lacks id, encrypted content, or summary");
                    }
                    replay.add(new EncryptedReasoning(item.get("id").getAsString(), item.get("encrypted_content").getAsString(), item.get("summary").toString()));
                } else if ("function_call".equals(type)) {
                    if (!item.has("call_id") || !item.has("name") || !item.has("arguments")) {
                        throw new IllegalArgumentException("completed function call is incomplete");
                    }
                    replay.add(new ToolCall(item.get("call_id").getAsString(),
                            item.has("namespace") && !item.get("namespace").isJsonNull() ? item.get("namespace").getAsString() : null,
                            item.get("name").getAsString(), item.get("arguments").getAsString()));
                } else if ("message".equals(type)) {
                    if (!item.has("id") || !item.has("status") || !item.has("role") || !"assistant".equals(item.get("role").getAsString())
                            || !item.has("content") || !item.get("content").isJsonArray()) {
                        throw new IllegalArgumentException("completed assistant message is incomplete");
                    }
                    List<OutputContent> content = new ArrayList<>();
                    for (JsonElement partValue : item.getAsJsonArray("content")) {
                        if (!partValue.isJsonObject() || !partValue.getAsJsonObject().has("type")) {
                            throw new IllegalArgumentException("assistant message content is incomplete");
                        }
                        JsonObject part = partValue.getAsJsonObject();
                        if ("output_text".equals(part.get("type").getAsString())) {
                            if (!part.has("text") || !part.has("annotations") || !part.get("annotations").isJsonArray()
                                    || part.has("logprobs") && (!part.get("logprobs").isJsonArray() || !part.getAsJsonArray("logprobs").isEmpty())) {
                                throw new IllegalArgumentException("assistant output text cannot be replayed losslessly");
                            }
                            List<UrlCitation> citations = new ArrayList<>();
                            for (JsonElement annotationValue : part.getAsJsonArray("annotations")) {
                                if (!annotationValue.isJsonObject()) throw new IllegalArgumentException("assistant annotation is incomplete");
                                JsonObject annotation = annotationValue.getAsJsonObject();
                                if (!annotation.has("type") || !"url_citation".equals(annotation.get("type").getAsString())
                                        || !annotation.has("start_index") || !annotation.has("end_index")
                                        || !annotation.has("title") || !annotation.has("url")) {
                                    throw new IllegalArgumentException("unsupported assistant annotation cannot be replayed");
                                }
                                citations.add(new UrlCitation(annotation.get("start_index").getAsInt(), annotation.get("end_index").getAsInt(),
                                        annotation.get("title").getAsString(), annotation.get("url").getAsString()));
                            }
                            content.add(new OutputText(part.get("text").getAsString(), citations));
                        } else if ("refusal".equals(part.get("type").getAsString()) && part.has("refusal")) {
                            content.add(new Refusal(part.get("refusal").getAsString()));
                        } else {
                            throw new IllegalArgumentException("unsupported assistant content cannot be replayed");
                        }
                    }
                    OutputPhase phase = item.has("phase") && !item.get("phase").isJsonNull()
                            ? OutputPhase.valueOf(item.get("phase").getAsString().toUpperCase(Locale.ROOT)) : null;
                    replay.add(new AssistantOutput(item.get("id").getAsString(),
                            OutputStatus.valueOf(item.get("status").getAsString().toUpperCase(Locale.ROOT)), phase, content));
                } else if ("web_search_call".equals(type)) {
                    if (!item.has("id") || !item.has("status")
                            || item.has("action") && !item.get("action").isJsonNull() && !item.get("action").isJsonObject()) {
                        throw new IllegalArgumentException("completed web search call is incomplete");
                    }
                    WebSearchAction projected = null;
                    if (item.has("action") && !item.get("action").isJsonNull()) {
                        JsonObject action = item.getAsJsonObject("action");
                        if (!action.has("type")) throw new IllegalArgumentException("web search action type is missing");
                        if ("search".equals(action.get("type").getAsString())) {
                            List<String> queries = new ArrayList<>();
                            if (action.has("queries") && !action.get("queries").isJsonArray()) {
                                throw new IllegalArgumentException("web search queries are malformed");
                            }
                            if (action.has("queries") && action.get("queries").isJsonArray()) {
                                for (JsonElement query : action.getAsJsonArray("queries")) queries.add(query.getAsString());
                            }
                            List<WebSearchSource> sources = new ArrayList<>();
                            if (action.has("sources") && !action.get("sources").isJsonArray()) {
                                throw new IllegalArgumentException("web search sources are malformed");
                            }
                            if (action.has("sources") && action.get("sources").isJsonArray()) {
                                for (JsonElement sourceValue : action.getAsJsonArray("sources")) {
                                    if (!sourceValue.isJsonObject() || !sourceValue.getAsJsonObject().has("type")
                                            || !"url".equals(sourceValue.getAsJsonObject().get("type").getAsString())
                                            || !sourceValue.getAsJsonObject().has("url")) {
                                        throw new IllegalArgumentException("web search source is incomplete");
                                    }
                                    sources.add(new WebSearchSource(sourceValue.getAsJsonObject().get("url").getAsString()));
                                }
                            }
                            projected = new SearchAction(action.has("query") && !action.get("query").isJsonNull() ? action.get("query").getAsString() : null,
                                    queries, sources);
                        } else if ("open_page".equals(action.get("type").getAsString())) {
                            projected = new OpenPageAction(action.has("url") && !action.get("url").isJsonNull() ? action.get("url").getAsString() : null);
                        } else if ("find_in_page".equals(action.get("type").getAsString()) && action.has("url") && action.has("pattern")) {
                            projected = new FindInPageAction(action.get("url").getAsString(), action.get("pattern").getAsString());
                        } else {
                            throw new IllegalArgumentException("unsupported web search action cannot be replayed");
                        }
                    }
                    replay.add(new WebSearchCall(item.get("id").getAsString(),
                            WebSearchStatus.valueOf(item.get("status").getAsString().toUpperCase(Locale.ROOT)), projected));
                } else {
                    throw new IllegalArgumentException("unsupported output item type: " + type);
                }
            }
            return replay;
        }

        private List<InputItem> continuationInputLocked(List<InputItem> stepReplay) {
            if (continuationStarted) return null;
            continuationStarted = true;
            List<InputItem> continuation = new ArrayList<>(currentInput);
            continuation.addAll(stepReplay);
            for (Map.Entry<String, ToolCall> call : calls.entrySet()) {
                ToolResult result = results.get(call.getKey());
                continuation.add(result);
                durableReplay.add(result);
                staleCallIds.add(call.getKey());
            }
            return List.copyOf(continuation);
        }

        @Override
        public void cancel() {
            CompletableFuture<HttpResponse<InputStream>> future;
            InputStream input;
            ChatGptStream currentStream;
            synchronized (lock) {
                if (cancelled || terminal) return;
                cancelled = true;
                generation++;
                future = responseFuture;
                input = responseInput;
                currentStream = stream;
            }
            if (currentStream != null) currentStream.cancel();
            if (future != null) future.cancel(true);
            if (input != null) try { input.close(); } catch (IOException ignored) { }
            clearIfFinished();
        }

        private boolean attachInput(long eventGeneration, InputStream input) {
            synchronized (lock) {
                if (!isCurrentLocked(eventGeneration)) return false;
                responseInput = input;
                return true;
            }
        }

        private void detachInput(long eventGeneration, InputStream input) {
            synchronized (lock) {
                if (generation == eventGeneration && responseInput == input) responseInput = null;
            }
        }

        private boolean isCurrent(long eventGeneration) {
            synchronized (lock) { return isCurrentLocked(eventGeneration); }
        }

        private boolean isCurrentLocked(long eventGeneration) {
            return !cancelled && !terminal && generation == eventGeneration;
        }

        private void recordResponse(long eventGeneration, int status, String requestId) {
            synchronized (lock) {
                if (!isCurrentLocked(eventGeneration)) return;
                responseStatus = status;
                responseRequestId = requestId;
            }
        }

        private void transportFailure(long eventGeneration, String detail) {
            synchronized (lock) {
                if (!isCurrentLocked(eventGeneration)) return;
                failLocked(detail);
            }
            PackSageLog.LOGGER.warn("PackSage model request failed: {}", detail);
            events.accept(new Event(EventKind.FAILED, null, null, List.of(), detail));
            clearIfFinished();
        }

        private void failLocked(String detail) {
            terminal = true;
            if (stream != null) stream.cancel();
            if (responseFuture != null) responseFuture.cancel(true);
            if (responseInput != null) try { responseInput.close(); } catch (IOException ignored) { }
        }

        private void clearIfFinished() {
            synchronized (exchangeLock) {
                synchronized (lock) {
                    if ((terminal || cancelled) && activeExchange == this) activeExchange = null;
                }
            }
        }
    }
}
