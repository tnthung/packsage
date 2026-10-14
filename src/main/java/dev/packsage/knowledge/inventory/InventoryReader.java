package dev.packsage.knowledge.inventory;

import dev.packsage.knowledge.Evidence;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

@FunctionalInterface
public interface InventoryReader {
    CompletionStage<Evidence.Result> read(Request request);

    enum Operation { READ, SEARCH }

    record Request(Operation operation, List<String> identities, String text, List<String> fields, int limit,
                   String cursor, Evidence.Scope scope) {
        public Request {
            Objects.requireNonNull(operation);
            Objects.requireNonNull(scope);
            var requestedIdentities = identities == null ? List.<String>of() : new ArrayList<>(identities);
            if (requestedIdentities.size() > 32 || requestedIdentities.stream().anyMatch(identity -> identity == null
                    || identity.isBlank() || !identity.contains(":") || ResourceLocation.tryParse(identity) == null
                    || !Objects.equals(ResourceLocation.tryParse(identity).toString(), identity))) {
                throw new IllegalArgumentException("identities must contain up to 32 exact namespaced item IDs");
            }
            identities = List.copyOf(requestedIdentities);
            fields = fields == null ? List.of() : List.copyOf(fields);
            if (limit < 1 || limit > 32) throw new IllegalArgumentException("limit must be between 1 and 32");
            if (cursor != null && cursor.length() > 1024) throw new IllegalArgumentException("cursor must not exceed 1024 characters");
            if (operation == Operation.SEARCH) {
                if (text == null || text.isBlank() || text.length() > 256) {
                    throw new IllegalArgumentException("search text must be non-blank and at most 256 characters");
                }
                if (fields.size() > 4) throw new IllegalArgumentException("search accepts at most four fields");
                if (fields.stream().anyMatch(field -> !List.of("name", "id", "tag", "tooltip").contains(field))) {
                    throw new IllegalArgumentException("search fields must be name, id, tag, or tooltip");
                }
                if (fields.isEmpty()) fields = List.of("name", "id");
            } else {
                text = null;
                fields = List.of();
            }
        }
    }
}
