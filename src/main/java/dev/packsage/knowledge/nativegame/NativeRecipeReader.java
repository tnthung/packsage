package dev.packsage.knowledge.nativegame;

import com.google.common.collect.ImmutableCollection;
import dev.packsage.knowledge.Evidence;
import dev.packsage.knowledge.RecipePlanner;
import net.minecraft.client.Minecraft;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.alchemy.PotionBrewing;
import net.minecraft.world.item.crafting.RecipeHolder;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

final class NativeRecipeReader {
    static final int MAX_CANDIDATES = 512;
    static final int MAX_OUTPUT_CANDIDATES = 4096;
    static final int MAX_PLAN_INDEX = 65536;
    private static final int MAX_PLAN_COPIES = 512;

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
        int brewingCount = NativeBrewingReader.candidateCount(client.level.potionBrewing(), client.level.registryAccess());
        if (!ordinary.exhausted()) return new Page(ordinary.records(), ordinary.nextOffset(), false, ordinary.scanned(),
                ordinary.partial(), progressDetail(startOffset, ordinary.nextOffset(), recipeCount, brewingCount,
                ordinary.records().size(), true));
        if (ordinary.records().size() == limit) return new Page(ordinary.records(), recipeCount, false,
                ordinary.scanned(), ordinary.partial(), progressDetail(startOffset, recipeCount, recipeCount, brewingCount,
                ordinary.records().size(), true));
        var brewing = NativeBrewingReader.searchCandidates(client.level.potionBrewing(), client.level.registryAccess(),
                itemRegistry, client.getResourceManager(), outputItem, text, Math.max(0, startOffset - recipeCount),
                limit - ordinary.records().size(), scope);
        var combined = new ArrayList<Evidence.Record>(ordinary.records());
        combined.addAll(brewing.records());
        int nextOffset = recipeCount + brewing.nextOffset();
        return new Page(combined, nextOffset, brewing.exhausted(), ordinary.scanned() + brewing.scanned(),
                ordinary.partial() || brewing.partial(), progressDetail(startOffset, nextOffset, recipeCount, brewingCount,
                combined.size(), !brewing.exhausted()));
    }

    static String progressDetail(int startOffset, int nextOffset, int ordinaryCount, int brewingCount, int matches,
                                 boolean hasContinuation) {
        return "scanned=" + startOffset + "-" + nextOffset + " of " + (ordinaryCount + brewingCount)
                + " (ordinary recipes " + ordinaryCount + ", brewing candidates " + brewingCount + "); phase="
                + (nextOffset <= ordinaryCount ? "ordinary" : "brewing") + "; matches=" + matches
                + (matches == 0 && hasContinuation ? "; no matches in this range; continue with the cursor to scan the rest" : "");
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

    static PlanSource planSource(Minecraft client, Evidence.Scope scope) {
        if (!client.isSameThread()) throw new IllegalStateException("native recipe read must run on the client thread");
        if (client.level == null) throw new IllegalStateException("client level unavailable");
        return planSource(client.level.getRecipeManager().getRecipes(), client.level.potionBrewing(), client.level.registryAccess(),
                client.level.registryAccess().registryOrThrow(Registries.ITEM), client.getResourceManager(), scope);
    }

    static PlanSource planSource(Collection<RecipeHolder<?>> recipes, PotionBrewing brewing, RegistryAccess registries,
                                 Registry<Item> itemRegistry, ResourceManager resourceManager, Evidence.Scope scope) {
        var result = new PlanSource(scope);
        int scanned = 0;
        for (RecipeHolder<?> holder : recipes) {
            if (scanned++ == MAX_PLAN_INDEX) {
                result.reasons.add("native-recipe-index-cap=" + MAX_PLAN_INDEX);
                break;
            }
            try {
                var output = holder.value().getResultItem(registries);
                if (output.isEmpty()) {
                    result.reasons.add("native-recipe-output-unreadable");
                    continue;
                }
                var identity = NativeSnapshot.itemIdentity(output, itemRegistry);
                result.outputs.computeIfAbsent(identity, ignored -> new ArrayList<>()).add(() ->
                        NativeSnapshot.copyRecipe(holder, registries, itemRegistry, resourceManager, scope, identity));
            } catch (RuntimeException unreadableOutput) {
                result.reasons.add("native-recipe-output-unreadable");
            }
        }
        NativeBrewingReader.indexPlanCandidates(brewing, registries, itemRegistry, scope, result);
        return result;
    }

    record Page(List<Evidence.Record> records, int nextOffset, boolean exhausted, int scanned, boolean partial,
                String detail) {
        Page {
            records = List.copyOf(records);
        }

        Page(List<Evidence.Record> records, int nextOffset, boolean exhausted, int scanned, boolean partial) {
            this(records, nextOffset, exhausted, scanned, partial, null);
        }

        Page(List<Evidence.Record> records, int nextOffset, boolean exhausted, int scanned) {
            this(records, nextOffset, exhausted, scanned, false, null);
        }
    }

    static final class PlanSource implements RecipePlanner.Source {
        private final Evidence.Scope scope;
        final Map<String, List<Supplier<Evidence.Record>>> outputs = new LinkedHashMap<>();
        final Set<String> reasons = new LinkedHashSet<>();
        private final Map<String, List<Evidence.Record>> copied = new LinkedHashMap<>();
        private int copies;

        private PlanSource(Evidence.Scope scope) {
            this.scope = scope;
        }

        @Override public boolean available(Evidence.Scope requestedScope) { return scope.equals(requestedScope); }

        @Override
        public List<Evidence.Record> recipeOutputs(String identity, Evidence.JsonObject components, Evidence.Scope requestedScope) {
            if (!available(requestedScope)) return List.of();
            return copied.computeIfAbsent(identity, key -> {
                var records = new ArrayList<Evidence.Record>();
                for (var candidate : outputs.getOrDefault(key, List.of())) {
                    if (copies == MAX_PLAN_COPIES) {
                        reasons.add("native-recipe-copy-cap=" + MAX_PLAN_COPIES);
                        break;
                    }
                    copies++;
                    try {
                        var record = candidate.get();
                        if (record == null) reasons.add("native-recipe-copy-unreadable=" + key);
                        else records.add(record);
                    } catch (RuntimeException unreadableRecipe) {
                        reasons.add("native-recipe-copy-unreadable=" + key);
                    }
                }
                return List.copyOf(records);
            }).stream().filter(record -> record.recipePayload().outputs().stream().anyMatch(output ->
                    identity.equals(output.comparisonIdentity())
                            && (components.values().isEmpty() || output.components().equals(components)))).toList();
        }
    }
}
