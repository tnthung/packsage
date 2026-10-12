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

    sealed interface InputItem permits Message, EncryptedReasoning, ToolCall, ToolResult, OpaqueHostedOutput {
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

    record OpaqueHostedOutput(String type, String serializedContent) implements InputItem {
        public OpaqueHostedOutput {
            Objects.requireNonNull(type);
            Objects.requireNonNull(serializedContent);
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

    record ToolCall(String callId, String name, String serializedArguments) implements InputItem {
        public ToolCall {
            Objects.requireNonNull(callId);
            Objects.requireNonNull(name);
            Objects.requireNonNull(serializedArguments);
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
