package dev.packsage.world.minecraft;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.mojang.brigadier.arguments.IntegerArgumentType.integer;
import static com.mojang.brigadier.arguments.StringArgumentType.word;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class CommandReaderTest {
    private final CommandDispatcher<Integer> dispatcher = new CommandDispatcher<>();

    CommandReaderTest() {
        dispatcher.register(LiteralArgumentBuilder.<Integer>literal("fill").requires(level -> level >= 2)
                .then(RequiredArgumentBuilder.<Integer, Integer>argument("from", integer())
                        .then(RequiredArgumentBuilder.<Integer, Integer>argument("to", integer())
                                .then(RequiredArgumentBuilder.<Integer, String>argument("block", word()).executes(context -> fail("executed fill"))
                                        .then(LiteralArgumentBuilder.<Integer>literal("replace")
                                                .then(RequiredArgumentBuilder.<Integer, String>argument("filter", word())
                                                        .executes(context -> fail("executed replace"))))))));
        dispatcher.register(LiteralArgumentBuilder.<Integer>literal("say")
                .then(RequiredArgumentBuilder.<Integer, String>argument("message", word()).executes(context -> fail("executed say"))));
        dispatcher.register(LiteralArgumentBuilder.<Integer>literal("gamerule").requires(level -> level >= 2)
                .then(LiteralArgumentBuilder.<Integer>literal("keepInventory")
                        .then(RequiredArgumentBuilder.<Integer, String>argument("value", word()).executes(context -> fail("executed gamerule")))));
        var teleport = dispatcher.register(LiteralArgumentBuilder.<Integer>literal("teleport")
                .then(RequiredArgumentBuilder.<Integer, String>argument("target", word()).executes(context -> fail("executed teleport"))));
        dispatcher.register(LiteralArgumentBuilder.<Integer>literal("tp").redirect(teleport));
    }

    @Test
    void searchMatchesNamesAndUsage() {
        var reader = new CommandReader<>(dispatcher, 0, type -> type.getClass().getSimpleName());
        assertEquals(List.of("fill"), reader.search("FILL").stream().map(CommandReader.Summary::name).toList());
        assertEquals(List.of("fill"), reader.search("FILTER").stream().map(CommandReader.Summary::name).toList());
        assertEquals(List.of("fill", "gamerule", "say", "teleport", "tp"),
                reader.search(null).stream().map(CommandReader.Summary::name).toList());
        assertEquals(List.of("<from> <to> <block>", "<from> <to> <block> replace <filter>"), reader.search("fill").getFirst().usage());
    }

    @Test
    void readDescribesCommandsThePlayerCannotRun() {
        var detail = new CommandReader<>(dispatcher, 0, type -> type.getClass().getSimpleName()).read("fill");
        assertFalse(detail.canUse());
        assertFalse(detail.usage().isEmpty());
        assertTrue(detail.playerUsage().isEmpty());
        assertEquals(List.of("from", "to", "block", "filter"), detail.arguments().stream().map(CommandReader.Argument::name).toList());
        assertEquals(List.of("IntegerArgumentType", "IntegerArgumentType", "StringArgumentType", "StringArgumentType"),
                detail.arguments().stream().map(CommandReader.Argument::type).toList());
        assertTrue(detail.arguments().stream().noneMatch(CommandReader.Argument::canUse));
        assertEquals("fill from to block replace filter", detail.arguments().getLast().path());
        assertFalse(detail.arguments().getFirst().examples().isEmpty());
    }

    @Test
    void readFollowsSubcommandPathsAndReportsRedirects() {
        var reader = new CommandReader<>(dispatcher, 2, type -> type.getClass().getSimpleName());
        var gamerule = reader.read("/gamerule keepInventory");
        assertEquals("gamerule keepInventory", gamerule.path());
        assertEquals(List.of("value"), gamerule.arguments().stream().map(CommandReader.Argument::name).toList());
        assertTrue(reader.read("fill").usage().stream().anyMatch(usage -> usage.contains("replace")));
        assertFalse(reader.read("fill").playerUsage().isEmpty());
        assertEquals("teleport", reader.read("tp").redirect());
        assertTrue(reader.read("tp").arguments().isEmpty());
        assertNull(reader.read("nosuch"));
        assertNull(reader.read("gamerule nosuch"));
        assertFalse(new CommandReader<>(dispatcher, 0, type -> "word").read("gamerule keepInventory").canUse());
    }

    @Test
    void argumentWalkIsBoundedAndCanContinue() {
        var tail = RequiredArgumentBuilder.<Integer, Integer>argument("arg299", integer()).executes(context -> fail("executed chain"));
        for (int i = 298; i >= 0; i--) tail = RequiredArgumentBuilder.<Integer, Integer>argument("arg" + i, integer()).then(tail);
        dispatcher.register(LiteralArgumentBuilder.<Integer>literal("chain").then(tail));
        var reader = new CommandReader<>(dispatcher, 0, type -> "integer");
        var first = reader.read("chain");
        assertEquals(256, first.arguments().size());
        assertTrue(first.argumentsBounded());
        var rest = reader.read("chain", 256);
        assertEquals(44, rest.arguments().size());
        assertEquals("arg256", rest.arguments().getFirst().name());
        assertEquals("arg299", rest.arguments().getLast().name());
        assertFalse(rest.argumentsBounded());
    }

    @Test
    void readingNeverExecutes() {
        var reader = new CommandReader<>(dispatcher, 4, type -> type.getClass().getSimpleName());
        for (var command : reader.search(null)) reader.read(command.name());
    }

    @Test
    void snapshotPreservesRedirectUsageForTargetsOutsideTheRoot() {
        var target = LiteralArgumentBuilder.<Integer>literal("external").build();
        dispatcher.register(LiteralArgumentBuilder.<Integer>literal("alias").redirect(target));
        dispatcher.register(LiteralArgumentBuilder.<Integer>literal("again").redirect(dispatcher.getRoot()));
        var reader = new CommandReader<>(dispatcher, 0, type -> "word");
        var snapshot = reader.snapshot();
        while (snapshot.step()) { }
        assertEquals(reader.read("alias"), snapshot.result().read("alias"));
        assertEquals(reader.read("again"), snapshot.result().read("again"));
        assertNull(reader.read("alias").redirect());
        assertNull(snapshot.result().read("alias").redirect());
        assertNull(reader.search("alias").getFirst().redirect());
        assertNull(snapshot.result().search("alias").getFirst().redirect());
        assertEquals("", snapshot.result().read("again").redirect());
    }

    @Test
    void snapshotPacesLiveReadsAndNeverConsultsThemAfterwards() {
        var readingLiveState = new AtomicBoolean(true);
        dispatcher.register(LiteralArgumentBuilder.<Integer>literal("live")
                .requires(level -> {
                    assertTrue(readingLiveState.get(), "worker consulted a live requirement");
                    return level >= 2;
                }).executes(context -> fail("executed live")));
        var snapshot = new CommandReader<>(dispatcher, 0, type -> {
            assertTrue(readingLiveState.get(), "worker consulted a live argument type");
            return type.getClass().getSimpleName();
        }).snapshot();
        assertTrue(snapshot.step());
        assertTrue(snapshot.stopEarly());
        assertNull(snapshot.result());
        while (snapshot.step()) { }
        readingLiveState.set(false);
        dispatcher.register(LiteralArgumentBuilder.<Integer>literal("late"));
        var reader = snapshot.result();
        assertNull(reader.read("late"));
        assertFalse(reader.read("live").canUse());
        assertFalse(reader.read("fill").canUse());
        assertEquals("teleport", reader.read("tp").redirect());
        assertEquals(4, reader.read("fill").arguments().size());
        assertEquals(6, reader.search(null).size());
    }

    @Test
    void snapshotKeepsArgumentsWhoseExamplesThrow() {
        class BrokenExamples implements com.mojang.brigadier.arguments.ArgumentType<String> {
            public String parse(com.mojang.brigadier.StringReader reader) { return reader.readUnquotedString(); }
            public java.util.Collection<String> getExamples() { throw new IllegalStateException("broken examples"); }
        }
        dispatcher.register(LiteralArgumentBuilder.<Integer>literal("broken")
                .then(RequiredArgumentBuilder.<Integer, String>argument("value", new BrokenExamples())
                        .executes(context -> fail("executed broken"))));
        var snapshot = new CommandReader<>(dispatcher, 0, type -> type.getClass().getSimpleName()).snapshot();
        while (snapshot.step()) { }
        var arguments = snapshot.result().read("broken").arguments();
        assertEquals(1, arguments.size());
        assertEquals("BrokenExamples", arguments.getFirst().type());
        assertTrue(arguments.getFirst().examples().isEmpty());
        assertEquals(4, snapshot.result().read("fill").arguments().size());
    }
}
