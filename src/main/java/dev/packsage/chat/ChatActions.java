package dev.packsage.chat;

import dev.packsage.knowledge.Evidence;

public interface ChatActions {
    ChatContracts.View view();

    void signIn();

    void cancelSignIn();

    void signOut();

    void send(String question, boolean includeScreenshot, boolean explicitWebSearch);

    void stop();

    void newChat();

    void setPaused(boolean paused);

    void navigate(Evidence.NavigationTarget target);
}
