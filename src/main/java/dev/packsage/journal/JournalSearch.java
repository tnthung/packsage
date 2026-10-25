package dev.packsage.journal;

import dev.packsage.knowledge.Evidence;
import dev.packsage.world.BlockPlace;
import dev.packsage.world.Page;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Bounded keyset searches on the reader connection. Call from a worker, never from the server thread. */
public final class JournalSearch {
    public static final int PAGE = 64;
    public static final int MAX_CANDIDATES = 4096;

    private final JournalStore store;
    private final JournalStats stats;

    /** Searches without failed-event information. Production callers must supply the writer's actual stats. */
    public JournalSearch(JournalStore store) {
        this(store, null);
    }

    public JournalSearch(JournalStore store, JournalStats stats) {
        this.store = store;
        this.stats = stats;
    }

    public Page<JournalEntry> search(JournalFilter filter, String cursor, long nowWall) throws SQLException {
        return store.read(connection -> {
            var events = query(connection, filter, cursor, nowWall, false, false);
            try (events; var notes = query(connection, filter, cursor, nowWall, true, false);
                 var eventRows = events.executeQuery(); var noteRows = notes.executeQuery()) {
                var items = new ArrayList<JournalEntry>();
                var reasons = new ArrayList<String>();
                var hasEvent = eventRows.next();
                var hasNote = noteRows.next();
                var examined = 0;
                String last = null;
                while ((hasEvent || hasNote) && items.size() < PAGE && examined < MAX_CANDIDATES) {
                    JournalEntry entry;
                    if (hasEvent && (!hasNote || eventRows.getLong("first_wall") > noteRows.getLong("created_wall")
                            || eventRows.getLong("first_wall") == noteRows.getLong("created_wall")
                            && eventRows.getString("ref").compareTo(noteRows.getString("ref")) > 0)) {
                        last = eventRows.getLong("first_wall") + ":" + eventRows.getString("ref");
                        entry = event(eventRows, filter.area());
                        hasEvent = eventRows.next();
                    } else {
                        last = noteRows.getLong("created_wall") + ":" + noteRows.getString("ref");
                        var dimension = noteRows.getString("dimension");
                        var place = dimension == null ? null : new BlockPlace(dimension, noteRows.getInt("x"),
                                noteRows.getInt("y"), noteRows.getInt("z"));
                        entry = new JournalEntry.NoteEntry(new Note(noteRows.getString("id"), noteRows.getString("text"),
                                noteRows.getString("player_words"), noteRows.getString("tags").isEmpty() ? List.of()
                                : Arrays.asList(noteRows.getString("tags").split(",")), place, noteRows.getLong("created_wall"),
                                noteRows.getLong("created_game_time"), noteRows.getLong("updated_wall"),
                                noteRows.getString("origin_conversation"), noteRows.getString("origin_request")));
                        if (filter.area() != null && (place == null || !inside(filter.area(), place.x(), place.y(), place.z()))) {
                            entry = null;
                        }
                        hasNote = noteRows.next();
                    }
                    examined++;
                    if (entry != null) {
                        items.add(entry);
                    }
                }
                var bounded = examined == MAX_CANDIDATES && items.size() < PAGE;
                var more = hasEvent || hasNote || bounded;
                if (bounded) {
                    reasons.add("examined 4,096 candidate runs; continue with the cursor");
                } else if (more) {
                    reasons.add("more journal entries are available; continue with the cursor");
                }
                if (filter.area() != null) {
                    try (var count = query(connection, filter, null, nowWall, false, true); var rows = count.executeQuery()) {
                        if (rows.next() && rows.getLong(1) > 0) {
                            reasons.add(rows.getLong(1) + " count-only rows in this period have no positions; the area cannot rule them out");
                        }
                    }
                }
                var meta = store.meta();
                if (meta == null) {
                    reasons.add("the journal is not recording yet");
                } else if (filter.afterWall() != null && filter.afterWall() < meta.createdWall()) {
                    reasons.add("the journal began recording at " + Instant.ofEpochMilli(meta.createdWall()));
                }
                var sessions = store.sessions();
                for (int i = 1; i < sessions.size(); i++) {
                    var previous = sessions.get(i - 1);
                    var next = sessions.get(i);
                    long endedWall = previous.endedWall() == null ? previous.startedWall() : previous.endedWall();
                    long endedGame = previous.endedGameTime() == null ? previous.startedGameTime() : previous.endedGameTime();
                    if (previous.endedGameTime() == null) {
                        try (var statement = connection.prepareStatement("SELECT MAX(last_wall), MAX(last_game_time) FROM events"
                                + " WHERE first_wall >= ? AND first_wall < ?")) {
                            statement.setLong(1, previous.startedWall());
                            statement.setLong(2, next.startedWall());
                            try (var rows = statement.executeQuery()) {
                                if (rows.next() && rows.getObject(1) != null) {
                                    endedWall = rows.getLong(1);
                                    endedGame = rows.getLong(2);
                                }
                            }
                        }
                    }
                    if (next.startedGameTime() - endedGame > 200
                            && (filter.afterWall() == null || filter.afterWall() < next.startedWall())
                            && (filter.beforeWall() == null ? nowWall : filter.beforeWall()) > endedWall) {
                        reasons.add("the world ran without recording between " + Instant.ofEpochMilli(endedWall)
                                + " and " + Instant.ofEpochMilli(next.startedWall()));
                    }
                }
                if (stats != null && stats.failedEvents() > 0) {
                    reasons.add(stats.failedEvents() + " events failed to record");
                }
                return new Page<>(items, more ? last : null, reasons.isEmpty() ? Evidence.Coverage.COMPLETE : Evidence.Coverage.PARTIAL,
                        reasons.isEmpty() ? null : String.join("; ", reasons));
            }
        });
    }

    /** Builds either keyset stream, or the count-only coverage query using the same non-spatial filters. */
    private static PreparedStatement query(Connection connection, JournalFilter filter, String cursor, long nowWall,
                                           boolean notes, boolean countOnly) throws SQLException {
        var time = notes ? "created_wall" : "first_wall";
        var ref = notes ? "'n' || id" : "printf('e%012d', id)";
        var sql = new StringBuilder(countOnly ? "SELECT COUNT(*) FROM events WHERE count_only = 1"
                : "SELECT *, " + ref + " AS ref FROM " + (notes ? "notes" : "events") + " WHERE 1 = 1");
        var arguments = new ArrayList<Object>();
        sql.append(" AND ").append(notes ? time : "last_wall").append(" >= ? AND ").append(time).append(" <= ?");
        arguments.add(filter.afterWall() == null ? Long.MIN_VALUE : filter.afterWall());
        arguments.add(filter.beforeWall() == null ? nowWall : filter.beforeWall());
        if (notes) {
            if (!filter.types().isEmpty() && !filter.types().contains("note") || !filter.subjects().isEmpty()) {
                sql.append(" AND 0 = 1");
            }
        } else {
            if (filter.notesOnly()) {
                sql.append(" AND 0 = 1");
            } else if (!filter.types().isEmpty()) {
                sql.append(" AND type IN (").append(String.join(",", Collections.nCopies(filter.types().size(), "?"))).append(")");
                arguments.addAll(filter.types());
            }
            if (!filter.subjects().isEmpty()) {
                sql.append(" AND (subject IN (").append(String.join(",", Collections.nCopies(filter.subjects().size(), "?")))
                        .append(countOnly ? ") OR subject = '*')" : "))");
                arguments.addAll(filter.subjects());
            }
        }
        if (filter.dimension() != null) {
            sql.append(" AND dimension = ?");
            arguments.add(filter.dimension());
        }
        if (filter.text() != null && !countOnly) {
            sql.append(notes ? " AND (text LIKE ? ESCAPE '\\' COLLATE NOCASE OR player_words LIKE ? ESCAPE '\\' COLLATE NOCASE"
                    + " OR tags LIKE ? ESCAPE '\\' COLLATE NOCASE)"
                    : " AND (subject LIKE ? ESCAPE '\\' COLLATE NOCASE OR actor LIKE ? ESCAPE '\\' COLLATE NOCASE"
                    + " OR detail LIKE ? ESCAPE '\\' COLLATE NOCASE)");
            var pattern = "%" + filter.text().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            arguments.add(pattern);
            arguments.add(pattern);
            arguments.add(pattern);
        }
        if (filter.area() != null) {
            var area = filter.area();
            sql.append(" AND dimension = ?");
            arguments.add(area.dimension());
            if (!countOnly) {
                sql.append(notes ? " AND x >= ? AND x <= ? AND y >= ? AND y <= ? AND z >= ? AND z <= ?"
                        : " AND id IN (SELECT id FROM events_box WHERE max_x >= ? AND min_x <= ?"
                        + " AND max_y >= ? AND min_y <= ? AND max_z >= ? AND min_z <= ?)");
                arguments.add((long) area.x() - area.radius());
                arguments.add((long) area.x() + area.radius());
                arguments.add((long) area.y() - area.radius());
                arguments.add((long) area.y() + area.radius());
                arguments.add((long) area.z() - area.radius());
                arguments.add((long) area.z() + area.radius());
            }
        }
        if (cursor != null && !countOnly) {
            var colon = cursor.indexOf(':');
            if (colon < 1 || colon == cursor.length() - 1) {
                throw new IllegalArgumentException("invalid journal cursor " + cursor);
            }
            var wall = Long.parseLong(cursor.substring(0, colon));
            sql.append(" AND (").append(time).append(" < ? OR (").append(time).append(" = ? AND ").append(ref).append(" < ?))");
            arguments.add(wall);
            arguments.add(wall);
            arguments.add(cursor.substring(colon + 1));
        }
        if (!countOnly) {
            sql.append(" ORDER BY ").append(time).append(" DESC, ref DESC LIMIT ").append(MAX_CANDIDATES + 1);
        }
        var statement = connection.prepareStatement(sql.toString());
        try {
            for (int i = 0; i < arguments.size(); i++) {
                statement.setObject(i + 1, arguments.get(i));
            }
            return statement;
        } catch (SQLException | RuntimeException e) {
            try {
                statement.close();
            } catch (SQLException close) {
                e.addSuppressed(close);
            }
            throw e;
        }
    }

    /** Decodes exact positions only after the R*Tree has admitted the candidate. */
    private static JournalEntry.EventEntry event(ResultSet row, JournalFilter.Area area) throws SQLException {
        var dimension = row.getString("dimension");
        var countOnly = row.getBoolean("count_only");
        var inArea = new ArrayList<BlockPlace>();
        var inAreaCount = 0;
        if (area != null) {
            for (var packed : Positions.decode(row.getBytes("positions"))) {
                if (inside(area, Positions.x(packed), Positions.y(packed), Positions.z(packed))) {
                    inAreaCount++;
                    if (inArea.size() < 16) {
                        inArea.add(new BlockPlace(dimension, Positions.x(packed), Positions.y(packed), Positions.z(packed)));
                    }
                }
            }
            if (inAreaCount == 0) {
                return null;
            }
        }
        return new JournalEntry.EventEntry(row.getLong("id"), EventType.byId(row.getString("type")), row.getString("actor"),
                row.getString("subject"), dimension, row.getLong("count"), row.getLong("amount"), row.getLong("first_game_time"),
                row.getLong("last_game_time"), row.getLong("first_wall"), row.getLong("last_wall"),
                countOnly ? null : new BlockPlace(dimension, row.getInt("at_x"), row.getInt("at_y"), row.getInt("at_z")),
                countOnly ? null : new Box(row.getInt("min_x"), row.getInt("min_y"), row.getInt("min_z"),
                        row.getInt("max_x"), row.getInt("max_y"), row.getInt("max_z")), row.getString("detail"), countOnly, inArea, inAreaCount);
    }

    private static boolean inside(JournalFilter.Area area, int x, int y, int z) {
        double dx = (long) x - area.x();
        double dy = (long) y - area.y();
        double dz = (long) z - area.z();
        return dx * dx + dy * dy + dz * dz <= (double) area.radius() * area.radius();
    }
}
