package dev.packsage.world;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ClientFocusTest {
    @Test
    void targetedRootNamesTheBlockPlaceOrEntity() {
        var hit = new BlockHitResult(new Vec3(1.5, 64.5, 2.5), Direction.UP, new BlockPos(1, 64, 2), false);
        var root = ClientFocus.targetedRoot(hit, "minecraft:overworld");
        assertEquals("block:minecraft:overworld|1,64,2", new ItemPath(root, java.util.List.of()).text());

        assertNull(ClientFocus.targetedRoot(BlockHitResult.miss(new Vec3(0, 64, 0), Direction.UP, new BlockPos(0, 64, 0)), "minecraft:overworld"));
        assertNull(ClientFocus.targetedRoot(null, "minecraft:overworld"));
        assertNull(ClientFocus.NONE.targeted());
        assertEquals(-1, ClientFocus.NONE.openedContainerId());
    }
}
