package dev.packsage.world.minecraft;

import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPOutputStream;
import java.util.zip.DeflaterOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class ReadOnlyRegionsTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void neverCreatesMissingFilesOrDirectories(@TempDir Path directory) throws Exception {
        assertEquals(SavedStarts.Verdict.UNKNOWN, ReadOnlyRegions.read(directory.resolve("missing"), new ChunkPos(0, 0), "minecraft:test"));
        try (var files = Files.list(directory)) {
            assertEquals(0, files.count());
        }
    }

    @Test
    void readsSupportedCompressionWithoutChangingExistingBytesOrSize(@TempDir Path directory) throws Exception {
        var tag = new CompoundTag();
        tag.putInt("DataVersion", SharedConstants.getCurrentVersion().getDataVersion().getVersion());
        tag.putString("Status", "minecraft:full");
        var starts = new CompoundTag();
        var start = new CompoundTag();
        start.putString("id", "minecraft:test");
        starts.put("minecraft:test", start);
        var structures = new CompoundTag();
        structures.put("starts", starts);
        tag.put("structures", structures);
        var region = directory.resolve("region/r.0.0.mca");
        Files.createDirectories(region.getParent());
        for (int compression : new int[] { 1, 2, 3, 4, 0x82 }) {
            var bytes = new ByteArrayOutputStream();
            try (var output = new DataOutputStream(compression == 1 ? new GZIPOutputStream(bytes)
                    : compression == 2 ? new DeflaterOutputStream(bytes) : bytes)) {
                NbtIo.write(tag, output);
            }
            var file = ByteBuffer.allocate(12289); // deliberately unpadded: vanilla close would change this
            file.putInt(0, (2 << 8) | 1);
            file.position(8192);
            file.putInt(bytes.size() + 1).put((byte) compression).put(bytes.toByteArray());
            Files.write(region, file.array());
            assertEquals(compression <= 3 ? SavedStarts.Verdict.FOUND : SavedStarts.Verdict.UNKNOWN,
                    ReadOnlyRegions.read(directory, new ChunkPos(0, 0), "minecraft:test"));
            assertEquals(file.capacity(), Files.size(region));
            assertArrayEquals(file.array(), Files.readAllBytes(region));
            if (compression <= 3) assertEquals(SavedStarts.Verdict.ABSENT, ReadOnlyRegions.read(directory, new ChunkPos(0, 0), "minecraft:other"));
        }
        Files.write(region, new byte[] { 1, 2, 3 });
        assertEquals(SavedStarts.Verdict.UNKNOWN, ReadOnlyRegions.read(directory, new ChunkPos(0, 0), "minecraft:test"));
        assertArrayEquals(new byte[] { 1, 2, 3 }, Files.readAllBytes(region));
    }
}
