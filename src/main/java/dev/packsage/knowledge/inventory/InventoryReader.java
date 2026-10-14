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

    record Filter(List<String> names, List<String> ids, List<String> tags, List<String> tooltip, List<String> sections) {
        public Filter {
            names = names == null ? List.of() : List.copyOf(names);
            ids = ids == null ? List.of() : List.copyOf(ids);
            tags = tags == null ? List.of() : List.copyOf(tags);
            tooltip = tooltip == null ? List.of() : List.copyOf(tooltip);
            sections = sections == null ? List.of() : List.copyOf(sections);
            if (names.size() > 32 || ids.size() > 32 || tags.size() > 32 || tooltip.size() > 32 || sections.size() > 32
                    || names.stream().anyMatch(value -> value == null || value.length() > 256)
                    || tooltip.stream().anyMatch(value -> value == null || value.length() > 512)
                    || sections.stream().anyMatch(value -> value == null || value.isBlank() || value.length() > 64)
                    || java.util.stream.Stream.concat(ids.stream(), tags.stream()).anyMatch(identity -> identity == null
                    || identity.isBlank() || !identity.contains(":") || ResourceLocation.tryParse(identity) == null
                    || !Objects.equals(ResourceLocation.tryParse(identity).toString(), identity))) {
                throw new IllegalArgumentException("inventory filters contain invalid or excessive selections");
            }
        }

        public static Filter empty() { return new Filter(List.of(), List.of(), List.of(), List.of(), List.of()); }
    }

    record Request(Operation operation, Filter filter, int limit, String cursor, Evidence.Scope scope) {
        public Request {
            Objects.requireNonNull(operation);
            Objects.requireNonNull(scope);
            filter = filter == null ? Filter.empty() : filter;
            if (limit < 1 || limit > 32) throw new IllegalArgumentException("limit must be between 1 and 32");
            if (cursor != null && cursor.length() > 1024) throw new IllegalArgumentException("cursor must not exceed 1024 characters");
        }

        public Request(Operation operation, List<String> identities, String text, List<String> fields, int limit,
                       String cursor, Evidence.Scope scope) {
            this(operation, operation == Operation.READ ? new Filter(List.of(), identities, List.of(), List.of(), List.of())
                    : new Filter(fields == null || fields.isEmpty() || fields.contains("name") ? text == null ? List.of() : List.of(text) : List.of(),
                    fields == null || fields.isEmpty() || fields.contains("id") ? text == null ? List.of() : List.of(text) : List.of(),
                    fields != null && fields.contains("tag") && text != null ? List.of(text) : List.of(),
                    fields != null && fields.contains("tooltip") && text != null ? List.of(text) : List.of(), List.of()), limit, cursor, scope);
        }

        public List<String> identities() { return filter.ids(); }
        public String text() { return !filter.names().isEmpty() ? filter.names().getFirst() : !filter.ids().isEmpty() ? filter.ids().getFirst() : null; }
        public List<String> fields() { var result = new ArrayList<String>(); if (!filter.names().isEmpty()) result.add("name"); if (!filter.ids().isEmpty()) result.add("id"); if (!filter.tags().isEmpty()) result.add("tag"); if (!filter.tooltip().isEmpty()) result.add("tooltip"); return result.isEmpty() ? List.of("name", "id") : List.copyOf(result); }
    }
}
