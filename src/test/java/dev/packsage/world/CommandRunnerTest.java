package dev.packsage.world;

import com.google.gson.JsonParser;
import com.mojang.brigadier.CommandDispatcher;
import dev.packsage.chat.PackSageSettings;
import dev.packsage.knowledge.Evidence;
import dev.packsage.model.ModelBackend;
import dev.packsage.model.PackSageTools;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class CommandRunnerTest {
    private static final Evidence.Scope SCOPE = new Evidence.Scope("world", "resources", "player", "runtime", 1);

    @Test
    void riskyOnlyAllowsTheFixedReadOnlyCommandForms() {
        for (var command : List.of("help", "/help give", "list", "seed", "locate structure minecraft:village_plains",
                "data get entity @s", "scoreboard players get @s points", "time query daytime", "worldborder get",
                "difficulty", "gamerule keepInventory", "attribute @s minecraft:generic.max_health get",
                "attribute @s minecraft:generic.max_health base get 2", "attribute @s minecraft:generic.max_health modifier value get minecraft:test")) {
            assertTrue(CommandRunner.readOnly(command), command);
        }
        for (var command : List.of("give @s diamond", "execute run help", "data merge entity @s {}",
                "scoreboard players set @s points 1", "time set day", "worldborder set 1", "difficulty peaceful",
                "gamerule keepInventory true", "attribute @s minecraft:generic.max_health base set 1",
                "attribute @s minecraft:generic.max_health modifier remove minecraft:test", "other:help", "minecraft:help",
                "help\ngive @s diamond", "", "attribute @s[nbt={CustomName:'get'}] test base set 1")) {
            assertFalse(CommandRunner.readOnly(command), command);
        }
    }

    @Test
    void confirmationRunsExactlyOnceAndCancelAndTimeoutNeverExecute() throws Exception {
        var calls = new AtomicInteger();
        var clock = new AtomicLong();
        var runner = new CommandRunner(() -> PackSageSettings.CommandConfirmation.ALWAYS, (command, active) -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(new CommandRunner.Execution(true, "executed", 1, List.of("done"), false, false));
        }, clock::get);
        var run = runner.run("/give @s diamond", "request", SCOPE, () -> true).toCompletableFuture();
        assertFalse(run.isDone());
        assertEquals("/give @s diamond", runner.pending("request").getFirst().command());
        var id = runner.pending("request").getFirst().id();
        runner.decide(id, true);
        runner.decide(id, true);
        assertEquals(new Evidence.JsonBoolean(true), run.get().records().getFirst().payload().values().get("ran"));
        assertEquals(1, calls.get());
        var cancel = runner.run("/give @s stone", "request", SCOPE, () -> true).toCompletableFuture();
        runner.decide(runner.pending("request").getFirst().id(), false);
        assertTrue(cancel.get().detail().contains("cancelled"));
        var timeout = runner.run("help", "request", SCOPE, () -> true).toCompletableFuture();
        clock.set(CommandRunner.CONFIRMATION_NANOS);
        runner.tick();
        assertTrue(timeout.get().detail().contains("timed out"));
        assertTrue(runner.pending("request").isEmpty());
        assertEquals(1, calls.get());
    }

    @Test
    void worldCloseOrRequestCancellationInvalidatesPendingAndLateRun() throws Exception {
        var active = new AtomicBoolean(true);
        var calls = new AtomicInteger();
        var runner = new CommandRunner(() -> PackSageSettings.CommandConfirmation.ALWAYS, (command, valid) -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(new CommandRunner.Execution(true, "executed", 0, List.of(), false, false));
        }, System::nanoTime);
        var result = runner.run("help", "request", SCOPE, active::get).toCompletableFuture();
        var id = runner.pending("request").getFirst().id();
        active.set(false);
        runner.decide(id, true);
        assertTrue(result.get().detail().contains("cancelled"));
        assertEquals(0, calls.get());
    }

    @Test
    void riskyAndDirectSkipConfirmationOnlyForTheSelectedPolicy() throws Exception {
        var mode = new java.util.concurrent.atomic.AtomicReference<>(PackSageSettings.CommandConfirmation.RISKY);
        var calls = new AtomicInteger();
        var runner = new CommandRunner(mode::get, (command, active) -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(new CommandRunner.Execution(true, "executed", 1, List.of(), false, false));
        }, System::nanoTime);
        assertTrue(runner.run("time query daytime", "r", SCOPE, () -> true).toCompletableFuture().isDone());
        assertFalse(runner.run("time set day", "r", SCOPE, () -> true).toCompletableFuture().isDone());
        runner.decide(runner.pending("r").getFirst().id(), false);
        mode.set(PackSageSettings.CommandConfirmation.DIRECT);
        runner.run("time set day", "r", SCOPE, () -> true).toCompletableFuture().get();
        assertEquals(2, calls.get());
    }

    @Test
    void playerSourcePermissionsArePreservedWhenCapturingFeedback() {
        var dispatcher = new CommandDispatcher<CommandSourceStack>();
        var calls = new AtomicInteger();
        dispatcher.register(Commands.literal("op_only").requires(source -> source.hasPermission(2)).executes(context -> {
            calls.incrementAndGet();
            return 1;
        }));
        var source = new CommandSourceStack(CommandSource.NULL, Vec3.ZERO, Vec2.ZERO, null, 0, "player",
                Component.literal("player"), null, null);
        var result = CommandRunner.capture("op_only", source, (playerSource, command) -> {
            assertFalse(playerSource.hasPermission(2));
            try {
                dispatcher.execute(command, playerSource);
            } catch (com.mojang.brigadier.exceptions.CommandSyntaxException failure) {
                playerSource.sendFailure(Component.literal(failure.getMessage()));
            }
        });
        assertTrue(result.ran());
        assertEquals(0, result.success());
        assertFalse(result.feedback().isEmpty());
        assertEquals(0, calls.get());
    }

    @Test
    void commandResultsUseTheSharedEncoderAndBoundFeedback() throws Exception {
        var source = new CommandSourceStack(CommandSource.NULL, Vec3.ZERO, Vec2.ZERO, null, 0, "player",
                Component.literal("player"), null, null);
        var execution = CommandRunner.capture("help", source, (playerSource, command) -> {
            for (int i = 0; i < 100; i++) playerSource.sendSystemMessage(Component.literal("x".repeat(4000)));
            playerSource.callback().onResult(true, 7);
        });
        assertTrue(execution.truncated());
        assertEquals(32, execution.feedback().size());
        assertEquals(1024, execution.feedback().getFirst().length());
        var runner = new CommandRunner(() -> PackSageSettings.CommandConfirmation.DIRECT,
                (command, active) -> CompletableFuture.completedFuture(execution), System::nanoTime);
        var tools = new PackSageTools(request -> runner.run("help", "r", request.scope(), () -> true));
        var response = tools.execute(new ModelBackend.ToolCall("call", "run_command", "{\"command\":\"help\"}"), SCOPE)
                .toCompletableFuture().get();
        assertFalse(response.result().error());
        var json = JsonParser.parseString(response.result().serializedResult()).getAsJsonObject();
        var record = json.getAsJsonArray("records").get(0).getAsJsonObject();
        assertEquals("help", record.getAsJsonObject("payload").get("command").getAsString());
        assertEquals(7, record.getAsJsonObject("payload").get("success").getAsInt());
        assertEquals("PARTIAL", json.get("coverage").getAsString());
        assertTrue(record.has("reference"));
    }
}
