package dev.packsage.chat;

import dev.packsage.knowledge.Evidence;
import dev.packsage.model.ModelBackend;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public final class ChatContracts {
    private ChatContracts() {
    }

    public enum SignInState { UNKNOWN, SIGNED_OUT, SIGNING_IN, SIGNED_IN, FAILED }
    public enum OutcomeStatus { ACTIVE, COMPLETED, CANCELLED, FAILED }
    public enum ActivityStatus { RUNNING, COMPLETED, FAILED, CANCELLED }

    public record ActivityStep(String id, String title, String detail, ActivityStatus status, String serializedArguments) {
        public ActivityStep(String id, String title, String detail, ActivityStatus status) {
            this(id, title, detail, status, null);
        }

        public ActivityStep {
            Objects.requireNonNull(id);
            Objects.requireNonNull(title);
            detail = detail == null ? "" : detail;
            Objects.requireNonNull(status);
            if (id.isBlank() || title.isBlank()) throw new IllegalArgumentException("activity identity and title are required");
        }
    }

    public record SteeringMessage(String text, int answerOffset) {
        public SteeringMessage {
            Objects.requireNonNull(text);
            if (text.isBlank() || answerOffset < 0) throw new IllegalArgumentException("steering text and answer offset are required");
        }
    }

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
                             int outputBudget, ModelBackend.ReasoningEffort reasoningEffort, RequestId replacing) {
        public Submission {
            Objects.requireNonNull(requestId);
            Objects.requireNonNull(world);
            Objects.requireNonNull(scope);
            Objects.requireNonNull(question);
            Objects.requireNonNull(historyLimits);
            Objects.requireNonNull(selectedModel);
            Objects.requireNonNull(searchMode);
            Objects.requireNonNull(reasoningEffort);
            if (outputBudget < 1) throw new IllegalArgumentException("outputBudget must be positive");
        }

        public Submission(RequestId requestId, WorldIdentity world, Evidence.Scope scope, String question,
                          boolean includeScreenshot, HistoryLimits historyLimits,
                          ModelBackend.ModelInfo selectedModel, ModelBackend.SearchMode searchMode,
                          int outputBudget, ModelBackend.ReasoningEffort reasoningEffort) {
            this(requestId, world, scope, question, includeScreenshot, historyLimits, selectedModel, searchMode,
                    outputBudget, reasoningEffort, null);
        }

        public Submission(RequestId requestId, WorldIdentity world, Evidence.Scope scope, String question,
                          boolean includeScreenshot, HistoryLimits historyLimits,
                          ModelBackend.ModelInfo selectedModel, ModelBackend.SearchMode searchMode,
                          int outputBudget) {
            this(requestId, world, scope, question, includeScreenshot, historyLimits, selectedModel, searchMode,
                    outputBudget, ModelBackend.ReasoningEffort.DEFAULT);
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
                       Context context, List<Evidence.Source> sources, List<Evidence.NavigationTarget> navigationTargets,
                       Outcome outcome, int tokenCount, Instant sentAt, List<ActivityStep> activities,
                       List<SteeringMessage> steeringMessages) {
        public Turn {
            Objects.requireNonNull(requestId);
            Objects.requireNonNull(question);
            Objects.requireNonNull(answer);
            replayItems = List.copyOf(replayItems);
            Objects.requireNonNull(context);
            sources = List.copyOf(sources);
            navigationTargets = List.copyOf(navigationTargets);
            activities = List.copyOf(activities);
            steeringMessages = List.copyOf(steeringMessages);
            int previousOffset = -1;
            for (SteeringMessage message : steeringMessages) {
                if (message.answerOffset() < previousOffset || message.answerOffset() > answer.length()) {
                    throw new IllegalArgumentException("steering offsets must be ordered within the answer");
                }
                previousOffset = message.answerOffset();
            }
            for (Evidence.NavigationTarget target : navigationTargets) {
                if (!context.scope().equals(target.scope()) || sources.stream().noneMatch(source ->
                        source.scope().equals(target.scope()) && source.providerId().equals(target.providerId())
                                && source.sourceHandle().equals(target.sourceHandle()))) {
                    throw new IllegalArgumentException("turn navigation target is not bound to a source");
                }
            }
            Objects.requireNonNull(outcome);
            if (tokenCount < 0) throw new IllegalArgumentException("tokenCount cannot be negative");
        }

        public Turn(RequestId requestId, String question, String answer, List<ModelBackend.InputItem> replayItems,
                    Context context, List<Evidence.Source> sources, List<Evidence.NavigationTarget> navigationTargets,
                    Outcome outcome, int tokenCount, Instant sentAt, List<ActivityStep> activities) {
            this(requestId, question, answer, replayItems, context, sources, navigationTargets, outcome, tokenCount,
                    sentAt, activities, List.of());
        }

        public Turn(RequestId requestId, String question, String answer, List<ModelBackend.InputItem> replayItems,
                    Context context, List<Evidence.Source> sources, List<Evidence.NavigationTarget> navigationTargets,
                    Outcome outcome, int tokenCount, Instant sentAt) {
            this(requestId, question, answer, replayItems, context, sources, navigationTargets, outcome, tokenCount, sentAt, List.of());
        }

        public Turn(RequestId requestId, String question, String answer, List<ModelBackend.InputItem> replayItems,
                    Context context, List<Evidence.Source> sources, Outcome outcome, int tokenCount) {
            this(requestId, question, answer, replayItems, context, sources, List.of(), outcome, tokenCount, null);
        }

        public Turn(RequestId requestId, String question, String answer, List<ModelBackend.InputItem> replayItems,
                    Context context, List<Evidence.Source> sources, List<Evidence.NavigationTarget> navigationTargets,
                    Outcome outcome, int tokenCount) {
            this(requestId, question, answer, replayItems, context, sources, navigationTargets, outcome, tokenCount, null);
        }
    }

    public record SessionSummary(WorldIdentity world, String id, String title, String lastMessage, Instant updatedAt) {
        public SessionSummary {
            Objects.requireNonNull(world);
            Objects.requireNonNull(id);
            Objects.requireNonNull(title);
            Objects.requireNonNull(lastMessage);
            Objects.requireNonNull(updatedAt);
            if (id.isBlank()) throw new IllegalArgumentException("session id cannot be blank");
        }
    }

    public record AccountInfo(ModelBackend.ProviderInfo provider, String hint) {
        public AccountInfo {
            Objects.requireNonNull(provider);
            hint = hint == null ? "" : hint;
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
            }
        }

        public HistorySelection(List<Turn> turns, int tokenCount) {
            this(turns, tokenCount, List.of());
        }
    }

    public record TokenCounts(int user, int assistant) {
        public TokenCounts {
            if (user < 0 || assistant < 0) throw new IllegalArgumentException("token counts cannot be negative");
        }

        public int total() { return user + assistant; }
    }

    public record HistoryPreview(HistorySelection selection, int configuredTokens, int effectiveTokens,
                                 Integer modelTokens, int configuredTurns) {
        public HistoryPreview {
            Objects.requireNonNull(selection);
            if (configuredTokens < 1 || effectiveTokens < 1 || configuredTurns < 1
                    || modelTokens != null && modelTokens < 1) throw new IllegalArgumentException("history preview limits must be positive");
            if (effectiveTokens > configuredTokens || modelTokens != null && effectiveTokens > modelTokens) {
                throw new IllegalArgumentException("effective history limit exceeds an input limit");
            }
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
                       List<String> errors, String streamingAnswer, String activity, WorldIdentity activeWorld, boolean readOnly,
                       String activeQuestion, Instant activeSentAt, List<ActivityStep> activities, String activeRequestId,
                       List<SteeringMessage> activeSteeringMessages) {
        public View {
            Objects.requireNonNull(signIn);
            Objects.requireNonNull(conversation);
            Objects.requireNonNull(context);
            sources = List.copyOf(sources);
            navigationTargets = List.copyOf(navigationTargets);
            errors = List.copyOf(errors);
            Objects.requireNonNull(streamingAnswer);
            Objects.requireNonNull(activity);
            Objects.requireNonNull(activeWorld);
            Objects.requireNonNull(activeQuestion);
            activities = List.copyOf(activities);
            activeSteeringMessages = List.copyOf(activeSteeringMessages);
            if (readOnly && active) throw new IllegalArgumentException("a read-only history view cannot have an active request");
        }

        public View(boolean active, SignInState signIn, boolean paused, Conversation conversation,
                    Context context, List<Evidence.Source> sources, List<Evidence.NavigationTarget> navigationTargets,
                    List<String> errors, String streamingAnswer, String activity, WorldIdentity activeWorld, boolean readOnly,
                    String activeQuestion, Instant activeSentAt, List<ActivityStep> activities, String activeRequestId) {
            this(active, signIn, paused, conversation, context, sources, navigationTargets, errors, streamingAnswer,
                    activity, activeWorld, readOnly, activeQuestion, activeSentAt, activities, activeRequestId, List.of());
        }

        public View(boolean active, SignInState signIn, boolean paused, Conversation conversation,
                    Context context, List<Evidence.Source> sources, List<Evidence.NavigationTarget> navigationTargets,
                    List<String> errors, String streamingAnswer, String activity, WorldIdentity activeWorld, boolean readOnly,
                    String activeQuestion, Instant activeSentAt, List<ActivityStep> activities) {
            this(active, signIn, paused, conversation, context, sources, navigationTargets, errors, streamingAnswer,
                    activity, activeWorld, readOnly, activeQuestion, activeSentAt, activities, null);
        }

        public View(boolean active, SignInState signIn, boolean paused, Conversation conversation,
                    Context context, List<Evidence.Source> sources, List<Evidence.NavigationTarget> navigationTargets,
                    List<String> errors, String streamingAnswer, String activity, WorldIdentity activeWorld, boolean readOnly,
                    String activeQuestion, Instant activeSentAt) {
            this(active, signIn, paused, conversation, context, sources, navigationTargets, errors, streamingAnswer,
                    activity, activeWorld, readOnly, activeQuestion, activeSentAt, List.of(), null);
        }

        public View(boolean active, SignInState signIn, boolean paused, Conversation conversation,
                    Context context, List<Evidence.Source> sources, List<Evidence.NavigationTarget> navigationTargets,
                    List<String> errors, String streamingAnswer, String activity, WorldIdentity activeWorld, boolean readOnly) {
            this(active, signIn, paused, conversation, context, sources, navigationTargets, errors, streamingAnswer,
                    activity, activeWorld, readOnly, "", null);
        }

        public View(boolean active, SignInState signIn, boolean paused, Conversation conversation,
                    Context context, List<Evidence.Source> sources, List<Evidence.NavigationTarget> navigationTargets,
                    List<String> errors, String streamingAnswer, String activity, WorldIdentity activeWorld, boolean readOnly,
                    String activeQuestion) {
            this(active, signIn, paused, conversation, context, sources, navigationTargets, errors, streamingAnswer,
                    activity, activeWorld, readOnly, activeQuestion, null);
        }

        public View(boolean active, SignInState signIn, boolean paused, Conversation conversation,
                    Context context, List<Evidence.Source> sources, List<Evidence.NavigationTarget> navigationTargets,
                    List<String> errors, String streamingAnswer, String activity) {
            this(active, signIn, paused, conversation, context, sources, navigationTargets, errors, streamingAnswer,
                    activity, conversation.world(), false);
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
