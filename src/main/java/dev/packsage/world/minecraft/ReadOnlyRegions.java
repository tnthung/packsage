package dev.packsage.world.minecraft;

import net.minecraft.SharedConstants;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.ChunkPos;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/** Worker-only region reads. Never opens vanilla storage or creates, writes, pads or repairs a file. */
public final class ReadOnlyRegions {
    private ReadOnlyRegions() {
    }

    public static SavedStarts.Verdict read(Path folder, ChunkPos chunk, String structure) {
        try (var channel = FileChannel.open(SavedStarts.regionFile(folder, chunk), StandardOpenOption.READ)) {
            var size = channel.size();
            var header = ByteBuffer.allocate(8192);
            while (header.hasRemaining()) if (channel.read(header, header.position()) <= 0) return SavedStarts.Verdict.UNKNOWN;
            var location = header.getInt(4 * (Math.floorMod(chunk.x, 32) + Math.floorMod(chunk.z, 32) * 32));
            var offset = (long) (location >>> 8) * 4096;
            var sectors = location & 255;
            if (offset < 8192 || sectors == 0 || offset + sectors * 4096L > size) return SavedStarts.Verdict.UNKNOWN;
            var data = ByteBuffer.allocate(sectors * 4096);
            while (data.hasRemaining()) if (channel.read(data, offset + data.position()) <= 0) return SavedStarts.Verdict.UNKNOWN;
            var length = data.getInt(0);
            if (length <= 1 || length > data.capacity() - 4) return SavedStarts.Verdict.UNKNOWN;
            var compression = Byte.toUnsignedInt(data.get(4));
            if (compression < 1 || compression > 3) return SavedStarts.Verdict.UNKNOWN;
            // Detect concurrent relocation, overwrite or truncation before accepting the snapshot.
            var again = ByteBuffer.allocate(data.capacity());
            while (again.hasRemaining()) if (channel.read(again, offset + again.position()) <= 0) return SavedStarts.Verdict.UNKNOWN;
            var headerAgain = ByteBuffer.allocate(8192);
            while (headerAgain.hasRemaining()) if (channel.read(headerAgain, headerAgain.position()) <= 0) return SavedStarts.Verdict.UNKNOWN;
            if (channel.size() != size || !Arrays.equals(data.array(), again.array()) || !Arrays.equals(header.array(), headerAgain.array())) {
                return SavedStarts.Verdict.UNKNOWN;
            }
            var bytes = new ByteArrayInputStream(data.array(), 5, length - 1);
            // Fully decompress so truncated streams and invalid compression checksums remain UNKNOWN,
            // even when the selective visitor stops before the end of the NBT document.
            byte[] nbt;
            try (var input = compression == 1 ? new GZIPInputStream(bytes) : compression == 2 ? new InflaterInputStream(bytes) : bytes) {
                nbt = input.readNBytes(16 * 1024 * 1024 + 1);
            }
            if (nbt.length > 16 * 1024 * 1024) return SavedStarts.Verdict.UNKNOWN;
            var selector = SavedStarts.selector();
            NbtIo.parse(new DataInputStream(new ByteArrayInputStream(nbt)), selector, NbtAccounter.create(16 * 1024 * 1024));
            return SavedStarts.classify(selector.getResult(), structure, SharedConstants.getCurrentVersion().getDataVersion().getVersion());
        } catch (IOException | RuntimeException exception) {
            return SavedStarts.Verdict.UNKNOWN;
        }
    }
}
