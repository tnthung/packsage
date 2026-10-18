package dev.packsage.knowledge;

import dev.packsage.model.PackSageTools;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

public interface Toolbox {
    enum Code { NOT_HANDLED, INVALID_ARGUMENTS, INVALID_REFERENCE, INVALID_CURSOR, STALE_SCOPE,
        UNAVAILABLE, UNSUPPORTED_FILTER, EXTRACTION_FAILED }

    final class Failure extends RuntimeException {
        private final Code code;

        public Failure(Code code, String message) {
            super(code + ": " + message);
            this.code = Objects.requireNonNull(code);
        }

        public Code code() { return code; }
    }

    record Metadata(String id, List<String> documentTypes, boolean recipes, List<String> registries,
                    Map<String, String> inventoryTargets) {
        public Metadata {
            Objects.requireNonNull(id);
            documentTypes = List.copyOf(documentTypes);
            registries = List.copyOf(registries);
            inventoryTargets = Map.copyOf(inventoryTargets);
        }
    }

    record Reference(String providerId, String sourceHandle, Evidence.RecordKind kind, Evidence.Scope scope) { }

    final class DocumentBudget {
        private int documents;
        private int values;
        private int bytes;
        private boolean exhausted;
        private final Map<String, Integer> callbacks = new java.util.HashMap<>();

        public synchronized boolean document() {
            if (documents >= 256) { exhausted = true; return false; }
            documents++;
            return true;
        }

        public synchronized boolean value() {
            if (values >= 2048) { exhausted = true; return false; }
            values++;
            return true;
        }

        public synchronized boolean bytes(int count) {
            if (count < 0 || count > remainingBytes()) { exhausted = true; return false; }
            bytes += count;
            return true;
        }

        public synchronized boolean callback(String providerId, int limit) {
            int count = callbacks.getOrDefault(providerId, 0);
            if (count >= limit) { exhausted = true; return false; }
            callbacks.put(providerId, count + 1);
            return true;
        }

        public synchronized int remainingBytes() { return 96 * 1024 - bytes; }
        public synchronized boolean exhausted() { return exhausted; }
    }

    record Context(Supplier<Evidence.Scope> currentScope, Map<String, Reference> references,
                   AdapterRegistry registry, DocumentBudget documentBudget, String replacingRequestId) {
        public Context {
            Objects.requireNonNull(currentScope);
            Objects.requireNonNull(references);
            Objects.requireNonNull(registry);
            Objects.requireNonNull(documentBudget);
        }

        public Reference reference(PackSageTools.ReadRequest request) {
            if (!request.scope().equals(currentScope.get())) throw new Failure(Code.STALE_SCOPE, "repeat the lookup in the current scope");
            if (!(request.arguments().values().get("handles") instanceof Evidence.JsonArray handles)
                    || handles.values().size() != 1 || !(handles.values().getFirst() instanceof Evidence.JsonString handle)) {
                throw new Failure(Code.INVALID_ARGUMENTS, "adapter reads require one observed handle");
            }
            var reference = references.get(handle.value());
            if (reference == null || !reference.scope().equals(request.scope())) {
                throw new Failure(Code.INVALID_REFERENCE, "handle was not observed in this scope; repeat discovery");
            }
            return reference;
        }
    }

    Metadata metadata();

    default CompletionStage<Evidence.Result> searchInventory(PackSageTools.ReadRequest request, Context context) { return CompletableFuture.failedFuture(new Failure(Code.NOT_HANDLED, "inventory")); }
    default CompletionStage<Evidence.Result> searchDocument(PackSageTools.ReadRequest request, Context context) { return CompletableFuture.failedFuture(new Failure(Code.NOT_HANDLED, "document")); }
    default CompletionStage<Evidence.Result> searchRecipe(PackSageTools.ReadRequest request, Context context) { return CompletableFuture.failedFuture(new Failure(Code.NOT_HANDLED, "recipe")); }
    default CompletionStage<Evidence.Result> searchRegistry(PackSageTools.ReadRequest request, Context context) { return CompletableFuture.failedFuture(new Failure(Code.NOT_HANDLED, "registry")); }
    default CompletionStage<Evidence.Result> readEvidence(PackSageTools.ReadRequest request, Context context) { return CompletableFuture.failedFuture(new Failure(Code.NOT_HANDLED, "evidence")); }
    default CompletionStage<Evidence.Result> readProgress(PackSageTools.ReadRequest request, Context context) { return CompletableFuture.failedFuture(new Failure(Code.NOT_HANDLED, "progress")); }
    default CompletionStage<Evidence.Result> planRecipe(PackSageTools.ReadRequest request, Context context) { return CompletableFuture.failedFuture(new Failure(Code.NOT_HANDLED, "plan")); }
    default CompletionStage<Evidence.Result> listConfigs(PackSageTools.ReadRequest request, Context context) { return CompletableFuture.failedFuture(new Failure(Code.NOT_HANDLED, "configs")); }
    default CompletionStage<Evidence.Result> searchConfig(PackSageTools.ReadRequest request, Context context) { return CompletableFuture.failedFuture(new Failure(Code.NOT_HANDLED, "config search")); }
    default CompletionStage<Evidence.Result> readConfig(PackSageTools.ReadRequest request, Context context) { return CompletableFuture.failedFuture(new Failure(Code.NOT_HANDLED, "config")); }
    default CompletionStage<Evidence.Result> searchHistory(PackSageTools.ReadRequest request, Context context) { return CompletableFuture.failedFuture(new Failure(Code.NOT_HANDLED, "history search")); }
    default CompletionStage<Evidence.Result> readHistory(PackSageTools.ReadRequest request, Context context) { return CompletableFuture.failedFuture(new Failure(Code.NOT_HANDLED, "history")); }
}
