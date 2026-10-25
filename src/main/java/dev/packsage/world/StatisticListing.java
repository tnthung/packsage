package dev.packsage.world;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;

/** Orders, merges and pages statistics off the server thread. */
public final class StatisticListing {
    static final String NO_MATCH_NOTE = "no counter matches";
    static final String ZERO_NOTE = "unlisted counters are zero";
    private static final List<String> CATEGORY_ORDER = List.of("mined", "crafted", "used", "broken", "picked_up", "dropped", "killed", "killed_by", "custom");
    private static final Comparator<Statistic> ORDER = Comparator.<Statistic>comparingInt(row -> rank(row.category()))
            .thenComparing(Statistic::category)
            .thenComparing(Statistic::subject, Comparator.nullsFirst(Comparator.naturalOrder()));

    private StatisticListing() {
    }

    /**
     * Orders rows by category (the vanilla order, then other categories alphabetically) and then subject, dropping
     * repeats of an (adapter, category, subject), then pages them as {@link ListingPages#slice} does. An empty complete
     * listing says unlisted counters are zero; an empty partial one does not.
     */
    public static Page<Statistic> page(List<List<Statistic>> perAdapter, int offset, List<String> notes) {
        return page(perAdapter, offset, notes, null);
    }

    public static Page<Statistic> page(List<List<Statistic>> perAdapter, int offset, List<String> notes,
                                       java.util.function.ToIntFunction<Statistic> encodedBytes) {
        var seen = new HashSet<List<String>>();
        var rows = new ArrayList<Statistic>();
        for (var adapterRows : perAdapter) {
            for (var row : adapterRows) {
                if (seen.add(Arrays.asList(row.adapter(), row.category(), row.subject()))) {
                    rows.add(row);
                }
            }
        }
        rows.sort(ORDER);
        // A partial read may have missed counters, so only a complete one says the unlisted counters are zero.
        return ListingPages.slice(rows, offset, notes, notes.isEmpty() ? NO_MATCH_NOTE + "; " + ZERO_NOTE : NO_MATCH_NOTE, encodedBytes);
    }

    private static int rank(String category) {
        var index = CATEGORY_ORDER.indexOf(category);
        return index < 0 ? CATEGORY_ORDER.size() : index;
    }
}
