package dev.packsage.knowledge.nativegame;

import dev.packsage.knowledge.Evidence;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Registry;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.alchemy.PotionBrewing;
import net.minecraft.server.packs.resources.ResourceManager;

import java.util.List;

final class NativeBrewingReader {
    private NativeBrewingReader() { }

    static Page searchCandidates(PotionBrewing brewing, HolderLookup.Provider registries,
                                 Registry<Item> itemRegistry, ResourceManager resourceManager,
                                 String outputItem, String text,
                                 int offset, int limit, Evidence.Scope scope) {
        return new Page(List.of(), offset, true, 0);
    }

    static Evidence.Record readCandidate(PotionBrewing brewing, String identity,
                                         HolderLookup.Provider registries, Registry<Item> itemRegistry,
                                         Evidence.Scope scope) {
        return null;
    }

    record Page(List<Evidence.Record> records, int nextOffset, boolean exhausted, int scanned) {
        Page {
            records = List.copyOf(records);
        }
    }
}
