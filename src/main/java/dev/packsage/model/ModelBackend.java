package dev.packsage.model;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

public interface ModelBackend {
    enum AuthStatus { UNKNOWN, SIGNED_OUT, SIGNING_IN, SIGNED_IN, EXPIRED, FAILED }
    enum SearchMode { AUTOMATIC, EXPLICIT_ONLY, EXPLICIT_REQUEST, DISABLED }
    enum EventKind { TEXT_DELTA, CLIENT_TOOL_CALL, HOSTED_SEARCH, COMPLETED, FAILED }
    enum ResponseKind { TEXT, CITATION, IMAGE, SPOILER_RESERVED }
    enum Role { USER, ASSISTANT, SYSTEM }
    enum OutputStatus { IN_PROGRESS, COMPLETED, INCOMPLETE }
    enum OutputPhase { COMMENTARY, FINAL_ANSWER }
    enum WebSearchStatus { IN_PROGRESS, SEARCHING, COMPLETED, FAILED, INCOMPLETE }

    sealed interface InputItem permits Message, EncryptedReasoning, ToolCall, ToolResult, AssistantOutput, WebSearchCall {
    }

    sealed interface Content permits TextContent, ImageContent {
    }

    record Message(Role role, List<Content> content) implements InputItem {
        public Message {
            Objects.requireNonNull(role);
            content = List.copyOf(content);
        }
    }

    record TextContent(String text) implements Content {
        public TextContent {
            Objects.requireNonNull(text);
        }
    }

    record ImageContent(String dataUrl) implements Content {
        public ImageContent {
            Objects.requireNonNull(dataUrl);
        }
    }

    record EncryptedReasoning(String id, String encryptedContent, String serializedSummary) implements InputItem {
        public EncryptedReasoning {
            Objects.requireNonNull(id);
            Objects.requireNonNull(encryptedContent);
            Objects.requireNonNull(serializedSummary);
        }
    }

    sealed interface OutputContent permits OutputText, Refusal {
    }

    record UrlCitation(int startIndex, int endIndex, String title, String url) {
        public UrlCitation {
            if (startIndex < 0 || endIndex < startIndex) throw new IllegalArgumentException("invalid citation range");
            Objects.requireNonNull(title);
            Objects.requireNonNull(url);
        }
    }

    record OutputText(String text, List<UrlCitation> citations) implements OutputContent {
        public OutputText {
            Objects.requireNonNull(text);
            citations = List.copyOf(citations);
        }
    }

    record Refusal(String refusal) implements OutputContent {
        public Refusal {
            Objects.requireNonNull(refusal);
        }
    }

    record AssistantOutput(String id, OutputStatus status, OutputPhase phase,
                           List<OutputContent> content) implements InputItem {
        public AssistantOutput {
            if (id == null || id.isBlank()) throw new IllegalArgumentException("assistant output id is required");
            Objects.requireNonNull(status);
            content = List.copyOf(content);
        }
    }

    sealed interface WebSearchAction permits SearchAction, OpenPageAction, FindInPageAction {
    }

    record WebSearchSource(String url) {
        public WebSearchSource {
            if (url == null || url.isBlank()) throw new IllegalArgumentException("web search source URL is required");
        }
    }

    record SearchAction(String query, List<String> queries, List<WebSearchSource> sources) implements WebSearchAction {
        public SearchAction {
            queries = List.copyOf(queries);
            sources = List.copyOf(sources);
            if (queries.stream().anyMatch(value -> value == null || value.isBlank())) {
                throw new IllegalArgumentException("web search queries cannot contain blanks");
            }
        }
    }

    record OpenPageAction(String url) implements WebSearchAction {
    }

    record FindInPageAction(String url, String pattern) implements WebSearchAction {
        public FindInPageAction {
            Objects.requireNonNull(url);
            Objects.requireNonNull(pattern);
        }
    }

    record WebSearchCall(String id, WebSearchStatus status, WebSearchAction action) implements InputItem {
        public WebSearchCall {
            if (id == null || id.isBlank()) throw new IllegalArgumentException("web search call id is required");
            Objects.requireNonNull(status);
            Objects.requireNonNull(action);
        }
    }

    record Request(String modelId, String instructions, List<InputItem> input, List<ToolDefinition> tools,
                   SearchMode searchMode, String requestId, int outputBudget) {
        public Request {
            Objects.requireNonNull(modelId);
            Objects.requireNonNull(instructions);
            input = List.copyOf(input);
            tools = List.copyOf(tools);
            Objects.requireNonNull(searchMode);
            Objects.requireNonNull(requestId);
            if (outputBudget < 1) throw new IllegalArgumentException("outputBudget must be positive");
        }
    }

    record AuthState(AuthStatus status, String accountLabel, String detail) {
        public AuthState {
            Objects.requireNonNull(status);
        }
    }

    record ModelInfo(String id, String displayName, Boolean imageInput, Boolean hostedSearch, Integer contextTokens, Integer outputTokens) {
        public ModelInfo {
            Objects.requireNonNull(id);
            displayName = displayName == null || displayName.isBlank() ? id : displayName;
        }

        public ModelInfo(String id, Boolean imageInput, Boolean hostedSearch, Integer contextTokens, Integer outputTokens) {
            this(id, id, imageInput, hostedSearch, contextTokens, outputTokens);
        }
    }

    record Capabilities(List<ModelInfo> modelCatalog, String selectedModelId, Boolean imageInput,
                        Boolean hostedSearch, Integer contextTokens, Integer outputTokens) {
        public Capabilities {
            modelCatalog = List.copyOf(modelCatalog);
            if (contextTokens != null && contextTokens < 1) throw new IllegalArgumentException("contextTokens must be positive");
            if (outputTokens != null && outputTokens < 1) throw new IllegalArgumentException("outputTokens must be positive");
            if (selectedModelId != null) {
                ModelInfo selected = modelCatalog.stream().filter(model -> selectedModelId.equals(model.id()))
                        .findFirst().orElseThrow(() -> new IllegalArgumentException("selected model is absent from catalog"));
                if (!Objects.equals(imageInput, selected.imageInput())
                        || !Objects.equals(hostedSearch, selected.hostedSearch())
                        || !Objects.equals(contextTokens, selected.contextTokens())
                        || !Objects.equals(outputTokens, selected.outputTokens())) {
                    throw new IllegalArgumentException("selected model capabilities do not match catalog");
                }
            }
        }

        public Capabilities(Boolean imageInput, Boolean hostedSearch, Integer contextTokens, Integer outputTokens) {
            this(List.of(), null, imageInput, hostedSearch, contextTokens, outputTokens);
        }
    }

    record ToolDefinition(String name, String description, String serializedSchema) {
        public ToolDefinition {
            Objects.requireNonNull(name);
            Objects.requireNonNull(description);
            Objects.requireNonNull(serializedSchema);
        }
    }

    record ToolCall(String callId, String namespace, String name, String serializedArguments) implements InputItem {
        public ToolCall {
            Objects.requireNonNull(callId);
            Objects.requireNonNull(name);
            Objects.requireNonNull(serializedArguments);
        }

        public ToolCall(String callId, String name, String serializedArguments) {
            this(callId, null, name, serializedArguments);
        }
    }

    record ToolResult(String callId, String serializedResult, boolean error) implements InputItem {
        public ToolResult {
            Objects.requireNonNull(callId);
            Objects.requireNonNull(serializedResult);
        }
    }

    record ResponseBlock(ResponseKind kind, String serializedContent, String sourceUrl) {
        public ResponseBlock {
            Objects.requireNonNull(kind);
            Objects.requireNonNull(serializedContent);
        }
    }

    record Event(EventKind kind, String text, ToolCall toolCall, List<ResponseBlock> blocks,
                 List<InputItem> replayItems, String detail) {
        public Event {
            Objects.requireNonNull(kind);
            blocks = List.copyOf(blocks);
            replayItems = List.copyOf(replayItems);
        }

        public Event(EventKind kind, String text, ToolCall toolCall, List<ResponseBlock> blocks, String detail) {
            this(kind, text, toolCall, blocks, List.of(), detail);
        }
    }

    CompletionStage<AuthState> signIn();

    void cancelSignIn();

    void signOut();

    CompletionStage<Capabilities> capabilities();

    Exchange start(Request request, Consumer<Event> events);

    interface Exchange {
        void submit(ToolResult result);

        void cancel();
    }
}
