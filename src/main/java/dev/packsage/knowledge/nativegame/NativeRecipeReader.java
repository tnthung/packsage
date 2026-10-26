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
import net.minecraft.world.item.ItemStack;
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
    private static final int MAX_PLAN_TAG_SCAN = 4096;
    private static final int MAX_PLAN_TAG_MEMBERS = 16;
    /** Whether a recipe could make the item, in the requested components, when its result is only computed from the grid. */
    private interface Producer {
        boolean test(net.minecraft.world.item.crafting.Recipe<?> recipe, Item item, Evidence.JsonObject components);
    }

    /** What each vanilla special recipe can produce; any other class with a computed result could produce anything. */
    private static final Map<Class<?>, Producer> SPECIAL_OUTPUTS = Map.ofEntries(
            Map.entry(net.minecraft.world.item.crafting.FireworkRocketRecipe.class, (recipe, item, components) -> item == net.minecraft.world.item.Items.FIREWORK_ROCKET),
            Map.entry(net.minecraft.world.item.crafting.FireworkStarRecipe.class, (recipe, item, components) -> item == net.minecraft.world.item.Items.FIREWORK_STAR),
            Map.entry(net.minecraft.world.item.crafting.FireworkStarFadeRecipe.class, (recipe, item, components) -> item == net.minecraft.world.item.Items.FIREWORK_STAR),
            Map.entry(net.minecraft.world.item.crafting.TippedArrowRecipe.class, (recipe, item, components) -> item == net.minecraft.world.item.Items.TIPPED_ARROW),
            Map.entry(net.minecraft.world.item.crafting.SuspiciousStewRecipe.class, (recipe, item, components) -> item == net.minecraft.world.item.Items.SUSPICIOUS_STEW),
            Map.entry(net.minecraft.world.item.crafting.MapCloningRecipe.class, (recipe, item, components) -> item == net.minecraft.world.item.Items.FILLED_MAP),
            Map.entry(net.minecraft.world.item.crafting.MapExtendingRecipe.class, (recipe, item, components) -> item == net.minecraft.world.item.Items.FILLED_MAP),
            Map.entry(net.minecraft.world.item.crafting.BookCloningRecipe.class, (recipe, item, components) -> item == net.minecraft.world.item.Items.WRITTEN_BOOK),
            Map.entry(net.minecraft.world.item.crafting.DecoratedPotRecipe.class, (recipe, item, components) -> item == net.minecraft.world.item.Items.DECORATED_POT),
            Map.entry(net.minecraft.world.item.crafting.ShieldDecorationRecipe.class, (recipe, item, components) -> item == net.minecraft.world.item.Items.SHIELD),
            Map.entry(net.minecraft.world.item.crafting.BannerDuplicateRecipe.class, (recipe, item, components) -> item instanceof net.minecraft.world.item.BannerItem),
            Map.entry(net.minecraft.world.item.crafting.ShulkerBoxColoring.class, (recipe, item, components) -> item instanceof net.minecraft.world.item.BlockItem block
                    && block.getBlock() instanceof net.minecraft.world.level.block.ShulkerBoxBlock),
            Map.entry(net.minecraft.world.item.crafting.ArmorDyeRecipe.class, (recipe, item, components) -> new net.minecraft.world.item.ItemStack(item).is(net.minecraft.tags.ItemTags.DYEABLE)),
            // A trim result is the recipe's own base item with a trim, whatever base a data pack names.
            Map.entry(net.minecraft.world.item.crafting.SmithingTrimRecipe.class, (recipe, item, components) -> ((net.minecraft.world.item.crafting.SmithingTrimRecipe) recipe).isBaseIngredient(new net.minecraft.world.item.ItemStack(item))),
            // A repaired stack carries durability; it can stand for a request only when that request can have it.
            Map.entry(net.minecraft.world.item.crafting.RepairItemRecipe.class, (recipe, item, components) -> item.components().has(net.minecraft.core.component.DataComponents.MAX_DAMAGE)
                    || components.values().containsKey("minecraft:max_damage")));

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
        result.items = itemRegistry;
        var expandedTags = new LinkedHashMap<String, List<Evidence.RecipeAlternative>>();
        int scanned = 0;
        for (RecipeHolder<?> holder : recipes) {
            if (scanned++ == MAX_PLAN_INDEX) {
                result.reasons.add("native-recipe-index-cap=" + MAX_PLAN_INDEX);
                result.indexIncomplete = true;
                break;
            }
            try {
                // A trim recipe's result is only an illustration; the real item is the base armor with its trim.
                // Map extension shows an empty map but makes a filled one.
                var output = holder.value() instanceof net.minecraft.world.item.crafting.SmithingTrimRecipe
                        || holder.value() instanceof net.minecraft.world.item.crafting.MapExtendingRecipe
                        ? net.minecraft.world.item.ItemStack.EMPTY : holder.value().getResultItem(registries);
                if (output.isEmpty()) {
                    // Special recipes compute their result from the grid; they matter only for an item with no indexed recipe.
                    result.dynamicOutputs++;
                    var produces = SPECIAL_OUTPUTS.get(holder.value().getClass());
                    if (produces == null) result.unknownDynamic = true;
                    else {
                        var recipe = holder.value();
                        result.dynamicProducers.add((item, components) -> produces.test(recipe, item, components));
                    }
                    continue;
                }
                var identity = NativeSnapshot.itemIdentity(output, itemRegistry);
                result.outputs.computeIfAbsent(identity, ignored -> new ArrayList<>()).add(() ->
                        expandPlanTags(NativeSnapshot.copyRecipe(holder, registries, itemRegistry, resourceManager, scope, identity),
                                itemRegistry, registries, result.reasons, expandedTags));
            } catch (RuntimeException unreadableOutput) {
                result.reasons.add("native-recipe-output-unreadable");
                result.indexIncomplete = true;
            }
        }
        int before = result.reasons.size();
        NativeBrewingReader.indexPlanCandidates(brewing, registries, itemRegistry, scope, result);
        // An unreadable, opaque or capped brewing rule could produce any item.
        result.brewingIncomplete = result.reasons.size() > before;
        return result;
    }

    private static Evidence.Record expandPlanTags(Evidence.Record record, Registry<Item> itemRegistry, RegistryAccess registries,
                                                  Set<String> reasons, Map<String, List<Evidence.RecipeAlternative>> expandedTags) {
        if (record == null) return null;
        var slots = new ArrayList<Evidence.RecipeSlot>();
        for (Evidence.RecipeSlot slot : record.recipePayload().inputs()) {
            var alternatives = new ArrayList<Evidence.RecipeAlternative>();
            for (Evidence.RecipeAlternative alternative : slot.alternatives()) {
                if (!alternative.type().equals("tag") || !alternative.comparisonIdentity().startsWith("#")) {
                    alternatives.add(alternative);
                    continue;
                }
                var members = expandedTags.get(alternative.comparisonIdentity());
                if (members == null) {
                    var location = ResourceLocation.tryParse(alternative.comparisonIdentity().substring(1));
                    var tagKey = location == null ? null : net.minecraft.tags.TagKey.create(Registries.ITEM, location);
                    var tag = tagKey == null ? null : itemRegistry.getTag(tagKey).orElse(null);
                    if (tag == null) {
                        reasons.add("native-recipe-tag-unresolved=" + alternative.comparisonIdentity());
                        members = List.of();
                    } else {
                        var ordered = new ArrayList<net.minecraft.core.Holder<Item>>();
                        int registryScanned = 0;
                        while (registryScanned < itemRegistry.size() && registryScanned < MAX_PLAN_TAG_SCAN
                                && ordered.size() < MAX_PLAN_TAG_MEMBERS + 1) {
                            var member = itemRegistry.getHolder(registryScanned++).orElse(null);
                            if (member != null && member.is(tagKey)) ordered.add(member);
                        }
                        if (tag.size() > MAX_PLAN_TAG_MEMBERS) reasons.add("native-recipe-tag-member-cap=" + alternative.comparisonIdentity() + ":" + MAX_PLAN_TAG_MEMBERS);
                        if (registryScanned == MAX_PLAN_TAG_SCAN && registryScanned < itemRegistry.size() && tag.size() > ordered.size())
                            reasons.add("native-recipe-tag-scan-cap=" + alternative.comparisonIdentity() + ":" + MAX_PLAN_TAG_SCAN);
                        members = ordered.stream().limit(MAX_PLAN_TAG_MEMBERS).map(member -> {
                            var stack = new ItemStack(member.value());
                            return new Evidence.RecipeAlternative("item", NativeSnapshot.itemIdentity(stack, itemRegistry),
                                    NativeSnapshot.componentPayload(stack, registries), 1, Evidence.Consumption.UNKNOWN);
                        }).toList();
                    }
                    expandedTags.put(alternative.comparisonIdentity(), members);
                }
                if (members.isEmpty()) alternatives.add(alternative);
                else alternatives.addAll(members.stream().map(member -> new Evidence.RecipeAlternative(member.type(), member.comparisonIdentity(),
                        member.components(), member.quantity(), alternative.consumption())).toList());
            }
            slots.add(new Evidence.RecipeSlot(slot.groupId(), alternatives));
        }
        var recipe = record.recipePayload();
        var payload = new LinkedHashMap<>(record.payload().values());
        payload.put("inputs", NativeSnapshot.recipePayloadJson(recipe.canonicalRecipeId(), slots, recipe.outputs(), recipe.remainders(),
                recipe.catalysts(), recipe.constraints(), recipe.decodingStatus(), Set.of()).values().get("inputs"));
        return new Evidence.Record(record.source(), record.kind(), record.title(), new Evidence.JsonObject(payload), record.targets(),
                new Evidence.RecipePayload(recipe.canonicalRecipeId(), slots, recipe.outputs(), recipe.remainders(), recipe.catalysts(),
                        recipe.constraints(), recipe.decodingStatus()));
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
        private int dynamicOutputs;
        private boolean indexIncomplete;
        private boolean unknownDynamic;
        private boolean brewingIncomplete;
        private final List<java.util.function.BiPredicate<Item, Evidence.JsonObject>> dynamicProducers = new ArrayList<>();
        private Registry<Item> items;
        private final Set<String> incomplete = new java.util.HashSet<>();

        private PlanSource(Evidence.Scope scope) {
            this.scope = scope;
        }

        @Override public boolean available(Evidence.Scope requestedScope) { return scope.equals(requestedScope); }

        @Override
        public boolean recipesComplete(String identity, Evidence.JsonObject components) {
            if (indexIncomplete || unknownDynamic || brewingIncomplete || incomplete.contains(identity)) return false;
            var key = ResourceLocation.tryParse(identity);
            var item = key == null || items == null ? null : items.getOptional(key).orElse(null);
            return item == null || dynamicProducers.stream().noneMatch(produces -> produces.test(item, components));
        }

        @Override
        public List<Evidence.Record> recipeOutputs(String identity, Evidence.JsonObject components, Evidence.Scope requestedScope) {
            if (!available(requestedScope)) return List.of();
            return copied.computeIfAbsent(identity, key -> {
                var records = new ArrayList<Evidence.Record>();
                var indexed = outputs.getOrDefault(key, List.of());
                // Only the requested item: ingredients without a recipe are ordinary raw materials.
                if (copied.isEmpty() && indexed.isEmpty() && dynamicOutputs > 0) {
                    reasons.add("native-recipe-dynamic-outputs-unindexed=" + dynamicOutputs);
                }
                for (var candidate : indexed) {
                    if (copies == MAX_PLAN_COPIES) {
                        reasons.add("native-recipe-copy-cap=" + MAX_PLAN_COPIES);
                        incomplete.add(key);
                        break;
                    }
                    copies++;
                    try {
                        var record = candidate.get();
                        if (record == null) {
                            reasons.add("native-recipe-copy-unreadable=" + key);
                            incomplete.add(key);
                        } else records.add(record);
                    } catch (RuntimeException unreadableRecipe) {
                        reasons.add("native-recipe-copy-unreadable=" + key);
                        incomplete.add(key);
                    }
                }
                return List.copyOf(records);
            }).stream().filter(record -> record.recipePayload().outputs().stream().anyMatch(output ->
                    identity.equals(output.comparisonIdentity())
                            && (components.values().isEmpty() || output.components().equals(components)))).toList();
        }
    }
}
