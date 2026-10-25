package dev.packsage.world;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * What the client shows the player when a world tool runs: the holder under the crosshair (null when nothing is
 * targeted) and the open container screen's menu id (-1 when none is open) and title. Captured on the client thread.
 */
public record ClientFocus(PathRoot targeted, int openedContainerId, String openedTitle) {
    public static final ClientFocus NONE = new ClientFocus(null, -1, null);

    /** The block place or entity a crosshair hit names in this dimension; null for a miss. */
    public static PathRoot targetedRoot(HitResult hit, String dimension) {
        if (hit instanceof BlockHitResult block && block.getType() == HitResult.Type.BLOCK) {
            var pos = block.getBlockPos();
            return new PathRoot.Block(new BlockPlace(dimension, pos.getX(), pos.getY(), pos.getZ()));
        }
        if (hit instanceof EntityHitResult entity && entity.getType() == HitResult.Type.ENTITY) {
            var target = entity.getEntity();
            return target instanceof Player ? new PathRoot.Player(target.getUUID()) : new PathRoot.Entity(target.getUUID());
        }
        return null;
    }
}
