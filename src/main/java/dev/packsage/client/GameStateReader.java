package dev.packsage.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.packsage.knowledge.Evidence;
import dev.packsage.knowledge.Toolbox;
import dev.packsage.model.PackSageTools;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class GameStateReader implements Toolbox {
    private static final String ID = "game_state";
    private static final String VERSION = "1";
    private static final List<String> SECTIONS = List.of(
            "performance", "entities_and_chunks", "light_and_environment", "position", "looking_at");

    @FunctionalInterface
    interface Capture {
        CompletionStage<Snapshot> capture(Set<String> sections);
    }

    record Snapshot(boolean multiplayer, JsonObject values) {
        Snapshot {
            values = values.deepCopy();
        }
    }

    private static final String DIFFICULTY_AT = "_localDifficultyAt";

    private final Capture capture;

    public GameStateReader(Minecraft client) {
        this(sections -> client.submit(() -> capture(client, sections)).thenCompose(snapshot -> withLocalDifficulty(client, snapshot)));
    }

    /** Reads local difficulty on the integrated server thread from an already loaded chunk, never loading one; after two seconds it stays unavailable. */
    private static CompletionStage<Snapshot> withLocalDifficulty(Minecraft client, Snapshot snapshot) {
        var values = snapshot.values();
        if (!(values.remove(DIFFICULTY_AT) instanceof JsonObject at) || !(values.get("light_and_environment") instanceof JsonObject environment)) {
            return CompletableFuture.completedFuture(new Snapshot(snapshot.multiplayer(), values));
        }
        var server = client.getSingleplayerServer();
        if (server == null) return CompletableFuture.completedFuture(new Snapshot(snapshot.multiplayer(), values));
        var dimension = net.minecraft.resources.ResourceLocation.tryParse(at.get("dimension").getAsString());
        int x = at.get("x").getAsInt();
        int z = at.get("z").getAsInt();
        return server.submit(() -> {
            var serverLevel = dimension == null ? null : server.getLevel(net.minecraft.resources.ResourceKey.create(
                    net.minecraft.core.registries.Registries.DIMENSION, dimension));
            var chunk = serverLevel == null ? null : serverLevel.getChunkSource().getChunkNow(x >> 4, z >> 4);
            if (chunk == null) return null;
            var difficulty = new DifficultyInstance(serverLevel.getDifficulty(), serverLevel.getDayTime(),
                    chunk.getInhabitedTime(), serverLevel.getMoonBrightness());
            var local = new JsonObject();
            local.addProperty("effective", difficulty.getEffectiveDifficulty());
            local.addProperty("specialMultiplier", difficulty.getSpecialMultiplier());
            return local;
        }).completeOnTimeout(null, 2, java.util.concurrent.TimeUnit.SECONDS).handle((local, failure) -> {
            if (local != null) environment.add("localDifficulty", local);
            return new Snapshot(snapshot.multiplayer(), values);
        });
    }

    GameStateReader(Capture capture) {
        this.capture = capture;
    }

    @Override
    public Metadata metadata() {
        return new Metadata(ID, List.of(), false, List.of());
    }

    @Override
    public CompletionStage<Evidence.Result> handle(PackSageTools.ReadRequest request, Context context) {
        if (!request.tool().equals("read_game_state")) {
            return CompletableFuture.failedFuture(new Failure(Code.NOT_HANDLED, request.tool()));
        }
        Set<String> sections = new LinkedHashSet<>(SECTIONS);
        var requested = request.arguments().values().get("sections");
        if (requested != null && !(requested instanceof Evidence.JsonNull)) {
            if (!(requested instanceof Evidence.JsonArray values) || values.values().size() > SECTIONS.size()) {
                return CompletableFuture.failedFuture(new Failure(Code.INVALID_ARGUMENTS, "sections must select known game-state sections"));
            }
            sections.clear();
            for (var value : values.values()) {
                if (!(value instanceof Evidence.JsonString section) || !SECTIONS.contains(section.value()) || !sections.add(section.value())) {
                    return CompletableFuture.failedFuture(new Failure(Code.INVALID_ARGUMENTS, "sections must contain unique known game-state sections"));
                }
            }
        }
        Set<String> selected = Set.copyOf(sections);
        return capture.capture(selected).thenApply(snapshot -> {
            var payload = new JsonObject();
            for (var section : SECTIONS) {
                if (selected.contains(section)) payload.add(section, normalize(snapshot.values().get(section)));
            }
            if (snapshot.multiplayer() && payload.has("performance") && payload.get("performance").isJsonObject()) {
                var performance = payload.getAsJsonObject("performance");
                performance.addProperty("integratedServerTps", "unavailable");
                performance.addProperty("integratedServerMspt", "unavailable");
            }
            var source = new Evidence.Source(request.scope(), ID, VERSION, "current", request.scope().requestGeneration(),
                    Instant.now(), Evidence.Activity.ACTIVE, Evidence.Coverage.COMPLETE, Evidence.Authority.LOADED,
                    List.of("minecraft-f3-debug-screen"));
            var record = new Evidence.Record(source, Evidence.RecordKind.OTHER, "Current F3 game state",
                    Evidence.deserializeObject(payload.toString()), List.of());
            return new Evidence.Result(List.of(record), Evidence.Coverage.COMPLETE, Evidence.Outcome.COMPLETE, null);
        });
    }

    private static JsonElement normalize(JsonElement value) {
        if (value == null || value.isJsonNull()) return new JsonPrimitive("unavailable");
        if (value.isJsonObject()) {
            var object = new JsonObject();
            value.getAsJsonObject().entrySet().forEach(entry -> object.add(entry.getKey(), normalize(entry.getValue())));
            return object;
        }
        if (value.isJsonArray()) {
            var array = new JsonArray();
            value.getAsJsonArray().forEach(entry -> array.add(normalize(entry)));
            return array;
        }
        return value.deepCopy();
    }

    private static Snapshot capture(Minecraft client, Set<String> sections) {
        var values = new JsonObject();
        var level = client.level;
        var camera = client.getCameraEntity();
        var player = client.player;
        var cameraPosition = camera == null ? null : camera.blockPosition();
        var server = client.getSingleplayerServer();

        if (sections.contains("performance")) {
            var performance = new JsonObject();
            performance.addProperty("fps", client.getFps());
            long frameTimeNs = client.getFrameTimeNs();
            performance.addProperty("frameTimeMs", frameTimeNs > 0L ? Double.valueOf(frameTimeNs / 1_000_000.0) : null);
            Double integratedServerTps = null;
            Double integratedServerMspt = null;
            if (server != null) {
                double mspt = server.getCurrentSmoothedTickTime();
                if (Double.isFinite(mspt) && mspt > 0.0) {
                    var tickRate = server.tickRateManager();
                    integratedServerMspt = mspt;
                    integratedServerTps = tickRate.isFrozen() ? 0.0
                            : tickRate.isSprinting() ? 1000.0 / mspt : Math.min(tickRate.tickrate(), 1000.0 / mspt);
                }
            }
            performance.addProperty("integratedServerTps", integratedServerTps);
            performance.addProperty("integratedServerMspt", integratedServerMspt);
            performance.addProperty("renderDistance", level == null ? null : (int) client.levelRenderer.getLastViewDistance());
            performance.addProperty("simulationDistance", level == null ? null : level.getServerSimulationDistance());
            performance.addProperty("loadedChunks", level == null ? null : level.getChunkSource().getLoadedChunksCount());
            performance.addProperty("renderedChunkSections", level == null ? null : client.levelRenderer.countRenderedSections());
            performance.addProperty("totalChunkSections", level == null ? null : (int) client.levelRenderer.getTotalSections());
            var runtime = Runtime.getRuntime();
            long allocatedMemory = runtime.totalMemory();
            var memory = new JsonObject();
            memory.addProperty("usedBytes", allocatedMemory - runtime.freeMemory());
            memory.addProperty("maxBytes", runtime.maxMemory());
            memory.addProperty("allocatedBytes", allocatedMemory);
            performance.add("memory", memory);
            values.add("performance", performance);
        }

        if (sections.contains("entities_and_chunks")) {
            if (level == null) {
                values.addProperty("entities_and_chunks", "unavailable");
            } else {
                var entitiesAndChunks = new JsonObject();
                Integer renderedEntities = null;
                String entityStatistics = client.levelRenderer.getEntityStatistics();
                int slash = entityStatistics.indexOf('/');
                if (entityStatistics.startsWith("E: ") && slash > 3) {
                    try {
                        renderedEntities = Integer.valueOf(entityStatistics.substring(3, slash));
                    } catch (NumberFormatException ignored) {
                    }
                }
                entitiesAndChunks.addProperty("renderedEntities", renderedEntities);
                entitiesAndChunks.addProperty("totalEntities", level.getEntityCount());
                if (player == null) {
                    entitiesAndChunks.add("playerChunk", JsonNull.INSTANCE);
                } else {
                    var playerChunk = new ChunkPos(player.blockPosition());
                    var chunk = new JsonObject();
                    chunk.addProperty("x", playerChunk.x);
                    chunk.addProperty("z", playerChunk.z);
                    entitiesAndChunks.add("playerChunk", chunk);
                }
                if (cameraPosition == null) {
                    entitiesAndChunks.add("chunkSection", JsonNull.INSTANCE);
                    entitiesAndChunks.add("region", JsonNull.INSTANCE);
                } else {
                    var chunkPosition = new ChunkPos(cameraPosition);
                    var section = new JsonObject();
                    section.addProperty("x", chunkPosition.x);
                    section.addProperty("y", SectionPos.blockToSectionCoord(cameraPosition.getY()));
                    section.addProperty("z", chunkPosition.z);
                    entitiesAndChunks.add("chunkSection", section);
                    var region = new JsonObject();
                    region.addProperty("localX", chunkPosition.getRegionLocalX());
                    region.addProperty("localZ", chunkPosition.getRegionLocalZ());
                    region.addProperty("x", chunkPosition.getRegionX());
                    region.addProperty("z", chunkPosition.getRegionZ());
                    region.addProperty("file", "r." + chunkPosition.getRegionX() + "." + chunkPosition.getRegionZ() + ".mca");
                    entitiesAndChunks.add("region", region);
                }
                values.add("entities_and_chunks", entitiesAndChunks);
            }
        }

        if (sections.contains("light_and_environment")) {
            if (level == null) {
                values.addProperty("light_and_environment", "unavailable");
            } else {
                var environment = new JsonObject();
                var light = new JsonObject();
                boolean chunkLoaded = cameraPosition != null && level.hasChunkAt(cameraPosition);
                light.addProperty("block", chunkLoaded ? level.getBrightness(LightLayer.BLOCK, cameraPosition) : null);
                light.addProperty("sky", chunkLoaded ? level.getBrightness(LightLayer.SKY, cameraPosition) : null);
                light.addProperty("combined", chunkLoaded
                        ? level.getChunkSource().getLightEngine().getRawBrightness(cameraPosition, 0) : null);
                environment.add("light", light);
                boolean validHeight = cameraPosition != null && cameraPosition.getY() >= level.getMinBuildHeight()
                        && cameraPosition.getY() < level.getMaxBuildHeight();
                if (chunkLoaded && validHeight) {
                    String biome = level.getBiome(cameraPosition).unwrap().map(
                            key -> key.location().toString(), unregistered -> "[unregistered " + unregistered + "]");
                    environment.addProperty("biome", biome);
                } else {
                    environment.add("biome", JsonNull.INSTANCE);
                }
                // Local difficulty is server-owned; it is read on the server thread after this client capture.
                environment.add("localDifficulty", JsonNull.INSTANCE);
                if (server != null && chunkLoaded && validHeight) {
                    var at = new JsonObject();
                    at.addProperty("dimension", level.dimension().location().toString());
                    at.addProperty("x", cameraPosition.getX());
                    at.addProperty("z", cameraPosition.getZ());
                    values.add(DIFFICULTY_AT, at);
                }
                environment.addProperty("gameTime", level.getGameTime());
                environment.addProperty("dayTime", level.getDayTime());
                environment.addProperty("day", level.getDayTime() / 24000L);
                environment.addProperty("moonPhase", level.getMoonPhase());
                values.add("light_and_environment", environment);
            }
        }

        if (sections.contains("position")) {
            if (level == null || camera == null || cameraPosition == null) {
                values.addProperty("position", "unavailable");
            } else {
                var position = new JsonObject();
                var xyz = new JsonObject();
                xyz.addProperty("x", camera.getX());
                xyz.addProperty("y", camera.getY());
                xyz.addProperty("z", camera.getZ());
                position.add("xyz", xyz);
                var block = new JsonObject();
                block.addProperty("x", cameraPosition.getX());
                block.addProperty("y", cameraPosition.getY());
                block.addProperty("z", cameraPosition.getZ());
                position.add("block", block);
                Direction direction = camera.getDirection();
                var facing = new JsonObject();
                facing.addProperty("direction", direction.toString());
                facing.addProperty("description", switch (direction) {
                    case NORTH -> "Towards negative Z";
                    case SOUTH -> "Towards positive Z";
                    case WEST -> "Towards negative X";
                    case EAST -> "Towards positive X";
                    default -> "Invalid";
                });
                facing.addProperty("yaw", net.minecraft.util.Mth.wrapDegrees(camera.getYRot()));
                facing.addProperty("pitch", net.minecraft.util.Mth.wrapDegrees(camera.getXRot()));
                position.add("facing", facing);
                position.addProperty("dimension", level.dimension().location().toString());
                values.add("position", position);
            }
        }

        if (sections.contains("looking_at")) {
            if (level == null || camera == null) {
                var lookingAt = new JsonObject();
                lookingAt.addProperty("block", "unavailable");
                lookingAt.addProperty("fluid", "unavailable");
                lookingAt.addProperty("entity", "unavailable");
                values.add("looking_at", lookingAt);
            } else {
                var lookingAt = new JsonObject();
                HitResult blockHit = camera.pick(20.0, 0.0F, false);
                if (blockHit.getType() == HitResult.Type.BLOCK) {
                    BlockPos blockPosition = ((BlockHitResult) blockHit).getBlockPos();
                    BlockState blockState = level.getBlockState(blockPosition);
                    var block = new JsonObject();
                    block.addProperty("id", BuiltInRegistries.BLOCK.getKey(blockState.getBlock()).toString());
                    var coordinates = new JsonObject();
                    coordinates.addProperty("x", blockPosition.getX());
                    coordinates.addProperty("y", blockPosition.getY());
                    coordinates.addProperty("z", blockPosition.getZ());
                    block.add("position", coordinates);
                    var properties = new JsonObject();
                    blockState.getValues().forEach((property, value) -> properties.addProperty(property.getName(), value.toString()));
                    block.add("properties", properties);
                    var tags = new JsonArray();
                    blockState.getTags().map(tag -> "#" + tag.location()).forEach(tags::add);
                    block.add("tags", tags);
                    lookingAt.add("block", block);
                } else {
                    var block = new JsonObject();
                    block.addProperty("status", "none");
                    lookingAt.add("block", block);
                }
                HitResult fluidHit = camera.pick(20.0, 0.0F, true);
                if (fluidHit.getType() == HitResult.Type.BLOCK) {
                    BlockPos fluidPosition = ((BlockHitResult) fluidHit).getBlockPos();
                    var fluidState = level.getFluidState(fluidPosition);
                    var fluid = new JsonObject();
                    fluid.addProperty("id", BuiltInRegistries.FLUID.getKey(fluidState.getType()).toString());
                    var coordinates = new JsonObject();
                    coordinates.addProperty("x", fluidPosition.getX());
                    coordinates.addProperty("y", fluidPosition.getY());
                    coordinates.addProperty("z", fluidPosition.getZ());
                    fluid.add("position", coordinates);
                    var properties = new JsonObject();
                    fluidState.getValues().forEach((property, value) -> properties.addProperty(property.getName(), value.toString()));
                    fluid.add("properties", properties);
                    var tags = new JsonArray();
                    fluidState.getTags().map(tag -> "#" + tag.location()).forEach(tags::add);
                    fluid.add("tags", tags);
                    lookingAt.add("fluid", fluid);
                } else {
                    var fluid = new JsonObject();
                    fluid.addProperty("status", "none");
                    lookingAt.add("fluid", fluid);
                }
                Entity entity = client.crosshairPickEntity;
                if (entity == null) {
                    var target = new JsonObject();
                    target.addProperty("status", "none");
                    lookingAt.add("entity", target);
                } else {
                    var target = new JsonObject();
                    target.addProperty("type", BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
                    var tags = new JsonArray();
                    entity.getType().builtInRegistryHolder().tags().forEach(tag -> tags.add("#" + tag.location()));
                    target.add("tags", tags);
                    lookingAt.add("entity", target);
                }
                values.add("looking_at", lookingAt);
            }
        }
        return new Snapshot(level != null && server == null, values);
    }
}
