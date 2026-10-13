package dev.packsage.client;

import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import dev.packsage.chat.ChatContracts;
import dev.packsage.knowledge.Evidence;
import net.minecraft.advancements.critereon.BlockPredicate;
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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
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
                "1", "miss", "third_person_back"));
        var capture = new GameplayContextCapture(observation::get,
                request -> new GameplayContextCapture.CapturedFrame("image/png", new byte[]{1}, Instant.EPOCH),
                Runnable::run, () -> SCOPE);
        var missed = JsonParser.parseString(capture.preview().serializedGameplayContext()).getAsJsonObject();

        assertEquals("miss", missed.get("focusKind").getAsString());
        assertEquals("unavailable", missed.get("targeted").getAsString());
        assertEquals("unavailable", missed.get("hovered").getAsString());
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
        assertNull(preview.screenshot());
        assertNull(actual.context().screenshot());
        assertEquals(3, observationReads.get());
        assertEquals(0, frameCaptures.get());
    }
}
