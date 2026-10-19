package dev.packsage.knowledge.nativegame;

import dev.packsage.knowledge.Evidence;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.PlainTextContents;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.PotionItem;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionBrewing;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.crafting.Ingredient;
import net.neoforged.neoforge.common.brewing.BrewingRecipe;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class NativeBrewingReader {
    static final int MAX_CANDIDATES = 512;
    static final int MAX_OUTPUT_CANDIDATES = 4096;
    private static final int MAX_RULES = 8192;
    private static final int MAX_ALTERNATIVES = 64;
    private static final int MAX_CONTAINERS = 64;
    private static final int MAX_POTIONS = 256;
    private static final int MAX_READ_CANDIDATES = 4096;
    private static final int MAX_COMPONENT_BYTES = 16384;
    private static final int MAX_FACT_ID_LENGTH = 128;

    private NativeBrewingReader() { }

    static Page searchCandidates(PotionBrewing brewing, RegistryAccess registries,
                                 Registry<Item> itemRegistry, ResourceManager resourceManager,
                                 String outputItem, String text, int offset, int limit, Evidence.Scope scope) {
        var source = source(brewing, registries);
        var records = new ArrayList<Evidence.Record>();
        var normalizedText = text == null ? null : text.toLowerCase(Locale.ROOT);
        int index = Math.max(offset, 0);
        int scanned = 0;
        boolean partial = source.partial();
        int candidateLimit = outputItem == null ? MAX_CANDIDATES : MAX_OUTPUT_CANDIDATES;
        if (limit < 1) return new Page(records, index, index >= source.count(), 0, partial);
        while (index < source.count() && scanned < candidateLimit) {
            var projected = candidateAt(source, index++, registries, itemRegistry, scope);
            scanned++;
            if (projected == null) continue;
            partial |= projected.partial();
            var record = projected.record();
            if (record == null) continue;
            if (outputItem != null && (!projected.outputKnown() || !outputItem.equals(projected.outputItem()))) continue;
            if (normalizedText != null && !(record.title() + " " + record.source().sourceHandle() + " "
                    + Evidence.serialize(record.payload())).toLowerCase(Locale.ROOT).contains(normalizedText)) continue;
            records.add(record);
            if (records.size() == limit) return new Page(records, index, index >= source.count(), scanned, partial);
        }
        return new Page(records, index, index >= source.count(), scanned, partial);
    }

    static Evidence.Record readCandidate(PotionBrewing brewing, String identity, RegistryAccess registries,
                                         Registry<Item> itemRegistry, Evidence.Scope scope) {
        if (identity == null || identity.length() > 160) return null;
        var parts = identity.split(":", -1);
        if (parts.length != 5 || !parts[0].equals("brewing") || parts[4].length() != 64) return null;
        int ruleIndex;
        int variantIndex;
        try {
            ruleIndex = Integer.parseInt(parts[2]);
            variantIndex = Integer.parseInt(parts[3]);
        } catch (NumberFormatException invalidLocator) {
            return null;
        }
        if (ruleIndex < 0 || variantIndex < 0 || !parts[4].matches("[0-9a-f]{64}")) return null;

        var source = source(brewing, registries);
        String expectedFingerprint = parts[4];
        var direct = candidateAt(source, parts[1], ruleIndex, variantIndex, registries, itemRegistry, scope);
        if (direct != null && identity.equals(direct.identity())) return direct.record();

        for (int index = 0; index < Math.min(source.count(), MAX_READ_CANDIDATES); index++) {
            var projected = candidateAt(source, index, registries, itemRegistry, scope);
            if (projected != null) {
                var refreshedParts = projected.identity().split(":", -1);
                if (refreshedParts.length == 5 && parts[1].equals(refreshedParts[1])
                        && expectedFingerprint.equals(refreshedParts[4])) return projected.record();
            }
        }
        return null;
    }

    private static Projection candidateAt(Source source, int index, RegistryAccess registries,
                                           Registry<Item> itemRegistry, Evidence.Scope scope) {
        if (index < source.customCount()) return projectCustom(source.customRecipes().get(index), index,
                registries, itemRegistry, scope);
        index -= source.customCount();
        int containerVariants = Math.max(1, source.potions().size());
        int containerCandidates = source.containerMixCount() * containerVariants;
        if (index < containerCandidates) {
            int rule = index / containerVariants;
            int variant = index % containerVariants;
            return projectContainer(source.containerMixes().get(rule), rule, variant, source,
                    registries, itemRegistry, scope);
        }
        index -= containerCandidates;
        int bottleVariants = source.bottles().size();
        if (bottleVariants == 0 || index >= source.potionMixCount() * bottleVariants) return null;
        int rule = index / bottleVariants;
        int variant = index % bottleVariants;
        return projectPotion(source.potionMixes().get(rule), rule, variant, source,
                registries, itemRegistry, scope);
    }

    private static Projection candidateAt(Source source, String phase, int ruleIndex, int variantIndex,
                                           RegistryAccess registries, Registry<Item> itemRegistry, Evidence.Scope scope) {
        if (phase.equals("custom") && variantIndex == 0 && ruleIndex < source.customCount()) {
            return projectCustom(source.customRecipes().get(ruleIndex), ruleIndex, registries, itemRegistry, scope);
        }
        if (phase.equals("container") && ruleIndex < source.containerMixCount()
                && variantIndex < Math.max(1, source.potions().size())) {
            return projectContainer(source.containerMixes().get(ruleIndex), ruleIndex, variantIndex, source,
                    registries, itemRegistry, scope);
        }
        if (phase.equals("potion") && ruleIndex < source.potionMixCount() && variantIndex < source.bottles().size()) {
            return projectPotion(source.potionMixes().get(ruleIndex), ruleIndex, variantIndex, source,
                    registries, itemRegistry, scope);
        }
        return null;
    }

    static Page planCandidates(PotionBrewing brewing, RegistryAccess registries, Registry<Item> itemRegistry,
                               Evidence.Scope scope) {
        return searchCandidates(brewing, registries, itemRegistry, null, null, null, 0, MAX_CANDIDATES, scope);
    }

    private static Source source(PotionBrewing brewing, RegistryAccess registries) {
        boolean partial = true;
        if (brewing == null || brewing.getClass() != PotionBrewing.class) {
            return new Source(List.of(), 0, List.of(), 0, List.of(), 0, List.of(), 0,
                    List.of(), List.of(), true, 0);
        }

        List<?> customRecipes = List.of();
        List<?> containers = List.of();
        List<?> containerMixes = List.of();
        List<?> potionMixes = List.of();
        try {
            customRecipes = brewing.getRecipes();
        } catch (RuntimeException unreadableRecipes) {
            partial = true;
        }
        try {
            containers = exactList(brewing, "containers");
        } catch (ReflectiveOperationException unreadableContainers) {
            partial = true;
        }
        try {
            containerMixes = exactList(brewing, "containerMixes");
        } catch (ReflectiveOperationException unreadableContainerMixes) {
            partial = true;
        }
        try {
            potionMixes = exactList(brewing, "potionMixes");
        } catch (ReflectiveOperationException unreadablePotionMixes) {
            partial = true;
        }

        int customCount = Math.min(customRecipes.size(), MAX_RULES);
        int containerCount = Math.min(containers.size(), MAX_CONTAINERS);
        int containerMixCount = Math.min(containerMixes.size(), MAX_RULES);
        int potionMixCount = Math.min(potionMixes.size(), MAX_RULES);
        if (customRecipes.size() > MAX_RULES || containers.size() > MAX_CONTAINERS
                || containerMixes.size() > MAX_RULES || potionMixes.size() > MAX_RULES) partial = true;

        var bottles = new ArrayList<Item>();
        for (int index = 0; index < containerCount; index++) {
            Object raw = containers.get(index);
            if (!(raw instanceof Ingredient ingredient) || ingredient.getClass() != Ingredient.class
                    || ingredient.isCustom()) {
                partial = true;
                continue;
            }
            Ingredient.Value[] values;
            try {
                values = ingredient.getValues();
            } catch (RuntimeException unreadableValues) {
                partial = true;
                continue;
            }
            if (values.length > MAX_ALTERNATIVES) {
                partial = true;
                continue;
            }
            for (Ingredient.Value value : values) {
                if (value instanceof Ingredient.ItemValue itemValue) {
                    Item item = itemValue.item().getItem();
                    if (item instanceof PotionItem && !bottles.contains(item)) bottles.add(item);
                } else {
                    partial = true;
                }
            }
        }
        if (registries == null) partial = true;
        var potions = new ArrayList<PotionEntry>();
        if (registries != null) {
            try {
                Registry<Potion> potionRegistry = registries.registryOrThrow(Registries.POTION);
                var entries = potionRegistry.entrySet().iterator();
                for (int visited = 0; visited < MAX_POTIONS && entries.hasNext(); visited++) {
                    var entry = entries.next();
                    var key = potionRegistry.getKey(entry.getValue());
                    if (key == null || key.getNamespace().length() + key.getPath().length() + 1
                            > MAX_FACT_ID_LENGTH) {
                        partial = true;
                        continue;
                    }
                    String identity = key.toString();
                    if ("minecraft:empty".equals(identity)) continue;
                    var holder = potionRegistry.getHolder(entry.getKey()).orElse(null);
                    if (holder == null) partial = true;
                    else potions.add(new PotionEntry(holder, identity));
                }
                if (entries.hasNext()) partial = true;
            } catch (RuntimeException unreadablePotions) {
                partial = true;
            }
        }

        long candidateCount = (long) customCount
                + (long) containerMixCount * Math.max(1, potions.size())
                + (long) potionMixCount * bottles.size();
        return new Source(customRecipes, customCount, containers, containerCount, containerMixes, containerMixCount,
                potionMixes, potionMixCount, List.copyOf(bottles), List.copyOf(potions), partial,
                (int) Math.min(Integer.MAX_VALUE, candidateCount));
    }

    private static Projection projectCustom(Object recipe, int ruleIndex, RegistryAccess registries,
                                             Registry<Item> itemRegistry, Evidence.Scope scope) {
        if (recipe == null || recipe.getClass() != BrewingRecipe.class) {
            return opaqueProjection("custom", ruleIndex, 0, recipe, scope);
        }
        try {
            var declarative = (BrewingRecipe) recipe;
            Ingredient input = declarative.getInput();
            Ingredient ingredient = declarative.getIngredient();
            var inputs = facts(input, itemRegistry);
            var reagents = facts(ingredient, itemRegistry);
            ItemStack output = declarative.getOutput();
            var outputComponents = output == null || output.isEmpty()
                    ? new ComponentCopy(new Evidence.JsonObject(Map.of()), false, "empty")
                    : boundedComponents(output, registries);
            String outputItem = output == null || output.isEmpty() ? null : NativeSnapshot.itemIdentity(output, itemRegistry);
            String fingerprint = "custom|" + factKey(inputs.values()) + "|" + factKey(reagents.values())
                    + "|" + outputItem + "|" + (output == null ? 0 : output.getCount()) + "|" + outputComponents.fingerprint()
                    + (inputs.complete() ? "" : "|opaque-input:" + System.identityHashCode(input))
                    + (reagents.complete() ? "" : "|opaque-reagent:" + System.identityHashCode(ingredient));
            var identity = "brewing:custom:" + ruleIndex + ":0:" + digest(fingerprint);
            return record("custom", identity, inputs.values(), reagents.values(), output, outputComponents,
                    itemRegistry, scope);
        } catch (RuntimeException unreadableRecipe) {
            return opaqueProjection("custom", ruleIndex, 0, recipe, scope);
        }
    }

    private static Projection projectContainer(Object mix, int ruleIndex, int variantIndex, Source source,
                                               RegistryAccess registries, Registry<Item> itemRegistry,
                                               Evidence.Scope scope) {
        if (source.potions().isEmpty()) return opaqueProjection("container", ruleIndex, variantIndex, mix, scope);
        try {
            Object from = mixValue(mix, "from");
            Object ingredient = mixValue(mix, "ingredient");
            Object to = mixValue(mix, "to");
            if (!(from instanceof Holder<?> fromHolder) || !(fromHolder.value() instanceof Item fromItem)
                    || !(ingredient instanceof Ingredient reagent) || !(to instanceof Holder<?> toHolder)
                    || !(toHolder.value() instanceof Item toItem) || !(fromItem instanceof PotionItem)
                    || !source.bottles().contains(fromItem)) {
                return opaqueProjection("container", ruleIndex, variantIndex, mix, scope);
            }
            var reagents = facts(reagent, itemRegistry);
            var potion = source.potions().get(variantIndex);
            ItemStack input = PotionContents.createItemStack(fromItem, potion.holder());
            ItemStack output = PotionContents.createItemStack(toItem, potion.holder());
            var inputComponents = boundedComponents(input, registries);
            var outputComponents = boundedComponents(output, registries);
            String fingerprint = "container|" + NativeSnapshot.itemIdentity(fromItem, itemRegistry) + "|"
                    + NativeSnapshot.itemIdentity(toItem, itemRegistry) + "|" + potion.identity() + "|"
                    + factKey(reagents.values()) + "|" + inputComponents.fingerprint() + "|" + outputComponents.fingerprint()
                    + (reagents.complete() ? "" : "|opaque-reagent:" + System.identityHashCode(reagent));
            var identity = "brewing:container:" + ruleIndex + ":" + variantIndex + ":" + digest(fingerprint);
            var bottles = List.of(new Fact("item", NativeSnapshot.itemIdentity(input, itemRegistry), inputComponents.value()));
            return record("container", identity, bottles, reagents.values(), output, outputComponents,
                    itemRegistry, scope);
        } catch (ReflectiveOperationException | RuntimeException unreadableMix) {
            return opaqueProjection("container", ruleIndex, variantIndex, mix, scope);
        }
    }

    private static Projection projectPotion(Object mix, int ruleIndex, int variantIndex, Source source,
                                            RegistryAccess registries, Registry<Item> itemRegistry,
                                            Evidence.Scope scope) {
        try {
            Object from = mixValue(mix, "from");
            Object ingredient = mixValue(mix, "ingredient");
            Object to = mixValue(mix, "to");
            if (!(from instanceof Holder<?> fromHolder) || !(fromHolder.value() instanceof Potion fromPotion)
                    || !(ingredient instanceof Ingredient reagent) || !(to instanceof Holder<?> toHolder)
                    || !(toHolder.value() instanceof Potion toPotion)) {
                return opaqueProjection("potion", ruleIndex, variantIndex, mix, scope);
            }
            var potionRegistry = registries.registryOrThrow(Registries.POTION);
            var fromKey = potionRegistry.getKey(fromPotion);
            var toKey = potionRegistry.getKey(toPotion);
            if (fromKey == null || toKey == null
                    || fromKey.getNamespace().length() + fromKey.getPath().length() + 1 > MAX_FACT_ID_LENGTH
                    || toKey.getNamespace().length() + toKey.getPath().length() + 1 > MAX_FACT_ID_LENGTH) {
                return opaqueProjection("potion", ruleIndex, variantIndex, mix, scope);
            }
            var bottle = source.bottles().get(variantIndex);
            var reagents = facts(reagent, itemRegistry);
            ItemStack input = PotionContents.createItemStack(bottle, potionRegistry.wrapAsHolder(fromPotion));
            ItemStack output = PotionContents.createItemStack(bottle, potionRegistry.wrapAsHolder(toPotion));
            var inputComponents = boundedComponents(input, registries);
            var outputComponents = boundedComponents(output, registries);
            String bottleId = NativeSnapshot.itemIdentity(bottle, itemRegistry);
            String fingerprint = "potion|" + fromKey + "|" + toKey + "|" + bottleId + "|"
                    + factKey(reagents.values()) + "|" + inputComponents.fingerprint() + "|" + outputComponents.fingerprint()
                    + (reagents.complete() ? "" : "|opaque-reagent:" + System.identityHashCode(reagent));
            var identity = "brewing:potion:" + ruleIndex + ":" + variantIndex + ":" + digest(fingerprint);
            var bottles = List.of(new Fact("item", bottleId, inputComponents.value()));
            return record("potion", identity, bottles, reagents.values(), output, outputComponents,
                    itemRegistry, scope);
        } catch (ReflectiveOperationException | RuntimeException unreadableMix) {
            return opaqueProjection("potion", ruleIndex, variantIndex, mix, scope);
        }
    }

    private static Projection record(String kind, String identity, List<Fact> bottles, List<Fact> reagents,
                                     ItemStack output, ComponentCopy outputComponents,
                                     Registry<Item> itemRegistry, Evidence.Scope scope) {
        var slots = new ArrayList<Evidence.RecipeSlot>();
        if (!bottles.isEmpty()) slots.add(new Evidence.RecipeSlot("bottle", bottles.stream().map(fact ->
                new Evidence.RecipeAlternative(fact.type(), fact.identity(), fact.components(), null,
                        Evidence.Consumption.UNKNOWN)).toList()));
        if (!reagents.isEmpty()) slots.add(new Evidence.RecipeSlot("reagent", reagents.stream().map(fact ->
                new Evidence.RecipeAlternative(fact.type(), fact.identity(), fact.components(), null,
                        Evidence.Consumption.UNKNOWN)).toList()));
        boolean opaque = bottles.stream().anyMatch(fact -> fact.type().equals("unknown"))
                || reagents.stream().anyMatch(fact -> fact.type().equals("unknown"));
        var decoding = opaque ? Evidence.DecodingStatus.OPAQUE : Evidence.DecodingStatus.PARTIAL;
        Evidence.RecipeOutput recipeOutput;
        String outputItem;
        boolean outputKnown;
        if (output == null || output.isEmpty()) {
            recipeOutput = new Evidence.RecipeOutput("unknown", "unknown:brewing-output",
                    new Evidence.JsonObject(Map.of()), null);
            outputItem = null;
            outputKnown = false;
        } else {
            outputItem = NativeSnapshot.itemIdentity(output, itemRegistry);
            recipeOutput = new Evidence.RecipeOutput("item", outputItem, outputComponents.value(), output.getCount());
            outputKnown = true;
        }
        var constraints = new ArrayList<String>();
        constraints.add("brewing-batch=up-to-3-bottles; reagent=one-per-operation; fuel=unknown-per-operation; remainders=unknown");
        constraints.add("effective-server-rules=unknown");
        constraints.add("overlap-precedence=not-evaluated");
        if (outputComponents.bounded()) constraints.add("components=bounded");
        var outputs = List.of(recipeOutput);
        var payload = NativeSnapshot.recipePayloadJson(null, slots, outputs, List.of(), List.of(), constraints,
                decoding, new LinkedHashSet<>());
        var typed = new Evidence.RecipePayload(null, slots, outputs, List.of(), List.of(), constraints, decoding);
        var source = new Evidence.Source(scope, "native", "1.21.1", identity, scope.requestGeneration(), Instant.now(),
                Evidence.Activity.ACTIVE, opaque ? Evidence.Coverage.OPAQUE : Evidence.Coverage.PARTIAL,
                Evidence.Authority.LOADED, List.of("client-potion-brewing", kind + "-brewing-rule"));
        String title = outputKnown ? outputItem + " brewing transformation" : "Opaque brewing transformation";
        var record = new Evidence.Record(source, Evidence.RecordKind.RECIPE, title, payload, List.of(), typed);
        return new Projection(identity, record, outputKnown, outputItem, outputComponents.bounded() || opaque);
    }

    private static Projection opaqueProjection(String kind, int ruleIndex, int variantIndex, Object rule,
                                               Evidence.Scope scope) {
        String type = rule == null ? "null" : rule.getClass().getName();
        var identity = "brewing:" + kind + ":" + ruleIndex + ":" + variantIndex + ":"
                + digest("opaque|" + type + "|" + System.identityHashCode(rule));
        var output = new Evidence.RecipeOutput("unknown", "unknown:brewing-output",
                new Evidence.JsonObject(Map.of()), null);
        var constraints = List.of("brewing-batch=up-to-3-bottles; reagent=one-per-operation; fuel=unknown-per-operation; remainders=unknown",
                "effective-server-rules=unknown", "overlap-precedence=not-evaluated", "unsupported-brewing-rule=opaque");
        var payload = NativeSnapshot.recipePayloadJson(null, List.of(), List.of(output), List.of(), List.of(), constraints,
                Evidence.DecodingStatus.OPAQUE, new LinkedHashSet<>());
        var typed = new Evidence.RecipePayload(null, List.of(), List.of(output), List.of(), List.of(), constraints,
                Evidence.DecodingStatus.OPAQUE);
        var source = new Evidence.Source(scope, "native", "1.21.1", identity, scope.requestGeneration(), Instant.now(),
                Evidence.Activity.ACTIVE, Evidence.Coverage.OPAQUE, Evidence.Authority.LOADED,
                List.of("client-potion-brewing", "unsupported-custom-brewing-rule"));
        return new Projection(identity, new Evidence.Record(source, Evidence.RecordKind.RECIPE,
                "Opaque brewing transformation", payload, List.of(), typed), false, null, true);
    }

    private static ComponentCopy boundedComponents(ItemStack stack, RegistryAccess registries) {
        var components = stack.getComponentsPatch();
        if (components.size() > 16) {
            var fingerprint = new StringBuilder("component-count:").append(components.size())
                    .append(":stack:").append(System.identityHashCode(stack));
            int visited = 0;
            for (var entry : components.entrySet()) {
                if (visited++ == 16) break;
                fingerprint.append('|').append(System.identityHashCode(entry.getKey())).append('=')
                        .append(System.identityHashCode(entry.getValue()));
            }
            return new ComponentCopy(new Evidence.JsonObject(Map.of("encoding", new Evidence.JsonString("bounded"))),
                    true, fingerprint.toString());
        }
        var fingerprint = new StringBuilder();
        long estimatedBytes = 64;
        boolean bounded = false;
        for (var entry : components.entrySet()) {
            var type = entry.getKey();
            Object value = entry.getValue().orElse(null);
            fingerprint.append('|').append(System.identityHashCode(type)).append('=');
            if (value == null) {
                estimatedBytes += 8;
                fingerprint.append("removed");
            } else if (type == DataComponents.CUSTOM_NAME && value instanceof Component component
                    && component.getContents() instanceof PlainTextContents plainText
                    && component.getSiblings().isEmpty() && component.getStyle().isEmpty()) {
                String text = plainText.text();
                estimatedBytes += text.length() * 6L + 48;
                if (text.length() <= MAX_COMPONENT_BYTES / 6) fingerprint.append("name:").append(text);
                else {
                    bounded = true;
                    fingerprint.append("large-name:").append(text.length()).append(':').append(System.identityHashCode(value));
                }
            } else if (type == DataComponents.POTION_CONTENTS && value instanceof PotionContents potionContents
                    && potionContents.potion().isPresent() && potionContents.customColor().isEmpty()
                    && potionContents.customEffects().isEmpty()) {
                var potionKey = potionContents.potion().get().unwrapKey().orElse(null);
                int potionLength = potionKey == null ? 0 : potionKey.location().getNamespace().length()
                        + potionKey.location().getPath().length() + 1;
                estimatedBytes += potionLength * 6L + 48;
                if (potionKey != null && potionLength <= MAX_FACT_ID_LENGTH) {
                    fingerprint.append("potion:").append(potionKey.location());
                } else {
                    bounded = true;
                    fingerprint.append("large-potion:").append(potionLength).append(':').append(System.identityHashCode(value));
                }
            } else if (type == DataComponents.MAX_STACK_SIZE && value instanceof Integer number) {
                estimatedBytes += 16;
                fingerprint.append("max-stack:").append(number);
            } else if ((type == DataComponents.MAX_DAMAGE || type == DataComponents.DAMAGE) && value instanceof Integer number) {
                estimatedBytes += 16;
                fingerprint.append(type == DataComponents.MAX_DAMAGE ? "max-damage:" : "damage:").append(number);
            } else {
                bounded = true;
                fingerprint.append("unsupported:").append(value == null ? "null"
                        : value.getClass().getName() + ":" + System.identityHashCode(value));
                estimatedBytes += 64;
            }
            if (estimatedBytes > MAX_COMPONENT_BYTES) bounded = true;
        }
        if (bounded) return new ComponentCopy(new Evidence.JsonObject(Map.of("encoding", new Evidence.JsonString("bounded"))),
                true, fingerprint.toString());
        return new ComponentCopy(NativeSnapshot.componentPayload(stack, registries), false, fingerprint.toString());
    }

    private static Facts facts(Ingredient ingredient, Registry<Item> itemRegistry) {
        if (ingredient == null || ingredient.getClass() != Ingredient.class || ingredient.isCustom() || ingredient.isEmpty()) {
            return new Facts(List.of(new Fact("unknown", "unknown:brewing-ingredient", new Evidence.JsonObject(Map.of()))), false);
        }
        Ingredient.Value[] values;
        try {
            values = ingredient.getValues();
        } catch (RuntimeException unreadableValues) {
            return new Facts(List.of(new Fact("unknown", "unknown:brewing-ingredient", new Evidence.JsonObject(Map.of()))), false);
        }
        if (values.length > MAX_ALTERNATIVES) return new Facts(
                List.of(new Fact("unknown", "unknown:brewing-ingredient", new Evidence.JsonObject(Map.of()))), false);
        var facts = new ArrayList<Fact>();
        for (Ingredient.Value value : values) {
            if (value instanceof Ingredient.ItemValue itemValue) {
                String identity = NativeSnapshot.itemIdentity(itemValue.item(), itemRegistry);
                if (identity.length() > MAX_FACT_ID_LENGTH) return new Facts(
                        List.of(new Fact("unknown", "unknown:brewing-ingredient", new Evidence.JsonObject(Map.of()))), false);
                facts.add(new Fact("item", identity, new Evidence.JsonObject(Map.of())));
            } else if (value instanceof Ingredient.TagValue tagValue) {
                String identity = "#" + tagValue.tag().location();
                if (identity.length() > MAX_FACT_ID_LENGTH) return new Facts(
                        List.of(new Fact("unknown", "unknown:brewing-ingredient", new Evidence.JsonObject(Map.of()))), false);
                facts.add(new Fact("tag", identity, new Evidence.JsonObject(Map.of())));
            } else {
                return new Facts(List.of(new Fact("unknown", "unknown:brewing-ingredient", new Evidence.JsonObject(Map.of()))), false);
            }
        }
        return facts.isEmpty() ? new Facts(List.of(new Fact("unknown", "unknown:brewing-ingredient",
                new Evidence.JsonObject(Map.of()))), false) : new Facts(List.copyOf(facts), true);
    }

    private static String factKey(List<Fact> facts) {
        var values = new ArrayList<String>(facts.size());
        for (Fact fact : facts) values.add(fact.type() + ":" + fact.identity());
        Collections.sort(values);
        return String.join("|", values);
    }

    private static List<?> exactList(PotionBrewing brewing, String name) throws ReflectiveOperationException {
        Field field = PotionBrewing.class.getDeclaredField(name);
        if (!List.class.isAssignableFrom(field.getType()) || !field.trySetAccessible()) throw new ReflectiveOperationException(name);
        Object value = field.get(brewing);
        if (!(value instanceof List<?> list)) throw new ReflectiveOperationException(name);
        return list;
    }

    private static Object mixValue(Object mix, String name) throws ReflectiveOperationException {
        if (mix == null || !"net.minecraft.world.item.alchemy.PotionBrewing$Mix".equals(mix.getClass().getName())) {
            throw new ReflectiveOperationException("unexpected brewing mix");
        }
        Field field = mix.getClass().getDeclaredField(name);
        Class<?> expected = name.equals("ingredient") ? Ingredient.class : Holder.class;
        if (field.getType() != expected || !field.trySetAccessible()) throw new ReflectiveOperationException(name);
        return field.get(mix);
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private record Fact(String type, String identity, Evidence.JsonObject components) { }
    private record ComponentCopy(Evidence.JsonObject value, boolean bounded, String fingerprint) { }
    private record Facts(List<Fact> values, boolean complete) { }
    private record PotionEntry(Holder<Potion> holder, String identity) { }
    private record Source(List<?> customRecipes, int customCount, List<?> containers, int containerCount,
                         List<?> containerMixes, int containerMixCount, List<?> potionMixes, int potionMixCount,
                         List<Item> bottles, List<PotionEntry> potions, boolean partial, int count) { }
    private record Projection(String identity, Evidence.Record record, boolean outputKnown, String outputItem, boolean partial) { }

    record Page(List<Evidence.Record> records, int nextOffset, boolean exhausted, int scanned, boolean partial) {
        Page {
            records = List.copyOf(records);
        }
    }
}
