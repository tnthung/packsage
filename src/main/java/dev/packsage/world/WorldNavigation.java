package dev.packsage.world;

import dev.packsage.client.highlight.WorldHighlighter;
import dev.packsage.knowledge.Evidence;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.TickTask;

import java.util.UUID;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Opens {@code world-block|<place>}, {@code world-entity|<uuid>} and {@code world-place|<place>} targets as 30-second
 * highlights. The target id is the stable destination: a saved link resolves into the current scope without re-reading
 * evidence, and opening checks that the block or mob still exists before anything is drawn. A place is only coordinates,
 * usually in unloaded or ungenerated chunks, so it is marked without any check.
 */
public final class WorldNavigation {
    public static final long HIGHLIGHT_MILLIS = 30_000;
    private static final String BLOCK = "world-block|";
    private static final String ENTITY = "world-entity|";
    private static final String PLACE = "world-place|";
    private static final long CHECK_MILLIS = 1_000;
    private static final String NO_SERVER = "world highlights need an integrated singleplayer server";

    sealed interface Destination {
        record Block(BlockPlace place) implements Destination {
        }

        record Entity(UUID id) implements Destination {
        }

        record Place(BlockPlace place) implements Destination {
        }
    }

    /** The player's side: where the player is and what gets drawn. */
    interface Client {
        /** The player's current dimension, or null outside a world. */
        String dimension();

        /** Whether the client has the entity loaded, so it can glow. */
        boolean tracks(UUID id);

        void highlightBlock(BlockPlace place, long untilMillis);

        void highlightEntity(UUID id, long untilMillis);

        void highlightPlace(BlockPlace place, long untilMillis);
    }

    private final Function<BlockPlace, CompletionStage<Boolean>> blockExists;
    private final Function<UUID, CompletionStage<BlockPlace>> entityPlace;
    private final Client client;
    private final Map<Evidence.NavigationTarget, CompletableFuture<Check>> checks = new LinkedHashMap<>();

    private record Check(Destination destination, Boolean blockExists, BlockPlace entityPlace, String failure) {
    }

    WorldNavigation(Function<BlockPlace, Boolean> blockExists, Function<UUID, BlockPlace> entityPlace, Client client) {
        this.blockExists = place -> CompletableFuture.completedFuture(blockExists.apply(place));
        this.entityPlace = id -> CompletableFuture.completedFuture(entityPlace.apply(id));
        this.client = client;
    }

    WorldNavigation(Supplier<MinecraftServer> servers, Client client) {
        this.blockExists = blockLookup(servers);
        this.entityPlace = entityLookup(servers);
        this.client = client;
    }

    static String blockId(BlockPlace place) {
        return BLOCK + place.text();
    }

    static String entityId(UUID id) {
        return ENTITY + id;
    }

    static String placeId(BlockPlace place) {
        return PLACE + place.text();
    }

    static Destination parse(String targetId) {
        if (targetId.startsWith(PLACE)) return new Destination.Place(BlockPlace.parse(targetId.substring(PLACE.length())));
        if (targetId.startsWith(BLOCK)) return new Destination.Block(BlockPlace.parse(targetId.substring(BLOCK.length())));
        if (targetId.startsWith(ENTITY)) {
            var text = targetId.substring(ENTITY.length());
            UUID id;
            try {
                id = UUID.fromString(text);
            } catch (IllegalArgumentException failure) {
                throw new IllegalArgumentException("Invalid world target: " + targetId, failure);
            }
            if (!id.toString().equals(text)) throw new IllegalArgumentException("Invalid world target: " + targetId);
            return new Destination.Entity(id);
        }
        throw new IllegalArgumentException("Invalid world target: " + targetId);
    }

    /** Resolves server state without waiting on the client. The runtime rechecks selection/scope before opening. */
    CompletionStage<Evidence.NavigationTarget> refresh(Evidence.NavigationTarget target, Evidence.Scope scope) {
        if (!WorldTools.ID.equals(target.providerId())) return CompletableFuture.completedFuture(null);
        Destination destination;
        try {
            destination = parse(target.targetId());
        } catch (IllegalArgumentException failure) {
            return CompletableFuture.completedFuture(null);
        }
        var current = new Evidence.NavigationTarget(scope, WorldTools.ID, target.sourceHandle(), target.targetId());
        var pending = new CompletableFuture<Check>();
        synchronized (checks) {
            checks.put(current, pending);
            while (checks.size() > 64) checks.remove(checks.keySet().iterator().next());
        }
        CompletionStage<Check> resolution;
        try {
            resolution = switch (destination) {
                case Destination.Block block -> blockExists.apply(block.place())
                        .thenApply(exists -> new Check(destination, exists, null, null));
                case Destination.Entity entity -> entityPlace.apply(entity.id())
                        .thenApply(place -> new Check(destination, null, place, null));
                case Destination.Place place -> CompletableFuture.completedFuture(new Check(destination, null, null, null));
            };
        } catch (RuntimeException failure) {
            resolution = CompletableFuture.failedFuture(failure);
        }
        return resolution.handle((check, failure) -> {
            if (failure != null) {
                while (failure.getCause() != null) failure = failure.getCause();
                check = new Check(destination, null, null, failure.getMessage() == null ? "world check failed" : failure.getMessage());
            }
            pending.complete(check);
            synchronized (checks) {
                return checks.get(current) == pending ? current : null;
            }
        });
    }

    Evidence.NavigationResult open(Evidence.NavigationTarget target) {
        try {
            parse(target.targetId());
        } catch (IllegalArgumentException failure) {
            return new Evidence.NavigationResult(Evidence.NavigationStatus.ERROR, failure.getMessage());
        }
        CompletableFuture<Check> pending;
        synchronized (checks) {
            pending = checks.remove(target);
        }
        // getNow never waits, even when a caller skips the asynchronous refresh contract.
        var check = pending == null ? null : pending.getNow(null);
        if (check == null) return new Evidence.NavigationResult(Evidence.NavigationStatus.UNAVAILABLE, "world target has not been resolved");
        if (check.failure() != null) return new Evidence.NavigationResult(Evidence.NavigationStatus.UNAVAILABLE, check.failure());
        try {
            return switch (check.destination()) {
                case Destination.Block block -> openBlock(block.place(), check.blockExists());
                case Destination.Entity entity -> openEntity(entity.id(), check.entityPlace());
                case Destination.Place place -> openPlace(place.place());
            };
        } catch (RuntimeException failure) {
            return new Evidence.NavigationResult(Evidence.NavigationStatus.UNAVAILABLE, failure.getMessage());
        }
    }

    private Evidence.NavigationResult openBlock(BlockPlace place, Boolean exists) {
        var here = client.dimension();
        if (here == null) return new Evidence.NavigationResult(Evidence.NavigationStatus.UNAVAILABLE, "the player is not in a world");
        if (!Boolean.TRUE.equals(exists)) {
            return new Evidence.NavigationResult(Evidence.NavigationStatus.UNAVAILABLE,
                    "no block remains at " + coordinates(place) + " in " + place.dimension() + ", or its chunk is not loaded");
        }
        if (!here.equals(place.dimension())) {
            return new Evidence.NavigationResult(Evidence.NavigationStatus.OPENED, coordinates(place) + " in " + place.dimension());
        }
        client.highlightBlock(place, System.currentTimeMillis() + HIGHLIGHT_MILLIS);
        return new Evidence.NavigationResult(Evidence.NavigationStatus.OPENED, null);
    }

    /** Marks the place without reading the world: its chunk may be unloaded or not generated yet. */
    private Evidence.NavigationResult openPlace(BlockPlace place) {
        var here = client.dimension();
        if (here == null) return new Evidence.NavigationResult(Evidence.NavigationStatus.UNAVAILABLE, "the player is not in a world");
        if (!here.equals(place.dimension())) {
            return new Evidence.NavigationResult(Evidence.NavigationStatus.OPENED, coordinates(place) + " in " + place.dimension());
        }
        client.highlightPlace(place, System.currentTimeMillis() + HIGHLIGHT_MILLIS);
        return new Evidence.NavigationResult(Evidence.NavigationStatus.OPENED, null);
    }

    private static String coordinates(BlockPlace place) {
        return place.x() + ", " + place.y() + ", " + place.z();
    }

    private Evidence.NavigationResult openEntity(UUID id, BlockPlace place) {
        if (place == null) {
            return new Evidence.NavigationResult(Evidence.NavigationStatus.UNAVAILABLE, "entity " + id + " is no longer loaded and alive");
        }
        if (!place.dimension().equals(client.dimension()) || !client.tracks(id)) {
            return new Evidence.NavigationResult(Evidence.NavigationStatus.OPENED, coordinates(place) + " in " + place.dimension());
        }
        client.highlightEntity(id, System.currentTimeMillis() + HIGHLIGHT_MILLIS);
        return new Evidence.NavigationResult(Evidence.NavigationStatus.OPENED, null);
    }

    // Production lookups and client

    /** True when the place holds a non-air block in a loaded chunk; never loads a chunk. */
    static Function<BlockPlace, CompletionStage<Boolean>> blockLookup(Supplier<MinecraftServer> servers) {
        return place -> onServer(servers, server -> {
            var dimension = ResourceLocation.tryParse(place.dimension());
            var level = dimension == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, dimension));
            if (level == null) return false;
            var pos = new BlockPos(place.x(), place.y(), place.z());
            if (level.isOutsideBuildHeight(pos)) return false;
            var chunk = level.getChunkSource().getChunkNow(SectionPos.blockToSectionCoord(place.x()), SectionPos.blockToSectionCoord(place.z()));
            return chunk != null && !chunk.getBlockState(pos).isAir();
        });
    }

    /** Where the loaded, living entity with the id stands in any dimension, or null when there is none. */
    static Function<UUID, CompletionStage<BlockPlace>> entityLookup(Supplier<MinecraftServer> servers) {
        return id -> onServer(servers, server -> {
            for (var level : server.getAllLevels()) {
                var entity = level.getEntity(id);
                if (entity == null) continue;
                if (!entity.isAlive()) return null;
                var pos = entity.blockPosition();
                return new BlockPlace(level.dimension().location().toString(), pos.getX(), pos.getY(), pos.getZ());
            }
            return null;
        });
    }

    static Client client(Minecraft minecraft) {
        return new Client() {
            @Override
            public String dimension() {
                var level = minecraft.level;
                return level == null ? null : level.dimension().location().toString();
            }

            @Override
            public boolean tracks(UUID id) {
                var level = minecraft.level;
                if (level == null) return false;
                for (var entity : level.entitiesForRendering()) {
                    if (entity.getUUID().equals(id)) return true;
                }
                return false;
            }

            @Override
            public void highlightBlock(BlockPlace place, long untilMillis) {
                WorldHighlighter.highlightBlock(place, untilMillis);
            }

            @Override
            public void highlightEntity(UUID id, long untilMillis) {
                WorldHighlighter.highlightEntity(id, untilMillis);
            }

            @Override
            public void highlightPlace(BlockPlace place, long untilMillis) {
                WorldHighlighter.highlightPlace(place, untilMillis);
            }
        };
    }

    /** Completes a queued server read asynchronously; no caller waits for a tick or a timeout. */
    private static <T> CompletionStage<T> onServer(Supplier<MinecraftServer> servers, Function<MinecraftServer, T> read) {
        var server = servers.get();
        if (server == null) return CompletableFuture.failedFuture(new IllegalStateException(NO_SERVER));
        if (server.isSameThread()) return CompletableFuture.completedFuture(read.apply(server));
        var answer = new CompletableFuture<T>();
        server.tell(new TickTask(server.getTickCount(), () -> {
            try {
                if (servers.get() != server) throw new IllegalStateException(NO_SERVER);
                answer.complete(read.apply(server));
            } catch (RuntimeException failure) {
                answer.completeExceptionally(failure);
            }
        }));
        return answer.orTimeout(CHECK_MILLIS, TimeUnit.MILLISECONDS);
    }
}
