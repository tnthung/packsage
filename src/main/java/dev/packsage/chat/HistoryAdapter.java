package dev.packsage.chat;

import dev.packsage.knowledge.Evidence;
import dev.packsage.knowledge.Toolbox;
import dev.packsage.model.PackSageTools;

import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.UUID;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

final class HistoryAdapter implements Toolbox {
    @Override public Metadata metadata() { return new Metadata("history", List.of(), false, List.of(), java.util.Map.of()); }

    @Override public CompletionStage<Evidence.Result> searchHistory(PackSageTools.ReadRequest request, Context context) {
        return execute(request, context.replacingRequestId() == null ? null : new ChatContracts.RequestId(context.replacingRequestId()));
    }

    @Override public CompletionStage<Evidence.Result> readHistory(PackSageTools.ReadRequest request, Context context) {
        return execute(request, context.replacingRequestId() == null ? null : new ChatContracts.RequestId(context.replacingRequestId()));
    }


    private record Cursor(String text, ChatContracts.RequestId excluded, ConversationStore.HistoryCursor position) { }
    private record Handle(ConversationStore.HistoryLocator locator, ChatContracts.RequestId requestId) { }
    private final ConversationStore store;
    private final ChatContracts.WorldIdentity world;
    private final Supplier<Evidence.Scope> currentScope;
    private final LinkedHashMap<String, Handle> handles = new LinkedHashMap<>();
    private final LinkedHashMap<String, Cursor> cursors = new LinkedHashMap<>();
    private Evidence.Scope cacheScope;

    HistoryAdapter(ConversationStore store, ChatContracts.WorldIdentity world, Supplier<Evidence.Scope> currentScope) {
        this.store = Objects.requireNonNull(store);
        this.world = Objects.requireNonNull(world);
        this.currentScope = Objects.requireNonNull(currentScope);
    }

    CompletionStage<Evidence.Result> execute(PackSageTools.ReadRequest request, ChatContracts.RequestId excluded) {
        synchronized (this) {
            if (!request.scope().equals(currentScope.get())) {
                return CompletableFuture.completedFuture(new Evidence.Result(List.of(), Evidence.Coverage.ERROR,
                        Evidence.Outcome.ERROR, null, "history scope changed; repeat the search"));
            }
            if (!request.scope().equals(cacheScope)) {
                cacheScope = request.scope();
                handles.clear();
                cursors.clear();
            }
        }
        try {
            var arguments = request.arguments().values();
            if (request.tool().equals("search_history")) {
                String text = ((Evidence.JsonString) arguments.get("text")).value();
                int limit = arguments.get("limit") instanceof Evidence.JsonNumber number ? number.value().intValueExact() : 8;
                if (limit < 1 || limit > 16) throw new IllegalArgumentException("history search limit is out of bounds");
                ConversationStore.HistoryCursor position = null;
                synchronized (this) {
                    if (arguments.get("cursor") instanceof Evidence.JsonString token) {
                        var cursor = cursors.get(token.value());
                        if (cursor == null || !cursor.text().equals(text) || !Objects.equals(cursor.excluded(), excluded)) {
                            return CompletableFuture.completedFuture(new Evidence.Result(List.of(), Evidence.Coverage.ERROR,
                                    Evidence.Outcome.ERROR, null, "unknown or changed-query history cursor; repeat the search"));
                        }
                        position = cursor.position();
                    }
                }
                return store.searchHistory(world, text, excluded, limit, position).handle((page, failure) -> {
                    synchronized (this) {
                        if (!request.scope().equals(cacheScope) || !request.scope().equals(currentScope.get())) {
                            return new Evidence.Result(List.of(), Evidence.Coverage.ERROR, Evidence.Outcome.ERROR,
                                    null, "history scope changed; repeat the search");
                        }
                        if (failure != null) {
                            return new Evidence.Result(List.of(), Evidence.Coverage.ERROR, Evidence.Outcome.ERROR,
                                    null, "history search failed or its saved files changed; repeat the search");
                        }
                        var records = new ArrayList<Evidence.Record>();
                        for (var entry : page.entries()) {
                            String handle = UUID.randomUUID().toString();
                            handles.put(handle, new Handle(entry.locator(), entry.requestId()));
                            while (handles.size() > 128) handles.remove(handles.keySet().iterator().next());
                            var steeringMessages = new ArrayList<ChatContracts.SteeringMessage>();
                            int steeringCharacters = 4096;
                            for (var message : entry.steeringMessages()) {
                                if (steeringCharacters == 0) break;
                                int textLength = Math.min(message.text().length(), steeringCharacters);
                                steeringMessages.add(new ChatContracts.SteeringMessage(message.text().substring(0, textLength),
                                        message.answerOffset()));
                                steeringCharacters -= textLength;
                            }
                            boolean steeringTruncated = steeringMessages.size() < entry.steeringMessages().size()
                                    || !steeringMessages.isEmpty() && steeringMessages.getLast().text().length()
                                    < entry.steeringMessages().get(steeringMessages.size() - 1).text().length();
                            records.add(record(request.scope(), handle, entry, excerpt(entry.question(), text, 256),
                                    excerpt(entry.answer(), text, 512), steeringMessages, true,
                                    entry.question().length() > 256 || entry.answer().length() > 512 || steeringTruncated));
                        }
                        String next = null;
                        if (page.nextCursor() != null) {
                            next = UUID.randomUUID().toString();
                            cursors.put(next, new Cursor(text, excluded, page.nextCursor()));
                            while (cursors.size() > 64) cursors.remove(cursors.keySet().iterator().next());
                        }
                        return new Evidence.Result(records, page.coverage(), page.coverage() == Evidence.Coverage.COMPLETE
                                ? Evidence.Outcome.COMPLETE : Evidence.Outcome.PARTIAL, next, page.detail());
                    }
                });
            }
            if (request.tool().equals("read_history")) {
                var requested = ((Evidence.JsonArray) arguments.get("handles")).values();
                int maxChars = arguments.get("maxChars") instanceof Evidence.JsonNumber number ? number.value().intValueExact() : 8000;
                if (requested.isEmpty() || requested.size() > 8 || maxChars < 256 || maxChars > 16_000) {
                    throw new IllegalArgumentException("history read selection is out of bounds");
                }
                var selected = new ArrayList<String>();
                var locators = new ArrayList<ConversationStore.HistoryLocator>();
                synchronized (this) {
                    for (var value : requested) {
                        String reference = ((Evidence.JsonString) value).value();
                        String key = reference.startsWith("history:") ? reference.substring(8) : "";
                        var handle = handles.get(key);
                        if (handle == null || Objects.equals(handle.requestId(), excluded)) {
                            return CompletableFuture.completedFuture(new Evidence.Result(List.of(), Evidence.Coverage.ERROR,
                                    Evidence.Outcome.ERROR, null, "history handle was not discovered or is being replaced; repeat the search"));
                        }
                        selected.add(key);
                        locators.add(handle.locator());
                    }
                }
                var reads = locators.stream().map(locator -> store.readHistory(world, locator).toCompletableFuture()).toList();
                return CompletableFuture.allOf(reads.toArray(CompletableFuture[]::new)).handle((ignored, failure) -> {
                    synchronized (this) {
                        if (!request.scope().equals(cacheScope) || !request.scope().equals(currentScope.get())) {
                            return new Evidence.Result(List.of(), Evidence.Coverage.ERROR, Evidence.Outcome.ERROR,
                                    null, "history scope changed; repeat the search");
                        }
                        if (failure != null) {
                            return new Evidence.Result(List.of(), Evidence.Coverage.ERROR, Evidence.Outcome.ERROR,
                                    null, "history exchange is unavailable, deleted or changed; repeat the search");
                        }
                        var records = new ArrayList<Evidence.Record>();
                        boolean partial = false;
                        for (int index = 0; index < reads.size(); index++) {
                            var entry = reads.get(index).join();
                            int questionChars = Math.min(entry.question().length(), maxChars);
                            int answerChars = Math.min(entry.answer().length(), maxChars - questionChars);
                            int steeringCharacters = maxChars - questionChars - answerChars;
                            var steeringMessages = new ArrayList<ChatContracts.SteeringMessage>();
                            for (var message : entry.steeringMessages()) {
                                if (steeringCharacters == 0) break;
                                int textLength = Math.min(message.text().length(), steeringCharacters);
                                steeringMessages.add(new ChatContracts.SteeringMessage(message.text().substring(0, textLength),
                                        Math.min(message.answerOffset(), answerChars)));
                                steeringCharacters -= textLength;
                            }
                            boolean truncated = questionChars < entry.question().length() || answerChars < entry.answer().length()
                                    || steeringMessages.size() < entry.steeringMessages().size()
                                    || !steeringMessages.isEmpty() && steeringMessages.getLast().text().length()
                                    < entry.steeringMessages().get(steeringMessages.size() - 1).text().length();
                            partial |= truncated;
                            records.add(record(request.scope(), selected.get(index), entry,
                                    entry.question().substring(0, questionChars), entry.answer().substring(0, answerChars),
                                    steeringMessages, false, truncated));
                        }
                        return new Evidence.Result(records, partial ? Evidence.Coverage.PARTIAL : Evidence.Coverage.COMPLETE,
                                partial ? Evidence.Outcome.PARTIAL : Evidence.Outcome.COMPLETE, null,
                                partial ? "saved exchange text was truncated to maxChars" : null);
                    }
                });
            }
            throw new IllegalArgumentException("unknown history tool");
        } catch (RuntimeException failure) {
            return CompletableFuture.completedFuture(new Evidence.Result(List.of(), Evidence.Coverage.ERROR,
                    Evidence.Outcome.ERROR, null, "invalid history arguments"));
        }
    }

    private Evidence.Record record(Evidence.Scope scope, String handle, ConversationStore.HistoryEntry entry,
                                   String question, String answer, List<ChatContracts.SteeringMessage> steeringMessages,
                                   boolean excerpt, boolean truncated) {
        var payload = new LinkedHashMap<String, Evidence.JsonValue>();
        payload.put("world", new Evidence.JsonString(world.worldOrServer()));
        payload.put("sessionId", new Evidence.JsonString(entry.sessionId()));
        payload.put("requestId", new Evidence.JsonString(entry.requestId().value()));
        payload.put("sentAt", entry.sentAt() == null ? new Evidence.JsonNull() : new Evidence.JsonString(entry.sentAt().toString()));
        payload.put("question", new Evidence.JsonString(question));
        payload.put("answer", new Evidence.JsonString(answer));
        payload.put("steeringMessages", new Evidence.JsonArray(steeringMessages.stream().map(message ->
                (Evidence.JsonValue) new Evidence.JsonObject(Map.of("text", new Evidence.JsonString(message.text()),
                        "answerOffset", new Evidence.JsonNumber(java.math.BigDecimal.valueOf(message.answerOffset()))))).toList()));
        payload.put("historical", new Evidence.JsonBoolean(true));
        payload.put("excerpt", new Evidence.JsonBoolean(excerpt));
        payload.put("truncated", new Evidence.JsonBoolean(truncated));
        var coverage = truncated ? Evidence.Coverage.PARTIAL : Evidence.Coverage.COMPLETE;
        return new Evidence.Record(new Evidence.Source(scope, "history", "1", handle, scope.requestGeneration(),
                Instant.now(), Evidence.Activity.INACTIVE, coverage, Evidence.Authority.UNKNOWN,
                List.of("saved-conversation-text", "historical-context")), Evidence.RecordKind.OTHER,
                "Saved exchange: " + entry.requestId().value(), new Evidence.JsonObject(payload), List.of());
    }

    private static String excerpt(String text, String query, int limit) {
        if (text.length() <= limit) return text;
        int match = text.toLowerCase(java.util.Locale.ROOT).indexOf(query.toLowerCase(java.util.Locale.ROOT));
        int start = Math.max(0, Math.min(match < 0 ? 0 : match - limit / 3, text.length() - limit));
        return (start > 0 ? "…" : "") + text.substring(start, start + limit) + (start + limit < text.length() ? "…" : "");
    }
}
