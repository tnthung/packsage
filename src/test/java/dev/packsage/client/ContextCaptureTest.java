package dev.packsage.client;

import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import dev.packsage.chat.ChatContracts;
import dev.packsage.knowledge.Evidence;
import dev.packsage.world.ClientFocus;
import dev.packsage.world.PathRoot;
import net.minecraft.advancements.critereon.BlockPredicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.AdventureModePredicate;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextCaptureTest {
    private static final Evidence.Scope SCOPE = new Evidence.Scope("world", "en_us", "team", "runtime", 1);
    private static final ChatContracts.WorldIdentity WORLD = new ChatContracts.WorldIdentity("world", "World");

    @BeforeAll
    static void bootstrapMinecraft() {
        Bootstrap.bootStrap();
    }

    @Test
    void heldItemKeepsPersistentMergedComponentsInRegistryAwareForm() {
        var stack = new ItemStack(Items.DIAMOND_PICKAXE);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("Survey Pick"));
        var customData = new CompoundTag();
        customData.putString("packSageMarker", "retained");
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(customData));
        stack.set(DataComponents.CAN_BREAK, new AdventureModePredicate(List.of(
                BlockPredicate.Builder.block().of(BlockTags.MINEABLE_WITH_PICKAXE).build()), true));
        var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);

        var held = GameplayContextCapture.heldItem(stack, registries);

        assertTrue(held.startsWith("item:minecraft:diamond_pickaxe;count=1;name=Survey Pick;components="));
        var components = JsonParser.parseString(held.substring(held.indexOf(";components=") + ";components=".length()))
                .getAsJsonObject();
        assertEquals(1561, components.get("minecraft:max_damage").getAsInt());
        assertEquals(1, components.get("minecraft:max_stack_size").getAsInt());
        assertTrue(components.get("minecraft:custom_name").toString().contains("Survey Pick"));
        assertTrue(components.get("minecraft:custom_data").toString().contains("packSageMarker"));
        assertTrue(components.get("minecraft:custom_data").toString().contains("retained"));
        assertTrue(components.get("minecraft:can_break").toString().contains("mineable/pickaxe"));
        assertFalse(held.contains(stack.getComponents().toString()));

        stack.set(net.minecraft.core.component.DataComponentType.<String>builder().persistent(Codec.STRING).build(),
                "not registered");
        var unavailable = GameplayContextCapture.heldItem(stack, registries);
        assertTrue(unavailable.startsWith("item:minecraft:diamond_pickaxe;count=1;name=Survey Pick;components=unknown"));
        assertFalse(unavailable.contains(stack.getComponents().toString()));
    }

    @Test
    void missedRaysHaveNoBlockTargetAndCameraPerspectiveIsCapturedFresh() {
        var observation = new AtomicReference<>(new GameplayContextCapture.Observation("block:minecraft:air", null,
                "block:minecraft:air", "compactmachines:compact_world", "10,2,-1011", "compactmachines:machine",
                "1", "miss", "third_person_back", null, null, "block:compactmachines:compact_world|10,2,-1012", null));
        var capture = new GameplayContextCapture(observation::get,
                request -> new GameplayContextCapture.CapturedFrame("image/png", new byte[]{1}, Instant.EPOCH),
                Runnable::run, () -> SCOPE);
        var missed = JsonParser.parseString(capture.preview().serializedGameplayContext()).getAsJsonObject();

        assertEquals("miss", missed.get("focusKind").getAsString());
        assertEquals("unavailable", missed.get("targeted").getAsString());
        assertEquals("unavailable", missed.get("hovered").getAsString());
        assertEquals("unavailable", missed.get("targetedHolder").getAsString());
        assertEquals("third_person_back", missed.get("cameraPerspective").getAsString());

        observation.set(new GameplayContextCapture.Observation("block:minecraft:stone", null,
                "block:minecraft:stone", "compactmachines:compact_world", "10,2,-1011", "compactmachines:machine",
                "2", "block", "first_person"));
        var hit = JsonParser.parseString(capture.capture(new ChatContracts.RequestCapture(
                new ChatContracts.RequestId("fresh-perspective"), WORLD, SCOPE), false).toCompletableFuture().join()
                .context().serializedGameplayContext()).getAsJsonObject();
        assertEquals("block:minecraft:stone", hit.get("targeted").getAsString());
        assertEquals("first_person", hit.get("cameraPerspective").getAsString());
    }

    @Test
    void gameplayContextNamesTheTargetedHolderAndTheRiddenEntity() {
        var block = ClientFocus.targetedRoot(new BlockHitResult(Vec3.ZERO, Direction.UP, new BlockPos(1, 64, 2), false),
                "minecraft:overworld");
        var vehicle = UUID.fromString("00000000-0000-0000-0000-000000000007");
        var observation = new AtomicReference<>(new GameplayContextCapture.Observation(null, "held", "target",
                "minecraft:overworld", "1,64,3", "plains", "1", "block", "first_person", null, null,
                GameplayContextCapture.targetedHolder(block), GameplayContextCapture.ridden(vehicle)));
        var capture = new GameplayContextCapture(observation::get,
                request -> new GameplayContextCapture.CapturedFrame("image/png", new byte[]{1}, Instant.EPOCH),
                Runnable::run, () -> SCOPE);
        var riding = JsonParser.parseString(capture.preview().serializedGameplayContext()).getAsJsonObject();
        assertEquals("block:minecraft:overworld|1,64,2", riding.get("targetedHolder").getAsString());
        assertEquals("entity:00000000-0000-0000-0000-000000000007", riding.get("ridden").getAsString());

        var target = UUID.fromString("00000000-0000-0000-0000-000000000009");
        observation.set(new GameplayContextCapture.Observation(null, "held", "target", "minecraft:overworld",
                "1,64,3", "plains", "2", "entity", "first_person", null, null,
                GameplayContextCapture.targetedHolder(new PathRoot.Entity(target)), GameplayContextCapture.ridden(null)));
        var walking = JsonParser.parseString(capture.preview().serializedGameplayContext()).getAsJsonObject();
        assertEquals("entity:00000000-0000-0000-0000-000000000009", walking.get("targetedHolder").getAsString());
        assertEquals("none", walking.get("ridden").getAsString());

        observation.set(new GameplayContextCapture.Observation("held", "target", "overworld", "1,2,3", "plains",
                "1", "block"));
        var unknown = JsonParser.parseString(capture.preview().serializedGameplayContext()).getAsJsonObject();
        assertEquals("unavailable", unknown.get("targetedHolder").getAsString());
        assertEquals("none", unknown.get("ridden").getAsString());
        assertNull(GameplayContextCapture.targetedHolder(null));
    }

    private static final class MenuStub extends net.minecraft.world.inventory.AbstractContainerMenu {
        private MenuStub(net.minecraft.world.inventory.MenuType<?> type) {
            super(type, 1);
        }

        @Override
        public ItemStack quickMoveStack(net.minecraft.world.entity.player.Player player, int index) {
            return ItemStack.EMPTY;
        }

        @Override
        public boolean stillValid(net.minecraft.world.entity.player.Player player) {
            return true;
        }
    }

    @Test
    void openedMenuDefaultsToNoneAndReportsTheMenuTypeKey() {
        var observation = new AtomicReference<>(new GameplayContextCapture.Observation("held", "target", "overworld",
                "1,2,3", "plains", "1", "block"));
        var capture = new GameplayContextCapture(observation::get,
                request -> new GameplayContextCapture.CapturedFrame("image/png", new byte[]{1}, Instant.EPOCH),
                Runnable::run, () -> SCOPE);
        var none = JsonParser.parseString(capture.preview().serializedGameplayContext()).getAsJsonObject();
        assertEquals("none", none.get("openedMenu").getAsString());
        assertFalse(none.has("openedMenuTitle"));

        observation.set(new GameplayContextCapture.Observation(null, "held", "target", "overworld", "1,2,3", "plains",
                "1", "block", null, "minecraft:crafting", "Crafting"));
        var open = JsonParser.parseString(capture.preview().serializedGameplayContext()).getAsJsonObject();
        assertEquals("minecraft:crafting", open.get("openedMenu").getAsString());
        assertEquals("Crafting", open.get("openedMenuTitle").getAsString());
    }

    @Test
    void openedMenuKeyNeverReadsTheTypeOfThePlayerInventoryMenu() {
        var inventoryMenu = new MenuStub(null);
        assertEquals("none", GameplayContextCapture.openedMenuKey(inventoryMenu, inventoryMenu));
        assertEquals("unknown", GameplayContextCapture.openedMenuKey(new MenuStub(null), inventoryMenu));
        assertEquals("minecraft:crafting", GameplayContextCapture.openedMenuKey(
                new MenuStub(net.minecraft.world.inventory.MenuType.CRAFTING), inventoryMenu));
    }

    @Test
    void openedMenuTitleIsPlainTextWithinTheBound() {
        assertEquals("Crafting", GameplayContextCapture.openedMenuTitle(Component.literal("Crafting")));
        assertEquals("x".repeat(64), GameplayContextCapture.openedMenuTitle(Component.literal("x".repeat(64))));
        assertNull(GameplayContextCapture.openedMenuTitle(Component.literal("x".repeat(65))));
        assertNull(GameplayContextCapture.openedMenuTitle(null));
    }

    @Test
    void cancellingAnOldRequestCannotCancelANewerCapture() {
        var queued = new java.util.ArrayDeque<Runnable>();
        var capture = new GameplayContextCapture(
                () -> new GameplayContextCapture.Observation("held", "target", "overworld", "1,2,3", "plains", "1", "block"),
                request -> new GameplayContextCapture.CapturedFrame("image/png", new byte[]{1}, Instant.EPOCH),
                queued::add, () -> SCOPE);
        var newer = new ChatContracts.RequestId("new");
        var result = capture.capture(new ChatContracts.RequestCapture(newer, WORLD, SCOPE), true).toCompletableFuture();

        capture.cancel(new ChatContracts.RequestId("old"));
        while (!queued.isEmpty()) queued.remove().run();

        assertEquals("new.png", result.join().context().screenshot().assetId());
    }

    @Test
    void captureUsesFreshImmutableObservationAndOptionalScreenshot() {
        Executor direct = Runnable::run;
        var observations = new GameplayContextCapture.ObservationSource() {
            private int count;
            @Override public GameplayContextCapture.Observation capture() {
                count++;
                return new GameplayContextCapture.Observation("held-" + count, "target-" + count,
                        "overworld", "1,2,3", "plains", "time-" + count, "crosshair");
            }
        };
        var frames = (GameplayContextCapture.FrameCapture) request ->
                new GameplayContextCapture.CapturedFrame("image/png", new byte[]{1, 2, 3}, Instant.now());
        GameplayContextCapture capture = new GameplayContextCapture(observations, frames, direct, () -> SCOPE);

        ChatContracts.CapturedContext first = capture.capture(new ChatContracts.RequestCapture(
                new ChatContracts.RequestId("one"), WORLD, SCOPE), true).toCompletableFuture().join();
        ChatContracts.CapturedContext second = capture.capture(new ChatContracts.RequestCapture(
                new ChatContracts.RequestId("two"), WORLD, SCOPE), false).toCompletableFuture().join();

        assertNotEquals(first.context().serializedGameplayContext(), second.context().serializedGameplayContext());
        assertEquals("one.png", first.context().screenshot().assetId());
        assertEquals(3, first.context().screenshot().bytes());
        assertArrayEquals(new byte[]{1, 2, 3}, first.screenshotBytes());
        byte[] copy = first.screenshotBytes();
        copy[0] = 9;
        assertArrayEquals(new byte[]{1, 2, 3}, first.screenshotBytes());
        assertNull(second.context().screenshot());
        assertEquals(0, second.screenshotBytes().length);
    }

    @Test
    void gameplayContextIncludesLocalPlayerIdentityWithAndWithoutScreenshot() {
        var capture = new GameplayContextCapture(
                () -> new GameplayContextCapture.Observation("hovered", "held", "target", "overworld", "1,2,3", "plains",
                        "1", "block", "first_person", "none", null, null, null,
                        "Alex", "12345678-1234-5678-9abc-def012345678"),
                request -> new GameplayContextCapture.CapturedFrame("image/png", new byte[]{1}, Instant.EPOCH),
                Runnable::run, () -> SCOPE);

        for (boolean screenshot : List.of(false, true)) {
            var context = capture.capture(new ChatContracts.RequestCapture(
                    new ChatContracts.RequestId("identity"), WORLD, SCOPE), screenshot).toCompletableFuture().join();
            var json = JsonParser.parseString(context.context().serializedGameplayContext()).getAsJsonObject();
            assertEquals("Alex", json.get("playerName").getAsString());
            assertEquals("12345678-1234-5678-9abc-def012345678", json.get("playerUuid").getAsString());
        }
    }

    @Test
    void gameplayContextIncludesPlayerIdentityEvenWhenUnavailable() {
        var capture = new GameplayContextCapture(
                () -> new GameplayContextCapture.Observation("held", "target", "overworld", "1,2,3", "plains", "1", "block"),
                request -> new GameplayContextCapture.CapturedFrame("image/png", new byte[]{1}, Instant.EPOCH),
                Runnable::run, () -> SCOPE);

        var context = capture.capture(new ChatContracts.RequestCapture(
                new ChatContracts.RequestId("identity"), WORLD, SCOPE), false).toCompletableFuture().join();
        var json = JsonParser.parseString(context.context().serializedGameplayContext()).getAsJsonObject();
        assertEquals("unavailable", json.get("playerName").getAsString());
        assertEquals("unavailable", json.get("playerUuid").getAsString());
    }

    @Test
    void screenshotReferenceUsesRequestAssetAndEscapesGameplayValues() {
        Executor direct = Runnable::run;
        GameplayContextCapture capture = new GameplayContextCapture(
                () -> new GameplayContextCapture.Observation("hovered", "held\"\\\n", "target", "overworld",
                        "1,2,3", "plains", "1", "block"),
                request -> new GameplayContextCapture.CapturedFrame("image/png", new byte[]{1}, Instant.EPOCH),
                direct, () -> SCOPE);

        ChatContracts.CapturedContext captured = capture.capture(new ChatContracts.RequestCapture(
                new ChatContracts.RequestId("request-id"), WORLD, SCOPE), true).toCompletableFuture().join();

        JsonParser.parseString(captured.context().serializedGameplayContext());
        assertEquals("request-id.png", captured.context().screenshot().assetId());
        assertEquals(1, captured.screenshotBytes().length);
    }

    @Test
    void changedScopePreventsPublishingScreenshotReference() {
        Executor direct = Runnable::run;
        Evidence.Scope other = new Evidence.Scope("other-world", "en_us", "team", "runtime", 2);
        AtomicReference<Evidence.Scope> current = new AtomicReference<>(SCOPE);
        GameplayContextCapture capture = new GameplayContextCapture(
                () -> new GameplayContextCapture.Observation("held", "target", "overworld", "1,2,3", "plains", "1", "block"),
                request -> {
                    current.set(other);
                    return new GameplayContextCapture.CapturedFrame("image/png", new byte[]{1}, Instant.EPOCH);
                }, direct, current::get);

        var result = capture.capture(new ChatContracts.RequestCapture(
                new ChatContracts.RequestId("request-id"), WORLD, SCOPE), true).toCompletableFuture();

        assertThrows(java.util.concurrent.CompletionException.class, result::join);
    }

    @Test
    void previewReadsLiveGameplayAndMatchesNextRequestWithoutStartingCapture() {
        Executor direct = Runnable::run;
        AtomicReference<GameplayContextCapture.Observation> observation = new AtomicReference<>(
                new GameplayContextCapture.Observation("hovered-one", "held-one", "target-one", "overworld",
                        "1,2,3", "plains", "100", "block"));
        AtomicInteger observationReads = new AtomicInteger();
        AtomicInteger frameCaptures = new AtomicInteger();
        GameplayContextCapture capture = new GameplayContextCapture(
                () -> {
                    observationReads.incrementAndGet();
                    return observation.get();
                },
                request -> {
                    frameCaptures.incrementAndGet();
                    return new GameplayContextCapture.CapturedFrame("image/png", new byte[]{1}, Instant.EPOCH);
                }, direct, () -> SCOPE);

        ChatContracts.Context firstPreview = capture.preview();
        observation.set(new GameplayContextCapture.Observation("hovered-two", "held-two", "target-two", "nether",
                "4,5,6", "crimson_forest", "200", "entity"));
        ChatContracts.Context preview = capture.preview();
        ChatContracts.CapturedContext actual = capture.capture(new ChatContracts.RequestCapture(
                new ChatContracts.RequestId("preview-check"), WORLD, SCOPE), false).toCompletableFuture().join();

        var firstJson = JsonParser.parseString(firstPreview.serializedGameplayContext()).getAsJsonObject();
        var previewJson = JsonParser.parseString(preview.serializedGameplayContext()).getAsJsonObject();
        var actualJson = JsonParser.parseString(actual.context().serializedGameplayContext()).getAsJsonObject();
        for (String key : new String[]{"hovered", "held", "targeted", "dimension", "position", "biome", "time", "focusKind"}) {
            assertEquals(previewJson.get(key), actualJson.get(key), key);
        }
        assertEquals("hovered-one", firstJson.get("hovered").getAsString());
        assertEquals("hovered-two", previewJson.get("hovered").getAsString());
        assertEquals("crimson_forest", previewJson.get("biome").getAsString());
        var local = java.time.OffsetDateTime.parse(actualJson.get("playerLocalTime").getAsString());
        assertEquals(java.time.Instant.parse(actualJson.get("capturedAt").getAsString()).getEpochSecond(), local.toEpochSecond());
        assertEquals(java.time.ZoneId.systemDefault().getRules().getOffset(local.toInstant()), local.getOffset());
        assertNull(preview.screenshot());
        assertNull(actual.context().screenshot());
        assertEquals(3, observationReads.get());
        assertEquals(0, frameCaptures.get());
    }
}
