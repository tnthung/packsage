package dev.packsage;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.ModContainer;

@Mod(value = PackSage.MOD_ID, dist = Dist.CLIENT)
public final class PackSage {
    public static final String MOD_ID = "packsage";

    public PackSage(IEventBus modEventBus, ModContainer modContainer) {
    }
}
