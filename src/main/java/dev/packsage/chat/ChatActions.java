package dev.packsage.chat;

import dev.packsage.knowledge.Evidence;
import dev.packsage.model.ModelBackend;

import java.util.List;
import java.util.concurrent.CompletionStage;

public interface ChatActions {
    ChatContracts.View view();

    default ChatContracts.Context gameplayContext() {
        return view().context();
    }

    default CompletionStage<List<ChatContracts.SessionSummary>> sessions() {
        return java.util.concurrent.CompletableFuture.completedFuture(List.of());
    }

    default void openSession(ChatContracts.SessionSummary session) { }

    default CompletionStage<Void> deleteSession(ChatContracts.SessionSummary session) {
        return java.util.concurrent.CompletableFuture.failedFuture(new UnsupportedOperationException("session deletion is unavailable"));
    }

    default ChatContracts.AccountInfo account() {
        return new ChatContracts.AccountInfo(new ModelBackend.ProviderInfo("unknown", "Provider"), "");
    }

    default List<ModelBackend.ModelInfo> modelCatalog() { return List.of(); }

    default String providerSelectedModelId() { return null; }

    default PackSageSettings settings() { return PackSageSettings.defaults(); }

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

    void stop();

    void newChat();

    void setPaused(boolean paused);

    void navigate(Evidence.NavigationTarget target);
}
