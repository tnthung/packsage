package dev.packsage.world;

import dev.packsage.knowledge.Evidence;

import java.util.ArrayList;
import java.util.List;

/** Offset paging shared by the pure listings. */
final class ListingPages {
    private ListingPages() {
    }

    /**
     * Pages {@code items} by encoded bytes and at most {@link AreaScan#LIST_PAGE} from {@code offset}; the cursor is the next offset. Any
     * {@code notes} make the page partial, and an empty listing leads its note with {@code emptyNote}.
     */
    static <T> Page<T> slice(List<T> items, int offset, List<String> notes, String emptyNote,
                             java.util.function.ToIntFunction<T> encodedBytes) {
        if (offset < 0) {
            throw new IllegalArgumentException("Invalid listing offset: " + offset);
        }
        var coverage = notes.isEmpty() ? Evidence.Coverage.COMPLETE : Evidence.Coverage.PARTIAL;
        var noteParts = new ArrayList<String>();
        if (items.isEmpty()) {
            noteParts.add(emptyNote);
        }
        noteParts.addAll(notes);
        var note = noteParts.isEmpty() ? null : String.join("; ", noteParts);
        var start = Math.min(offset, items.size());
        var end = start;
        int bytes = 0;
        while (end < items.size() && end - start < AreaScan.LIST_PAGE) {
            // Pure-data callers can estimate conservatively; live providers supply their actual wire encoder.
            int added = encodedBytes == null ? new com.google.gson.Gson().toJson(items.get(end))
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8).length + 1024 : encodedBytes.applyAsInt(items.get(end));
            if (end > start && bytes + added > 96 * 1024) break;
            bytes += added;
            end++;
        }
        var next = end < items.size() ? Integer.toString(end) : null;
        return new Page<>(items.subList(start, end), next, coverage, note);
    }
}
