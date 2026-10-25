package dev.packsage.world;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HolderWordsTest {
    private static final UUID PLAYER = UUID.fromString("3f1c2a4e-0000-4000-8000-000000000001");
    private static final UUID HORSE = UUID.fromString("3f1c2a4e-0000-4000-8000-000000000002");

    private static String resolved(String text, HolderWords.Situation situation) {
        return ((HolderWords.Resolved.Path) HolderWords.resolve(HolderWords.parse(text), situation)).path().text();
    }

    private static String unresolved(String text, HolderWords.Situation situation) {
        return ((HolderWords.Resolved.Unresolved) HolderWords.resolve(HolderWords.parse(text), situation)).reason();
    }

    @Test
    void heldResolvesToTheSelectedSlotAtCallTime() {
        var text = "held>minecraft/container#0@minecraft:stone";
        assertEquals("player:" + PLAYER + ">minecraft/main#3@minecraft:iron_sword>minecraft/container#0@minecraft:stone",
                resolved(text, new HolderWords.Situation(PLAYER, 3, "minecraft:iron_sword", null, null, null, -1)));
        assertEquals("player:" + PLAYER + ">minecraft/main#4@minecraft:shulker_box>minecraft/container#0@minecraft:stone",
                resolved(text, new HolderWords.Situation(PLAYER, 4, "minecraft:shulker_box", null, null, null, -1)));

        var place = new BlockPlace("minecraft:overworld", 1, 64, 2);
        var situation = new HolderWords.Situation(PLAYER, 0, "minecraft:stick", "minecraft:shield", new PathRoot.Block(place), HORSE, 7);
        assertEquals("player:" + PLAYER + ">minecraft/offhand#0@minecraft:shield", resolved("offhand", situation));
        assertEquals("block:minecraft:overworld|1,64,2>minecraft/container#5@minecraft:dirt",
                resolved("targeted>minecraft/container#5@minecraft:dirt", situation));
        assertEquals("entity:" + HORSE, resolved("ridden", situation));
    }

    @Test
    void unresolvableWordsSaySoInsteadOfFailing() {
        var situation = new HolderWords.Situation(PLAYER, 0, null, null, null, null, -1);
        var details = Map.of(
                "held", "Your main hand is empty",
                "offhand", "Your offhand is empty",
                "targeted", "Nothing is under the crosshair",
                "ridden", "You are not riding anything",
                "opened", "No menu is open");
        details.forEach((word, detail) -> assertEquals(detail, unresolved(word, situation), word));
    }

    @Test
    void nonWordsAndMalformedStepsAreRejected() {
        assertNull(HolderWords.parse("heldx"));
        assertNull(HolderWords.parse("player"));
        assertNull(HolderWords.parse("block:minecraft:overworld|1,64,2"));
        assertThrows(IllegalArgumentException.class, () -> HolderWords.parse("held>bad"));
        assertThrows(IllegalArgumentException.class, () -> HolderWords.parse("held>"));
        assertEquals(new HolderWords.Phrase(HolderWords.Word.TARGETED, java.util.List.of()), HolderWords.parse("targeted"));

        var deep = HolderWords.parse("held" + ">minecraft/container#0@minecraft:shulker_box".repeat(ItemPath.MAX_STEPS));
        assertThrows(IllegalArgumentException.class,
                () -> HolderWords.resolve(deep, new HolderWords.Situation(PLAYER, 0, "minecraft:shulker_box", null, null, null, -1)));
    }
}
