package dev.packsage.chat;

import dev.packsage.knowledge.Evidence;
import dev.packsage.model.ModelBackend;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class ChatContracts {
    private ChatContracts() {
    }

    public enum SignInState { UNKNOWN, SIGNED_OUT, SIGNING_IN, SIGNED_IN, FAILED }
    public enum OutcomeStatus { ACTIVE, COMPLETED, CANCELLED, FAILED }

    public record WorldIdentity(String worldOrServer, String readableLabel) {
        public WorldIdentity {
            Objects.requireNonNull(worldOrServer);
            Objects.requireNonNull(readableLabel);
        }
    }

    public record Screenshot(String assetId, String mediaType, long bytes, Instant capturedAt) {
        public Screenshot {
            Objects.requireNonNull(assetId);
            Objects.requireNonNull(mediaType);
            Objects.requireNonNull(capturedAt);
            if (bytes < 0) throw new IllegalArgumentException("bytes cannot be negative");
        }
    }

    public record Context(String serializedGameplayContext, Screenshot screenshot, Evidence.Scope scope) {
        public Context {
            Objects.requireNonNull(serializedGameplayContext);
            Objects.requireNonNull(scope);
        }
    }

    public record RequestId(String value) {
        public RequestId {
            Objects.requireNonNull(value);
        }
    }

    public record RequestCapture(RequestId requestId, WorldIdentity world, Evidence.Scope scope) {
        public RequestCapture {
            Objects.requireNonNull(requestId);
            Objects.requireNonNull(world);
            Objects.requireNonNull(scope);
        }
    }

    public record Submission(RequestId requestId, WorldIdentity world, Evidence.Scope scope, String question,
                             boolean includeScreenshot, HistoryLimits historyLimits,
                             ModelBackend.ModelInfo selectedModel, ModelBackend.SearchMode searchMode,
                             int outputBudget) {
        public Submission {
            Objects.requireNonNull(requestId);
            Objects.requireNonNull(world);
            Objects.requireNonNull(scope);
            Objects.requireNonNull(question);
            Objects.requireNonNull(historyLimits);
            Objects.requireNonNull(selectedModel);
            Objects.requireNonNull(searchMode);
            if (outputBudget < 1) throw new IllegalArgumentException("outputBudget must be positive");
        }
    }

    public record CapturedContext(Context context, byte[] screenshotBytes) {
        public CapturedContext {
            Objects.requireNonNull(context);
            screenshotBytes = screenshotBytes == null ? new byte[0] : screenshotBytes.clone();
            if (context.screenshot() == null && screenshotBytes.length != 0) {
                throw new IllegalArgumentException("screenshot bytes require screenshot metadata");
            }
            if (context.screenshot() != null && context.screenshot().bytes() != screenshotBytes.length) {
                throw new IllegalArgumentException("screenshot byte count does not match metadata");
            }
        }

        @Override
        public byte[] screenshotBytes() {
            return screenshotBytes.clone();
        }
    }

    public record EvidenceSelection(List<Evidence.Source> sources,
                                    List<Evidence.NavigationTarget> navigationTargets) {
        public EvidenceSelection {
            sources = List.copyOf(sources);
            navigationTargets = List.copyOf(navigationTargets);
            for (Evidence.NavigationTarget target : navigationTargets) {
                if (sources.stream().noneMatch(source -> source.scope().equals(target.scope())
                        && source.providerId().equals(target.providerId())
                        && source.sourceHandle().equals(target.sourceHandle()))) {
                    throw new IllegalArgumentException("navigation target has no matching source");
                }
            }
        }
    }

    public record ToolRequest(Submission submission, ModelBackend.ToolCall call) {
        public ToolRequest {
            Objects.requireNonNull(submission);
            Objects.requireNonNull(call);
        }
    }

    public record ToolResponse(ModelBackend.ToolResult result, EvidenceSelection evidence) {
        public ToolResponse {
            Objects.requireNonNull(result);
            Objects.requireNonNull(evidence);
        }
    }

    public record PreparedRequest(Submission submission, HistorySelection history, CapturedContext capturedContext,
                                  EvidenceSelection evidence) {
        public PreparedRequest {
            Objects.requireNonNull(submission);
            Objects.requireNonNull(history);
            Objects.requireNonNull(capturedContext);
            Objects.requireNonNull(evidence);
            if (!submission.scope().equals(capturedContext.context().scope())) {
                throw new IllegalArgumentException("captured context scope does not match submission");
            }
            if (!submission.includeScreenshot() && capturedContext.context().screenshot() != null) {
                throw new IllegalArgumentException("captured screenshot was not requested");
            }
            if (evidence.sources().stream().anyMatch(source -> !submission.scope().equals(source.scope()))
                    || evidence.navigationTargets().stream().anyMatch(target -> !submission.scope().equals(target.scope()))) {
                throw new IllegalArgumentException("evidence scope does not match submission");
            }
        }
    }

    public record Outcome(OutcomeStatus status, String detail) {
        public Outcome {
            Objects.requireNonNull(status);
        }
    }

    public record Turn(RequestId requestId, String question, String answer, List<ModelBackend.InputItem> replayItems,
                       Context context, List<Evidence.Source> sources, Outcome outcome, int tokenCount) {
        public Turn {
            Objects.requireNonNull(requestId);
            Objects.requireNonNull(question);
            Objects.requireNonNull(answer);
            replayItems = List.copyOf(replayItems);
            Objects.requireNonNull(context);
            sources = List.copyOf(sources);
            Objects.requireNonNull(outcome);
            if (tokenCount < 0) throw new IllegalArgumentException("tokenCount cannot be negative");
        }
    }

    public record HistoryLimits(int completedTurns, int historicalTokens) {
        public HistoryLimits {
            if (completedTurns < 1 || historicalTokens < 1) throw new IllegalArgumentException("history limits must be positive");
        }
    }

    public record HistorySelection(List<Turn> turns, int tokenCount, List<String> errors) {
        public HistorySelection {
            turns = List.copyOf(turns);
            errors = List.copyOf(errors);
            if (tokenCount < 0) throw new IllegalArgumentException("tokenCount cannot be negative");
            for (Turn turn : turns) {
                if (turn.outcome().status() != OutcomeStatus.COMPLETED) {
                    throw new IllegalArgumentException("history can contain only completed turns");
                }
                Set<String> calls = new HashSet<>();
                Set<String> results = new HashSet<>();
                for (ModelBackend.InputItem item : turn.replayItems()) {
                    if (!(item instanceof ModelBackend.EncryptedReasoning
                            || item instanceof ModelBackend.ToolCall
                            || item instanceof ModelBackend.ToolResult
                            || item instanceof ModelBackend.AssistantOutput
                            || item instanceof ModelBackend.WebSearchCall)) {
                        throw new IllegalArgumentException("history contains a non-replay output item");
                    }
                    if (item instanceof ModelBackend.ToolCall call && !calls.add(call.callId())) {
                        throw new IllegalArgumentException("history contains a duplicate tool call");
                    }
                    if (item instanceof ModelBackend.ToolResult result
                            && (!calls.contains(result.callId()) || !results.add(result.callId()))) {
                        throw new IllegalArgumentException("history contains an invalid tool result");
                    }
                }
                if (!calls.equals(results)) throw new IllegalArgumentException("history contains incomplete tool replay");
            }
        }

        public HistorySelection(List<Turn> turns, int tokenCount) {
            this(turns, tokenCount, List.of());
        }
    }

    public record Conversation(String id, WorldIdentity world, List<Turn> turns, Outcome outcome) {
        public Conversation {
            Objects.requireNonNull(id);
            Objects.requireNonNull(world);
            turns = List.copyOf(turns);
            Objects.requireNonNull(outcome);
        }
    }

    public record View(boolean active, SignInState signIn, boolean paused, Conversation conversation,
                       Context context, List<Evidence.Source> sources, List<Evidence.NavigationTarget> navigationTargets,
                       List<String> errors, String streamingAnswer, String activity) {
        public View {
            Objects.requireNonNull(signIn);
            Objects.requireNonNull(conversation);
            Objects.requireNonNull(context);
            sources = List.copyOf(sources);
            navigationTargets = List.copyOf(navigationTargets);
            errors = List.copyOf(errors);
            Objects.requireNonNull(streamingAnswer);
            Objects.requireNonNull(activity);
        }

        public View(boolean active, SignInState signIn, boolean paused, Conversation conversation,
                    Context context, List<Evidence.Source> sources, List<Evidence.NavigationTarget> navigationTargets,
                    List<String> errors, String streamingAnswer) {
            this(active, signIn, paused, conversation, context, sources, navigationTargets, errors, streamingAnswer, "");
        }

        public View(boolean active, SignInState signIn, boolean paused, Conversation conversation,
                    Context context, List<Evidence.Source> sources, List<Evidence.NavigationTarget> navigationTargets,
                    List<String> errors) {
            this(active, signIn, paused, conversation, context, sources, navigationTargets, errors, "");
        }

        public View(boolean active, SignInState signIn, boolean paused, Conversation conversation,
                    Context context, List<Evidence.Source> sources, List<String> errors) {
            this(active, signIn, paused, conversation, context, sources, List.of(), errors, "");
        }
    }
}
