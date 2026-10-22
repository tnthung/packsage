package dev.packsage.world;

import dev.packsage.knowledge.Evidence;
import dev.packsage.knowledge.Toolbox;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerWorkTest {
    private final AtomicLong clock = new AtomicLong();

    /** A slice whose every step costs the given nanoseconds of server time and that finishes after the given steps. */
    private final class Costly implements ServerWork.Slice<Integer> {
        final String name;
        final long cost;
        final int total;
        final List<String> log;
        int steps;
        boolean stoppable;
        boolean stopped;

        Costly(String name, long cost, int total, List<String> log) {
            this.name = name;
            this.cost = cost;
            this.total = total;
            this.log = log;
        }

        @Override public boolean step() {
            clock.addAndGet(cost);
            log.add(name);
            return ++steps < total;
        }

        @Override public Integer result() { return stopped ? -steps : steps; }

        @Override public boolean stopEarly() {
            stopped = stoppable;
            return stoppable;
        }
    }

    @Test
    void workSpansTicksWhenTheBudgetIsSpent() {
        var work = new ServerWork(clock::get);
        var log = new ArrayList<String>();
        CompletableFuture<Integer> future = work.run(new Costly("a", 2_000_000, 7, log), () -> true).toCompletableFuture();
        assertTrue(log.isEmpty(), "nothing runs before a server tick");
        work.tick();
        assertEquals(3, log.size());
        work.tick();
        assertEquals(6, log.size());
        assertFalse(future.isDone());
        work.tick();
        assertEquals(7, future.join());
        assertFalse(work.hasWork());
    }

    @Test
    void oneTickSharesItsBudgetAcrossAllPendingJobs() {
        var work = new ServerWork(clock::get);
        var log = new ArrayList<String>();
        work.run(new Costly("a", 2_000_000, 100, log), () -> true);
        work.run(new Costly("b", 2_000_000, 100, log), () -> true);
        work.tick();
        assertEquals(List.of("a", "b", "a"), log);
        work.tick();
        assertEquals(List.of("a", "b", "a", "b", "a", "b"), log);
    }

    @Test
    void closingTheWorldFailsTheReadAndStopsScheduling() {
        var open = new AtomicBoolean(true);
        var work = new ServerWork(clock::get);
        var log = new ArrayList<String>();
        CompletableFuture<Integer> future = work.run(new Costly("a", 6_000_000, 100, log), open::get).toCompletableFuture();
        work.tick();
        assertEquals(1, log.size());
        open.set(false);
        work.tick();
        assertEquals(1, log.size());
        assertUnavailable(future);
        assertFalse(work.hasWork());
    }

    @Test
    void stoppingTheServerFailsPendingReadsAtOnce() {
        var work = new ServerWork(clock::get);
        var log = new ArrayList<String>();
        CompletableFuture<Integer> started = work.run(new Costly("a", 6_000_000, 100, log), () -> true).toCompletableFuture();
        work.tick();
        CompletableFuture<Integer> queued = work.run(new Costly("b", 6_000_000, 100, log), () -> true).toCompletableFuture();
        work.closeAll();
        assertUnavailable(started);
        assertUnavailable(queued);
        assertFalse(work.hasWork());
        work.tick();
        assertEquals(List.of("a"), log);
    }

    @Test
    void aReadSubmittedAfterTheWorldClosedFailsWithoutATick() {
        var work = new ServerWork(clock::get);
        CompletableFuture<Integer> future = work.run(new Costly("a", 1, 1, new ArrayList<>()), () -> false).toCompletableFuture();
        assertUnavailable(future);
    }

    @Test
    void aCallThatSpendsItsWorkBudgetStopsEarlyWhenItsSliceCan() {
        var work = new ServerWork(clock::get);
        var log = new ArrayList<String>();
        var stoppable = new Costly("a", 2_000_000, Integer.MAX_VALUE, log);
        stoppable.stoppable = true;
        var stubborn = new Costly("b", 2_000_000, 400, log);
        CompletableFuture<Integer> stopped = work.run(stoppable, () -> true).toCompletableFuture();
        CompletableFuture<Integer> finished = work.run(stubborn, () -> true).toCompletableFuture();
        for (var tick = 0; tick < 1_000 && !(stopped.isDone() && finished.isDone()); tick++) work.tick();
        var budgetSteps = (int) (ServerWork.CALL_BUDGET_NANOS / 2_000_000);
        assertEquals(-budgetSteps, stopped.join());
        assertEquals(400, finished.join());
    }

    @Test
    void anErrorFromASliceFailsItsReadAndLeavesOtherReadsRunning() {
        var work = new ServerWork(clock::get);
        CompletableFuture<Integer> broken = work.run(new ServerWork.Slice<Integer>() {
            @Override public boolean step() { throw new LinkageError("adapter class missing"); }

            @Override public Integer result() { return 0; }
        }, () -> true).toCompletableFuture();
        CompletableFuture<Integer> healthy = work.run(new Costly("a", 1, 2, new ArrayList<>()), () -> true).toCompletableFuture();
        work.tick();
        assertTrue(broken.isCompletedExceptionally());
        assertInstanceOf(LinkageError.class, broken.handle((value, failure) -> failure.getCause() == null ? failure : failure.getCause()).join());
        assertEquals(2, healthy.join());
    }

    private static void assertUnavailable(CompletableFuture<?> future) {
        assertTrue(future.isCompletedExceptionally());
        try {
            future.join();
        } catch (CompletionException e) {
            assertEquals(Toolbox.Code.UNAVAILABLE, assertInstanceOf(Toolbox.Failure.class, e.getCause()).code());
            return;
        }
        throw new AssertionError("expected failure");
    }

    @Test
    void chunkUnloadedBetweenSlicesIsCountedNotThrown() {
        var loaded = new boolean[] {true, true, true};
        var work = new ServerWork(() -> 0);
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
        var scan = work.run(slice, () -> true).toCompletableFuture();
        while (!scan.isDone()) work.tick();
        var result = scan.join();
        assertEquals(1, result.unloaded());
        assertEquals(2, result.read());
        var coverage = result.unloaded() == 0 ? Evidence.Coverage.COMPLETE : Evidence.Coverage.PARTIAL;
        assertEquals(Evidence.Coverage.PARTIAL, coverage);
    }
}
