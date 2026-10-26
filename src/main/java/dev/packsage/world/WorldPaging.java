package dev.packsage.world;

import dev.packsage.knowledge.Evidence;
import dev.packsage.model.PackSageTools;
import net.minecraft.server.MinecraftServer;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import static dev.packsage.world.WorldTools.*;

final class WorldPaging {
    private static final int CURSORS = 256;

    private record Cursor(String tool, Evidence.JsonObject signature, Evidence.Scope scope, Object state) {
    }

    record ReadSpan(long fromGameTime, long toGameTime) {
    }

    record Spanned<T>(T value, ReadSpan span) {
    }

    private final Map<String, Cursor> cursors = new LinkedHashMap<>(16, 0.75f, true);

    static Evidence.Result single(Evidence.Record record) {
        var complete = record.source().coverage() == Evidence.Coverage.COMPLETE;
        return new Evidence.Result(List.of(record), complete ? Evidence.Coverage.COMPLETE : Evidence.Coverage.PARTIAL,
                complete ? Evidence.Outcome.COMPLETE : Evidence.Outcome.PARTIAL, null);
    }

    static Evidence.Result result(List<Evidence.Record> records, Evidence.Coverage coverage, String next, String note) {
        var complete = next == null && coverage == Evidence.Coverage.COMPLETE;
        return new Evidence.Result(records, complete ? Evidence.Coverage.COMPLETE : Evidence.Coverage.PARTIAL,
                complete ? Evidence.Outcome.COMPLETE : Evidence.Outcome.PARTIAL, next, note);
    }

    String remember(String tool, Evidence.JsonObject arguments, Evidence.Scope scope, Object state) {
        var token = UUID.randomUUID().toString();
        synchronized (cursors) {
            cursors.put(token, new Cursor(tool, signature(arguments), scope, state));
            while (cursors.size() > CURSORS) cursors.remove(cursors.keySet().iterator().next());
        }
        return token;
    }

    String remember(PackSageTools.ReadRequest request, Object state) {
        return remember(request.tool(), request.arguments(), request.scope(), state);
    }

    void discard(String token) {
        synchronized (cursors) {
            cursors.remove(token);
        }
    }

    /** The state behind the request's cursor, or null without one. */
    Object cursor(PackSageTools.ReadRequest request) {
        if (!(argument(request, "cursor") instanceof Evidence.JsonString token)) return null;
        Cursor cursor;
        synchronized (cursors) {
            cursor = cursors.get(token.value());
        }
        if (cursor == null || !cursor.tool().equals(request.tool()) || !cursor.signature().equals(signature(request.arguments()))) {
            throw new Failure(Code.INVALID_CURSOR, "cursor does not match this query; restart without a cursor");
        }
        if (!cursor.scope().equals(request.scope())) throw new Failure(Code.STALE_SCOPE, "world cursor scope changed");
        return cursor.state();
    }

    static <T> T state(Object state, Class<T> type) {
        if (!type.isInstance(state)) throw new Failure(Code.INVALID_CURSOR, "cursor does not match this query; restart without a cursor");
        return type.cast(state);
    }

    /** The arguments a cursor is bound to: everything but cursor and limit, with null values dropped. */
    private static Evidence.JsonObject signature(Evidence.JsonObject arguments) {
        var values = new LinkedHashMap<String, Evidence.JsonValue>();
        arguments.values().forEach((key, value) -> {
            if (!key.equals("cursor") && !key.equals("limit") && !(value instanceof Evidence.JsonNull)) values.put(key, withoutNulls(value));
        });
        return new Evidence.JsonObject(values);
    }

    private static Evidence.JsonValue withoutNulls(Evidence.JsonValue value) {
        if (!(value instanceof Evidence.JsonObject object)) return value;
        var values = new LinkedHashMap<String, Evidence.JsonValue>();
        object.values().forEach((key, nested) -> {
            if (!(nested instanceof Evidence.JsonNull)) values.put(key, withoutNulls(nested));
        });
        return new Evidence.JsonObject(values);
    }

    static <T> ServerWork.Slice<T> once(Supplier<T> read) {
        return then(read, value -> mapped(done(), ignored -> value));
    }

    private static ServerWork.Slice<Boolean> done() {
        return new ServerWork.Slice<>() {
            @Override
            public boolean step() {
                return false;
            }

            @Override
            public Boolean result() {
                return Boolean.TRUE;
            }
        };
    }

    /** Records the game time before the slice's first step and after its last one. */
    static <T> ServerWork.Slice<Spanned<T>> spanned(LongSupplier gameTime, ServerWork.Slice<T> slice) {
        return new ServerWork.Slice<>() {
            private boolean started;
            private long from;
            private long to;

            @Override
            public boolean step() {
                if (!started) {
                    started = true;
                    from = gameTime.getAsLong();
                }
                var more = slice.step();
                to = gameTime.getAsLong();
                return more;
            }

            @Override
            public Spanned<T> result() {
                return new Spanned<>(slice.result(), new ReadSpan(from, to));
            }

            @Override
            public boolean stopEarly() {
                return slice.stopEarly();
            }
        };
    }

    /** The overworld's game time, which every dimension shares; server thread only. */
    static LongSupplier gameTime(MinecraftServer server) {
        return () -> server.overworld().getGameTime();
    }

    /** Runs first as one step, then the slice it starts. */
    static <A, T> ServerWork.Slice<T> then(Supplier<A> first, Function<A, ServerWork.Slice<T>> next) {
        return new ServerWork.Slice<>() {
            private ServerWork.Slice<T> inner;

            @Override
            public boolean step() {
                if (inner == null) {
                    inner = next.apply(first.get());
                    return true;
                }
                return inner.step();
            }

            @Override
            public T result() {
                return inner.result();
            }

            @Override
            public boolean stopEarly() {
                return inner != null && inner.stopEarly();
            }
        };
    }

    static <A, T> ServerWork.Slice<T> mapped(ServerWork.Slice<A> slice, Function<A, T> map) {
        return new ServerWork.Slice<>() {
            @Override
            public boolean step() {
                return slice.step();
            }

            @Override
            public T result() {
                return map.apply(slice.result());
            }

            @Override
            public boolean stopEarly() {
                return slice.stopEarly();
            }
        };
    }

    static <A, B> ServerWork.Slice<Map.Entry<A, B>> both(ServerWork.Slice<A> first, ServerWork.Slice<B> second) {
        return new ServerWork.Slice<>() {
            private boolean firstDone;

            @Override
            public boolean step() {
                if (!firstDone) {
                    firstDone = !first.step();
                    return true;
                }
                return second.step();
            }

            @Override
            public Map.Entry<A, B> result() {
                return Map.entry(first.result(), second.result());
            }

            /** Stopping inside the first slice stops the second before it starts, so both report what they missed. */
            @Override
            public boolean stopEarly() {
                if (firstDone) return second.stopEarly();
                return first.stopEarly() & second.stopEarly();
            }
        };
    }
}
