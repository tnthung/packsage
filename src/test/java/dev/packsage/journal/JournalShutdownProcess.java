package dev.packsage.journal;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** A real System.exit in a child JVM, so the test runner itself keeps running. */
public final class JournalShutdownProcess {
    public static void main(String[] args) throws Exception {
        var entered = new CountDownLatch(1);
        var exiting = new CountDownLatch(1);
        var journal = new Journal(Runnable::run, file -> {
            entered.countDown();
            try {
                exiting.await();
                if (args[1].equals("stuck")) {
                    new CountDownLatch(1).await();
                }
                Thread.sleep(500);
            } catch (InterruptedException e) {
                throw new SQLException(e);
            }
            return JournalStore.open(file);
        });
        journal.open(Path.of(args[0]), () -> 0);
        if (!entered.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("writer did not start");
        }
        journal.record(JournalEvents.place(new JournalEvents.Stamp(0, System.currentTimeMillis(), "minecraft:overworld"),
                "player:A", null, "minecraft:stone", 1, 64, 2));
        journal.close();
        exiting.countDown();
        System.exit(0);
    }
}
