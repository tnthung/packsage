package dev.packsage.knowledge.nativegame;

import dev.packsage.knowledge.Evidence;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.attributes.RangedAttribute;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativePlayerAttributeReaderTest {
    private static final Evidence.Scope SCOPE = new Evidence.Scope("world", "en_us", "team", "native", 1);

    @BeforeAll
    static void bootstrapMinecraft() {
        Bootstrap.bootStrap();
    }

    private static AttributeInstance luck() {
        var instance = new AttributeInstance(Attributes.LUCK, ignored -> { });
        instance.addPermanentModifier(new AttributeModifier(ResourceLocation.parse("packsage:test_charm"), 1,
                AttributeModifier.Operation.ADD_VALUE));
        instance.addPermanentModifier(new AttributeModifier(ResourceLocation.parse("minecraft:effect.luck"), 12,
                AttributeModifier.Operation.ADD_VALUE));
        return instance;
    }

    private static Evidence.JsonObject payload(Evidence.Record record) {
        return (Evidence.JsonObject) record.payload();
    }

    @Test
    void luckRecordListsSortedModifiersAndIsComplete() {
        var record = NativePlayerAttributeReader.record(luck(), SCOPE);
        assertEquals("player_attribute:minecraft:generic.luck", record.source().sourceHandle());
        assertEquals(Evidence.Coverage.COMPLETE, record.source().coverage());
        assertEquals(List.of("client-player:attributes"), record.source().provenance());
        var values = payload(record).values();
        assertEquals(new Evidence.JsonString("player_attribute"), values.get("registry"));
        assertEquals(new Evidence.JsonString("minecraft:generic.luck"), values.get("id"));
        assertEquals(new Evidence.JsonBoolean(true), values.get("syncable"));
        assertEquals(0, new java.math.BigDecimal(13).compareTo(((Evidence.JsonNumber) values.get("value")).value()));
        assertEquals(0, java.math.BigDecimal.ZERO.compareTo(((Evidence.JsonNumber) values.get("base")).value()));
        var modifiers = ((Evidence.JsonArray) values.get("modifiers")).values();
        assertEquals(2, modifiers.size());
        var first = ((Evidence.JsonObject) modifiers.get(0)).values();
        assertEquals(new Evidence.JsonString("minecraft:effect.luck"), first.get("id"));
        assertEquals(new Evidence.JsonString("add_value"), first.get("operation"));
        assertEquals(new Evidence.JsonString("packsage:test_charm"),
                ((Evidence.JsonObject) modifiers.get(1)).values().get("id"));
        assertFalse(values.containsKey("modifiersStatus"));
    }

    @Test
    void manyModifiersAreBoundedAndPartial() {
        var instance = new AttributeInstance(Attributes.LUCK, ignored -> { });
        for (int index = 0; index < 65; index++) {
            instance.addPermanentModifier(new AttributeModifier(ResourceLocation.parse("packsage:m" + index), 1,
                    AttributeModifier.Operation.ADD_VALUE));
        }
        var record = NativePlayerAttributeReader.record(instance, SCOPE);
        var values = payload(record).values();
        assertEquals(64, ((Evidence.JsonArray) values.get("modifiers")).values().size());
        assertEquals(new Evidence.JsonString("bounded"), values.get("modifiersStatus"));
        assertEquals(Evidence.Coverage.PARTIAL, record.source().coverage());
    }

    @Test
    void nonSyncableAttributeIsPartial() {
        var attribute = new RangedAttribute("attribute.name.packsage_test", 0, 0, 10).setSyncable(false);
        var record = NativePlayerAttributeReader.record(new AttributeInstance(Holder.direct(attribute), ignored -> { }), SCOPE);
        assertEquals(new Evidence.JsonBoolean(false), payload(record).values().get("syncable"));
        assertEquals(Evidence.Coverage.PARTIAL, record.source().coverage());
    }

    @Test
    void searchFiltersSortsAndPages() {
        var armor = new AttributeInstance(Attributes.ARMOR, ignored -> { });
        var all = List.of(luck(), armor);
        var page = NativePlayerAttributeReader.search(all, null, List.of(), List.of(), 0, 16, SCOPE);
        assertEquals(List.of("player_attribute:minecraft:generic.armor", "player_attribute:minecraft:generic.luck"),
                page.records().stream().map(record -> record.source().sourceHandle()).toList());
        var first = NativePlayerAttributeReader.search(all, null, List.of(), List.of(), 0, 1, SCOPE);
        assertEquals(1, first.nextOffset());
        assertFalse(first.exhausted());
        assertEquals(List.of("player_attribute:minecraft:generic.luck"), NativePlayerAttributeReader.search(all, null,
                List.of(), List.of(), first.nextOffset(), 1, SCOPE).records().stream().map(r -> r.source().sourceHandle()).toList());
        assertEquals(1, NativePlayerAttributeReader.search(all, null, List.of("minecraft:generic.luck"), List.of(), 0, 16, SCOPE)
                .records().size());
        assertEquals(1, NativePlayerAttributeReader.search(all, "LUC", List.of(), List.of(), 0, 16, SCOPE).records().size());
        assertTrue(NativePlayerAttributeReader.search(all, "nothing-here", List.of(), List.of(), 0, 16, SCOPE).records().isEmpty());
    }

    @Test
    void nonFiniteModifierAmountIsMarkedAndPartial() {
        var instance = luck();
        instance.addPermanentModifier(new AttributeModifier(ResourceLocation.parse("packsage:nan"), Double.NaN,
                AttributeModifier.Operation.ADD_VALUE));
        var record = NativePlayerAttributeReader.record(instance, SCOPE);
        var values = payload(record).values();
        var modifiers = ((Evidence.JsonArray) values.get("modifiers")).values();
        assertEquals(3, modifiers.size());
        assertTrue(modifiers.stream().anyMatch(modifier -> new Evidence.JsonString("non_finite")
                .equals(((Evidence.JsonObject) modifier).values().get("amount"))));
        assertEquals(new Evidence.JsonString("non_finite"), values.get("value"));
        assertEquals(Evidence.Coverage.PARTIAL, record.source().coverage());
    }

    @Test
    void searchKeepsOtherRecordsWhenOneAttributeThrows() {
        var broken = new AttributeInstance(Attributes.ARMOR, ignored -> { }) {
            @Override
            public double getValue() {
                throw new IllegalStateException("boom");
            }
        };
        var page = NativePlayerAttributeReader.search(List.of(luck(), broken), null, List.of(), List.of(), 0, 16, SCOPE);
        assertEquals(2, page.records().size());
        var stub = page.records().getFirst();
        assertEquals(Evidence.Coverage.PARTIAL, stub.source().coverage());
        assertEquals(new Evidence.JsonString("unknown"), payload(stub).values().get("status"));
        assertEquals(new Evidence.JsonString("minecraft:generic.armor"), payload(stub).values().get("id"));
        assertEquals(Evidence.Coverage.COMPLETE, page.records().getLast().source().coverage());
        var read = NativePlayerAttributeReader.read(List.of(broken), "minecraft:generic.armor", SCOPE);
        assertEquals(new Evidence.JsonString("unknown"), payload(read).values().get("status"));
    }

    @Test
    void readReturnsNullForAbsentAttribute() {
        assertNull(NativePlayerAttributeReader.read(List.of(), "minecraft:generic.luck", SCOPE));
        assertNotNull(NativePlayerAttributeReader.read(List.of(luck()), "minecraft:generic.luck", SCOPE));
    }
}
