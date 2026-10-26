package dev.packsage.chat;

import dev.packsage.knowledge.Evidence;
import dev.packsage.model.ModelBackend;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;

public interface ChatActions {
    ChatContracts.View view();

    default ChatContracts.Context gameplayContext() {
        return view().context();
    }

    default CompletionStage<List<ChatContracts.SessionSummary>> sessions() {
        return java.util.concurrent.CompletableFuture.completedFuture(List.of());
    }

    default CompletionStage<Void> loadSessions(java.util.function.Consumer<List<ChatContracts.SessionSummary>> refresh) {
        return sessions().thenAccept(refresh);
    }

    default CompletionStage<ConversationStore.SessionPage> sessionPage(int offset, int limit) {
        return sessions().thenApply(values -> {
            var visible = values.stream().filter(session -> settings().showChatsFromOtherSessions()
                    || session.world().worldOrServer().equals(view().activeWorld().worldOrServer())).toList();
            int end = (int) Math.min(visible.size(), (long) offset + limit);
            return new ConversationStore.SessionPage(visible.subList(Math.min(offset, visible.size()), end), end < visible.size());
        });
    }

    default void openSession(ChatContracts.SessionSummary session) { }

    default void markRead(ChatContracts.SessionSummary session, ChatContracts.RequestId expectedCompletion) { }

    default CompletionStage<Void> deleteSession(ChatContracts.SessionSummary session) {
        return java.util.concurrent.CompletableFuture.failedFuture(new UnsupportedOperationException("session deletion is unavailable"));
    }

    default ChatContracts.AccountInfo account() {
        return new ChatContracts.AccountInfo(new ModelBackend.ProviderInfo("unknown", "Provider"), "");
    }

    default List<ModelBackend.ModelInfo> modelCatalog() { return List.of(); }

    default String providerSelectedModelId() { return null; }

    default Map<String, Boolean> adapterAvailability() { return Map.of(); }

    default Map<String, String> adapterDetails() { return Map.of(); }

    default PackSageSettings settings() { return PackSageSettings.defaults(); }

    default List<dev.packsage.world.CommandRunner.Confirmation> commandConfirmations() { return List.of(); }

    default void confirmCommand(String id, boolean run) { }

    default ChatContracts.HistoryPreview historyPreview() {
        return new ChatContracts.HistoryPreview(new ChatContracts.HistorySelection(List.of(), 0),
                PackSageSettings.DEFAULT_HISTORICAL_TOKENS, PackSageSettings.DEFAULT_HISTORICAL_TOKENS, null,
                PackSageSettings.DEFAULT_HISTORY_TURNS);
    }

    default ChatContracts.HistoryPreview historyPreview(ChatContracts.RequestId replacing) { return historyPreview(); }

    default void resend(ChatContracts.RequestId expectedLatest, String question, boolean includeScreenshot,
                        boolean explicitWebSearch) { }

    void signIn();

    void cancelSignIn();

    void signOut();

    void send(String question, boolean includeScreenshot, boolean explicitWebSearch);

    default boolean steer(String text) { return false; }

    void stop();

    void newChat();

    void setPaused(boolean paused);

    void navigate(Evidence.NavigationTarget target);
}
