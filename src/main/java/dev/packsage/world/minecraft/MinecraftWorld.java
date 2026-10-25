package dev.packsage.world.minecraft;

import dev.packsage.knowledge.Evidence;
import dev.packsage.world.BlockInspector;
import dev.packsage.world.Section;
import dev.packsage.world.WorldAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.BubbleColumnBlock;
import net.minecraft.world.level.block.CopperBulbBlock;
import net.minecraft.world.level.block.EnderChestBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.NoteBlock;
import net.minecraft.world.level.block.Portal;
import net.minecraft.world.level.block.PoweredRailBlock;
import net.minecraft.world.level.block.RedstoneLampBlock;
import net.minecraft.world.level.block.SpawnerBlock;
import net.minecraft.world.level.block.TrialSpawnerBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.CampfireBlockEntity;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.piston.PistonHeadBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.chunk.LevelChunk;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Vanilla notable-block roles and block facts. Every read is side-effect free. */
public final class MinecraftWorld implements WorldAccessor, BlockInspector {
    @Override
    public String adapterId() {
        return MinecraftInventories.ADAPTER;
    }

    @Override
    public String notableRole(BlockState state, BlockEntity entity) {
        var block = state.getBlock();
        // The blocks MinecraftInventories reads a section from; this wins over workstation so barrels and smokers count as containers.
        if (entity instanceof Container || entity instanceof CampfireBlockEntity || entity instanceof LecternBlockEntity
                || block instanceof EnderChestBlock) return "container";
        var poi = PoiTypes.forState(state);
        if (poi.isPresent() && isWorkstation(poi.get())) return "workstation";
        if (block instanceof BedBlock) return "bed";
        if (block instanceof Portal) return "portal";
        if (block instanceof SpawnerBlock || block instanceof TrialSpawnerBlock) return "spawner";
        if (state.isSignalSource() || block instanceof PistonBaseBlock || block instanceof PistonHeadBlock
                || block instanceof RedstoneLampBlock || block instanceof NoteBlock || block instanceof PoweredRailBlock
                || block instanceof CopperBulbBlock) return "redstone";
        if (block instanceof LiquidBlock || block instanceof BubbleColumnBlock) return "fluid";
        if (state.getLightEmission(EmptyBlockGetter.INSTANCE, BlockPos.ZERO) > 0) return "light";
        return null;
    }

    /** A workstation is a point of interest some villager profession takes as its job site. */
    private static boolean isWorkstation(Holder<PoiType> poi) {
        return BuiltInRegistries.VILLAGER_PROFESSION.stream().anyMatch(profession -> profession.heldJobSite().test(poi));
    }

    @Override
    public Section inspectBlock(ServerLevel level, BlockPos pos) {
        var chunk = loadedChunk(level, pos);
        if (chunk == null) return null;
        var state = chunk.getBlockState(pos);
        var values = new LinkedHashMap<String, Evidence.JsonValue>();
        var properties = new LinkedHashMap<String, Evidence.JsonValue>();
        for (var property : state.getProperties()) {
            properties.put(property.getName(), new Evidence.JsonString(valueName(state, property)));
        }
        values.put("state", new Evidence.JsonObject(properties));
        values.put("blockLight", number(level.getBrightness(LightLayer.BLOCK, pos)));
        values.put("skyLight", number(level.getBrightness(LightLayer.SKY, pos)));
        var poi = poi(level.getPoiManager(), pos);
        if (poi != null) values.put("poi", poi);
        if (state.getBlock() instanceof BedBlock) {
            values.put("bedOccupied", new Evidence.JsonBoolean(state.getValue(BedBlock.OCCUPIED)));
        }
        if (signalChunks(pos).stream().allMatch(near -> level.getChunkSource().getChunkNow(near.x, near.z) != null)) {
            values.put("redstoneSignal", number(level.getBestNeighborSignal(pos)));
        }
        var note = poi != null && poi.values().containsKey("status") ? "POI section is not cached; POI facts are unavailable" : null;
        return new Section(new Evidence.JsonObject(values), note == null ? Evidence.Coverage.COMPLETE : Evidence.Coverage.PARTIAL, note);
    }

    static Evidence.JsonObject poi(PoiManager poiManager, BlockPos pos) {
        var section = poiManager.get(SectionPos.asLong(pos));
        if (section == null) return new Evidence.JsonObject(Map.of(
                "status", new Evidence.JsonString("unavailable: POI section is not cached")));
        var poi = section.flatMap(loaded -> loaded.getType(pos));
        if (poi.isPresent()) {
            return new Evidence.JsonObject(Map.of(
                    "type", new Evidence.JsonString(poi.get().getRegisteredName()),
                    "freeTickets", number(section.orElseThrow().getFreeTickets(pos))));
        }
        return null;
    }

    private static <T extends Comparable<T>> String valueName(BlockState state, Property<T> property) {
        return property.getName(state.getValue(property));
    }

    /**
     * The chunks a neighbour signal read can touch: neighbouring conductors read their own neighbours, so it reaches
     * two blocks out, and reading a block in an unloaded chunk would load it.
     */
    static List<ChunkPos> signalChunks(BlockPos pos) {
        var chunks = new ArrayList<ChunkPos>();
        for (int x = SectionPos.blockToSectionCoord(pos.getX() - 2); x <= SectionPos.blockToSectionCoord(pos.getX() + 2); x++) {
            for (int z = SectionPos.blockToSectionCoord(pos.getZ() - 2); z <= SectionPos.blockToSectionCoord(pos.getZ() + 2); z++) {
                chunks.add(new ChunkPos(x, z));
            }
        }
        return chunks;
    }

    private static LevelChunk loadedChunk(ServerLevel level, BlockPos pos) {
        return level.getChunkSource().getChunkNow(SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getZ()));
    }

    private static Evidence.JsonNumber number(long value) {
        return new Evidence.JsonNumber(BigDecimal.valueOf(value));
    }
}
