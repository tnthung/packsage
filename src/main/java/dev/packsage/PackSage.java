package dev.packsage;

import dev.packsage.model.chatgpt.ChatGptAuth;
import dev.packsage.model.chatgpt.ChatGptBackend;
import dev.packsage.model.chatgpt.ChatGptProbeScreen;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.ModContainer;

@Mod(value = PackSage.MOD_ID, dist = Dist.CLIENT)
public final class PackSage {
    public static final String MOD_ID = "packsage";
    private final KeyMapping openAssistant = new KeyMapping("key.packsage.open", InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_P, "key.categories.packsage");
    private ChatGptBackend probeBackend;
    private ChatGptProbeScreen probeScreen;

    public PackSage(IEventBus modEventBus, ModContainer modContainer) {
        modEventBus.addListener((RegisterKeyMappingsEvent event) -> event.register(openAssistant));
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> {
            var client = Minecraft.getInstance();
            while (openAssistant.consumeClick()) {
                if (client.level == null) continue;
                if (probeBackend == null) probeBackend = new ChatGptBackend(new ChatGptAuth(ChatGptAuth.defaultCredentialFile()));
                if (probeScreen == null) probeScreen = new ChatGptProbeScreen(probeBackend);
                client.setScreen(probeScreen);
            }
        });
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> {
            if (probeScreen != null) probeScreen.cancelRequest();
            if (probeBackend != null) probeBackend.cancelSignIn();
        });
    }
}
