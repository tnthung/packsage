package top.theillusivec4.curios.api;

import net.minecraft.world.entity.LivingEntity;
import top.theillusivec4.curios.api.type.capability.ICuriosItemHandler;

import java.util.Optional;

public final class CuriosApi {
    private static Optional<ICuriosItemHandler> inventory = Optional.empty();
    private static int calls;

    private CuriosApi() { }

    public static Optional<ICuriosItemHandler> getCuriosInventory(LivingEntity entity) {
        calls++;
        return inventory;
    }

    public static void setInventory(Optional<ICuriosItemHandler> value) { inventory = value; }

    public static int calls() { return calls; }

    public static void reset() {
        inventory = Optional.empty();
        calls = 0;
    }
}
