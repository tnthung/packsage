package dev.packsage.model;

import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

public final class ReferenceAliasBackend implements ModelBackend {
    private final ModelBackend backend;

    public ReferenceAliasBackend(ModelBackend backend) {
        this.backend = Objects.requireNonNull(backend);
    }

    @Override
    public ProviderInfo providerInfo() { return backend.providerInfo(); }

    @Override
    public CompletionStage<AuthState> signIn() { return backend.signIn(); }

    @Override
    public CompletionStage<AuthState> restoreSession() { return backend.restoreSession(); }

    @Override
    public void cancelSignIn() { backend.cancelSignIn(); }

    @Override
    public void signOut() { backend.signOut(); }

    @Override
    public CompletionStage<Capabilities> capabilities() { return backend.capabilities(); }

    @Override
    public Exchange start(Request request, Consumer<Event> events) { return backend.start(request, events); }
}
