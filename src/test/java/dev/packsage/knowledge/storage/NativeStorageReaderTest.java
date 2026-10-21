package dev.packsage.knowledge.storage;

import dev.packsage.knowledge.Evidence;
import dev.packsage.knowledge.nativegame.NativeInventoryReader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.entity.animal.horse.Horse;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.CartographyTableMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.CrafterMenu;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.inventory.GrindstoneMenu;
import net.minecraft.world.inventory.LoomMenu;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.inventory.MerchantResultSlot;
import net.minecraft.world.inventory.NonInteractiveResultSlot;
import net.minecraft.world.inventory.StonecutterMenu;
import net.minecraft.world.inventory.FurnaceFuelSlot;
import net.minecraft.world.inventory.FurnaceMenu;
import net.minecraft.world.inventory.FurnaceResultSlot;
import net.minecraft.world.inventory.HorseInventoryMenu;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.TransientCraftingContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeStorageReaderTest {
    private static final long FIVE_MINUTES_NANOS = java.util.concurrent.TimeUnit.MINUTES.toNanos(5);
    private static final Evidence.Scope FIRST = new Evidence.Scope("world", "resources", "team", "runtime", 1);
    private static final Evidence.Scope SECOND = new Evidence.Scope("world", "resources", "team", "runtime", 2);

    @BeforeAll
    static void bootstrapMinecraft() {
        Bootstrap.bootStrap();
    }

    @Test
    void pageRebindsScopeButKeepsObservationGenerationAndTime() {
        var entries = List.of(record(FIRST, "minecraft:stone", "Stone"), record(FIRST, "minecraft:dirt", "Dirt"));
        var request = new StorageReader.Request(StorageReader.Target.OPENED, null, null, List.of(), 1, null, SECOND);
        var result = NativeStorageReader.pageRecords(entries, request, 0, "snapshot", Evidence.Coverage.PARTIAL, Instant.EPOCH);

        assertEquals(Evidence.Outcome.PARTIAL, result.outcome());
        assertEquals(1, result.records().size());
        var page = result.records().getFirst();
        assertEquals("storage:snapshot:0", page.source().sourceHandle());
        assertEquals(Instant.EPOCH, page.source().observedAt());
        assertEquals(SECOND, page.source().scope());
        assertEquals(FIRST.requestGeneration(), page.source().observedGeneration());
        assertFalse(page.payload().values().containsKey("nextOffset"));
        assertEquals("partial", ((Evidence.JsonString) ((page.payload()).values().get("completeness"))).value());
        assertEquals(1, ((Evidence.JsonArray) ((page.payload()).values().get("entries"))).values().size());
    }

    @Test
    void finalPageHasNoContinuation() {
        var request = new StorageReader.Request(StorageReader.Target.ITEM, "native:inventory:fixture:main:5", null, List.of(), 2, null, FIRST);
        var result = NativeStorageReader.pageRecords(List.of(record(FIRST, "minecraft:stone", "Stone")), request, 0,
                "held", Evidence.Coverage.COMPLETE, Instant.EPOCH);

        assertNull(result.nextCursor());
        assertEquals(Evidence.Outcome.COMPLETE, result.outcome());
        assertEquals(1, ((Evidence.JsonArray) ((result.records().getFirst().payload()).values().get("entries"))).values().size());
    }

    @Test
    void openedCaptureExcludesPlayerSlotsAndPreservesDuplicateMenuSlots() {
        var storage = new SimpleContainer(new ItemStack(Items.STONE), new ItemStack(Items.STONE));
        var playerInventory = new SimpleContainer(new ItemStack(Items.DIRT));
        var menu = new TestMenu();
        menu.slots.add(new Slot(storage, 0, 0, 0));
        menu.slots.add(new Slot(playerInventory, 0, 0, 0));
        menu.slots.add(new Slot(storage, 1, 0, 0));
        var captured = NativeStorageReader.openedEntries(menu, playerInventory, FIRST, Instant.EPOCH,
                RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));

        assertEquals(Evidence.Coverage.COMPLETE, captured.coverage());
        assertEquals(2, captured.entries().size());
        assertEquals(0, ((Evidence.JsonNumber) ((captured.entries().getFirst().payload()).values().get("menuSlot"))).value().intValueExact());
        assertEquals(2, ((Evidence.JsonNumber) ((captured.entries().get(1).payload()).values().get("menuSlot"))).value().intValueExact());
        assertEquals("minecraft:stone", ((Evidence.JsonString) ((captured.entries().getFirst().payload()).values().get("id"))).value());
        assertEquals("minecraft:stone", ((Evidence.JsonString) ((captured.entries().get(1).payload()).values().get("id"))).value());
        assertNotEquals(captured.entries().getFirst().source().sourceHandle(),
                captured.entries().get(1).source().sourceHandle());
    }

    @Test
    void projectionKindRecognizesVanillaResultSlots() {
        assertEquals("recipe_preview", NativeStorageReader.projectionKind(CraftingMenu.class, ResultSlot.class, 0, -1));
        assertEquals("recipe_preview", NativeStorageReader.projectionKind(CrafterMenu.class, NonInteractiveResultSlot.class, 45, -1));
        assertEquals("trade_preview", NativeStorageReader.projectionKind(MerchantMenu.class, MerchantResultSlot.class, 2, -1));
        assertEquals("recipe_preview", NativeStorageReader.projectionKind(GrindstoneMenu.class, Slot.class, 2, -1));
        assertEquals("recipe_preview", NativeStorageReader.projectionKind(StonecutterMenu.class, Slot.class, 1, -1));
        assertEquals("recipe_preview", NativeStorageReader.projectionKind(LoomMenu.class, Slot.class, 3, -1));
        assertEquals("recipe_preview", NativeStorageReader.projectionKind(CartographyTableMenu.class, Slot.class, 2, -1));
        assertEquals("recipe_preview", NativeStorageReader.projectionKind(AnvilMenu.class, Slot.class, 2, 2));
        assertNull(NativeStorageReader.projectionKind(GrindstoneMenu.class, Slot.class, 0, -1));
        assertNull(NativeStorageReader.projectionKind(EnchantmentMenu.class, Slot.class, 0, -1));
        assertNull(NativeStorageReader.projectionKind(ChestMenu.class, Slot.class, 2, -1));
    }

    @Test
    void openedCaptureExcludesPreviewSlotsWithoutLosingCompleteCoverage() {
        var menu = new TestMenu();
        var stock = new SimpleContainer(1);
        stock.setItem(0, new ItemStack(Items.OAK_PLANKS, 4));
        var result = new SimpleContainer(1);
        result.setItem(0, new ItemStack(Items.CRAFTING_TABLE));
        menu.slots.add(new Slot(stock, 0, 0, 0));
        menu.slots.add(new NonInteractiveResultSlot(result, 0, 0, 0));
        var captured = NativeStorageReader.openedEntries(menu, new SimpleContainer(1), FIRST, Instant.EPOCH,
                RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
        assertEquals(List.of("minecraft:oak_planks"), captured.entries().stream().map(Evidence.Record::title).toList());
        assertEquals(List.of("1=recipe_preview"), captured.projections());
        assertEquals(Evidence.Coverage.COMPLETE, captured.coverage());
    }

    @Test
    void openedCaptureOnlyMarksTruncatedWhenAnotherStorageItemWasOmitted() {
        var storage = new SimpleContainer(256);
        var playerInventory = new SimpleContainer(new ItemStack(Items.DIRT));
        var menu = new TestMenu();
        for (int index = 0; index < 256; index++) {
            storage.setItem(index, new ItemStack(Items.STONE));
            menu.slots.add(new Slot(storage, index, 0, 0));
        }
        menu.slots.add(new Slot(playerInventory, 0, 0, 0));

        var captured = NativeStorageReader.openedEntries(menu, playerInventory, FIRST, Instant.EPOCH,
                RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));

        assertEquals(256, captured.entries().size());
        assertFalse(captured.truncated());
        assertEquals(Evidence.Coverage.COMPLETE, captured.coverage());
    }

    @Test
    void pageBudgetCreatesAProgressingContinuation() {
        var component = new Evidence.JsonObject(Map.of("minecraft:custom_name", new Evidence.JsonString("x".repeat(8_000))));
        var entries = new ArrayList<Evidence.Record>();
        var identities = new ArrayList<String>();
        for (int index = 0; index < 16; index++) {
            var id = "test:item_" + index;
            identities.add(id);
            entries.add(record(FIRST, id, "Item " + index, component));
        }
        var reader = openedReader(new AtomicLong(0), () -> FIRST, entries);
        var first = reader.read(new StorageReader.Request(StorageReader.Target.OPENED, null, null, List.of(), 32, null, FIRST))
                .toCompletableFuture().join();
        var firstPage = first.records().getFirst().payload();
        var firstEntries = ((Evidence.JsonArray) ((firstPage).values().get("entries")));
        var returned = firstEntries.values().size();

        assertTrue(returned > 0);
        assertTrue(returned < entries.size());
        assertNotNull(first.nextCursor());
        assertFalse(firstPage.values().containsKey("nextOffset"));
        assertTrue(Evidence.serialize(firstPage).getBytes(StandardCharsets.UTF_8).length <= 96 * 1024);

        var second = reader.read(new StorageReader.Request(StorageReader.Target.OPENED, null, null, List.of(), 32, first.nextCursor(), FIRST))
                .toCompletableFuture().join();
        var returnedIdentities = new ArrayList<String>();
        for (var entry : firstEntries.values()) {
            returnedIdentities.add(((Evidence.JsonString) ((((Evidence.JsonObject) (((Evidence.JsonObject) (entry)).values().get("payload")))).values().get("id"))).value());
        }
        for (var entry : ((Evidence.JsonArray) ((second.records().getFirst().payload()).values().get("entries"))).values()) {
            returnedIdentities.add(((Evidence.JsonString) ((((Evidence.JsonObject) (((Evidence.JsonObject) (entry)).values().get("payload")))).values().get("id"))).value());
        }
        assertEquals(identities, returnedIdentities);
        assertNull(second.nextCursor());
        assertTrue(Evidence.serialize(second.records().getFirst().payload()).getBytes(StandardCharsets.UTF_8).length <= 96 * 1024);
    }

    @Test
    void openedCursorRereadsCurrentContentsAfterANewerCapture() {
        var now = new AtomicLong(100);
        var reader = new NativeStorageReader(Runnable::run, () -> FIRST, now::get);
        reader.storeOpenedFixture(List.of(record(FIRST, "minecraft:stone", "Stone"), record(FIRST, "minecraft:dirt", "Dirt")),
                FIRST, Instant.EPOCH, now.get(), "Old chest", Map.of("completeness", new Evidence.JsonString("partial")), Evidence.Coverage.PARTIAL);
        var first = reader.read(new StorageReader.Request(StorageReader.Target.OPENED, null, null, List.of(), 1, null, FIRST))
                .toCompletableFuture().join();
        var oldCursor = first.nextCursor();
        assertNotNull(oldCursor);

        reader.storeOpenedFixture(List.of(record(FIRST, "minecraft:diamond", "Diamond"), record(FIRST, "minecraft:emerald", "Emerald")), FIRST, Instant.ofEpochSecond(1),
                now.get(), "New chest", Map.of("completeness", new Evidence.JsonString("partial")), Evidence.Coverage.PARTIAL);
        var second = reader.read(new StorageReader.Request(StorageReader.Target.OPENED, null, null, List.of(), 1, oldCursor, FIRST))
                .toCompletableFuture().join();
        var secondEntry = ((Evidence.JsonObject) (((Evidence.JsonArray) (second.records().getFirst().payload().values().get("entries"))).values().getFirst()));
        var secondPayload = ((Evidence.JsonObject) (secondEntry.values().get("payload")));

        assertEquals("minecraft:emerald", ((Evidence.JsonString) ((secondPayload).values().get("id"))).value());
        assertEquals(Instant.ofEpochSecond(1), second.records().getFirst().source().observedAt());
        var firstSourceHandle = first.records().getFirst().source().sourceHandle();
        assertEquals(firstSourceHandle.substring(0, firstSourceHandle.lastIndexOf(':')) + ":1",
                second.records().getFirst().source().sourceHandle());
        assertNull(second.nextCursor());
    }

    @Test
    void openedCursorRejectsAChangedOwnerIdentity() {
        var now = new AtomicLong(100);
        var reader = new NativeStorageReader(Runnable::run, () -> FIRST, now::get);
        reader.storeOpenedFixture(List.of(record(FIRST, "minecraft:stone", "Stone"), record(FIRST, "minecraft:dirt", "Dirt")), FIRST, Instant.EPOCH, now.get(),
                "Opened inventory", Map.of("ownerIdentity", new Evidence.JsonString("old-owner")), Evidence.Coverage.PARTIAL);
        var first = reader.read(new StorageReader.Request(StorageReader.Target.OPENED, null, null, List.of(), 1, null, FIRST))
                .toCompletableFuture().join();
        reader.storeOpenedFixture(List.of(record(FIRST, "minecraft:diamond", "Diamond")), FIRST, Instant.ofEpochSecond(1), now.get(),
                "Opened inventory", Map.of("ownerIdentity", new Evidence.JsonString("new-owner")), Evidence.Coverage.PARTIAL);

        var changed = reader.read(new StorageReader.Request(StorageReader.Target.OPENED, null, null, List.of(), 1, first.nextCursor(), FIRST))
                .toCompletableFuture().join();

        assertEquals(Evidence.Outcome.UNKNOWN, changed.outcome());
        assertEquals("inventory target changed; repeat the lookup", changed.detail());
    }

    @Test
    void cursorRejectsDifferentQueryAndRequestGeneration() {
        var reader = openedReader(new AtomicLong(0), () -> FIRST,
                List.of(record(FIRST, "minecraft:stone", "Stone"), record(FIRST, "minecraft:dirt", "Dirt")));
        var first = reader.read(new StorageReader.Request(StorageReader.Target.OPENED, null, null, List.of(), 1, null, FIRST))
                .toCompletableFuture().join();

        var wrongFilter = reader.read(new StorageReader.Request(StorageReader.Target.OPENED, null, "dirt", List.of(), 1, first.nextCursor(), FIRST))
                .toCompletableFuture().join();
        assertEquals(Evidence.Outcome.UNKNOWN, wrongFilter.outcome());
        assertEquals("storage cursor does not match this query or scope", wrongFilter.detail());
        var corrected = reader.read(new StorageReader.Request(StorageReader.Target.OPENED, null, null, List.of(), 1,
                first.nextCursor(), FIRST)).toCompletableFuture().join();
        assertEquals(Evidence.Outcome.PARTIAL, corrected.outcome());
        assertEquals("minecraft:dirt", ((Evidence.JsonString) ((Evidence.JsonObject) ((Evidence.JsonObject)
                ((Evidence.JsonArray) corrected.records().getFirst().payload().values().get("entries")).values()
                        .getFirst()).values().get("payload")).values().get("id")).value());

        var readerWithNewGeneration = openedReader(new AtomicLong(0), () -> FIRST,
                List.of(record(FIRST, "minecraft:stone", "Stone"), record(FIRST, "minecraft:dirt", "Dirt")));
        var firstAtOldGeneration = readerWithNewGeneration.read(new StorageReader.Request(StorageReader.Target.OPENED, null, null, List.of(), 1, null, FIRST))
                .toCompletableFuture().join();
        var newScope = new Evidence.Scope("world", "resources", "team", "runtime", 2);
        var changedGeneration = readerWithNewGeneration.read(new StorageReader.Request(StorageReader.Target.OPENED, null, null, List.of(), 1, firstAtOldGeneration.nextCursor(), newScope)).toCompletableFuture().join();
        assertEquals(Evidence.Outcome.UNKNOWN, changedGeneration.outcome());
        assertEquals("storage cursor does not match this query or scope", changedGeneration.detail());
    }

    @Test
    void openedObservationExpiresAndClearRemovesIt() {
        var now = new AtomicLong(5);
        var reader = openedReader(now, () -> FIRST, List.of(record(FIRST, "minecraft:stone", "Stone")));
        assertEquals(Evidence.Outcome.PARTIAL,
                reader.read(new StorageReader.Request(StorageReader.Target.OPENED, null, null, List.of(), 1, null, FIRST)).toCompletableFuture().join().outcome());

        now.set(FIVE_MINUTES_NANOS + 5);
        var expired = reader.read(new StorageReader.Request(StorageReader.Target.OPENED, null, null, List.of(), 1, null, FIRST))
                .toCompletableFuture().join();
        assertEquals(Evidence.Outcome.UNKNOWN, expired.outcome());

        reader.storeOpenedFixture(List.of(record(FIRST, "minecraft:stone", "Stone")), FIRST, Instant.EPOCH, now.get(),
                "Chest", Map.of(), Evidence.Coverage.PARTIAL);
        reader.clear();
        assertEquals(Evidence.Outcome.UNKNOWN,
                reader.read(new StorageReader.Request(StorageReader.Target.OPENED, null, null, List.of(), 1, null, FIRST)).toCompletableFuture().join().outcome());
    }

    @Test
    void cursorTtlUsesOriginalObservationTime() {
        var now = new AtomicLong(100);
        var reader = openedReader(now, () -> FIRST,
                List.of(record(FIRST, "minecraft:stone", "Stone"), record(FIRST, "minecraft:dirt", "Dirt"),
                        record(FIRST, "minecraft:diamond", "Diamond")));
        var first = reader.read(new StorageReader.Request(StorageReader.Target.OPENED, null, null, List.of(), 1, null, FIRST))
                .toCompletableFuture().join();
        now.set(100 + java.util.concurrent.TimeUnit.MINUTES.toNanos(4));
        var second = reader.read(new StorageReader.Request(StorageReader.Target.OPENED, null, null, List.of(), 1, first.nextCursor(), FIRST))
                .toCompletableFuture().join();
        assertNotNull(second.nextCursor());
        now.set(100 + FIVE_MINUTES_NANOS + 1);

        var expired = reader.read(new StorageReader.Request(StorageReader.Target.OPENED, null, null, List.of(), 1, second.nextCursor(), FIRST))
                .toCompletableFuture().join();
        assertEquals(Evidence.Outcome.UNKNOWN, expired.outcome());
        assertEquals("storage cursor is unknown or expired; repeat the lookup", expired.detail());
    }

    @Test
    void openedObservationRebindsOnlyWithinTheSameBaseScope() {
        var currentScope = new AtomicReference<>(SECOND);
        var reader = openedReader(new AtomicLong(0), currentScope::get, List.of(record(FIRST, "minecraft:stone", "Stone")));
        var rebound = reader.read(new StorageReader.Request(StorageReader.Target.OPENED, null, null, List.of(), 1, null, SECOND))
                .toCompletableFuture().join();
        assertEquals(Evidence.Outcome.PARTIAL, rebound.outcome());
        assertEquals(SECOND, rebound.records().getFirst().source().scope());
        assertEquals(FIRST.requestGeneration(), rebound.records().getFirst().source().observedGeneration());

        var changedScope = new Evidence.Scope("other-world", "resources", "team", "runtime", 2);
        currentScope.set(changedScope);
        var invalidated = reader.read(new StorageReader.Request(StorageReader.Target.OPENED, null, null, List.of(), 1, null, changedScope))
                .toCompletableFuture().join();
        assertEquals(Evidence.Outcome.UNKNOWN, invalidated.outcome());
    }

    @Test
    void identityAndTextFiltersApplyToTheCapturedEntries() {
        var reader = openedReader(new AtomicLong(0), () -> FIRST,
                List.of(record(FIRST, "minecraft:stone", "Stone block",
                        new Evidence.JsonObject(Map.of("minecraft:custom_name", new Evidence.JsonString("hidden phrase")))),
                        record(FIRST, "minecraft:dirt", "Dirt block")));
        var byIdentity = reader.read(new StorageReader.Request(StorageReader.Target.OPENED, null, null, List.of("minecraft:dirt"), 32, null, FIRST))
                .toCompletableFuture().join();
        assertEquals(1, ((Evidence.JsonArray) ((byIdentity.records().getFirst().payload()).values().get("entries"))).values().size());
        var byText = reader.read(new StorageReader.Request(StorageReader.Target.OPENED, null, "stone", List.of(), 32, null, FIRST))
                .toCompletableFuture().join();
        var entry = ((Evidence.JsonObject) (((Evidence.JsonArray) (byText.records().getFirst().payload().values().get("entries"))).values().getFirst()));
        assertEquals("minecraft:stone", ((Evidence.JsonString) ((((Evidence.JsonObject) (entry.values().get("payload")))).values().get("id"))).value());
        var byComponentText = reader.read(new StorageReader.Request(StorageReader.Target.OPENED, null, "hidden phrase", List.of(), 32, null, FIRST))
                .toCompletableFuture().join();
        assertTrue(((Evidence.JsonArray) ((byComponentText.records().getFirst().payload()).values().get("entries"))).values().isEmpty());
    }

    @Test
    void openedInventoryFiltersUseOrWithinFieldsAndAndAcrossFields() {
        var apple = new Evidence.Record(record(FIRST, "minecraft:apple", "Apple").source(), Evidence.RecordKind.ITEM, "Apple", new Evidence.JsonObject(Map.of(
                "id", new Evidence.JsonString("minecraft:apple"), "name", new Evidence.JsonString("Apple"),
                "tags", new Evidence.JsonArray(List.of(new Evidence.JsonString("minecraft:logs"))),
                "tooltip", new Evidence.JsonArray(List.of(new Evidence.JsonString("Red fruit"))),
                "section", new Evidence.JsonString("menu"))), List.of());
        var carrot = new Evidence.Record(record(FIRST, "minecraft:carrot", "Carrot").source(), Evidence.RecordKind.ITEM, "Carrot", new Evidence.JsonObject(Map.of(
                "id", new Evidence.JsonString("minecraft:carrot"), "name", new Evidence.JsonString("Carrot"),
                "tags", new Evidence.JsonArray(List.of(new Evidence.JsonString("minecraft:foods"))),
                "tooltip", new Evidence.JsonArray(List.of(new Evidence.JsonString("Orange food"))),
                "section", new Evidence.JsonString("menu"))), List.of());
        var excludedApple = new Evidence.Record(record(FIRST, "minecraft:apple", "Apple in cargo").source(), Evidence.RecordKind.ITEM,
                "Apple in cargo", new Evidence.JsonObject(Map.of(
                "id", new Evidence.JsonString("minecraft:apple"), "name", new Evidence.JsonString("Apple"),
                "tags", new Evidence.JsonArray(List.of(new Evidence.JsonString("minecraft:foods"))),
                "tooltip", new Evidence.JsonArray(List.of(new Evidence.JsonString("Red fruit"))),
                "section", new Evidence.JsonString("cargo"))), List.of());
        var reader = new NativeStorageReader(Runnable::run, () -> FIRST, () -> 0);
        reader.storeOpenedFixture(List.of(apple, carrot, excludedApple), FIRST, Instant.EPOCH, 0, "Opened inventory",
                Map.of("observation", new Evidence.JsonString("fixture")), Evidence.Coverage.PARTIAL);
        var filter = new dev.packsage.knowledge.inventory.InventoryReader.Filter(List.of("APP", "carro"),
                List.of("minecraft:apple", "minecraft:carrot"), List.of("minecraft:logs", "minecraft:foods"),
                List.of("red", "orange"), List.of("menu"));

        var result = reader.read(new StorageReader.Request(StorageReader.Target.OPENED, null, null, List.of(), 8, null,
                FIRST, filter)).toCompletableFuture().join();
        var entries = ((Evidence.JsonArray) result.records().getFirst().payload().values().get("entries")).values();

        assertEquals(List.of("minecraft:apple", "minecraft:carrot"), entries.stream().map(value ->
                ((Evidence.JsonString) ((Evidence.JsonObject) ((Evidence.JsonObject) value).values().get("payload"))
                        .values().get("id")).value()).toList());
    }

    @Test
    void itemComponentsProduceDirectChildEntriesAndRootComponentMetadata() {
        var pickaxe = new ItemStack(Items.DIAMOND_PICKAXE);
        pickaxe.set(DataComponents.DAMAGE, 7);
        var shulker = new ItemStack(Items.SHULKER_BOX);
        shulker.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(ItemStack.EMPTY, pickaxe)));
        var bundle = new ItemStack(Items.BUNDLE);
        bundle.set(DataComponents.BUNDLE_CONTENTS, new BundleContents(List.of(new ItemStack(Items.EMERALD, 3))));
        var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        var observation = NativeStorageReader.itemObservation(new NativeInventoryReader.LocatedItem("main", 5, null, false, shulker),
                "native:inventory:fixture:main:5", FIRST, registries, Instant.EPOCH, 0);

        assertEquals(Evidence.Coverage.COMPLETE, observation.coverage());
        assertEquals(2, observation.entries().size());
        assertEquals(new Evidence.JsonString("item"), observation.entries().getFirst().payload().values().get("section"));
        var containerEntry = observation.entries().get(1);
        var containerPayload = containerEntry.payload();
        assertEquals("native:inventory:fixture:main:5", ((Evidence.JsonString) containerPayload.values().get("slotReference")).value());
        assertEquals("container", ((Evidence.JsonString) ((containerPayload).values().get("storageType"))).value());
        assertEquals(1, ((Evidence.JsonNumber) ((containerPayload).values().get("componentSlot"))).value().intValueExact());
        assertEquals("minecraft:shulker_box", ((Evidence.JsonString) ((((Evidence.JsonObject) (containerPayload.values().get("owner")))).values().get("id"))).value());
        var components = ((Evidence.JsonObject) (containerPayload.values().get("components")));
        assertEquals(7, ((Evidence.JsonNumber) components.values().get("minecraft:damage")).value().intValueExact());
        assertEquals(1561, ((Evidence.JsonNumber) components.values().get("minecraft:max_damage")).value().intValueExact());
        assertTrue(components.values().containsKey("minecraft:attribute_modifiers"));
        var bundleObservation = NativeStorageReader.itemObservation(new NativeInventoryReader.LocatedItem("offhand", 0, "offhand", false, bundle),
                "native:inventory:fixture:offhand:0", FIRST, registries, Instant.EPOCH, 0);
        assertEquals(Evidence.Coverage.COMPLETE, bundleObservation.coverage());
        var bundlePayload = bundleObservation.entries().get(1).payload();
        assertEquals("native:inventory:fixture:offhand:0", ((Evidence.JsonString) bundlePayload.values().get("slotReference")).value());
        assertEquals(0, ((Evidence.JsonNumber) ((bundlePayload).values().get("componentSlot"))).value().intValueExact());
        assertEquals(3, ((Evidence.JsonNumber) ((bundlePayload).values().get("count"))).value().intValueExact());
        var replacedOwner = NativeStorageReader.itemObservation(new NativeInventoryReader.LocatedItem("main", 5, null, false, bundle),
                "native:inventory:fixture:main:5", FIRST, registries, Instant.EPOCH, 0);
        assertFalse(observation.details().get("ownerIdentity").equals(replacedOwner.details().get("ownerIdentity")));
        shulker.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(new ItemStack(Items.DIAMOND, 2))));
        var changedContents = NativeStorageReader.itemObservation(new NativeInventoryReader.LocatedItem("main", 5, null, false, shulker),
                "native:inventory:fixture:main:5", FIRST, registries, Instant.EPOCH, 0);
        assertEquals(observation.details().get("ownerIdentity"), changedContents.details().get("ownerIdentity"));
        assertEquals(new Evidence.JsonString("minecraft:diamond"), changedContents.entries().get(1).payload().values().get("id"));

        var owners = ((Evidence.JsonArray) ((new Evidence.JsonObject(observation.details())).values().get("owners")));
        assertTrue(((Evidence.JsonObject) (owners.values().getFirst())).values().containsKey("components"));
        var ownerComponents = ((Evidence.JsonObject) (((Evidence.JsonObject) (owners.values().getFirst())).values().get("components")));
        assertFalse(ownerComponents.values().containsKey("minecraft:container"));

        var request = new StorageReader.Request(StorageReader.Target.ITEM, "native:inventory:fixture:main:5", null, List.of(), 1, null, FIRST);
        var page = NativeStorageReader.pageRecords(java.util.stream.Stream.concat(observation.entries().stream(), bundleObservation.entries().stream()).toList(),
                request, 0, "item", observation.coverage(), Instant.EPOCH);
        assertEquals(1, ((Evidence.JsonArray) ((page.records().getFirst().payload()).values().get("entries"))).values().size());
        assertNotNull(page.nextCursor());
    }

    @Test
    void nonContainerSelectedItemReturnsItsOwnRecordWithEligibility() {
        var observation = NativeStorageReader.itemObservation(new NativeInventoryReader.LocatedItem("main", 5, null, false,
                        new ItemStack(Items.DIAMOND_PICKAXE)),
                "native:inventory:fixture:main:5", FIRST, net.minecraft.data.registries.VanillaRegistries.createLookup(),
                Instant.EPOCH, 0);

        assertNotEquals(Evidence.Coverage.UNAVAILABLE, observation.coverage());
        var first = observation.entries().getFirst();
        assertEquals(new Evidence.JsonString("item"), first.payload().values().get("section"));
        assertEquals(new Evidence.JsonString("minecraft:diamond_pickaxe"), first.payload().values().get("id"));
        assertEquals("storage-entry:item:native:inventory:fixture:main:5", first.source().sourceHandle());
        var eligibility = (Evidence.JsonObject) first.payload().values().get("enchantmentEligibility");
        assertNotNull(eligibility);
        assertTrue(eligibility.values().containsKey("status"));
        var owner = (Evidence.JsonObject) ((Evidence.JsonArray) observation.details().get("owners")).values().getFirst();
        assertEquals(new Evidence.JsonString("none"), owner.values().get("storageStatus"));
    }

    @Test
    void containerItemKeepsContentsAfterItsOwnRecord() {
        var shulker = new ItemStack(Items.SHULKER_BOX);
        shulker.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(new ItemStack(Items.DIAMOND, 2))));
        var observation = NativeStorageReader.itemObservation(new NativeInventoryReader.LocatedItem("main", 5, null, false, shulker),
                "native:inventory:fixture:main:5", FIRST, net.minecraft.data.registries.VanillaRegistries.createLookup(),
                Instant.EPOCH, 0);

        assertEquals(2, observation.entries().size());
        assertEquals(new Evidence.JsonString("item"), observation.entries().getFirst().payload().values().get("section"));
        assertEquals(new Evidence.JsonString("minecraft:shulker_box"), observation.entries().getFirst().payload().values().get("id"));
        assertEquals(new Evidence.JsonString("minecraft:diamond"), observation.entries().get(1).payload().values().get("id"));
    }

    @Test
    void storageMetadataDoesNotSerializeNestedContainerContents() {
        var nested = new ItemStack(Items.SHULKER_BOX);
        nested.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(new ItemStack(Items.DIAMOND))));
        var held = new ItemStack(Items.SHULKER_BOX);
        held.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(nested)));
        var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        var observation = NativeStorageReader.itemObservation(new NativeInventoryReader.LocatedItem("main", 5, null, false, held),
                "native:inventory:fixture:main:5", FIRST, registries, Instant.EPOCH, 0);
        assertEquals(2, observation.entries().size());
        assertFalse(((Evidence.JsonObject) observation.entries().getFirst().payload().values().get("components"))
                .values().containsKey("minecraft:container"));

        var menu = new TestMenu();
        menu.slots.add(new Slot(new SimpleContainer(nested), 0, 0, 0));
        var opened = NativeStorageReader.openedEntries(menu, new SimpleContainer(1), FIRST, Instant.EPOCH, registries);
        assertEquals(1, opened.entries().size());
        assertFalse(((Evidence.JsonObject) opened.entries().getFirst().payload().values().get("components"))
                .values().containsKey("minecraft:container"));
    }

    @Test
    void selectedNameTruncationCannotProveCompleteAbsence() {
        var child = new ItemStack(Items.DIAMOND);
        child.set(DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("x".repeat(256) + "needle"));
        var owner = new ItemStack(Items.SHULKER_BOX);
        owner.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(child)));
        var location = new NativeInventoryReader.LocatedItem("main", 0, null, false, owner);
        var reader = new NativeStorageReader(Runnable::run, () -> FIRST, () -> 0,
                (reference, scope) -> location, () -> RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
        var result = reader.read(new StorageReader.Request(StorageReader.Target.ITEM, "slot", null, List.of(),
                32, null, FIRST,
                new dev.packsage.knowledge.inventory.InventoryReader.Filter(List.of("needle"), List.of(), List.of(), List.of(), List.of())))
                .toCompletableFuture().join();
        assertEquals(Evidence.Coverage.PARTIAL, result.coverage());
        assertEquals(0, ((Evidence.JsonArray) result.records().getFirst().payload().values().get("entries")).values().size());
    }

    @Test
    void openedInventoryRecognizesFurnaceStockSlotsAndExcludesCraftingPreviews() {
        var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        var machineContents = new SimpleContainer(3);
        machineContents.setItem(1, new ItemStack(Items.COAL));
        machineContents.setItem(2, new ItemStack(Items.IRON_INGOT));
        var furnaceOutput = new FurnaceResultSlot(null, machineContents, 2, 0, 0);
        var furnaceFuel = new FurnaceFuelSlot(null, machineContents, 1, 0, 0);
        assertTrue(NativeStorageReader.supportedStockSlot(FurnaceMenu.class, furnaceOutput));
        assertTrue(NativeStorageReader.supportedStockSlot(FurnaceMenu.class, furnaceFuel));
        assertFalse(NativeStorageReader.supportedStockSlot(TestMenu.class, furnaceOutput));
        var playerInventory = new SimpleContainer(0);
        var unsupportedMenu = new TestMenu();
        unsupportedMenu.slots.add(furnaceOutput);
        var unsupported = NativeStorageReader.openedEntries(unsupportedMenu, playerInventory, FIRST, Instant.EPOCH, registries);
        assertTrue(unsupported.entries().isEmpty());
        assertEquals(Evidence.Coverage.PARTIAL, unsupported.coverage());

        var crafting = new TestMenu();
        var craftingInput = new TransientCraftingContainer(crafting, 2, 2);
        var craftingOutput = new SimpleContainer(1);
        craftingOutput.setItem(0, new ItemStack(Items.CRAFTING_TABLE));
        crafting.slots.add(new ResultSlot(null, craftingInput, craftingOutput, 0, 0, 0));
        var craftingCapture = NativeStorageReader.openedEntries(crafting, playerInventory, FIRST, Instant.EPOCH, registries);
        assertTrue(craftingCapture.entries().isEmpty());
        assertEquals(Evidence.Coverage.COMPLETE, craftingCapture.coverage());
        assertEquals(List.of("0=recipe_preview"), craftingCapture.projections());
    }

    @Test
    void openedHorseMenuReadsCurrentSlotsAndBindsCursorToTheExactMenu() throws Exception {
        var unsafeField = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        var unsafe = unsafeField.get(null);
        var allocateInstance = unsafe.getClass().getMethod("allocateInstance", Class.class);
        var fakeMinecraft = (Minecraft) allocateInstance.invoke(unsafe, Minecraft.class);
        var fakePlayer = (LocalPlayer) allocateInstance.invoke(unsafe, LocalPlayer.class);
        var fakeLevel = (ClientLevel) allocateInstance.invoke(unsafe, ClientLevel.class);
        var minecraftInstance = Minecraft.class.getDeclaredField("instance");
        minecraftInstance.setAccessible(true);
        var previousMinecraft = minecraftInstance.get(null);
        var gameThread = Minecraft.class.getDeclaredField("gameThread");
        gameThread.setAccessible(true);
        gameThread.set(fakeMinecraft, Thread.currentThread());
        var playerField = Minecraft.class.getDeclaredField("player");
        playerField.setAccessible(true);
        playerField.set(fakeMinecraft, fakePlayer);
        var levelField = Minecraft.class.getDeclaredField("level");
        levelField.setAccessible(true);
        levelField.set(fakeMinecraft, fakeLevel);
        var dimensionField = Level.class.getDeclaredField("dimension");
        dimensionField.setAccessible(true);
        dimensionField.set(fakeLevel, Level.OVERWORLD);
        var registryAccess = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        var registryAccessField = Level.class.getDeclaredField("registryAccess");
        registryAccessField.setAccessible(true);
        registryAccessField.set(fakeLevel, registryAccess);
        var uuidField = Entity.class.getDeclaredField("uuid");
        uuidField.setAccessible(true);
        uuidField.set(fakePlayer, java.util.UUID.randomUUID());
        var inventory = new Inventory(fakePlayer);
        var playerInventoryField = Player.class.getDeclaredField("inventory");
        playerInventoryField.setAccessible(true);
        playerInventoryField.set(fakePlayer, inventory);
        var inventoryMenuField = Player.class.getDeclaredField("inventoryMenu");
        inventoryMenuField.setAccessible(true);
        inventoryMenuField.set(fakePlayer, allocateInstance.invoke(unsafe, net.minecraft.world.inventory.InventoryMenu.class));
        var containerMenuField = Player.class.getDeclaredField("containerMenu");
        containerMenuField.setAccessible(true);

        try {
            var horse = (Horse) allocateInstance.invoke(unsafe, Horse.class);
            var bodyArmor = new SimpleContainer(1);
            bodyArmor.setItem(0, new ItemStack(Items.DIAMOND_HORSE_ARMOR));
            var bodyArmorAccessField = AbstractHorse.class.getDeclaredField("bodyArmorAccess");
            bodyArmorAccessField.setAccessible(true);
            bodyArmorAccessField.set(horse, bodyArmor);
            var horseInventory = new SimpleContainer(4);
            horseInventory.setItem(0, new ItemStack(Items.SADDLE));
            horseInventory.setItem(1, new ItemStack(Items.DIAMOND));
            var menu = new HorseInventoryMenu(7, inventory, horseInventory, horse, 1);
            containerMenuField.set(fakePlayer, menu);
            minecraftInstance.set(null, fakeMinecraft);

            assertThrows(UnsupportedOperationException.class, menu::getType);
            assertFalse(menu.stillValid(fakePlayer), "the client proxy inventory is deliberately rejected by native stillValid");
            var reader = new NativeStorageReader(fakeMinecraft, Runnable::run, () -> FIRST, (reference, scope) -> null);
            var all = reader.read(new StorageReader.Request(StorageReader.Target.OPENED, null, null, List.of(), 32, null, FIRST))
                    .toCompletableFuture().join();
            assertEquals(Evidence.Outcome.COMPLETE, all.outcome());
            var sections = ((Evidence.JsonArray) all.records().getFirst().payload().values().get("entries")).values().stream()
                    .map(value -> ((Evidence.JsonObject) ((Evidence.JsonObject) value).values().get("payload")).values().get("section"))
                    .map(Evidence.JsonString.class::cast).map(Evidence.JsonString::value).toList();
            assertEquals(List.of("saddle", "body_armor", "cargo"), sections);

            var bodyFilter = new dev.packsage.knowledge.inventory.InventoryReader.Filter(List.of(), List.of(), List.of(), List.of(), List.of("body_armor"));
            var body = reader.read(new StorageReader.Request(StorageReader.Target.OPENED, null, null, List.of(), 32, null, FIRST, bodyFilter))
                    .toCompletableFuture().join();
            assertEquals(1, ((Evidence.JsonArray) body.records().getFirst().payload().values().get("entries")).values().size());

            var first = reader.read(new StorageReader.Request(StorageReader.Target.OPENED, null, null, List.of(), 1, null, FIRST))
                    .toCompletableFuture().join();
            assertNotNull(first.nextCursor());
            bodyArmor.setItem(0, new ItemStack(Items.IRON_HORSE_ARMOR));
            var next = reader.read(new StorageReader.Request(StorageReader.Target.OPENED, null, null, List.of(), 1, first.nextCursor(), FIRST))
                    .toCompletableFuture().join();
            var nextPayload = (Evidence.JsonObject) ((Evidence.JsonObject) ((Evidence.JsonArray)
                    next.records().getFirst().payload().values().get("entries")).values().getFirst()).values().get("payload");
            assertEquals("minecraft:iron_horse_armor", ((Evidence.JsonString) nextPayload.values().get("id")).value());

            var changedMenuCursor = reader.read(new StorageReader.Request(StorageReader.Target.OPENED, null, null, List.of(), 1, null, FIRST))
                    .toCompletableFuture().join().nextCursor();
            containerMenuField.set(fakePlayer, new HorseInventoryMenu(7, inventory, new SimpleContainer(4), horse, 1));
            var changedMenu = reader.read(new StorageReader.Request(StorageReader.Target.OPENED, null, null, List.of(), 1, changedMenuCursor, FIRST))
                    .toCompletableFuture().join();
            assertEquals("inventory target changed; repeat the lookup", changedMenu.detail());

            containerMenuField.set(fakePlayer, new TestMenu());
            var unsupported = reader.read(new StorageReader.Request(StorageReader.Target.OPENED, null, null, List.of(), 1, null, FIRST))
                    .toCompletableFuture().join();
            assertEquals(Evidence.Outcome.UNKNOWN, unsupported.outcome());
        } finally {
            minecraftInstance.set(null, previousMinecraft);
        }
    }

    @Test
    void recognizedEmptyComponentDiffersFromUnsupportedAndEmptySlots() {
        var shulker = new ItemStack(Items.SHULKER_BOX);
        var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        var recognizedEmpty = NativeStorageReader.itemObservation(new NativeInventoryReader.LocatedItem("main", 5, null, false, shulker),
                "native:inventory:fixture:main:5", FIRST, registries, Instant.EPOCH, 0);
        assertEquals(Evidence.Coverage.COMPLETE, recognizedEmpty.coverage());
        assertEquals(1, recognizedEmpty.entries().size());
        var ownerDetails = new Evidence.JsonObject(recognizedEmpty.details());
        var ownerStatuses = ((Evidence.JsonArray) ((ownerDetails).values().get("owners"))).values().stream()
                .map(value -> ((Evidence.JsonString) ((((Evidence.JsonObject) (value))).values().get("storageStatus"))).value()).toList();
        assertEquals(List.of("supported"), ownerStatuses);
        assertEquals("complete", ((Evidence.JsonString) ((((Evidence.JsonObject) (((Evidence.JsonArray) (ownerDetails.values().get("owners"))).values().getFirst()))).values().get("contentsStatus"))).value());

        var unsupported = NativeStorageReader.itemObservation(new NativeInventoryReader.LocatedItem("main", 5, null, false, new ItemStack(Items.STONE)),
                "native:inventory:fixture:main:5", FIRST,
                registries, Instant.EPOCH, 0);
        assertEquals(Evidence.Coverage.COMPLETE, unsupported.coverage());
        assertEquals(1, unsupported.entries().size());
        var empty = NativeStorageReader.itemObservation(new NativeInventoryReader.LocatedItem("main", 5, null, false, ItemStack.EMPTY),
                "native:inventory:fixture:main:5", FIRST, registries, Instant.EPOCH, 0);
        assertEquals(Evidence.Coverage.UNAVAILABLE, empty.coverage());
        assertEquals(List.of(), empty.entries());
        assertEquals(Evidence.Outcome.UNKNOWN, NativeStorageReader.pageRecords(empty.entries(),
                new StorageReader.Request(StorageReader.Target.ITEM, "native:inventory:fixture:main:5", null, List.of(), 1, null, FIRST), 0, "held",
                empty.coverage(), Instant.EPOCH).outcome());
    }

    @Test
    void itemReadUsesTheResolvedMainInventorySlotAndBindsItsContinuation() {
        var shulker = new ItemStack(Items.SHULKER_BOX);
        shulker.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(
                new ItemStack(Items.COAL, 3), new ItemStack(Items.STONE, 4))));
        var resolved = new ArrayList<String>();
        var reader = new NativeStorageReader(Runnable::run, () -> FIRST, () -> 0,
                (reference, scope) -> {
                    resolved.add(reference);
                    assertEquals(FIRST, scope);
                    return new NativeInventoryReader.LocatedItem("main", 5, null, false, shulker);
                }, () -> RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
        var first = reader.read(new StorageReader.Request(StorageReader.Target.ITEM,
                "native:inventory:fixture:main:5", null, List.of(), 1, null, FIRST)).toCompletableFuture().join();
        assertEquals(Evidence.Coverage.COMPLETE, first.coverage());
        assertEquals(List.of("native:inventory:fixture:main:5"), resolved);
        var payload = first.records().getFirst().payload().values();
        var owner = (Evidence.JsonObject) ((Evidence.JsonArray) payload.get("owners")).values().getFirst();
        assertEquals("main", ((Evidence.JsonString) owner.values().get("section")).value());
        assertEquals(5, ((Evidence.JsonNumber) owner.values().get("slot")).value().intValueExact());
        assertNotNull(first.nextCursor());
        var wrong = reader.read(new StorageReader.Request(StorageReader.Target.ITEM,
                "native:inventory:other:main:5", null, List.of(), 1, first.nextCursor(), FIRST)).toCompletableFuture().join();
        assertEquals(Evidence.Outcome.UNKNOWN, wrong.outcome());
        var second = reader.read(new StorageReader.Request(StorageReader.Target.ITEM,
                "native:inventory:fixture:main:5", null, List.of(), 1, first.nextCursor(), FIRST)).toCompletableFuture().join();
        assertNotNull(second.nextCursor());
        var next = reader.read(new StorageReader.Request(StorageReader.Target.ITEM,
                "native:inventory:fixture:main:5", null, List.of(), 1, second.nextCursor(), FIRST)).toCompletableFuture().join();
        assertEquals(Evidence.Coverage.COMPLETE, next.coverage());
        assertNull(next.nextCursor());
        var entry = (Evidence.JsonObject) ((Evidence.JsonArray) next.records().getFirst().payload().values().get("entries")).values().getFirst();
        assertEquals("minecraft:stone", ((Evidence.JsonString) ((Evidence.JsonObject) entry.values().get("payload")).values().get("id")).value());
        assertEquals(3, resolved.size());
    }

    @Test
    void changedSlotReferenceReturnsUnknownInsteadOfReadingTheReplacement() {
        var reader = new NativeStorageReader(Runnable::run, () -> FIRST, () -> 0,
                (reference, scope) -> { throw new IllegalArgumentException("inventory slot contents changed"); },
                () -> RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
        var result = reader.read(new StorageReader.Request(StorageReader.Target.ITEM,
                "native:inventory:fixture:main:5", null, List.of(), 32, null, FIRST)).toCompletableFuture().join();
        assertEquals(Evidence.Outcome.UNKNOWN, result.outcome());
        assertEquals(Evidence.Coverage.UNAVAILABLE, result.coverage());
        var payload = result.records().getFirst().payload().values();
        assertTrue(((Evidence.JsonArray) payload.get("entries")).values().isEmpty());
        assertEquals("inventory slot contents changed", ((Evidence.JsonString) payload.get("detail")).value());
    }

    private static NativeStorageReader openedReader(AtomicLong now, java.util.function.Supplier<Evidence.Scope> scope,
                                                    List<Evidence.Record> entries) {
        var reader = new NativeStorageReader(Runnable::run, scope, now::get);
        reader.storeOpenedFixture(entries, FIRST, Instant.EPOCH, now.get(), "Opened chest",
                Map.of("observation", new Evidence.JsonString("last_opened_menu"), "completeness", new Evidence.JsonString("partial")), Evidence.Coverage.PARTIAL);
        return reader;
    }

    private static Evidence.Record record(Evidence.Scope scope, String id, String title) {
        return record(scope, id, title, new Evidence.JsonObject(Map.of()));
    }

    private static Evidence.Record record(Evidence.Scope scope, String id, String title, Evidence.JsonObject components) {
        var source = new Evidence.Source(scope, "native", "1.21.1", "slot:" + id, scope.requestGeneration(),
                Instant.EPOCH, Evidence.Activity.ACTIVE, Evidence.Coverage.COMPLETE,
                Evidence.Authority.VIEWER, List.of("test"));
        return new Evidence.Record(source, Evidence.RecordKind.ITEM, title,
                new Evidence.JsonObject(Map.of("id", new Evidence.JsonString(id), "components", components)), List.of());
    }

    private static final class TestMenu extends AbstractContainerMenu {
        private TestMenu() {
            super(null, 0);
        }

        @Override
        public ItemStack quickMoveStack(Player player, int slotIndex) {
            return ItemStack.EMPTY;
        }

        @Override
        public boolean stillValid(Player player) {
            return true;
        }
    }
}
