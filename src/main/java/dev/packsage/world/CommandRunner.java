package dev.packsage.world;

import dev.packsage.chat.PackSageSettings;
import dev.packsage.knowledge.Evidence;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** One player-authorized command per call. Confirmation never occupies a worker or the server thread. */
public final class CommandRunner {
    public static final long CONFIRMATION_NANOS = 60_000_000_000L;

    public record Confirmation(String id, String requestId, String command) { }

    public record Execution(boolean ran, String reason, Integer success, List<String> feedback, boolean truncated, boolean remote) {
        public Execution { feedback = List.copyOf(feedback); }
    }

    @FunctionalInterface
    public interface Executor {
        CompletionStage<Execution> execute(String command, BooleanSupplier active);
    }

    private record Pending(Confirmation confirmation, BooleanSupplier active, long started, CompletableFuture<Execution> decision) { }

    private final Supplier<PackSageSettings.CommandConfirmation> setting;
    private final Executor executor;
    private final LongSupplier clock;
    private final Map<String, Pending> pending = new LinkedHashMap<>();

    public CommandRunner(Supplier<PackSageSettings.CommandConfirmation> setting, Executor executor, LongSupplier clock) {
        this.setting = setting;
        this.executor = executor;
        this.clock = clock;
    }

    public static CommandRunner forMinecraft(Minecraft client, PackSageSettings settings) {
        return new CommandRunner(settings::commandConfirmation, (command, active) -> client.submit(() -> {
            if (!active.getAsBoolean() || client.player == null || client.level == null || client.getConnection() == null) {
                return CompletableFuture.completedFuture(new Execution(false, "request cancelled or world closed", null, List.of(), false, false));
            }
            var server = client.getSingleplayerServer();
            if (server == null) {
                client.getConnection().sendCommand(command.startsWith("/") ? command.substring(1) : command);
                return CompletableFuture.completedFuture(new Execution(true,
                        "sent through the client connection; only the server's feedback in chat is available; success is unknown",
                        null, List.of(), false, true));
            }
            var playerId = client.player.getUUID();
            return server.submit(() -> {
                if (!active.getAsBoolean() || server.isStopped()) return new Execution(false,
                        "request cancelled or world closed", null, List.of(), false, false);
                // ServerWork is pumped while paused for reads. Commands deliberately do not use that queue.
                if (server.isPaused()) return new Execution(false, "world is paused; unpause before running the command",
                        null, List.of(), false, false);
                var player = server.getPlayerList().getPlayer(playerId);
                if (player == null) return new Execution(false, "player left the world", null, List.of(), false, false);
                return capture(command, player.createCommandSourceStack(), server.getCommands()::performPrefixedCommand);
            });
        }).thenCompose(stage -> stage), System::nanoTime);
    }

    public CompletionStage<Evidence.Result> run(String command, String requestId, Evidence.Scope scope, BooleanSupplier active) {
        if (command == null || command.isBlank() || command.length() > 2048 || !command.equals(command.strip())
                || command.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("give one command without surrounding whitespace or control characters (up to 2048 characters)");
        if (!active.getAsBoolean()) return CompletableFuture.completedFuture(result(scope, command,
                new Execution(false, "request cancelled or world closed", null, List.of(), false, false)));
        var mode = setting.get();
        CompletionStage<Execution> execution;
        if (mode == PackSageSettings.CommandConfirmation.ALWAYS || mode == PackSageSettings.CommandConfirmation.RISKY && !readOnly(command)) {
            var confirmation = new Confirmation(UUID.randomUUID().toString(), requestId, command);
            var decision = new CompletableFuture<Execution>();
            synchronized (pending) {
                pending.put(confirmation.id(), new Pending(confirmation, active, clock.getAsLong(), decision));
            }
            execution = decision;
        } else execution = executor.execute(command, active);
        return execution.thenApply(value -> result(scope, command, value));
    }

    public List<Confirmation> pending(String requestId) {
        synchronized (pending) {
            return pending.values().stream().map(Pending::confirmation).filter(value -> value.requestId().equals(requestId)).toList();
        }
    }

    public void decide(String id, boolean run) {
        Pending value;
        synchronized (pending) { value = pending.remove(id); }
        if (value == null) return;
        if (!value.active().getAsBoolean()) value.decision().complete(new Execution(false, "request cancelled or world closed", null, List.of(), false, false));
        else if (clock.getAsLong() - value.started() >= CONFIRMATION_NANOS) value.decision().complete(new Execution(false,
                "confirmation timed out after 60 seconds; player did not run the command", null, List.of(), false, false));
        else if (!run) value.decision().complete(new Execution(false, "player cancelled; command not run", null, List.of(), false, false));
        else {
            try {
                executor.execute(value.confirmation().command(), value.active()).whenComplete((result, failure) -> {
                    if (failure == null) value.decision().complete(result);
                    else value.decision().completeExceptionally(failure);
                });
            } catch (RuntimeException failure) { value.decision().completeExceptionally(failure); }
        }
    }

    /** Client tick, including when the chat is closed: expire decisions and discard cancelled requests. */
    public void tick() {
        List<String> expired;
        synchronized (pending) {
            expired = pending.values().stream().filter(value -> !value.active().getAsBoolean()
                    || clock.getAsLong() - value.started() >= CONFIRMATION_NANOS).map(value -> value.confirmation().id()).toList();
        }
        expired.forEach(id -> decide(id, false));
    }

    /** Conservative token parsing: selectors with spaces or quotes fall back to confirmation. Namespaced aliases ask. */
    public static boolean readOnly(String command) {
        if (command == null || command.isBlank() || command.chars().anyMatch(Character::isISOControl)) return false;
        var tokens = (command.startsWith("/") ? command.substring(1) : command).split(" +");
        return switch (tokens[0]) {
            case "help", "list", "seed", "locate" -> true;
            case "data" -> tokens.length >= 2 && tokens[1].equals("get");
            case "scoreboard" -> tokens.length >= 3 && tokens[1].equals("players") && tokens[2].equals("get");
            case "time" -> tokens.length >= 2 && tokens[1].equals("query");
            case "worldborder" -> tokens.length == 2 && tokens[1].equals("get");
            case "difficulty" -> tokens.length == 1;
            case "gamerule" -> tokens.length == 2;
            case "attribute" -> tokens.length >= 4 && tokens[1].matches("[a-zA-Z0-9_@.,=!:#\\[\\]-]+")
                    && (tokens[3].equals("get") && tokens.length <= 5
                    || tokens.length >= 5 && tokens[3].equals("base") && tokens[4].equals("get") && tokens.length <= 6
                    || tokens.length >= 7 && tokens[3].equals("modifier") && tokens[4].equals("value")
                    && tokens[5].equals("get") && tokens.length <= 8);
            default -> false;
        };
    }

    /** Changes only output/callback, retaining the player's entity, world, position and permission level. Server thread. */
    static Execution capture(String command, CommandSourceStack source, BiConsumer<CommandSourceStack, String> execute) {
        var feedback = new ArrayList<String>();
        boolean[] truncated = { false };
        int[] success = { 0 };
        execute.accept(source.withSource(new CommandSource() {
            @Override public void sendSystemMessage(Component message) {
                if (feedback.size() == 32) { truncated[0] = true; return; }
                var text = message.getString();
                if (text.length() > 1024) { text = text.substring(0, 1024); truncated[0] = true; }
                feedback.add(text);
            }
            @Override public boolean acceptsSuccess() { return true; }
            @Override public boolean acceptsFailure() { return true; }
            @Override public boolean shouldInformAdmins() { return false; }
        }).withCallback((ok, value) -> success[0] = value), command);
        return new Execution(true, "executed as the player", success[0], feedback, truncated[0], false);
    }

    private static Evidence.Result result(Evidence.Scope scope, String command, Execution execution) {
        var payload = new LinkedHashMap<String, Evidence.JsonValue>();
        payload.put("command", new Evidence.JsonString(command));
        payload.put("ran", new Evidence.JsonBoolean(execution.ran()));
        payload.put("reason", new Evidence.JsonString(execution.reason()));
        payload.put("success", execution.success() == null ? new Evidence.JsonNull() : WorldTools.number(execution.success()));
        payload.put("feedback", new Evidence.JsonArray(execution.feedback().stream().map(line -> (Evidence.JsonValue) new Evidence.JsonString(line)).toList()));
        payload.put("feedbackTruncated", new Evidence.JsonBoolean(execution.truncated()));
        var coverage = execution.truncated() || execution.remote() ? Evidence.Coverage.PARTIAL : Evidence.Coverage.COMPLETE;
        var source = new Evidence.Source(scope, "command", "1", "run|" + UUID.randomUUID(), scope.requestGeneration(), Instant.now(),
                Evidence.Activity.ACTIVE, coverage, execution.remote() ? Evidence.Authority.UNKNOWN : Evidence.Authority.SERVER_READ, List.of());
        return new Evidence.Result(List.of(new Evidence.Record(source, Evidence.RecordKind.OTHER, "Command: " + command,
                new Evidence.JsonObject(payload), List.of())), coverage,
                coverage == Evidence.Coverage.COMPLETE ? Evidence.Outcome.COMPLETE : Evidence.Outcome.PARTIAL, null,
                execution.reason() + (execution.truncated() ? "; feedback bounded to 32 lines of 1024 characters" : ""));
    }
}
