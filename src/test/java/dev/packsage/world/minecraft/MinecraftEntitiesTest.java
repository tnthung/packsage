package dev.packsage.world.minecraft;

import dev.packsage.knowledge.Evidence;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.ai.attributes.AttributeMap;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MinecraftEntitiesTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void attributesListEveryInstanceWithBaseValueAndModifiers() {
        var attributes = MinecraftEntities.attributes(new AttributeMap(AbstractHorse.createBaseHorseAttributes().build()));
        var jump = attributes.values().stream().map(Evidence.JsonObject.class::cast)
                .filter(entry -> entry.values().get("id").equals(new Evidence.JsonString("minecraft:generic.jump_strength")))
                .findFirst().orElseThrow();
        assertTrue(jump.values().containsKey("base"));
        assertTrue(jump.values().containsKey("value"));
        assertTrue(jump.values().get("modifiers") instanceof Evidence.JsonArray);
        assertTrue(attributes.values().stream().map(Evidence.JsonObject.class::cast)
                .anyMatch(entry -> entry.values().get("id").equals(new Evidence.JsonString("minecraft:generic.movement_speed"))));
    }

    @Test
    void horseJumpAndSpeedAreConvertedToBlocks() {
        var mount = MinecraftEntities.mount(1.0, 0.3375);
        assertEquals(0, new BigDecimal("5.29").compareTo(((Evidence.JsonNumber) mount.values().get("jumpHeightBlocks")).value()));
        assertEquals(0, new BigDecimal("14.23").compareTo(((Evidence.JsonNumber) mount.values().get("speedBlocksPerSecond")).value()));
        assertTrue(mount.values().containsKey("note"));
    }
}
