package dev.packsage.debug;

import com.google.gson.JsonObject;
import dev.packsage.chat.PackSageSettings;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public final class DebugBridge implements AutoCloseable {
    private final Path tokenFile;
    private final int port;
    private final DebugRouter.Game game;
    private final Consumer<String> log;
    private final Set<Socket> connections = ConcurrentHashMap.newKeySet();
    private ServerSocket listener;
    private ThreadPoolExecutor workers;
    private boolean published;

    public DebugBridge(Path gameDirectory, int port, DebugRouter.Game game, Consumer<String> log) {
        tokenFile = gameDirectory.resolve("packsage/debug-bridge.json");
        this.port = port;
        this.game = game;
        this.log = log;
    }

    public static boolean enabled(PackSageSettings settings) {
        return settings.debugBridge() || Boolean.getBoolean("packsage.debugBridge");
    }

    public synchronized boolean start() {
        if (listener != null) return false;
        Path temporary = null;
        try {
            listener = new ServerSocket(port, 16, InetAddress.getByName("127.0.0.1"));
            byte[] random = new byte[32];
            new SecureRandom().nextBytes(random);
            String token = HexFormat.of().formatHex(random);
            var metadata = new JsonObject();
            metadata.addProperty("port", listener.getLocalPort());
            metadata.addProperty("token", token);
            metadata.addProperty("pid", ProcessHandle.current().pid());
            Files.createDirectories(tokenFile.getParent());
            temporary = Files.createTempFile(tokenFile.getParent(), "debug-bridge-", ".tmp");
            Files.writeString(temporary, metadata.toString(), StandardCharsets.UTF_8);
            Files.move(temporary, tokenFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            published = true;
            var router = new DebugRouter(token, game);
            workers = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(8), runnable -> {
                var thread = new Thread(runnable, "packsage-debug-request");
                thread.setDaemon(true);
                return thread;
            });
            ServerSocket accepting = listener;
            var thread = new Thread(() -> {
                while (!accepting.isClosed()) {
                    try {
                        Socket socket = accepting.accept();
                        socket.setSoTimeout(10_000);
                        connections.add(socket);
                        try {
                            workers.execute(() -> handle(socket, router));
                        } catch (java.util.concurrent.RejectedExecutionException exception) {
                            connections.remove(socket);
                            socket.close();
                        }
                    } catch (IOException exception) {
                        if (!accepting.isClosed()) close();
                    }
                }
            }, "packsage-debug-bridge");
            thread.setDaemon(true);
            thread.start();
            log.accept("PackSage debug bridge started port=" + listener.getLocalPort() + " tokenFile=" + tokenFile);
            return true;
        } catch (IOException | RuntimeException exception) {
            if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
            if (listener != null) try { listener.close(); } catch (IOException ignored) { }
            listener = null;
            if (published) try { Files.deleteIfExists(tokenFile); } catch (IOException ignored) { }
            published = false;
            log.accept("PackSage debug bridge refused port=" + port + ": " + exception.getClass().getSimpleName());
            return false;
        }
    }

    private void handle(Socket socket, DebugRouter router) {
        try (socket) {
            DebugHttp.Response response;
            try {
                response = router.route(DebugHttp.parse(socket.getInputStream()));
            } catch (DebugHttp.Failure failure) {
                response = DebugHttp.error(failure.status, failure.getMessage());
            } catch (java.net.SocketTimeoutException exception) {
                response = DebugHttp.error(408, "Request read timeout");
            }
            byte[] body = response.body().toString().getBytes(StandardCharsets.UTF_8);
            socket.getOutputStream().write(("HTTP/1.1 " + response.status() + " Result\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: "
                    + body.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().write(body);
        } catch (IOException ignored) {
        } finally {
            connections.remove(socket);
        }
    }

    @Override
    public synchronized void close() {
        if (listener == null) return;
        try { listener.close(); } catch (IOException ignored) { }
        if (workers != null) workers.shutdownNow();
        for (Socket socket : connections) try { socket.close(); } catch (IOException ignored) { }
        connections.clear();
        try { if (published) Files.deleteIfExists(tokenFile); } catch (IOException exception) {
            log.accept("PackSage debug bridge stopped; token file could not be removed: " + tokenFile);
            listener = null;
            return;
        }
        published = false;
        listener = null;
        log.accept("PackSage debug bridge stopped");
    }
}
