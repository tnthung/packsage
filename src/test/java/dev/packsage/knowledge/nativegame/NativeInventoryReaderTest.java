package dev.packsage.knowledge.nativegame;

import dev.packsage.knowledge.Evidence;
import dev.packsage.knowledge.inventory.InventoryReader;
import net.minecraft.server.Bootstrap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeInventoryReaderTest {
    private static final Evidence.Scope SCOPE = new Evidence.Scope("world", "en_us", "team", "native", 1);

    @BeforeAll
    static void bootstrapMinecraft() {
        Bootstrap.bootStrap();
    }

    @Test
    void searchPagesRecaptureSelectedFieldsAndContinueByOffset() {
        var captures = new AtomicInteger();
        var now = new AtomicLong(1_000);
        var firstRecord = new Evidence.Record(new Evidence.Source(SCOPE, "native", "1.21.1", "ref-1", 1,
                Instant.EPOCH, Evidence.Activity.ACTIVE, Evidence.Coverage.COMPLETE, Evidence.Authority.LOADED, List.of()),
                Evidence.RecordKind.ITEM, "First Apple", new Evidence.JsonObject(Map.of()), List.of());
        var secondRecord = new Evidence.Record(new Evidence.Source(SCOPE, "native", "1.21.1", "ref-2", 1,
                Instant.EPOCH, Evidence.Activity.ACTIVE, Evidence.Coverage.COMPLETE, Evidence.Authority.LOADED, List.of()),
                Evidence.RecordKind.ITEM, "Second Apple", new Evidence.JsonObject(Map.of()), List.of());
        var summary = new Evidence.Record(new Evidence.Source(SCOPE, "native", "1.21.1", "summary", 1, Instant.EPOCH,
                Evidence.Activity.ACTIVE, Evidence.Coverage.COMPLETE, Evidence.Authority.LOADED, List.of()),
                Evidence.RecordKind.OTHER, "Inventory", new Evidence.JsonObject(Map.of()), List.of());
        var first = new NativeInventoryReader.CapturedSlot("hotbar", 0, null, true, "minecraft:apple", "First Apple",
                "ref-1", firstRecord, List.of(), List.of(), new ItemStack(Items.APPLE));
        var second = new NativeInventoryReader.CapturedSlot("main", 0, null, false, "minecraft:apple", "Second Apple",
                "ref-2", secondRecord, List.of(), List.of(), new ItemStack(Items.APPLE));
        var reader = new NativeInventoryReader(Runnable::run, () -> SCOPE, request -> {
            captures.incrementAndGet();
            return new NativeInventoryReader.Capture(List.of(first, second), summary, Evidence.Coverage.COMPLETE,
                    Instant.EPOCH, now.get());
        }, address -> null, now::get);

        var firstPage = reader.read(new InventoryReader.Request(InventoryReader.Operation.SEARCH, List.of(), "minecraft:apple",
                List.of("id"), 1, null, SCOPE)).toCompletableFuture().join();
        assertEquals("First Apple", firstPage.records().getFirst().title());
        assertNotNull(firstPage.nextCursor());
        var nextPage = reader.read(new InventoryReader.Request(InventoryReader.Operation.SEARCH, List.of(), "minecraft:apple",
                List.of("id"), 1, firstPage.nextCursor(), SCOPE)).toCompletableFuture().join();

        assertEquals("Second Apple", nextPage.records().getFirst().title());
        assertEquals(2, captures.get());
        assertTrue(nextPage.detail().contains("vanilla-player-inventory-and-equipment"));
    }

    @Test
    void inventoryFiltersUseOrWithinFieldsAndAndAcrossFields() {
        var apple = new Evidence.Record(new Evidence.Source(SCOPE, "native", "1.21.1", "apple", 1, Instant.EPOCH,
                Evidence.Activity.ACTIVE, Evidence.Coverage.COMPLETE, Evidence.Authority.LOADED, List.of()),
                Evidence.RecordKind.ITEM, "Apple", new Evidence.JsonObject(Map.of()), List.of());
        var carrot = new Evidence.Record(new Evidence.Source(SCOPE, "native", "1.21.1", "carrot", 1, Instant.EPOCH,
                Evidence.Activity.ACTIVE, Evidence.Coverage.COMPLETE, Evidence.Authority.LOADED, List.of()),
                Evidence.RecordKind.ITEM, "Carrot", new Evidence.JsonObject(Map.of()), List.of());
        var excludedApple = new Evidence.Record(new Evidence.Source(SCOPE, "native", "1.21.1", "excluded", 1, Instant.EPOCH,
                Evidence.Activity.ACTIVE, Evidence.Coverage.COMPLETE, Evidence.Authority.LOADED, List.of()),
                Evidence.RecordKind.ITEM, "Apple in armor", new Evidence.JsonObject(Map.of()), List.of());
        var slots = List.of(
                new NativeInventoryReader.CapturedSlot("hotbar", 0, null, true, "minecraft:apple", "Apple", "apple", apple,
                        List.of("minecraft:logs"), List.of("Red fruit"), new ItemStack(Items.APPLE)),
                new NativeInventoryReader.CapturedSlot("main", 0, null, false, "minecraft:carrot", "Carrot", "carrot", carrot,
                        List.of("minecraft:foods"), List.of("Orange food"), new ItemStack(Items.CARROT)),
                new NativeInventoryReader.CapturedSlot("armor", 0, "head", false, "minecraft:apple", "Apple in armor",
                        "excluded", excludedApple, List.of("minecraft:foods"), List.of("Red fruit"), new ItemStack(Items.APPLE)));
        var summary = new Evidence.Record(new Evidence.Source(SCOPE, "native", "1.21.1", "summary", 1, Instant.EPOCH,
                Evidence.Activity.ACTIVE, Evidence.Coverage.COMPLETE, Evidence.Authority.LOADED, List.of()),
                Evidence.RecordKind.OTHER, "Inventory", new Evidence.JsonObject(Map.of()), List.of());
        var reader = new NativeInventoryReader(Runnable::run, () -> SCOPE,
                request -> new NativeInventoryReader.Capture(slots, summary, Evidence.Coverage.COMPLETE, Instant.EPOCH, 1),
                address -> null, () -> 1);
        var filter = new InventoryReader.Filter(List.of("APP", "carro"), List.of("minecraft:apple", "minecraft:carrot"),
                List.of("minecraft:logs", "minecraft:foods"), List.of("red", "orange"), List.of("hotbar", "main"));

        var result = reader.read(new InventoryReader.Request(InventoryReader.Operation.SEARCH, filter, 32, null, SCOPE))
                .toCompletableFuture().join();

        assertEquals(List.of("Apple", "Carrot"), result.records().stream().map(Evidence.Record::title).toList());
    }

    @Test
    void filteredMissReportsDirectPlayerSlotScopeWhileUnfilteredReadExposesNestedReference() {
        var payload = new Evidence.JsonObject(Map.of(
                "id", new Evidence.JsonString("minecraft:bundle"),
                "slotReference", new Evidence.JsonString("native:inventory:observation:hotbar:2"),
                "nestedStorageContentsOmitted", new Evidence.JsonBoolean(true)));
        var bundle = new Evidence.Record(new Evidence.Source(SCOPE, "native", "1.21.1", "inventory:hotbar:2", 1,
                Instant.EPOCH, Evidence.Activity.ACTIVE, Evidence.Coverage.COMPLETE, Evidence.Authority.LOADED, List.of()),
                Evidence.RecordKind.ITEM, "Bundle", payload, List.of());
        var slot = new NativeInventoryReader.CapturedSlot("hotbar", 2, null, false, "minecraft:bundle", "Bundle",
                "native:inventory:observation:hotbar:2", bundle, List.of(), List.of(), new ItemStack(Items.BUNDLE));
        var summary = new Evidence.Record(new Evidence.Source(SCOPE, "native", "1.21.1", "summary", 1, Instant.EPOCH,
                Evidence.Activity.ACTIVE, Evidence.Coverage.COMPLETE, Evidence.Authority.LOADED, List.of()),
                Evidence.RecordKind.OTHER, "Inventory", new Evidence.JsonObject(Map.of()), List.of());
        var reader = new NativeInventoryReader(Runnable::run, () -> SCOPE,
                request -> new NativeInventoryReader.Capture(List.of(slot), summary, Evidence.Coverage.COMPLETE,
                        Instant.EPOCH, 1), address -> null, () -> 1);

        var filteredMiss = reader.read(new InventoryReader.Request(InventoryReader.Operation.SEARCH,
                new InventoryReader.Filter(List.of(), List.of("minecraft:redstone"), List.of(), List.of(), List.of()),
                32, null, SCOPE)).toCompletableFuture().join();

        assertEquals(Evidence.Coverage.COMPLETE, filteredMiss.coverage());
        assertTrue(filteredMiss.records().isEmpty());
        assertTrue(filteredMiss.detail().contains("direct vanilla player slots and equipment"));
        assertTrue(filteredMiss.detail().contains("nested storage contents are not inspected"));

        var unfiltered = reader.read(new InventoryReader.Request(InventoryReader.Operation.SEARCH,
                InventoryReader.Filter.empty(), 32, null, SCOPE)).toCompletableFuture().join();

        assertEquals("native:inventory:observation:hotbar:2",
                ((Evidence.JsonString) unfiltered.records().getFirst().payload().values().get("slotReference")).value());
        assertTrue(((Evidence.JsonBoolean) unfiltered.records().getFirst().payload().values()
                .get("nestedStorageContentsOmitted")).value());
    }

    @Test
    void unsupportedSectionsAreReportedAndMixedCoverageIsPartial() {
        var apple = new Evidence.Record(new Evidence.Source(SCOPE, "native", "1.21.1", "apple", 1, Instant.EPOCH,
                Evidence.Activity.ACTIVE, Evidence.Coverage.COMPLETE, Evidence.Authority.LOADED, List.of()),
                Evidence.RecordKind.ITEM, "Apple", new Evidence.JsonObject(Map.of()), List.of());
        var summary = new Evidence.Record(new Evidence.Source(SCOPE, "native", "1.21.1", "summary", 1, Instant.EPOCH,
                Evidence.Activity.ACTIVE, Evidence.Coverage.COMPLETE, Evidence.Authority.LOADED, List.of()),
                Evidence.RecordKind.OTHER, "Inventory", new Evidence.JsonObject(Map.of()), List.of());
        var slot = new NativeInventoryReader.CapturedSlot("main", 0, null, false, "minecraft:apple", "Apple",
                "apple", apple, List.of(), List.of(), new ItemStack(Items.APPLE));
        var reader = new NativeInventoryReader(Runnable::run, () -> SCOPE,
                request -> new NativeInventoryReader.Capture(List.of(slot), summary, Evidence.Coverage.COMPLETE,
                        Instant.EPOCH, 1), address -> null, () -> 1);

        var unsupported = reader.read(new InventoryReader.Request(InventoryReader.Operation.SEARCH,
                new InventoryReader.Filter(List.of(), List.of(), List.of(), List.of(), List.of("accessories")),
                32, null, SCOPE)).toCompletableFuture().join();
        assertEquals(Evidence.Coverage.UNAVAILABLE, unsupported.coverage());
        assertEquals(Evidence.Outcome.UNKNOWN, unsupported.outcome());
        assertTrue(unsupported.records().isEmpty());
        assertTrue(unsupported.detail().contains("accessories"));
        assertTrue(unsupported.detail().contains("not inspected"));
        assertTrue(unsupported.detail().contains("hotbar"));
        assertTrue(unsupported.detail().contains("main"));
        assertTrue(unsupported.detail().contains("armor"));
        assertTrue(unsupported.detail().contains("offhand"));

        var mixed = reader.read(new InventoryReader.Request(InventoryReader.Operation.SEARCH,
                new InventoryReader.Filter(List.of(), List.of(), List.of(), List.of(), List.of("main", "accessories")),
                32, null, SCOPE)).toCompletableFuture().join();
        assertEquals(Evidence.Coverage.PARTIAL, mixed.coverage());
        assertEquals(List.of("Apple"), mixed.records().stream().map(Evidence.Record::title).toList());
    }

    @Test
    void referenceResolutionReadsCurrentStackAtObservedLocationAndReturnsCopies() {
        var now = new AtomicLong(1_000);
        var stack = new ItemStack(Items.SHULKER_BOX);
        stack.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(new ItemStack(Items.DIAMOND))));
        var record = new Evidence.Record(new Evidence.Source(SCOPE, "native", "1.21.1", "ref", 1, Instant.EPOCH,
                Evidence.Activity.ACTIVE, Evidence.Coverage.COMPLETE, Evidence.Authority.LOADED, List.of()),
                Evidence.RecordKind.ITEM, "minecraft:shulker_box", new Evidence.JsonObject(Map.of()), List.of());
        var slot = new NativeInventoryReader.CapturedSlot("hotbar", 2, null, false, "minecraft:shulker_box", "Shulker Box",
                "native:inventory:observation:hotbar:2", record, List.of(), List.of(), stack);
        var summary = new Evidence.Record(new Evidence.Source(SCOPE, "native", "1.21.1", "summary", 1, Instant.EPOCH,
                Evidence.Activity.ACTIVE, Evidence.Coverage.COMPLETE, Evidence.Authority.LOADED, List.of()),
                Evidence.RecordKind.OTHER, "Inventory", new Evidence.JsonObject(Map.of()), List.of());
        var live = new AtomicReference<>(new NativeInventoryReader.LocatedItem("hotbar", 2, null, false, stack));
        var reader = new NativeInventoryReader(Runnable::run, () -> SCOPE,
                request -> new NativeInventoryReader.Capture(List.of(slot), summary, Evidence.Coverage.COMPLETE, Instant.EPOCH, now.get()),
                address -> live.get(), now::get);
        reader.read(new InventoryReader.Request(InventoryReader.Operation.READ, List.of(), null, List.of(), 32, null, SCOPE))
                .toCompletableFuture().join();
        var reference = slot.slotReference();
        var resolved = reader.resolve(reference, SCOPE);
        resolved.stack().setCount(17);
        assertEquals(1, reader.resolve(reference, SCOPE).stack().getCount());

        live.set(new NativeInventoryReader.LocatedItem("main", 2, null, false, stack));
        assertThrows(IllegalArgumentException.class, () -> reader.resolve(reference, SCOPE));

        var changedContents = new ItemStack(Items.SHULKER_BOX);
        changedContents.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(new ItemStack(Items.EMERALD))));
        live.set(new NativeInventoryReader.LocatedItem("hotbar", 2, null, false, changedContents));
        assertEquals(Items.SHULKER_BOX, reader.resolve(reference, SCOPE).stack().getItem());
        assertEquals(changedContents.get(DataComponents.CONTAINER),
                reader.resolve(reference, SCOPE).stack().get(DataComponents.CONTAINER));

        changedContents.setCount(2);
        live.set(new NativeInventoryReader.LocatedItem("hotbar", 2, null, false, changedContents));
        assertEquals(2, reader.resolve(reference, SCOPE).stack().getCount());
    }

    @Test
    void summaryEvidenceReadsFreshCaptureInsteadOfTheOldObservation() {
        var captures = new AtomicInteger();
        var reader = new NativeInventoryReader(Runnable::run, () -> SCOPE, request -> {
            var capture = captures.incrementAndGet();
            var summary = new Evidence.Record(new Evidence.Source(SCOPE, "native", "1.21.1", "inventory-summary:old",
                    1, Instant.EPOCH, Evidence.Activity.ACTIVE, Evidence.Coverage.COMPLETE, Evidence.Authority.LOADED, List.of()),
                    Evidence.RecordKind.OTHER, "Player inventory observation",
                    new Evidence.JsonObject(Map.of("capture", new Evidence.JsonNumber(java.math.BigDecimal.valueOf(capture)))), List.of());
            return new NativeInventoryReader.Capture(List.of(), summary, Evidence.Coverage.COMPLETE, Instant.EPOCH, capture);
        }, address -> null, captures::get);

        var first = reader.readEvidence("inventory-summary:old", SCOPE);
        var second = reader.readEvidence("inventory-summary:old", SCOPE);

        assertEquals(1, ((Evidence.JsonNumber) first.payload().values().get("capture")).value().intValueExact());
        assertEquals(2, ((Evidence.JsonNumber) second.payload().values().get("capture")).value().intValueExact());
        assertEquals(2, captures.get());
    }

    @Test
    void emptySearchPreservesPartialCoverageWhenSelectedFieldExtractionFailed() {
        var now = new AtomicLong(1_000);
        var summary = new Evidence.Record(new Evidence.Source(SCOPE, "native", "1.21.1", "summary", 1, Instant.EPOCH,
                Evidence.Activity.ACTIVE, Evidence.Coverage.PARTIAL, Evidence.Authority.LOADED, List.of()),
                Evidence.RecordKind.OTHER, "Inventory", new Evidence.JsonObject(Map.of()), List.of());
        var reader = new NativeInventoryReader(Runnable::run, () -> SCOPE,
                request -> new NativeInventoryReader.Capture(List.of(), summary, Evidence.Coverage.PARTIAL, Instant.EPOCH, now.get()),
                address -> null, now::get);

        var result = reader.read(new InventoryReader.Request(InventoryReader.Operation.SEARCH, List.of(), "minecraft:ores",
                List.of("tag"), 8, null, SCOPE)).toCompletableFuture().join();

        assertTrue(result.records().isEmpty());
        assertEquals(Evidence.Coverage.PARTIAL, result.coverage());
        assertEquals(Evidence.Outcome.PARTIAL, result.outcome());
    }
}
