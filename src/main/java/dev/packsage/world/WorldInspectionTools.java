package dev.packsage.world;

import dev.packsage.knowledge.Evidence;
import dev.packsage.model.PackSageTools;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.core.BlockPos;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

import static dev.packsage.world.WorldTools.*;

final class WorldInspectionTools {
    private final WorldTools world;

    WorldInspectionTools(WorldTools world) {
        this.world = world;
    }

    CompletionStage<Evidence.Result> inspect(PackSageTools.ReadRequest request, String handle) {
        return world.reread(handle, request.scope()).thenApply(WorldTools::single);
    }

    Evidence.Record inspectBlock(MinecraftServer server, BlockPlace place, Evidence.Scope scope) {
        var level = level(server, place.dimension());
        var pos = new BlockPos(place.x(), place.y(), place.z());
        if (!loaded(level, pos)) throw new Failure(Code.UNAVAILABLE, "the chunk at " + place.text() + " is not loaded");
        var state = level.getBlockState(pos);
        var inventories = world.registry.inventories(new LiveHolder.OfBlock(level, pos));
        var sections = world.registry.inspectBlock(level, pos);
        var payload = new LinkedHashMap<String, Evidence.JsonValue>();
        var kind = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        payload.put("block", text(kind));
        payload.put("name", text(state.getBlock().getName().getString()));
        payload.put("place", text(place.text()));
        payload.put("holder", text(new ItemPath(new PathRoot.Block(place), List.of()).text()));
        payload.put("sections", sections(sections));
        payload.put("inventories", inventories(inventories));
        putTime(payload, level);
        return evidence(scope, "block|" + place.text(), Evidence.RecordKind.BLOCK, kind + " at " + place.text(), payload,
                coverage(sections, inventories), state.isAir() ? null : WorldNavigation.blockId(place));
    }

    Evidence.Record inspectEntity(MinecraftServer server, UUID id, Evidence.Scope scope) {
        var entity = entity(server, id);
        if (entity == null) throw new Failure(Code.UNAVAILABLE, "no loaded entity has id " + id);
        var holder = entity instanceof ServerPlayer player ? new LiveHolder.OfPlayer(player) : new LiveHolder.OfEntity(entity);
        var root = entity instanceof ServerPlayer ? new PathRoot.Player(id) : new PathRoot.Entity(id);
        var inventories = world.registry.inventories(holder);
        var sections = world.registry.inspectEntity(entity);
        var identity = world.areaTools.entityRecord(scope, handle(entity), null);
        var payload = new LinkedHashMap<>(identity.payload().values());
        payload.put("holder", text(new ItemPath(root, List.of()).text()));
        payload.put("sections", sections(sections));
        payload.put("inventories", inventories(inventories));
        putTime(payload, entity.level());
        return evidence(scope, "entity|" + id, Evidence.RecordKind.OTHER, identity.title(), payload,
                identity.source().coverage() == Evidence.Coverage.PARTIAL ? Evidence.Coverage.PARTIAL : coverage(sections, inventories),
                WorldNavigation.entityId(id));
    }

    private static EntityHandle handle(Entity entity) {
        return new EntityHandle(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString(), entity.getUUID(),
                entity.level().dimension().location().toString(), entity.getX(), entity.getY(), entity.getZ(), entity.getName().getString());
    }
}
