package dev.packsage.client.highlight;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.packsage.world.BlockPlace;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BeaconRenderer;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.opengl.GL11;

import java.util.HashMap;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Draws world navigation highlights until they expire: a see-through line box around a block, a see-through beam
 * rising from a place, or a glow on a loaded mob. Blocks and places are drawn only in the
 * player's current dimension; mobs glow only while the client has them loaded.
 */
public final class WorldHighlighter {
    private static final double INSET = 0.002;
    /** Horizontal blocks from the camera beyond which a place's beam is drawn at this distance in its direction. */
    static final double MARKER_RANGE = 128;
    private static final RenderStateShard.DepthTestStateShard THROUGH_TERRAIN = new RenderStateShard.DepthTestStateShard("always", GL11.GL_ALWAYS) {
        @Override
        public void setupRenderState() {
            RenderSystem.disableDepthTest();
        }

        @Override
        public void clearRenderState() {
            RenderSystem.enableDepthTest();
        }
    };
    private static final RenderType LINES = RenderType.create("packsage_highlight_lines", DefaultVertexFormat.POSITION_COLOR_NORMAL,
            VertexFormat.Mode.LINES, 1536, false, false, RenderType.CompositeState.builder()
                    .setShaderState(RenderStateShard.RENDERTYPE_LINES_SHADER)
                    .setLineState(new RenderStateShard.LineStateShard(OptionalDouble.of(2.5)))
                    .setLayeringState(RenderStateShard.VIEW_OFFSET_Z_LAYERING)
                    .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                    .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                    // Vanilla NO_DEPTH_TEST is a no-op; THROUGH_TERRAIN explicitly disables depth testing here.
                    .setDepthTestState(THROUGH_TERRAIN)
                    .setCullState(RenderStateShard.NO_CULL)
                    .createCompositeState(false));
    private static final RenderType PLACE_BEAM_CORE = RenderType.create("packsage_place_beam_core", DefaultVertexFormat.BLOCK,
            VertexFormat.Mode.QUADS, 1536, false, true, RenderType.CompositeState.builder()
                    .setShaderState(RenderStateShard.RENDERTYPE_BEACON_BEAM_SHADER)
                    .setTextureState(new RenderStateShard.TextureStateShard(BeaconRenderer.BEAM_LOCATION, false, false))
                    .setDepthTestState(THROUGH_TERRAIN)
                    .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                    .createCompositeState(false));
    private static final RenderType PLACE_BEAM_GLOW = RenderType.create("packsage_place_beam_glow", DefaultVertexFormat.BLOCK,
            VertexFormat.Mode.QUADS, 1536, false, true, RenderType.CompositeState.builder()
                    .setShaderState(RenderStateShard.RENDERTYPE_BEACON_BEAM_SHADER)
                    .setTextureState(new RenderStateShard.TextureStateShard(BeaconRenderer.BEAM_LOCATION, false, false))
                    .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                    .setDepthTestState(THROUGH_TERRAIN)
                    .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                    .createCompositeState(false));
    private static final Map<BlockPlace, Long> BLOCKS = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> ENTITIES = new ConcurrentHashMap<>();
    private static final Map<BlockPlace, Long> PLACES = new ConcurrentHashMap<>();
    /** Mobs glowing because of a highlight, with whether they glowed before it; render thread only. */
    private static final Map<UUID, Glow> GLOWING = new HashMap<>();
    private static final PlaceBeamBuffers PLACE_BEAM_BUFFERS = new PlaceBeamBuffers();

    private record Glow(Entity entity, boolean original) {
    }

    private static final class PlaceBeamBuffers implements MultiBufferSource {
        private MultiBufferSource.BufferSource delegate;

        @Override
        public VertexConsumer getBuffer(RenderType renderType) {
            if (renderType == RenderType.beaconBeam(BeaconRenderer.BEAM_LOCATION, false)) return delegate.getBuffer(PLACE_BEAM_CORE);
            if (renderType == RenderType.beaconBeam(BeaconRenderer.BEAM_LOCATION, true)) return delegate.getBuffer(PLACE_BEAM_GLOW);
            return delegate.getBuffer(renderType);
        }
    }

    private WorldHighlighter() {
    }

    /** Registers the renderer once; logging out drops every highlight. */
    public static void register() {
        NeoForge.EVENT_BUS.addListener(WorldHighlighter::render);
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut ignored) -> {
            BLOCKS.clear();
            ENTITIES.clear();
            PLACES.clear();
        });
    }

    public static void highlightBlock(BlockPlace place, long untilMillis) {
        BLOCKS.put(place, untilMillis);
    }

    public static void highlightEntity(UUID id, long untilMillis) {
        ENTITIES.put(id, untilMillis);
    }

    /** Marks a block position with a beam rising from it to the level's maximum build height. */
    public static void highlightPlace(BlockPlace place, long untilMillis) {
        PLACES.put(place, untilMillis);
    }

    private static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        var minecraft = Minecraft.getInstance();
        var level = minecraft.level;
        long now = System.currentTimeMillis();
        BLOCKS.values().removeIf(until -> isExpired(until, now));
        ENTITIES.values().removeIf(until -> isExpired(until, now));
        PLACES.values().removeIf(until -> isExpired(until, now));
        glow(level);
        if (level == null || BLOCKS.isEmpty() && PLACES.isEmpty()) return;
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
        if (PLACES.isEmpty()) return;
        double far = minecraft.gameRenderer.getDepthFar();
        PLACE_BEAM_BUFFERS.delegate = buffers;
        try {
            for (var place : PLACES.keySet()) {
                if (!place.dimension().equals(dimension)) continue;
                double x = place.x() + 0.5 - camera.x;
                double z = place.z() + 0.5 - camera.z;
                double scale = markerScale(Math.sqrt(x * x + z * z), far);
                x *= scale;
                z *= scale;
                pose.pushPose();
                pose.translate(x - 0.5, place.y() - camera.y, z - 0.5);
                BeaconRenderer.renderBeaconBeam(pose, PLACE_BEAM_BUFFERS, BeaconRenderer.BEAM_LOCATION,
                        event.getPartialTick().getGameTimeDeltaPartialTick(false), 1.0F, level.getGameTime(), 0,
                        markerBeamHeight(place.y(), level.getMaxBuildHeight()), 0xFF4DD9FF, 0.14F, 0.5F);
                pose.popPose();
            }
        } finally {
            PLACE_BEAM_BUFFERS.delegate = null;
        }
        // A marker far away would fade out in terrain fog, which ends at the render distance or nearer in thick-fog dimensions.
        float fogStart = RenderSystem.getShaderFogStart();
        RenderSystem.setShaderFogStart(Float.MAX_VALUE);
        buffers.endBatch(PLACE_BEAM_CORE);
        buffers.endBatch(PLACE_BEAM_GLOW);
        RenderSystem.setShaderFogStart(fogStart);
    }

    /**
     * How far from the camera, horizontally, a place's beam is drawn: its own distance, at most the marker range,
     * and at most half the projection's far plane so tiny render distances do not clip it away.
     */
    static double markerDistance(double distance, double farPlane) {
        return Math.min(distance, Math.min(MARKER_RANGE, farPlane / 2));
    }

    static double markerScale(double distance, double farPlane) {
        return distance == 0 ? 1 : markerDistance(distance, farPlane) / distance;
    }

    static int markerBeamHeight(int placeY, int maxBuildHeight) {
        return Math.max(0, maxBuildHeight - placeY);
    }

    static boolean isExpired(long untilMillis, long nowMillis) {
        return untilMillis <= nowMillis;
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
