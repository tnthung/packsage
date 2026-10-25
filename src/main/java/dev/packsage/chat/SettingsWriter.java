package dev.packsage.chat;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

public final class SettingsWriter {
    public record Snapshot(long generation, Map<String, String> properties) {
        public Snapshot { properties = Map.copyOf(properties); }
    }

    private final AtomicLong generations = new AtomicLong();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        var thread = new Thread(runnable, "packsage-settings-writer");
        thread.setDaemon(true);
        return thread;
    });
    private final BiConsumer<Path, Snapshot> persistence;
    private volatile Snapshot latest;
    private volatile Path latestPath;
    private long writtenGeneration = -1;
    private boolean closed;

    public SettingsWriter() {
        this(SettingsWriter::write);
    }

    public SettingsWriter(BiConsumer<Path, Snapshot> persistence) {
        this.persistence = persistence;
    }

    long nextGeneration() {
        return generations.incrementAndGet();
    }

    synchronized CompletableFuture<Void> submit(Path path, Snapshot snapshot) {
        if (closed) return CompletableFuture.failedFuture(new IllegalStateException("settings writer is closed"));
        if (latest != null && (snapshot.generation() < latest.generation()
                || snapshot.generation() == latest.generation() && !snapshot.properties().equals(latest.properties()))) {
            return CompletableFuture.completedFuture(null);
        }
        if (latest == null || snapshot.generation() > latest.generation()) {
            latest = snapshot;
        }
        latestPath = path;
        var result = new CompletableFuture<Void>();
        executor.execute(() -> {
            try {
                if (snapshot.generation() < latest.generation()
                        || snapshot.generation() == latest.generation() && !snapshot.properties().equals(latest.properties())
                        || snapshot.generation() < writtenGeneration) {
                    result.complete(null);
                    return;
                }
                persistence.accept(path, snapshot);
                writtenGeneration = snapshot.generation();
                result.complete(null);
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
        });
        return result;
    }

    synchronized CompletableFuture<Void> flushAndClose(Path path, Snapshot snapshot) {
        if (closed) return CompletableFuture.failedFuture(new IllegalStateException("settings writer is closed"));
        if (latest == null || snapshot.generation() > latest.generation()) {
            latest = snapshot;
            latestPath = path;
        } else if (snapshot.generation() == latest.generation()
                && snapshot.properties().equals(latest.properties())) {
            latestPath = path;
        }
        Path target = latestPath == null ? path : latestPath;
        Snapshot finalSnapshot = latest;
        var result = new CompletableFuture<Void>();
        executor.execute(() -> {
            try {
                persistence.accept(target, finalSnapshot);
                writtenGeneration = finalSnapshot.generation();
                result.complete(null);
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
        });
        closed = true;
        executor.shutdown();
        return result;
    }

    public static void write(Path path, Snapshot snapshot) {
        Properties properties = new Properties();
        properties.putAll(snapshot.properties());
        try {
            if (path.getParent() != null) Files.createDirectories(path.getParent());
            Path absolute = path.toAbsolutePath();
            var temporary = Files.createTempFile(absolute.getParent(), "packsage-", ".properties.tmp");
            try {
                try (OutputStream output = Files.newOutputStream(temporary)) {
                    properties.store(output, "PackSage settings");
                }
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("could not save PackSage settings", exception);
        }
    }
}
