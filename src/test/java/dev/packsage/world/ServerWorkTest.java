package dev.packsage.world;

import dev.packsage.knowledge.Evidence;
import dev.packsage.knowledge.Toolbox;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerWorkTest {
    private final Queue<Runnable> queue = new ArrayDeque<>();
    private int submissions;

    private void submit(Runnable task) {
        submissions++;
        queue.add(task);
    }

    private void drain() {
        while (!queue.isEmpty()) queue.poll().run();
    }

    @Test
    void workSpansTicksWhenTheBudgetIsSpent() {
        var clock = new AtomicLong();
        var work = new ServerWork(this::submit, clock::get, () -> true);
        var steps = new int[1];
        CompletableFuture<Integer> future = work.run(new ServerWork.Slice<Integer>() {
            @Override public boolean step() {
                clock.addAndGet(2_000_000);
                return ++steps[0] < 7;
            }

            @Override public Integer result() { return steps[0]; }
        }).toCompletableFuture();
        drain();
        assertEquals(3, submissions);
        assertEquals(7, future.join());
    }

    @Test
    void closingTheWorldFailsTheReadAndStopsScheduling() {
        var open = new AtomicBoolean(true);
        var clock = new AtomicLong();
        var work = new ServerWork(this::submit, clock::get, open::get);
        var steps = new int[1];
        CompletableFuture<Integer> future = work.run(new ServerWork.Slice<Integer>() {
            @Override public boolean step() {
                clock.addAndGet(6_000_000);
                steps[0]++;
                return true;
            }

            @Override public Integer result() { return steps[0]; }
        }).toCompletableFuture();
        queue.poll().run();
        assertEquals(1, steps[0]);
        open.set(false);
        queue.poll().run();
        assertTrue(queue.isEmpty());
        assertEquals(1, steps[0]);
        var failure = assertInstanceOf(CompletionException.class, assertThrowsJoin(future)).getCause();
        assertEquals(Toolbox.Code.UNAVAILABLE, assertInstanceOf(Toolbox.Failure.class, failure).code());
    }

    private static Throwable assertThrowsJoin(CompletableFuture<?> future) {
        assertTrue(future.isCompletedExceptionally());
        try {
            future.join();
        } catch (RuntimeException e) {
            return e;
        }
        throw new AssertionError("expected failure");
    }

    @Test
    void chunkUnloadedBetweenSlicesIsCountedNotThrown() {
        var loaded = new boolean[] {true, true, true};
        var work = new ServerWork(this::submit, () -> 0, () -> true);
        record Scan(int unloaded, int read) {}
        var slice = new ServerWork.Slice<Scan>() {
            private int next;
            private int unloaded;
            private int read;

            @Override public boolean step() {
                if (loaded[next]) read++;
                else unloaded++;
                if (next == 0) loaded[1] = false;
                return ++next < loaded.length;
            }

            @Override public Scan result() { return new Scan(unloaded, read); }
        };
        var scan = work.run(slice).toCompletableFuture();
        drain();
        var result = scan.join();
        assertEquals(1, result.unloaded());
        assertEquals(2, result.read());
        var coverage = result.unloaded() == 0 ? Evidence.Coverage.COMPLETE : Evidence.Coverage.PARTIAL;
        assertEquals(Evidence.Coverage.PARTIAL, coverage);
        assertFalse(List.of(result).isEmpty());
    }
}
