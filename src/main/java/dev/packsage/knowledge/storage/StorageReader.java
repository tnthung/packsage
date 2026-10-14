package dev.packsage.knowledge.storage;

import dev.packsage.knowledge.Evidence;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

@FunctionalInterface
public interface StorageReader {
    enum Target { OPENED, TARGETED, ITEM }

    record Request(Target target, String slotReference, String text, List<String> identities, int limit, String cursor, Evidence.Scope scope) {
        public Request {
            Objects.requireNonNull(target);
            identities = List.copyOf(identities);
            Objects.requireNonNull(scope);
            if (target == Target.ITEM && (slotReference == null || slotReference.isBlank() || slotReference.length() > 1024)) {
                throw new IllegalArgumentException("item storage requires an observed slotReference");
            }
            if (target != Target.ITEM && slotReference != null) throw new IllegalArgumentException("slotReference requires target=item");
            if (limit < 1 || limit > 32) throw new IllegalArgumentException("storage page limit must be between 1 and 32");
        }
    }

    CompletionStage<Evidence.Result> read(Request request);
}
