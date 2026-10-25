package dev.packsage.world;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;

/** Orders, merges and pages statistics off the server thread. */
public final class StatisticListing {
    static final String NO_MATCH_NOTE = "no counter matches; unlisted counters are zero";
    private static final List<String> CATEGORY_ORDER = List.of("mined", "crafted", "used", "broken", "picked_up", "dropped", "killed", "killed_by", "custom");
    private static final Comparator<Statistic> ORDER = Comparator.<Statistic>comparingInt(row -> rank(row.category()))
            .thenComparing(Statistic::category)
            .thenComparing(Statistic::subject, Comparator.nullsFirst(Comparator.naturalOrder()));

    private StatisticListing() {
    }

    /**
     * Orders rows by category (the vanilla order, then other categories alphabetically) and then subject, dropping
     * repeats of an (adapter, category, subject). A page holds {@link AreaScan#LIST_PAGE} rows from {@code offset};
     * the cursor is the next offset, and any {@code notes} make the page partial.
     */
    public static Page<Statistic> page(List<List<Statistic>> perAdapter, int offset, List<String> notes) {
        if (offset < 0) {
            throw new IllegalArgumentException("Invalid statistic offset: " + offset);
        }
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
        return ProgressListing.slice(rows, offset, notes, NO_MATCH_NOTE);
    }

    private static int rank(String category) {
        var index = CATEGORY_ORDER.indexOf(category);
        return index < 0 ? CATEGORY_ORDER.size() : index;
    }
}
