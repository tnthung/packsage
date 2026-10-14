package dev.packsage.knowledge.nativegame;

import dev.packsage.knowledge.Evidence;
import dev.packsage.knowledge.inventory.InventoryReader;
import dev.packsage.model.ModelBackend;
import dev.packsage.model.PackSageTools;
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
    void searchPagesReuseTheCapturedRecordsAndSelectedFields() {
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
        assertEquals(1, captures.get());
        assertTrue(nextPage.detail().contains("vanilla-player-inventory-and-equipment"));
    }

    @Test
    void referenceResolutionRejectsSameIdWithChangedContentsOrCountAndReturnsCopies() {
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
        assertThrows(IllegalArgumentException.class, () -> reader.resolve(reference, SCOPE));

        changedContents.setCount(2);
        live.set(new NativeInventoryReader.LocatedItem("hotbar", 2, null, false, changedContents));
        assertThrows(IllegalArgumentException.class, () -> reader.resolve(reference, SCOPE));
    }

    @Test
    void outputBudgetCreatesStablePagesWithoutDroppingRecords() {
        var now = new AtomicLong(1_000);
        var slots = new java.util.ArrayList<NativeInventoryReader.CapturedSlot>();
        var body = "x".repeat(8 * 1024);
        for (int index = 0; index < 20; index++) {
            var reference = "native:inventory:observation:main:" + index;
            var record = new Evidence.Record(new Evidence.Source(SCOPE, "native", "1.21.1", reference.substring("native:".length()), 1,
                    Instant.EPOCH, Evidence.Activity.ACTIVE, Evidence.Coverage.COMPLETE, Evidence.Authority.LOADED, List.of()),
                    Evidence.RecordKind.ITEM, "Apple " + index, new Evidence.JsonObject(Map.of("metadata", new Evidence.JsonString(body))),
                    List.of());
            slots.add(new NativeInventoryReader.CapturedSlot("main", index, null, false, "minecraft:apple", "Apple " + index,
                    reference, record, List.of(), List.of(), new ItemStack(Items.APPLE)));
        }
        var summary = new Evidence.Record(new Evidence.Source(SCOPE, "native", "1.21.1", "summary", 1, Instant.EPOCH,
                Evidence.Activity.ACTIVE, Evidence.Coverage.COMPLETE, Evidence.Authority.LOADED, List.of()),
                Evidence.RecordKind.OTHER, "Inventory", new Evidence.JsonObject(Map.of()), List.of());
        var reader = new NativeInventoryReader(Runnable::run, () -> SCOPE,
                request -> new NativeInventoryReader.Capture(slots, summary, Evidence.Coverage.COMPLETE, Instant.EPOCH, now.get()),
                address -> null, now::get);
        var tools = new PackSageTools(read -> {
            var values = read.arguments().values();
            var fields = ((Evidence.JsonArray) values.get("fields")).values().stream()
                    .map(value -> ((Evidence.JsonString) value).value()).toList();
            var cursorValue = values.get("cursor");
            var cursor = cursorValue instanceof Evidence.JsonString value ? value.value() : null;
            var limit = ((Evidence.JsonNumber) values.get("limit")).value().intValueExact();
            var text = ((Evidence.JsonString) values.get("text")).value();
            return reader.read(new InventoryReader.Request(InventoryReader.Operation.SEARCH, List.of(), text, fields,
                    limit, cursor, read.scope()));
        });
        var page = reader.read(new InventoryReader.Request(InventoryReader.Operation.SEARCH, List.of(), "minecraft:apple",
                List.of("id"), 32, null, SCOPE)).toCompletableFuture().join();
        var titles = new java.util.ArrayList<String>();
        String toolCursor = null;
        var pageIndex = 0;
        while (true) {
            var arguments = "{\"text\":\"minecraft:apple\",\"fields\":[\"id\"],\"limit\":32,\"cursor\":"
                    + (toolCursor == null ? "null" : "\"" + toolCursor + "\"") + "}";
            var toolResponse = tools.execute(new ModelBackend.ToolCall("budget-" + pageIndex++, "search_inventory", arguments), SCOPE)
                    .toCompletableFuture().join();
            var toolJson = com.google.gson.JsonParser.parseString(toolResponse.result().serializedResult()).getAsJsonObject();
            var encodedRecords = toolJson.getAsJsonArray("records");
            assertFalse(toolJson.has("detail") && toolJson.get("detail").getAsString()
                    .contains("tool output limit reached"));
            assertEquals(page.records(), toolResponse.records());
            assertEquals(page.records().size(), encodedRecords.size());
            for (int index = 0; index < encodedRecords.size(); index++) {
                var encoded = encodedRecords.get(index).getAsJsonObject();
                var expected = page.records().get(index);
                assertEquals(expected.title(), encoded.get("title").getAsString());
                assertEquals(expected.source().providerId() + ":" + expected.source().sourceHandle(),
                        encoded.get("reference").getAsString());
                assertEquals(com.google.gson.JsonParser.parseString(Evidence.serialize(expected.payload())), encoded.get("payload"));
            }
            titles.addAll(toolResponse.records().stream().map(Evidence.Record::title).toList());
            assertTrue(page.records().stream().mapToInt(record -> Evidence.serialize(record.payload())
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                    + record.title().getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                    + record.source().sourceHandle().getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                    + record.source().provenance().stream().mapToInt(value -> value.getBytes(
                            java.nio.charset.StandardCharsets.UTF_8).length).sum() + 256).sum() <= 96 * 1024);
            var actualCursorValue = toolJson.get("nextCursor");
            var nextToolCursor = actualCursorValue == null || actualCursorValue.isJsonNull()
                    ? null : actualCursorValue.getAsString();
            assertEquals(page.nextCursor() == null, nextToolCursor == null);
            if (page.nextCursor() == null) break;
            toolCursor = nextToolCursor;
            page = reader.read(new InventoryReader.Request(InventoryReader.Operation.SEARCH, List.of(), "minecraft:apple",
                    List.of("id"), 32, page.nextCursor(), SCOPE)).toCompletableFuture().join();
        }

        assertEquals(slots.stream().map(NativeInventoryReader.CapturedSlot::name).toList(), titles);
        assertEquals(20, titles.stream().distinct().count());
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

        var result = reader.read(new InventoryReader.Request(InventoryReader.Operation.SEARCH, List.of(), "ores",
                List.of("tag"), 8, null, SCOPE)).toCompletableFuture().join();

        assertTrue(result.records().isEmpty());
        assertEquals(Evidence.Coverage.PARTIAL, result.coverage());
        assertEquals(Evidence.Outcome.PARTIAL, result.outcome());
    }
}
