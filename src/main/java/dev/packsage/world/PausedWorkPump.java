package dev.packsage.world;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Drives {@link ServerWork} while the integrated server is paused. A paused server skips its ticks, and with them
 * ServerTickEvent, but still runs queued tasks; while it is paused and reads are pending, each {@link #poll} from the
 * client tick queues one budgeted tick. At most one task is in flight per server, and a task that a stopped server
 * never ran blocks neither the next server nor, after {@link #reset}, the same one.
 */
public final class PausedWorkPump {
    public interface Server {
        /** Tells servers apart; a task in flight for one server does not hold back another. */
        Object identity();

        boolean isPaused();

        boolean isStopped();

        boolean isSameThread();

        void execute(Runnable task);
    }

    private final ServerWork work;
    private final AtomicReference<Object> inFlight = new AtomicReference<>();

    public PausedWorkPump(ServerWork work) {
        this.work = work;
    }

    /** Queues one ServerWork tick on a paused server with pending reads, unless one is already in flight for it. */
    public void poll(Server server) {
        if (!server.isPaused() || !work.hasWork()) return;
        var identity = server.identity();
        var current = inFlight.get();
        if (current == identity || !inFlight.compareAndSet(current, identity)) return;
        server.execute(() -> {
            inFlight.compareAndSet(identity, null);
            // A stopping server may run leftover tasks inline on another thread; only tick on its own live thread.
            if (!server.isStopped() && server.isSameThread() && server.isPaused()) work.tick();
        });
    }

    /** Forgets the task in flight, which a stopping server may never run. */
    public void reset() {
        inFlight.set(null);
    }
}
