package dev.packsage.world.minecraft;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.visitors.CollectFields;
import net.minecraft.nbt.visitors.FieldSelector;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.nio.file.Files;
import java.nio.file.Path;

/** Saved-NBT decisions without opening chunk storage or datafixing the collected tag. */
public final class SavedStarts {
    public enum Verdict { FOUND, ABSENT, UNKNOWN }

    private SavedStarts() {
    }

    public static Path regionFile(Path dimensionFolder, ChunkPos chunk) {
        return dimensionFolder.resolve("region").resolve("r." + chunk.getRegionX() + "." + chunk.getRegionZ() + ".mca");
    }

    /** Run on the worker: even a file-existence check may wait on disk. This never opens a region file. */
    public static boolean saved(Path dimensionFolder, ChunkPos chunk) {
        return Files.isRegularFile(regionFile(dimensionFolder, chunk));
    }

    public static CollectFields selector() {
        return new CollectFields(new FieldSelector(IntTag.TYPE, "DataVersion"), new FieldSelector(StringTag.TYPE, "Status"),
                new FieldSelector("structures", CompoundTag.TYPE, "starts"));
    }

    public static Verdict classify(Tag collected, String structureId, int currentDataVersion) {
        if (!(collected instanceof CompoundTag tag) || !tag.contains("DataVersion", Tag.TAG_INT)
                || tag.getInt("DataVersion") != currentDataVersion) return Verdict.UNKNOWN;
        var status = ChunkStatus.byName(tag.getString("Status"));
        if (status == null || !status.isOrAfter(ChunkStatus.STRUCTURE_STARTS)) return Verdict.UNKNOWN;
        var start = tag.getCompound("structures").getCompound("starts").getCompound(structureId);
        return !start.isEmpty() && !"INVALID".equals(start.getString("id")) ? Verdict.FOUND : Verdict.ABSENT;
    }
}
