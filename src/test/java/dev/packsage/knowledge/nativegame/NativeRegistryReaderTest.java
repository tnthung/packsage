package dev.packsage.knowledge.nativegame;

import dev.packsage.knowledge.Evidence;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeRegistryReaderTest {
    private static final Evidence.Scope SCOPE = new Evidence.Scope("world", "en_us", "team", "native", 1);

    @BeforeAll
    static void bootstrapMinecraft() {
        Bootstrap.bootStrap();
    }

    @Test
    void exactRegistryIdsReadByIdentityAndApplyLiteralTextBeforePaging() {
        var page = NativeRegistryReader.searchEntries(BuiltInRegistries.ITEM, "item", "minecraft:",
                List.of("minecraft:diamond", "minecraft:emerald"), List.of(), 0, 1, SCOPE,
                item -> record(BuiltInRegistries.ITEM.getKey(item).toString()));

        assertEquals(List.of("minecraft:diamond"), page.records().stream().map(Evidence.Record::title).toList());
        assertEquals(1, page.nextOffset());
        assertFalse(page.exhausted());
        var second = NativeRegistryReader.searchEntries(BuiltInRegistries.ITEM, "item", "minecraft:",
                List.of("minecraft:diamond", "minecraft:emerald"), List.of(), page.nextOffset(), 1, SCOPE,
                item -> record(BuiltInRegistries.ITEM.getKey(item).toString()));
        assertEquals(List.of("minecraft:emerald"), second.records().stream().map(Evidence.Record::title).toList());
        assertEquals(2, second.nextOffset());
        assertTrue(second.exhausted());

        var noTextMatch = NativeRegistryReader.searchEntries(BuiltInRegistries.ITEM, "item", "netherite",
                List.of("minecraft:diamond"), List.of(), 0, 1, SCOPE,
                item -> record(BuiltInRegistries.ITEM.getKey(item).toString()));
        assertEquals(List.of(), noTextMatch.records());
        assertEquals(0, noTextMatch.nextOffset());
        assertTrue(noTextMatch.exhausted());
    }

    @Test
    void itemTagContinuationAdvancesPastTheCandidateBoundOnFreshIterators() {
        var first = NativeRegistryReader.page(IntStream.range(0, 600).iterator(), 512, 16,
                value -> true, NativeRegistryReaderTest::record);

        assertEquals("512", first.records().getFirst().title());
        assertEquals("527", first.records().getLast().title());
        assertEquals(528, first.nextOffset());
        assertEquals(16, first.scanned());
        assertFalse(first.exhausted());

        var second = NativeRegistryReader.page(IntStream.range(0, 600).iterator(), first.nextOffset(), 16,
                value -> true, NativeRegistryReaderTest::record);

        assertEquals("528", second.records().getFirst().title());
        assertEquals(544, second.nextOffset());
        assertEquals(16, second.scanned());
        assertFalse(second.exhausted());
    }

    private static Evidence.Record record(int identity) {
        return record(Integer.toString(identity));
    }

    private static Evidence.Record record(String identity) {
        var source = new Evidence.Source(SCOPE, "native", "1.21.1", "tag:" + identity, 1, Instant.EPOCH,
                Evidence.Activity.ACTIVE, Evidence.Coverage.COMPLETE, Evidence.Authority.LOADED, List.of("fixture"));
        return new Evidence.Record(source, Evidence.RecordKind.OTHER, identity,
                new Evidence.JsonObject(Map.of("id", new Evidence.JsonString(identity))), List.of());
    }
}
