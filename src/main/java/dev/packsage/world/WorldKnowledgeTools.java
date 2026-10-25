package dev.packsage.world;

import dev.packsage.knowledge.Evidence;
import dev.packsage.knowledge.Toolbox.Code;
import dev.packsage.knowledge.Toolbox.Failure;
import dev.packsage.model.PackSageTools;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * The world-knowledge tools: loot tables, the nearest biome or structure, and the command tree. {@link WorldTools}
 * delegates these tool names and the {@code loot|} and {@code command|} reread handles here, and lends its server
 * access, pacing, cursors and record helpers.
 */
final class WorldKnowledgeTools {
    static final Set<String> TOOLS = Set.of("read_loot", "locate", "search_commands", "read_command");

    private final WorldTools calls;

    WorldKnowledgeTools(WorldTools calls) {
        this.calls = calls;
    }

    CompletionStage<Evidence.Result> handle(PackSageTools.ReadRequest request) {
        return switch (request.tool()) {
            case "read_loot" -> readLoot(request);
            case "locate" -> locate(request);
            case "search_commands" -> searchCommands(request);
            case "read_command" -> readCommand(request);
            default -> CompletableFuture.failedFuture(new Failure(Code.NOT_HANDLED, request.tool()));
        };
    }

    /** Reads one loot or command handle fresh. */
    CompletionStage<Evidence.Record> reread(String handle, Evidence.Scope scope) {
        if (!handle.startsWith("loot|") && !handle.startsWith("command|")) {
            throw new Failure(Code.INVALID_REFERENCE, "only loot and command records are reread here");
        }
        calls.server();
        return pending();
    }

    private CompletionStage<Evidence.Result> readLoot(PackSageTools.ReadRequest request) {
        exactlyOne(request, List.of("table", "block", "entity", "place"));
        calls.server();
        return pending();
    }

    private CompletionStage<Evidence.Result> locate(PackSageTools.ReadRequest request) {
        exactlyOne(request, List.of("biome", "structure"));
        calls.server();
        return pending();
    }

    private CompletionStage<Evidence.Result> searchCommands(PackSageTools.ReadRequest request) {
        calls.server();
        return pending();
    }

    private CompletionStage<Evidence.Result> readCommand(PackSageTools.ReadRequest request) {
        if (WorldTools.requiredText(request, "name").isBlank()) throw WorldTools.invalid("name must not be blank");
        calls.server();
        return pending();
    }

    private static void exactlyOne(PackSageTools.ReadRequest request, List<String> names) {
        if (names.stream().filter(name -> WorldTools.argument(request, name) != null).count() != 1) {
            throw WorldTools.invalid("give exactly one of " + String.join(", ", names));
        }
    }

    private static <T> CompletionStage<T> pending() {
        return CompletableFuture.failedFuture(new Failure(Code.UNAVAILABLE, "not implemented yet"));
    }
}
