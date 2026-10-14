package dev.packsage.knowledge.nativegame;

import dev.packsage.knowledge.Evidence;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

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
        var firstSet = Set.of(ResourceKey.create(Registries.DIMENSION, ResourceLocation.withDefaultNamespace("overworld")),
                ResourceKey.create(Registries.DIMENSION, ResourceLocation.withDefaultNamespace("the_nether")));
        var first = NativeDimensionReader.clientPage(firstSet, 0, 1, "minecraft:", List.of(), SCOPE);
        assertEquals(List.of("minecraft:overworld"), first.records().stream().map(Evidence.Record::title).toList());
        assertEquals(1, first.nextOffset());
        assertFalse(first.exhausted());
        assertEquals(Evidence.Coverage.PARTIAL, first.records().getFirst().source().coverage());

        var changedSet = Set.of(ResourceKey.create(Registries.DIMENSION, ResourceLocation.withDefaultNamespace("the_end")));
        var changed = NativeDimensionReader.clientPage(changedSet, 0, 16, "end", List.of(), SCOPE);
        assertEquals(List.of("minecraft:the_end"), changed.records().stream().map(Evidence.Record::title).toList());
        assertTrue(changed.exhausted());
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
    void dimensionTypeProjectionIncludesEnvironmentalRulesAndTags() {
        var registry = RegistryAccess.BUILTIN.registryOrThrow(Registries.DIMENSION_TYPE);
        var type = registry.get(ResourceLocation.withDefaultNamespace("overworld"));
        var projected = NativeRegistryReader.dimensionType(type, registry, SCOPE);

        assertEquals("dimension_type", ((Evidence.JsonString) projected.payload().values().get("registry")).value());
        assertEquals("minecraft:overworld", ((Evidence.JsonString) projected.payload().values().get("id")).value());
        assertTrue(projected.payload().values().containsKey("minY"));
        assertTrue(projected.payload().values().containsKey("height"));
        assertTrue(projected.payload().values().containsKey("coordinateScale"));
        assertTrue(projected.payload().values().containsKey("tags"));
    }
}
