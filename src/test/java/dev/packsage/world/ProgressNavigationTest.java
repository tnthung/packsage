package dev.packsage.world;

import dev.packsage.knowledge.Evidence;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ProgressNavigationTest {
    private static final Evidence.Scope SCOPE = new Evidence.Scope("world", "resources", "team", "runtime", 1);
    private static final Evidence.Scope RELOADED = new Evidence.Scope("world", "resources-2", "team", "runtime-2", 7);
    private static final ProgressId ROOT = new ProgressId("minecraft", "minecraft:story/root");
    private static final ProgressId MINE_STONE = new ProgressId("minecraft", "minecraft:story/mine_stone");
    private static final String TARGET = "advancement|minecraft:story/root|minecraft:story/mine_stone";

    private static final class FakeClient implements ProgressNavigation.Client {
        boolean inWorld = true;
        Set<String> visible = Set.of();
        final List<ResourceLocation> opened = new ArrayList<>();

        @Override
        public boolean inWorld() {
            return inWorld;
        }

        @Override
        public boolean visible(ResourceLocation id) {
            return visible.contains(id.toString());
        }

        @Override
        public void openTab(ResourceLocation root) {
            opened.add(root);
        }
    }

    private static Evidence.NavigationTarget target(String targetId) {
        return new Evidence.NavigationTarget(SCOPE, ProgressNavigation.PROVIDER, "progress|minecraft/minecraft:story/mine_stone", targetId);
    }

    @Test
    void targetIdsRoundTripAndHiddenHandlesGetNone() {
        assertEquals(TARGET, ProgressNavigation.targetId(ROOT, MINE_STONE));
        assertEquals(new ProgressNavigation.Destination(ResourceLocation.parse("minecraft:story/root"),
                ResourceLocation.parse("minecraft:story/mine_stone")), ProgressNavigation.parse(TARGET));
        assertEquals(TARGET, ProgressNavigation.targetId(new ProgressHandle(MINE_STONE, ROOT, "Stone Age", ProgressStatus.DONE)));
        assertNull(ProgressNavigation.targetId(new ProgressHandle(MINE_STONE, ROOT, null, ProgressStatus.HIDDEN)));
        assertNull(ProgressNavigation.targetId(new ProgressId("ftbquests", "chapter"), new ProgressId("ftbquests", "quest")));
    }

    @Test
    void opensTheRootTabWhenTheAdvancementIsNotVisibleYet() {
        var client = new FakeClient();
        client.visible = Set.of("minecraft:story/root");

        var result = new ProgressNavigation(client).open(target(TARGET));

        assertEquals(Evidence.NavigationStatus.OPENED, result.status());
        assertEquals(List.of(ResourceLocation.parse("minecraft:story/root")), client.opened);
    }

    @Test
    void missingAdvancementIsUnavailableAndOpensNothing() {
        var client = new FakeClient();
        var navigation = new ProgressNavigation(client);

        assertEquals(new Evidence.NavigationResult(Evidence.NavigationStatus.UNAVAILABLE, "advancement minecraft:story/mine_stone is not in this world"),
                navigation.open(target(TARGET)));
        client.visible = Set.of("minecraft:story/root", "minecraft:story/mine_stone");
        client.inWorld = false;
        assertEquals(Evidence.NavigationStatus.UNAVAILABLE, navigation.open(target(TARGET)).status());
        assertEquals(Evidence.NavigationStatus.ERROR, navigation.open(target("advancement|minecraft:story/root")).status());
        assertEquals(Evidence.NavigationStatus.ERROR, navigation.open(target("advancement|Bad Root|minecraft:story/mine_stone")).status());
        assertEquals(List.of(), client.opened);
    }

    @Test
    void savedTargetsRebindToANewScope() throws Exception {
        var navigation = new ProgressNavigation(new FakeClient());

        var refreshed = navigation.refresh(target(TARGET), RELOADED).toCompletableFuture().get();

        assertEquals(new Evidence.NavigationTarget(RELOADED, ProgressNavigation.PROVIDER, "progress|minecraft/minecraft:story/mine_stone", TARGET), refreshed);
        assertNull(navigation.refresh(target("advancement|minecraft:story/root"), RELOADED).toCompletableFuture().get());
        assertNull(navigation.refresh(new Evidence.NavigationTarget(SCOPE, "world", "handle", TARGET), RELOADED).toCompletableFuture().get());
    }
}
