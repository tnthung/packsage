package dev.packsage;

import com.mojang.blaze3d.platform.InputConstants;
import dev.packsage.client.ClientAssistant;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import org.lwjgl.glfw.GLFW;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

@Mod(value = PackSage.MOD_ID, dist = Dist.CLIENT)
public final class PackSage {
    public static final String MOD_ID = "packsage";
    private final KeyMapping openAssistant = new KeyMapping("key.packsage.open", InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_P, "key.categories.packsage");
    private ClientAssistant assistant;

    public PackSage(IEventBus modEventBus, ModContainer modContainer) {
        modEventBus.addListener((RegisterKeyMappingsEvent event) -> event.register(openAssistant));
        modEventBus.addListener((RegisterClientReloadListenersEvent event) -> event.registerReloadListener(
                new net.minecraft.server.packs.resources.PreparableReloadListener() {
                    @Override
                    public CompletableFuture<Void> reload(PreparationBarrier barrier,
                                                          net.minecraft.server.packs.resources.ResourceManager resourceManager,
                                                          net.minecraft.util.profiling.ProfilerFiller preparationsProfiler,
                                                          net.minecraft.util.profiling.ProfilerFiller reloadProfiler,
                                                          Executor backgroundExecutor, Executor gameExecutor) {
                        return barrier.wait(null).thenRunAsync(() -> {
                            if (assistant != null) assistant.resourceReloaded();
                        }, gameExecutor);
                    }
                }));
        modEventBus.addListener((net.neoforged.fml.event.lifecycle.FMLClientSetupEvent event) ->
                event.enqueueWork(() -> assistant = new ClientAssistant(openAssistant)));
    }
}
