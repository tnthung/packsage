package dev.packsage.knowledge.nativegame;

import dev.packsage.knowledge.Evidence;
import net.minecraft.tags.TagKey;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.util.valueproviders.ConstantInt;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.LinkedHashSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeDimensionReaderTest {
    private static final Evidence.Scope SCOPE = new Evidence.Scope("world", "en_us", "team", "native", 1);

    @BeforeAll
    static void bootstrapMinecraft() {
        Bootstrap.bootStrap();
    }

    @Test
    void clientDimensionMembershipIsFreshFilteredAndPaged() {
        var firstSet = new LinkedHashSet<>(List.of(
                ResourceKey.create(Registries.DIMENSION, ResourceLocation.withDefaultNamespace("overworld")),
                ResourceKey.create(Registries.DIMENSION, ResourceLocation.withDefaultNamespace("the_nether"))));
        var first = NativeDimensionReader.clientPage(firstSet, 0, 1, null, List.of(), SCOPE);
        assertEquals(List.of("minecraft:overworld"), first.records().stream().map(Evidence.Record::title).toList());
        assertEquals(1, first.nextOffset());
        assertFalse(first.exhausted());
        assertEquals(Evidence.Coverage.PARTIAL, first.records().getFirst().source().coverage());
        var second = NativeDimensionReader.clientPage(firstSet, first.nextOffset(), 1, null, List.of(), SCOPE);
        assertEquals(List.of("minecraft:the_nether"), second.records().stream().map(Evidence.Record::title).toList());
        assertTrue(second.exhausted());

        var changedSet = Set.of(ResourceKey.create(Registries.DIMENSION, ResourceLocation.withDefaultNamespace("the_end")));
        var changed = NativeDimensionReader.clientPage(changedSet, 0, 16, "end", List.of(), SCOPE);
        assertEquals(List.of("minecraft:the_end"), changed.records().stream().map(Evidence.Record::title).toList());
        assertTrue(changed.exhausted());
        var invalidatedReference = NativeDimensionReader.clientPage(changedSet, 0, 1, null,
                List.of("minecraft:overworld"), SCOPE);
        assertTrue(invalidatedReference.records().isEmpty());
    }

    @Test
    void emptyClientKnowledgeRemainsPartialAndExactFilterCanSelectAnObservedId() {
        var empty = NativeDimensionReader.clientPage(Set.of(), 0, 16, null, List.of(), SCOPE);
        assertTrue(empty.records().isEmpty());
        assertTrue(empty.partial());
        assertTrue(empty.exhausted());

        var keys = Set.of(ResourceKey.create(Registries.DIMENSION, ResourceLocation.withDefaultNamespace("overworld")),
                ResourceKey.create(Registries.DIMENSION, ResourceLocation.withDefaultNamespace("the_nether")));
        var selected = NativeDimensionReader.clientPage(keys, 0, 16, null, List.of("minecraft:the_nether"), SCOPE);
        assertEquals(List.of("minecraft:the_nether"), selected.records().stream().map(Evidence.Record::title).toList());
    }

    @Test
    void filteredCandidateWorkStopsAtTheBoundAndContinuationAdvances() {
        var keys = new LinkedHashSet<ResourceKey<Level>>();
        for (int index = 0; index < 600; index++) keys.add(ResourceKey.create(Registries.DIMENSION,
                ResourceLocation.fromNamespaceAndPath("test", "dimension_" + index)));

        var first = NativeDimensionReader.clientPage(keys, 0, 16, "absent", List.of(), SCOPE);
        assertTrue(first.records().isEmpty());
        assertEquals(512, first.scanned());
        assertEquals(512, first.nextOffset());
        assertFalse(first.exhausted());
        var second = NativeDimensionReader.clientPage(keys, first.nextOffset(), 16, "absent", List.of(), SCOPE);
        assertTrue(second.records().isEmpty());
        assertTrue(second.exhausted());
    }

    @Test
    void fullScanAtSeekLimitIsPartialAndDoesNotOfferAnUnusableCursor() {
        var keys = new LinkedHashSet<ResourceKey<Level>>();
        for (int index = 0; index < 2600; index++) keys.add(ResourceKey.create(Registries.DIMENSION,
                ResourceLocation.fromNamespaceAndPath("test", "dimension_" + index)));

        var capped = NativeDimensionReader.clientPage(keys, 1536, 16, "absent", List.of(), SCOPE);

        assertTrue(capped.records().isEmpty());
        assertFalse(capped.exhausted());
        assertTrue(capped.partial());
        assertEquals(1536, capped.nextOffset());
        assertTrue(capped.detail().contains("narrow"));
    }

    @Test
    void dimensionTypeProjectionIncludesEnvironmentalRulesAndTags() {
        var type = new DimensionType(java.util.OptionalLong.empty(), true, false, false, true, 1.0, true, false,
                0, 256, 256, TagKey.create(Registries.BLOCK, ResourceLocation.withDefaultNamespace("infiniburn_overworld")),
                ResourceLocation.withDefaultNamespace("overworld"), 0.0f,
                new DimensionType.MonsterSettings(false, true, ConstantInt.of(0), 0));
        var projected = NativeRegistryReader.projectDimensionType(type, "minecraft:overworld", List.of("minecraft:test"), false, SCOPE);

        assertEquals("dimension_type", ((Evidence.JsonString) projected.payload().values().get("registry")).value());
        assertEquals("minecraft:overworld", ((Evidence.JsonString) projected.payload().values().get("id")).value());
        assertTrue(projected.payload().values().containsKey("minY"));
        assertTrue(projected.payload().values().containsKey("height"));
        assertTrue(projected.payload().values().containsKey("coordinateScale"));
        assertTrue(projected.payload().values().containsKey("tags"));
        assertEquals(Evidence.Coverage.COMPLETE, projected.source().coverage());

        var truncated = NativeRegistryReader.projectDimensionType(type, "minecraft:overworld",
                java.util.Collections.nCopies(128, "minecraft:test"), true, SCOPE);
        assertEquals(Evidence.Coverage.PARTIAL, truncated.source().coverage());
    }

    @Test
    void serverDimensionWithoutATypeKeepsServerAuthorityAndPartialCoverage() {
        var record = NativeDimensionReader.dimensionRecord("mod:runtime", null, SCOPE, Evidence.Coverage.PARTIAL,
                Evidence.Authority.SERVER_READ, "integrated-server:runtime-dimension");

        assertEquals(Evidence.Authority.SERVER_READ, record.source().authority());
        assertEquals(Evidence.Coverage.PARTIAL, record.source().coverage());
        assertFalse(record.payload().values().containsKey("dimensionType"));
    }
}
