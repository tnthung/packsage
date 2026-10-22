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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Opens {@code world-block|<place>} and {@code world-entity|<uuid>} targets as 30-second highlights. The target id is
 * the stable destination: a saved link resolves into the current scope without re-reading evidence, and opening checks
 * that the block or mob still exists before anything is drawn.
 */
final class WorldNavigation {
    static final long HIGHLIGHT_MILLIS = 30_000;
    private static final String BLOCK = "world-block|";
    private static final String ENTITY = "world-entity|";
    private static final long CHECK_MILLIS = 1_000;
    private static final String NO_SERVER = "world highlights need an integrated singleplayer server";

    sealed interface Destination {
        record Block(BlockPlace place) implements Destination {
        }

        record Entity(UUID id) implements Destination {
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
    }

    private final Function<BlockPlace, Boolean> blockExists;
    private final Function<UUID, BlockPlace> entityPlace;
    private final Client client;

    WorldNavigation(Function<BlockPlace, Boolean> blockExists, Function<UUID, BlockPlace> entityPlace, Client client) {
        this.blockExists = blockExists;
        this.entityPlace = entityPlace;
        this.client = client;
    }

    static String blockId(BlockPlace place) {
        return BLOCK + place.text();
    }

    static String entityId(UUID id) {
        return ENTITY + id;
    }

    static Destination parse(String targetId) {
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

    /** Rebinds a well-formed saved target to the current scope; existence is checked when it opens. */
    CompletionStage<Evidence.NavigationTarget> refresh(Evidence.NavigationTarget target, Evidence.Scope scope) {
        if (!WorldTools.ID.equals(target.providerId())) return CompletableFuture.completedFuture(null);
        try {
            parse(target.targetId());
        } catch (IllegalArgumentException failure) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.completedFuture(new Evidence.NavigationTarget(scope, WorldTools.ID, target.sourceHandle(), target.targetId()));
    }

    Evidence.NavigationResult open(Evidence.NavigationTarget target) {
        Destination destination;
        try {
            destination = parse(target.targetId());
        } catch (IllegalArgumentException failure) {
            return new Evidence.NavigationResult(Evidence.NavigationStatus.ERROR, failure.getMessage());
        }
        try {
            return switch (destination) {
                case Destination.Block block -> openBlock(block.place());
                case Destination.Entity entity -> openEntity(entity.id());
            };
        } catch (RuntimeException failure) {
            return new Evidence.NavigationResult(Evidence.NavigationStatus.UNAVAILABLE, failure.getMessage());
        }
    }

    private Evidence.NavigationResult openBlock(BlockPlace place) {
        var here = client.dimension();
        if (here == null) return new Evidence.NavigationResult(Evidence.NavigationStatus.UNAVAILABLE, "the player is not in a world");
        if (!here.equals(place.dimension())) {
            return new Evidence.NavigationResult(Evidence.NavigationStatus.OPENED, coordinates(place) + " in " + place.dimension());
        }
        if (!Boolean.TRUE.equals(blockExists.apply(place))) {
            return new Evidence.NavigationResult(Evidence.NavigationStatus.UNAVAILABLE,
                    "no block remains at " + coordinates(place) + " in " + place.dimension() + ", or its chunk is not loaded");
        }
        client.highlightBlock(place, System.currentTimeMillis() + HIGHLIGHT_MILLIS);
        return new Evidence.NavigationResult(Evidence.NavigationStatus.OPENED, null);
    }

    private static String coordinates(BlockPlace place) {
        return place.x() + ", " + place.y() + ", " + place.z();
    }

    private Evidence.NavigationResult openEntity(UUID id) {
        var place = entityPlace.apply(id);
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
    static Function<BlockPlace, Boolean> blockLookup(Supplier<MinecraftServer> servers) {
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
    static Function<UUID, BlockPlace> entityLookup(Supplier<MinecraftServer> servers) {
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
        };
    }

    /** Runs a world read on the server thread, waiting at most a second so a click never stalls the client for long. */
    private static <T> T onServer(Supplier<MinecraftServer> servers, Function<MinecraftServer, T> read) {
        var server = servers.get();
        if (server == null) throw new IllegalStateException(NO_SERVER);
        if (server.isSameThread()) return read.apply(server);
        var answer = new CompletableFuture<T>();
        server.tell(new TickTask(server.getTickCount(), () -> {
            try {
                answer.complete(read.apply(server));
            } catch (RuntimeException failure) {
                answer.completeExceptionally(failure);
            }
        }));
        try {
            return answer.get(CHECK_MILLIS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException failure) {
            throw new IllegalStateException("the server did not answer in time");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while checking the world");
        } catch (ExecutionException failure) {
            throw new IllegalStateException(failure.getCause() == null ? "world check failed" : failure.getCause().getMessage());
        }
    }
}
