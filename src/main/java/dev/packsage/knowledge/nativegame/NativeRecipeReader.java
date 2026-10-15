package dev.packsage.knowledge.nativegame;

import com.google.common.collect.ImmutableCollection;
import dev.packsage.knowledge.Evidence;
import net.minecraft.client.Minecraft;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.RecipeHolder;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class NativeRecipeReader {
    static final int MAX_CANDIDATES = 512;
    static final int MAX_OUTPUT_CANDIDATES = 4096;

    private NativeRecipeReader() { }

    static Page search(Minecraft client, String outputItem, String text, int offset, int limit, Evidence.Scope scope) {
        if (!client.isSameThread()) throw new IllegalStateException("native recipe read must run on the client thread");
        if (client.level == null) throw new IllegalStateException("client level unavailable");
        Registry<Item> itemRegistry = client.level.registryAccess().registryOrThrow(Registries.ITEM);
        var recipes = client.level.getRecipeManager().getRecipes();
        if (!(recipes instanceof ImmutableCollection<?> immutableRecipes)) return new Page(List.of(), offset, false, 0);
        return searchCandidates(immutableRecipes.asList(), client.level.registryAccess(), itemRegistry,
                client.getResourceManager(), outputItem, text, offset, limit, scope);
    }

    static Page searchCandidates(List<?> orderedRecipes, HolderLookup.Provider registries,
                                 Registry<Item> itemRegistry, ResourceManager resourceManager,
                                 String outputItem, String text, int offset, int limit, Evidence.Scope scope) {
        var records = new ArrayList<Evidence.Record>();
        int index = Math.min(offset, orderedRecipes.size());
        int scanned = 0;
        while (index < orderedRecipes.size() && scanned < (outputItem == null ? MAX_CANDIDATES : MAX_OUTPUT_CANDIDATES)) {
            RecipeHolder<?> holder = (RecipeHolder<?>) orderedRecipes.get(index);
            index++;
            scanned++;
            var record = NativeSnapshot.copyRecipe(holder, registries, itemRegistry, resourceManager, scope, outputItem);
            if (record == null) continue;
            if (text != null && !(record.title() + " " + record.source().sourceHandle() + " "
                    + Evidence.serialize(record.payload())).toLowerCase(Locale.ROOT).contains(text.toLowerCase(Locale.ROOT))) continue;
            records.add(record);
            if (records.size() == limit) break;
        }
        return new Page(records, index, index >= orderedRecipes.size(), scanned);
    }

    static Evidence.Record read(Minecraft client, String identity, Evidence.Scope scope) {
        if (!client.isSameThread()) throw new IllegalStateException("native recipe read must run on the client thread");
        if (client.level == null) return null;
        var id = ResourceLocation.tryParse(identity);
        if (id == null || !id.toString().equals(identity)) return null;
        var holder = client.level.getRecipeManager().byKey(id).orElse(null);
        if (holder == null) return null;
        var items = client.level.registryAccess().registryOrThrow(Registries.ITEM);
        return NativeSnapshot.copyRecipe(holder, client.level.registryAccess(), items, client.getResourceManager(), scope, null);
    }

    static List<Evidence.Record> planCandidates(Minecraft client, Evidence.Scope scope) {
        if (!client.isSameThread()) throw new IllegalStateException("native recipe read must run on the client thread");
        if (client.level == null) return List.of();
        Registry<Item> itemRegistry = client.level.registryAccess().registryOrThrow(Registries.ITEM);
        var recipes = client.level.getRecipeManager().getRecipes();
        var result = new ArrayList<Evidence.Record>();
        int scanned = 0;
        for (RecipeHolder<?> holder : recipes) {
            if (scanned++ == MAX_CANDIDATES) break;
            result.add(NativeSnapshot.copyRecipe(holder, client.level.registryAccess(), itemRegistry,
                    client.getResourceManager(), scope, null));
        }
        return List.copyOf(result);
    }

    record Page(List<Evidence.Record> records, int nextOffset, boolean exhausted, int scanned) {
        Page {
            records = List.copyOf(records);
        }
    }
}
