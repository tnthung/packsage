package dev.packsage.world;

import java.util.Collection;
import java.util.List;
import java.util.Locale;

public record KindFilter(List<String> ids, List<String> tags, String text) {
    public static final KindFilter ANY = new KindFilter(List.of(), List.of(), "");

    public KindFilter {
        ids = ids == null ? List.of() : List.copyOf(ids);
        tags = tags == null ? List.of() : List.copyOf(tags);
        text = text == null ? "" : text;
    }

    public boolean matches(String id, Collection<String> itemTags, String name) {
        if (!ids.isEmpty() && !ids.contains(id)) {
            return false;
        }
        if (!tags.isEmpty() && tags.stream().noneMatch(itemTags::contains)) {
            return false;
        }
        if (!text.isEmpty()) {
            var needle = text.toLowerCase(Locale.ROOT);
            return id.toLowerCase(Locale.ROOT).contains(needle) || (name != null && name.toLowerCase(Locale.ROOT).contains(needle));
        }
        return true;
    }
}
