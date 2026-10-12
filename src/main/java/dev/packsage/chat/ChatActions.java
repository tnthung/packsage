package dev.packsage.chat;

import dev.packsage.knowledge.Evidence;
import dev.packsage.model.ModelBackend;

import java.util.List;
import java.util.concurrent.CompletionStage;

public interface ChatActions {
    ChatContracts.View view();

    default CompletionStage<List<ChatContracts.SessionSummary>> sessions() {
        return java.util.concurrent.CompletableFuture.completedFuture(List.of());
    }

    default void openSession(ChatContracts.SessionSummary session) { }

    default ChatContracts.AccountInfo account() {
        return new ChatContracts.AccountInfo(new ModelBackend.ProviderInfo("unknown", "Provider"), "");
    }

    default List<ModelBackend.ModelInfo> modelCatalog() { return List.of(); }

    default String providerSelectedModelId() { return null; }

    void signIn();

    void cancelSignIn();

    void signOut();

    void send(String question, boolean includeScreenshot, boolean explicitWebSearch);

    void stop();

    void newChat();

    void setPaused(boolean paused);

    void navigate(Evidence.NavigationTarget target);
}
