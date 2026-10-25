package dev.packsage.world;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/** Ids, tags and name text combine with AND, alternatives within each with OR; tooltip fragments apply only to items. */
public record KindFilter(List<String> ids, List<String> tags, String text, List<String> tooltip) {
    public static final KindFilter ANY = new KindFilter(List.of(), List.of(), "");

    public KindFilter {
        ids = ids == null ? List.of() : List.copyOf(ids);
        tags = tags == null ? List.of() : List.copyOf(tags);
        text = text == null ? "" : text;
        tooltip = tooltip == null ? List.of() : List.copyOf(tooltip);
    }

    public KindFilter(List<String> ids, List<String> tags, String text) {
        this(ids, tags, text, List.of());
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

    /**
     * Also requires one tooltip fragment to be a case-insensitive substring of a tooltip line. The tooltip is read
     * only when there are fragments and the other fields matched; an {@code unknown} tooltip never matches.
     */
    public boolean matches(String id, Collection<String> itemTags, String name, Supplier<ItemTooltips.Tooltip> itemTooltip) {
        if (!matches(id, itemTags, name)) {
            return false;
        }
        if (tooltip.isEmpty()) {
            return true;
        }
        var read = itemTooltip.get();
        if (read.status().equals("unknown")) {
            return false;
        }
        var lines = read.lines().stream().map(line -> line.toLowerCase(Locale.ROOT)).toList();
        return tooltip.stream().map(fragment -> fragment.toLowerCase(Locale.ROOT)).anyMatch(fragment -> lines.stream().anyMatch(line -> line.contains(fragment)));
    }
}
