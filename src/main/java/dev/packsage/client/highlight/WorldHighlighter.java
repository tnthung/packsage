package dev.packsage.client.highlight;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.packsage.world.BlockPlace;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.util.HashMap;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Draws world navigation highlights until they expire: a see-through line box around a block, or a glow on a loaded
 * mob. Blocks are drawn only in the player's current dimension; mobs glow only while the client has them loaded.
 */
public final class WorldHighlighter {
    private static final double INSET = 0.002;
    private static final RenderType LINES = RenderType.create("packsage_highlight_lines", DefaultVertexFormat.POSITION_COLOR_NORMAL,
            VertexFormat.Mode.LINES, 1536, false, false, RenderType.CompositeState.builder()
                    .setShaderState(RenderStateShard.RENDERTYPE_LINES_SHADER)
                    .setLineState(new RenderStateShard.LineStateShard(OptionalDouble.of(2.5)))
                    .setLayeringState(RenderStateShard.VIEW_OFFSET_Z_LAYERING)
                    .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                    .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                    .setDepthTestState(RenderStateShard.NO_DEPTH_TEST)
                    .setCullState(RenderStateShard.NO_CULL)
                    .createCompositeState(false));
    private static final Map<BlockPlace, Long> BLOCKS = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> ENTITIES = new ConcurrentHashMap<>();
    /** Mobs glowing because of a highlight, with whether they glowed before it; render thread only. */
    private static final Map<UUID, Glow> GLOWING = new HashMap<>();

    private record Glow(Entity entity, boolean original) {
    }

    private WorldHighlighter() {
    }

    /** Registers the renderer once; logging out drops every highlight. */
    public static void register() {
        NeoForge.EVENT_BUS.addListener(WorldHighlighter::render);
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut ignored) -> {
            BLOCKS.clear();
            ENTITIES.clear();
        });
    }

    public static void highlightBlock(BlockPlace place, long untilMillis) {
        BLOCKS.put(place, untilMillis);
    }

    public static void highlightEntity(UUID id, long untilMillis) {
        ENTITIES.put(id, untilMillis);
    }

    private static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        var minecraft = Minecraft.getInstance();
        var level = minecraft.level;
        long now = System.currentTimeMillis();
        BLOCKS.values().removeIf(until -> until <= now);
        ENTITIES.values().removeIf(until -> until <= now);
        glow(level);
        if (level == null || BLOCKS.isEmpty()) return;
        var dimension = level.dimension().location().toString();
        var camera = event.getCamera().getPosition();
        var buffers = minecraft.renderBuffers().bufferSource();
        var lines = buffers.getBuffer(LINES);
        var pose = new PoseStack();
        for (var place : BLOCKS.keySet()) {
            if (!place.dimension().equals(dimension)) continue;
            double x = place.x() - camera.x;
            double y = place.y() - camera.y;
            double z = place.z() - camera.z;
            LevelRenderer.renderLineBox(pose, lines, x - INSET, y - INSET, z - INSET, x + 1 + INSET, y + 1 + INSET, z + 1 + INSET,
                    1.0F, 0.85F, 0.2F, 1.0F);
        }
        buffers.endBatch(LINES);
    }

    /** Keeps highlighted mobs glowing, re-applying after server syncs, and restores them when the highlight ends. */
    private static void glow(ClientLevel level) {
        var iterator = GLOWING.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            var glow = entry.getValue();
            if (ENTITIES.containsKey(entry.getKey()) && !glow.entity().isRemoved() && glow.entity().level() == level) continue;
            if (!glow.original()) SharedFlags.glowing(glow.entity(), false);
            iterator.remove();
        }
        if (level == null || ENTITIES.isEmpty()) return;
        for (var entity : level.entitiesForRendering()) {
            var id = entity.getUUID();
            if (!ENTITIES.containsKey(id)) continue;
            var glow = GLOWING.get(id);
            if (glow == null || glow.entity() != entity) GLOWING.put(id, new Glow(entity, SharedFlags.glowing(entity)));
            if (!SharedFlags.glowing(entity)) SharedFlags.glowing(entity, true);
        }
    }

    /**
     * Reads and writes the synced glowing flag. A client-side {@code setGlowingTag(true)} cannot be used: on the
     * client it rewrites the flag from {@code isCurrentlyGlowing()}, which itself reads that flag. Never instantiated.
     */
    private abstract static class SharedFlags extends Entity {
        private SharedFlags() {
            super(null, null);
        }

        static boolean glowing(Entity entity) {
            return (entity.getEntityData().get(DATA_SHARED_FLAGS_ID) & 1 << FLAG_GLOWING) != 0;
        }

        static void glowing(Entity entity, boolean value) {
            byte flags = entity.getEntityData().get(DATA_SHARED_FLAGS_ID);
            entity.getEntityData().set(DATA_SHARED_FLAGS_ID, (byte) (value ? flags | 1 << FLAG_GLOWING : flags & ~(1 << FLAG_GLOWING)));
        }
    }
}
