package dev.packsage.world;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PausedWorkPumpTest {
    /** A paused server that keeps the tasks it is given, or runs them inline when asked to. */
    private static final class FakeServer implements PausedWorkPump.Server {
        final List<Runnable> queued = new ArrayList<>();
        boolean paused = true;
        boolean stopped;
        boolean sameThread = true;
        boolean runInline;

        @Override public Object identity() { return this; }
        @Override public boolean isPaused() { return paused; }
        @Override public boolean isStopped() { return stopped; }
        @Override public boolean isSameThread() { return sameThread; }

        @Override public void execute(Runnable task) {
            queued.add(task);
            if (runInline) task.run();
        }
    }

    /** A slice that counts its steps and finishes after the first. */
    private static final class Counting implements ServerWork.Slice<Integer> {
        int steps;

        @Override public boolean step() {
            steps++;
            return false;
        }

        @Override public Integer result() { return steps; }
    }

    private final ServerWork work = new ServerWork(System::nanoTime);
    private final PausedWorkPump pump = new PausedWorkPump(work);

    @Test
    void aTaskThatNeverRanDoesNotBlockTheNextServer() {
        work.run(new Counting(), () -> true);
        var a = new FakeServer();
        var b = new FakeServer();
        pump.poll(a);
        pump.poll(a);
        assertEquals(1, a.queued.size(), "one task in flight for A");
        pump.poll(b);
        assertEquals(1, b.queued.size(), "A's dropped task does not block B");
    }

    @Test
    void resetClearsATaskThatNeverRan() {
        work.run(new Counting(), () -> true);
        var a = new FakeServer();
        pump.poll(a);
        pump.reset();
        pump.poll(a);
        assertEquals(2, a.queued.size());
    }

    @Test
    void aTaskRunInlineOffTheServerThreadDoesNotTick() {
        var offThread = new Counting();
        work.run(offThread, () -> true);
        var a = new FakeServer();
        a.runInline = true;
        a.sameThread = false;
        pump.poll(a);
        assertEquals(1, a.queued.size());
        assertEquals(0, offThread.steps, "no tick off the server thread");

        var b = new FakeServer();
        b.runInline = true;
        b.stopped = true;
        pump.poll(b);
        assertEquals(1, b.queued.size());
        assertEquals(0, offThread.steps, "no tick on a stopped server");
        assertTrue(work.hasWork());
    }

    @Test
    void aPausedServerTicksQueuedWorkOnItsThread() {
        var slice = new Counting();
        CompletableFuture<Integer> future = work.run(slice, () -> true).toCompletableFuture();
        var a = new FakeServer();
        pump.poll(a);
        assertEquals(1, a.queued.size());
        a.queued.getFirst().run();
        assertEquals(1, slice.steps);
        assertEquals(1, future.join());
        assertFalse(work.hasWork());
        pump.poll(a);
        assertEquals(1, a.queued.size(), "nothing queued once the work is done");
    }
}
