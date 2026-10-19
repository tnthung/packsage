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
        boolean readableRecipes = recipes instanceof ImmutableCollection<?>;
        List<?> orderedRecipes = readableRecipes ? ((ImmutableCollection<?>) recipes).asList() : List.of();
        int recipeCount = orderedRecipes.size();
        int startOffset = Math.max(offset, 0);
        var ordinary = startOffset < recipeCount
                ? searchCandidates(orderedRecipes, client.level.registryAccess(), itemRegistry, client.getResourceManager(),
                        outputItem, text, startOffset, limit, scope)
                : new Page(List.of(), recipeCount, true, 0, false);
        if (!readableRecipes) ordinary = new Page(ordinary.records(), ordinary.nextOffset(), ordinary.exhausted(),
                ordinary.scanned(), true);
        if (!ordinary.exhausted()) return ordinary;
        if (ordinary.records().size() == limit) return new Page(ordinary.records(), recipeCount, false,
                ordinary.scanned(), ordinary.partial());
        var brewing = NativeBrewingReader.searchCandidates(client.level.potionBrewing(), client.level.registryAccess(),
                itemRegistry, client.getResourceManager(), outputItem, text, Math.max(0, startOffset - recipeCount),
                limit - ordinary.records().size(), scope);
        var combined = new ArrayList<Evidence.Record>(ordinary.records());
        combined.addAll(brewing.records());
        int nextOffset = recipeCount + brewing.nextOffset();
        return new Page(combined, nextOffset, brewing.exhausted(), ordinary.scanned() + brewing.scanned(),
                ordinary.partial() || brewing.partial());
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
        if (id == null || !id.toString().equals(identity)) {
            if (!identity.startsWith("brewing:")) return null;
            var items = client.level.registryAccess().registryOrThrow(Registries.ITEM);
            return NativeBrewingReader.readCandidate(client.level.potionBrewing(), identity,
                    client.level.registryAccess(), items, scope);
        }
        var holder = client.level.getRecipeManager().byKey(id).orElse(null);
        if (holder == null) return null;
        var items = client.level.registryAccess().registryOrThrow(Registries.ITEM);
        return NativeSnapshot.copyRecipe(holder, client.level.registryAccess(), items, client.getResourceManager(), scope, null);
    }

    static PlanPage planCandidates(Minecraft client, Evidence.Scope scope) {
        if (!client.isSameThread()) throw new IllegalStateException("native recipe read must run on the client thread");
        if (client.level == null) return new PlanPage(List.of(), true);
        Registry<Item> itemRegistry = client.level.registryAccess().registryOrThrow(Registries.ITEM);
        var recipes = client.level.getRecipeManager().getRecipes();
        var result = new ArrayList<Evidence.Record>();
        int scanned = 0;
        for (RecipeHolder<?> holder : recipes) {
            if (scanned++ == MAX_CANDIDATES) break;
            result.add(NativeSnapshot.copyRecipe(holder, client.level.registryAccess(), itemRegistry,
                    client.getResourceManager(), scope, null));
        }
        var brewing = NativeBrewingReader.planCandidates(client.level.potionBrewing(), client.level.registryAccess(), itemRegistry, scope);
        result.addAll(brewing.records());
        return new PlanPage(List.copyOf(result), recipes.size() > MAX_CANDIDATES || brewing.partial() || !brewing.exhausted());
    }

    record Page(List<Evidence.Record> records, int nextOffset, boolean exhausted, int scanned, boolean partial) {
        Page {
            records = List.copyOf(records);
        }

        Page(List<Evidence.Record> records, int nextOffset, boolean exhausted, int scanned) {
            this(records, nextOffset, exhausted, scanned, false);
        }
    }

    record PlanPage(List<Evidence.Record> records, boolean partial) {
        PlanPage {
            records = List.copyOf(records);
        }
    }
}
