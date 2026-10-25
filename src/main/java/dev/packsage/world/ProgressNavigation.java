package dev.packsage.world;

import dev.packsage.knowledge.Evidence;
import dev.packsage.world.minecraft.MinecraftInventories;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.advancements.AdvancementsScreen;
import net.minecraft.resources.ResourceLocation;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Opens {@code advancement|<root id>|<advancement id>} targets as the advancements screen on the root's tab. The target
 * id is the stable advancement identity: a saved link rebinds to the current scope without reading, and opening looks
 * the advancement up in the world's current advancements and opens its current root's tab, even when the advancement
 * itself is not visible yet. The stored root is only a hint.
 */
final class ProgressNavigation {
    static final String PROVIDER = "progress";
    private static final String PREFIX = "advancement";

    record Destination(ResourceLocation root, ResourceLocation id) {
    }

    /** The player's side: the world's current advancements, the client's advancement tree and screen. */
    interface Client {
        boolean inWorld();

        /** The root of the id in the world's current advancements, or null when the world has no such advancement. */
        ResourceLocation currentRoot(ResourceLocation id);

        /** Whether the client's advancement tree holds the id. */
        boolean visible(ResourceLocation id);

        void openTab(ResourceLocation root);
    }

    private final Client client;

    ProgressNavigation(Client client) {
        this.client = client;
    }

    /** The link for a vanilla advancement; adapters other than vanilla's have no advancement screen, so they get none. */
    static String targetId(ProgressId tree, ProgressId entry) {
        if (!MinecraftInventories.ADAPTER.equals(tree.adapter()) || !MinecraftInventories.ADAPTER.equals(entry.adapter())) return null;
        return PREFIX + "|" + tree.local() + "|" + entry.local();
    }

    /** As {@link #targetId(ProgressId, ProgressId)}, and none for a hidden entry, whose link would reveal it. */
    static String targetId(ProgressHandle handle) {
        return handle.status() == ProgressStatus.HIDDEN ? null : targetId(handle.tree(), handle.id());
    }

    static Destination parse(String targetId) {
        var parts = targetId.split("\\|", -1);
        if (parts.length != 3 || !PREFIX.equals(parts[0])) throw new IllegalArgumentException("Invalid advancement target: " + targetId);
        var root = ResourceLocation.tryParse(parts[1]);
        var id = ResourceLocation.tryParse(parts[2]);
        if (root == null || id == null) throw new IllegalArgumentException("Invalid advancement target: " + targetId);
        return new Destination(root, id);
    }

    /** Rebinds a well-formed saved target to the current scope; visibility is checked when it opens. */
    CompletionStage<Evidence.NavigationTarget> refresh(Evidence.NavigationTarget target, Evidence.Scope scope) {
        if (!PROVIDER.equals(target.providerId())) return CompletableFuture.completedFuture(null);
        try {
            parse(target.targetId());
        } catch (IllegalArgumentException failure) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.completedFuture(new Evidence.NavigationTarget(scope, PROVIDER, target.sourceHandle(), target.targetId()));
    }

    Evidence.NavigationResult open(Evidence.NavigationTarget target) {
        Destination destination;
        try {
            destination = parse(target.targetId());
        } catch (IllegalArgumentException failure) {
            return new Evidence.NavigationResult(Evidence.NavigationStatus.ERROR, failure.getMessage());
        }
        try {
            if (!client.inWorld()) return new Evidence.NavigationResult(Evidence.NavigationStatus.UNAVAILABLE, "the player is not in a world");
            // The stored root is only a hint: a removed advancement opens nothing, and a re-parented one opens its current tab.
            var root = client.currentRoot(destination.id());
            if (root == null) {
                return new Evidence.NavigationResult(Evidence.NavigationStatus.UNAVAILABLE, "advancement " + destination.id() + " is not in this world");
            }
            if (!client.visible(root)) {
                return new Evidence.NavigationResult(Evidence.NavigationStatus.UNAVAILABLE,
                        "the advancements screen does not show the tab of " + destination.id() + " yet");
            }
            client.openTab(root);
            return new Evidence.NavigationResult(Evidence.NavigationStatus.OPENED, null);
        } catch (RuntimeException failure) {
            return new Evidence.NavigationResult(Evidence.NavigationStatus.UNAVAILABLE, failure.getMessage() == null ? failure.toString() : failure.getMessage());
        }
    }

    static Client client(Minecraft minecraft) {
        return new Client() {
            @Override
            public boolean inWorld() {
                return minecraft.level != null && minecraft.getConnection() != null;
            }

            /** Read-only; a reload replaces the server's tree wholesale rather than changing it in place. */
            @Override
            public ResourceLocation currentRoot(ResourceLocation id) {
                var server = minecraft.getSingleplayerServer();
                if (server == null) throw new IllegalStateException("advancement links need an integrated singleplayer server");
                var node = server.getAdvancements().tree().get(id);
                return node == null ? null : node.root().holder().id();
            }

            @Override
            public boolean visible(ResourceLocation id) {
                var connection = minecraft.getConnection();
                return connection != null && connection.getAdvancements().get(id) != null;
            }

            /**
             * Selects the tab without telling the server; the screen's {@code init} selects it again and sends vanilla's
             * single {@code openedTab} packet. Runs on the client thread.
             */
            @Override
            public void openTab(ResourceLocation root) {
                var advancements = minecraft.getConnection().getAdvancements();
                var holder = advancements.get(root);
                if (holder != null) advancements.setSelectedTab(holder, false);
                minecraft.setScreen(new AdvancementsScreen(advancements, minecraft.screen));
            }
        };
    }
}
