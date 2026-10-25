package dev.packsage.world.minecraft;

import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SavedStartsTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void classifiesSavedStarts() {
        var version = SharedConstants.getCurrentVersion().getDataVersion().getVersion();
        var tag = new CompoundTag();
        tag.putInt("DataVersion", version);
        tag.putString("Status", "minecraft:full");
        var start = new CompoundTag();
        start.putString("id", "minecraft:village_plains");
        var starts = new CompoundTag();
        starts.put("minecraft:village_plains", start);
        var structures = new CompoundTag();
        structures.put("starts", starts);
        tag.put("structures", structures);
        assertEquals(SavedStarts.Verdict.FOUND, SavedStarts.classify(tag, "minecraft:village_plains", version));

        tag.putString("Status", "minecraft:structure_starts");
        assertEquals(SavedStarts.Verdict.FOUND, SavedStarts.classify(tag, "minecraft:village_plains", version));
        start.putString("id", "INVALID");
        assertEquals(SavedStarts.Verdict.ABSENT, SavedStarts.classify(tag, "minecraft:village_plains", version));
        starts.remove("minecraft:village_plains");
        assertEquals(SavedStarts.Verdict.ABSENT, SavedStarts.classify(tag, "minecraft:village_plains", version));
        tag.putString("Status", "minecraft:empty");
        assertEquals(SavedStarts.Verdict.UNKNOWN, SavedStarts.classify(tag, "minecraft:village_plains", version));
        tag.putString("Status", "minecraft:full");
        tag.putInt("DataVersion", version - 1);
        assertEquals(SavedStarts.Verdict.UNKNOWN, SavedStarts.classify(tag, "minecraft:village_plains", version));
        assertEquals(SavedStarts.Verdict.UNKNOWN, SavedStarts.classify(null, "minecraft:village_plains", version));
    }

    @Test
    void missingOrUnrecognizedMetadataIsUnknown() {
        var version = SharedConstants.getCurrentVersion().getDataVersion().getVersion();
        var tag = new CompoundTag();
        assertEquals(SavedStarts.Verdict.UNKNOWN, SavedStarts.classify(tag, "minecraft:village_plains", version));
        tag.putInt("DataVersion", version);
        assertEquals(SavedStarts.Verdict.UNKNOWN, SavedStarts.classify(tag, "minecraft:village_plains", version));
        tag.putString("Status", "minecraft:unknown_status");
        assertEquals(SavedStarts.Verdict.UNKNOWN, SavedStarts.classify(tag, "minecraft:village_plains", version));
        assertEquals(SavedStarts.Verdict.UNKNOWN, SavedStarts.classify(IntTag.valueOf(version), "minecraft:village_plains", version));
    }

    @Test
    void selectorCollectsOnlyTheSavedStartFields() {
        var tag = new CompoundTag();
        tag.putInt("DataVersion", 3955);
        tag.putString("Status", "minecraft:full");
        tag.putString("unrelated", "not selected");
        var start = new CompoundTag();
        start.putString("id", "minecraft:village_plains");
        var starts = new CompoundTag();
        starts.put("minecraft:village_plains", start);
        var structures = new CompoundTag();
        structures.put("starts", starts);
        structures.putString("References", "not selected");
        tag.put("structures", structures);
        var selector = SavedStarts.selector();
        tag.acceptAsRoot(selector);

        var expected = tag.copy();
        expected.remove("unrelated");
        expected.getCompound("structures").remove("References");
        assertEquals(expected, selector.getResult());
        assertEquals(0, selector.getMissingFieldCount());
    }

    @Test
    void noRegionFileMeansNotSavedAndCreatesNothing(@TempDir Path directory) throws IOException {
        assertFalse(SavedStarts.saved(directory, new ChunkPos(40, 40)));
        try (var files = Files.list(directory)) {
            assertEquals(0, files.count());
        }
    }

    @Test
    void regionFileUsesSignedRegionCoordinatesAndRequiresARegularFile(@TempDir Path directory) throws IOException {
        var region = directory.resolve("region/r.-1.-2.mca");
        assertEquals(region, SavedStarts.regionFile(directory, new ChunkPos(-1, -33)));
        Files.createDirectories(region);
        assertFalse(SavedStarts.saved(directory, new ChunkPos(-1, -33)));
        Files.delete(region);
        Files.write(region, new byte[] { 1, 2, 3 });
        assertTrue(SavedStarts.saved(directory, new ChunkPos(-1, -33)));
        assertEquals(3, Files.size(region));
    }
}
