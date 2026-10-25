package dev.packsage.journal;

import org.sqlite.SQLiteConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * The per-world SQLite journal: one write connection and one read connection over the same WAL database. Every
 * method is called from one thread at a time, and the write methods only from the journal writer thread.
 */
public final class JournalStore implements AutoCloseable {
    static final long SCHEMA_VERSION = 1;

    private static final String[] SCHEMA = {
            "CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT NOT NULL)",
            "CREATE TABLE sessions(id INTEGER PRIMARY KEY, started_wall INTEGER NOT NULL, started_game_time INTEGER NOT NULL,"
                    + " ended_wall INTEGER, ended_game_time INTEGER)",
            "CREATE TABLE events("
                    + "id INTEGER PRIMARY KEY, type TEXT NOT NULL, actor TEXT NOT NULL, subject TEXT, dimension TEXT NOT NULL, grp TEXT,"
                    + " count INTEGER NOT NULL, amount INTEGER NOT NULL,"
                    + " first_game_time INTEGER NOT NULL, last_game_time INTEGER NOT NULL, first_wall INTEGER NOT NULL, last_wall INTEGER NOT NULL,"
                    + " at_x INTEGER, at_y INTEGER, at_z INTEGER,"
                    + " min_x INTEGER, min_y INTEGER, min_z INTEGER, max_x INTEGER, max_y INTEGER, max_z INTEGER,"
                    + " positions BLOB,"
                    + " detail TEXT,"
                    + " count_only INTEGER NOT NULL DEFAULT 0)",
            "CREATE INDEX events_time ON events(first_wall)",
            "CREATE INDEX events_dimension_time ON events(dimension, first_wall)",
            "CREATE INDEX events_subject_time ON events(subject, first_wall)",
            "CREATE INDEX events_type_time ON events(type, first_wall)",
            "CREATE VIRTUAL TABLE events_box USING rtree_i32(id, min_x, max_x, min_y, max_y, min_z, max_z)",
            "CREATE TABLE structure_visits(dimension TEXT NOT NULL, structure TEXT NOT NULL, start_chunk TEXT NOT NULL,"
                    + " PRIMARY KEY(dimension, structure, start_chunk))",
            "CREATE TABLE notes("
                    + "id TEXT PRIMARY KEY, text TEXT NOT NULL, player_words TEXT, tags TEXT NOT NULL,"
                    + " dimension TEXT, x INTEGER, y INTEGER, z INTEGER,"
                    + " created_wall INTEGER NOT NULL, created_game_time INTEGER NOT NULL, updated_wall INTEGER NOT NULL,"
                    + " origin_conversation TEXT, origin_request TEXT)",
            "CREATE INDEX notes_time ON notes(created_wall)"
    };

    /** When the journal was first recorded into, in wall milliseconds and game ticks. */
    public record Meta(long createdWall, long createdGameTime) {
    }

    /** One recording session; the end is null while it is open or when the game stopped without closing it. */
    public record Session(long id, long startedWall, long startedGameTime, Long endedWall, Long endedGameTime) {
    }

    private final Connection writer;
    private final Connection reader;
    private final long schemaVersion;

    private JournalStore(Connection writer, Connection reader, long schemaVersion) {
        this.writer = writer;
        this.reader = reader;
        this.schemaVersion = schemaVersion;
    }

    /** Opens or creates the journal at {@code file}, creating its folders and schema v1, and refuses unknown versions. */
    public static JournalStore open(Path file) throws SQLException {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
        } catch (IOException e) {
            throw new SQLException("cannot create the journal folder for " + file + ": " + e.getMessage(), e);
        }
        var writer = connect(file);
        try {
            var version = readSchemaVersion(writer);
            if (version == null) {
                applySchema(writer);
                version = SCHEMA_VERSION;
            } else if (version != SCHEMA_VERSION) {
                throw new SQLException("journal schema version " + version + " is not supported (this build reads version "
                        + SCHEMA_VERSION + ")");
            }
            var reader = connect(file);
            return new JournalStore(writer, reader, version);
        } catch (SQLException | RuntimeException e) {
            closeQuietly(writer, e);
            throw e;
        }
    }

    public long schemaVersion() {
        return schemaVersion;
    }

    /** The journal's creation time, or null until the first session is started on a fresh database. */
    public Meta meta() throws SQLException {
        Long wall = null;
        Long gameTime = null;
        try (var statement = reader.prepareStatement("SELECT key, value FROM meta WHERE key IN ('created_wall', 'created_game_time')");
             var result = statement.executeQuery()) {
            while (result.next()) {
                var value = Long.parseLong(result.getString(2));
                if (result.getString(1).equals("created_wall")) {
                    wall = value;
                } else {
                    gameTime = value;
                }
            }
        }
        return wall == null || gameTime == null ? null : new Meta(wall, gameTime);
    }

    /** Opens a session and, on a fresh database, records the journal's creation time. Writer thread only. */
    public long startSession(long wall, long gameTime) throws SQLException {
        writer.setAutoCommit(false);
        try {
            try (var statement = writer.prepareStatement("INSERT OR IGNORE INTO meta(key, value) VALUES (?, ?)")) {
                statement.setString(1, "created_wall");
                statement.setString(2, Long.toString(wall));
                statement.addBatch();
                statement.setString(1, "created_game_time");
                statement.setString(2, Long.toString(gameTime));
                statement.addBatch();
                statement.executeBatch();
            }
            long id;
            try (var statement = writer.prepareStatement("INSERT INTO sessions(started_wall, started_game_time) VALUES (?, ?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                statement.setLong(1, wall);
                statement.setLong(2, gameTime);
                statement.executeUpdate();
                try (var keys = statement.getGeneratedKeys()) {
                    keys.next();
                    id = keys.getLong(1);
                }
            }
            writer.commit();
            return id;
        } catch (SQLException | RuntimeException e) {
            writer.rollback();
            throw e;
        } finally {
            writer.setAutoCommit(true);
        }
    }

    /** Closes a session. Writer thread only. */
    public void endSession(long sessionId, long wall, long gameTime) throws SQLException {
        try (var statement = writer.prepareStatement("UPDATE sessions SET ended_wall = ?, ended_game_time = ? WHERE id = ?")) {
            statement.setLong(1, wall);
            statement.setLong(2, gameTime);
            statement.setLong(3, sessionId);
            statement.executeUpdate();
        }
    }

    /** Every session, oldest first. */
    public List<Session> sessions() throws SQLException {
        var sessions = new ArrayList<Session>();
        try (var statement = reader.prepareStatement(
                "SELECT id, started_wall, started_game_time, ended_wall, ended_game_time FROM sessions ORDER BY id");
             var result = statement.executeQuery()) {
            while (result.next()) {
                sessions.add(new Session(result.getLong(1), result.getLong(2), result.getLong(3),
                        nullableLong(result, 4), nullableLong(result, 5)));
            }
        }
        return List.copyOf(sessions);
    }

    /** Checkpoints the WAL into the database file and closes both connections. */
    @Override
    public void close() throws SQLException {
        SQLException failure = null;
        try (var statement = writer.createStatement()) {
            statement.execute("PRAGMA wal_checkpoint(TRUNCATE)");
        } catch (SQLException e) {
            failure = e;
        }
        for (var connection : new Connection[]{reader, writer}) {
            try {
                connection.close();
            } catch (SQLException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    Connection writer() {
        return writer;
    }

    Connection reader() {
        return reader;
    }

    private static Connection connect(Path file) throws SQLException {
        var config = new SQLiteConfig();
        config.setJournalMode(SQLiteConfig.JournalMode.WAL);
        config.setSynchronous(SQLiteConfig.SynchronousMode.NORMAL);
        config.setBusyTimeout(5000);
        config.enforceForeignKeys(true);
        return config.createConnection("jdbc:sqlite:" + file);
    }

    private static Long readSchemaVersion(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'meta'");
             var result = statement.executeQuery()) {
            if (!result.next()) {
                return null;
            }
        }
        try (var statement = connection.prepareStatement("SELECT value FROM meta WHERE key = 'schema_version'");
             var result = statement.executeQuery()) {
            if (!result.next()) {
                return null;
            }
            var value = result.getString(1);
            try {
                return Long.parseLong(value);
            } catch (NumberFormatException e) {
                throw new SQLException("journal schema version " + value + " is not a number", e);
            }
        }
    }

    private static void applySchema(Connection connection) throws SQLException {
        connection.setAutoCommit(false);
        try (var statement = connection.createStatement()) {
            for (var sql : SCHEMA) {
                statement.execute(sql);
            }
            statement.executeUpdate("INSERT INTO meta(key, value) VALUES ('schema_version', '" + SCHEMA_VERSION + "')");
            connection.commit();
        } catch (SQLException | RuntimeException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    private static Long nullableLong(ResultSet result, int column) throws SQLException {
        var value = result.getLong(column);
        return result.wasNull() ? null : value;
    }

    private static void closeQuietly(Connection connection, Exception cause) {
        try {
            connection.close();
        } catch (SQLException e) {
            cause.addSuppressed(e);
        }
    }
}
