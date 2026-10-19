package dev.packsage.knowledge.nativegame;

import dev.packsage.knowledge.Evidence;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.PotionItem;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionBrewing;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.crafting.Ingredient;
import net.neoforged.neoforge.common.brewing.BrewingRecipe;
import net.neoforged.neoforge.common.brewing.IBrewingRecipe;

import java.lang.reflect.Field;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
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
    private static final int MAX_POTIONS = 256;
    private static final int MAX_READ_CANDIDATES = 4096;
    private static final int MAX_COMPONENT_BYTES = 16384;

    private NativeBrewingReader() { }

    static Page searchCandidates(PotionBrewing brewing, RegistryAccess registries,
                                 Registry<Item> itemRegistry, ResourceManager resourceManager,
                                 String outputItem, String text, int offset, int limit, Evidence.Scope scope) {
        var source = rules(brewing, registries, itemRegistry);
        var records = new ArrayList<Evidence.Record>();
        var normalizedText = text == null ? null : text.toLowerCase(Locale.ROOT);
        int startOffset = Math.max(offset, 0);
        int scanned = 0;
        boolean partial = source.partial();
        int candidateLimit = outputItem == null ? MAX_CANDIDATES : MAX_OUTPUT_CANDIDATES;
        if (limit < 1) return new Page(records, startOffset, startOffset >= source.count(), 0, partial);
        int index = startOffset;
        while (index < source.count() && scanned < candidateLimit) {
            var projected = candidateAt(source, index++, registries, itemRegistry, scope);
            scanned++;
            if (projected == null) continue;
            partial |= projected.partial();
            var record = projected.record();
            if (record == null) continue;
            if (outputItem != null && (!projected.outputKnown() || !outputItem.equals(projected.outputItem()))) continue;
            if (normalizedText != null && (projected.record() == null || !(record.title() + " " + record.source().sourceHandle() + " "
                    + Evidence.serialize(record.payload())).toLowerCase(Locale.ROOT).contains(normalizedText))) continue;
            records.add(record);
            if (records.size() == limit) {
                return new Page(records, index, index >= source.count(), scanned, partial);
            }
        }
        return new Page(records, index, index >= source.count(), scanned, partial);
    }

    static Evidence.Record readCandidate(PotionBrewing brewing, String identity, RegistryAccess registries,
                                         Registry<Item> itemRegistry, Evidence.Scope scope) {
        if (identity == null) return null;
        var source = rules(brewing, registries, itemRegistry);
        for (int index = 0; index < Math.min(source.count(), MAX_READ_CANDIDATES); index++) {
            var projected = candidateAt(source, index, registries, itemRegistry, scope);
            if (projected != null && identity.equals(projected.identity())) return projected.record();
        }
        return null;
    }

    private static Projection candidateAt(Source source, int index, RegistryAccess registries,
                                          Registry<Item> itemRegistry, Evidence.Scope scope) {
        if (index < source.customCount()) return projectCustom(source.customRecipes().get(index), index,
                registries, itemRegistry, scope);
        index -= source.customCount();
        int containerVariants = source.potions().isEmpty() ? 1 : source.potions().size();
        int containerCandidates = source.containerMixCount() * containerVariants;
        if (index < containerCandidates) {
            int rule = index / containerVariants;
            int variant = index % containerVariants;
            return projectContainer(source.containerMixes().get(rule), variant, source.potions(), source.containers(),
                    rule, registries, itemRegistry, scope);
        }
        index -= containerCandidates;
        int bottleVariants = source.potionBottles().size();
        if (bottleVariants == 0 || index >= source.potionMixCount() * bottleVariants) return null;
        int rule = index / bottleVariants;
        int variant = index % bottleVariants;
        return projectPotion(source.potionMixes().get(rule), source.potionBottles().get(variant), rule,
                registries, itemRegistry, scope);
    }

    static Page planCandidates(PotionBrewing brewing, RegistryAccess registries, Registry<Item> itemRegistry,
                               Evidence.Scope scope) {
        return searchCandidates(brewing, registries, itemRegistry, null, null, null, 0, MAX_CANDIDATES, scope);
    }

    private static Source rules(PotionBrewing brewing, RegistryAccess registries, Registry<Item> itemRegistry) {
        var result = new ArrayList<Rule>();
        boolean partial = true;
        if (brewing == null || brewing.getClass() != PotionBrewing.class) return new Source(result, true, 0);
        try {
            List<?> recipes = brewing.getRecipes();
            if (recipes.size() > MAX_RULES) partial = true;
            for (int index = 0; index < Math.min(recipes.size(), MAX_RULES); index++) {
                Object recipe = recipes.get(index);
                if (recipe != null && recipe.getClass() == BrewingRecipe.class) {
                    var declarative = (BrewingRecipe) recipe;
                    var inputs = facts(declarative.getInput(), itemRegistry);
                    var reagents = facts(declarative.getIngredient(), itemRegistry);
                    if (inputs.complete() && reagents.complete()) {
                        ItemStack output = declarative.getOutput().copy();
                        var components = boundedComponents(output, registries);
                        if (components.bounded()) {
                            result.add(opaque("custom", index, recipe.getClass().getName()));
                            partial = true;
                        } else {
                            result.add(new Rule("custom", digest("custom|" + factKey(inputs.values()) + "|"
                                    + factKey(reagents.values()) + "|" + NativeSnapshot.itemIdentity(output, itemRegistry) + "|"
                                    + Evidence.serialize(components.value()) + "|" + output.getCount()), inputs.values(),
                                    reagents.values(), null, null, null, null, null, output, false));
                        }
                    } else {
                        result.add(opaque("custom", index, recipe.getClass().getName()));
                        partial = true;
                    }
                } else {
                    result.add(opaque("custom", index, recipe == null ? "null" : recipe.getClass().getName()));
                    partial = true;
                }
            }

            var containerFacts = new ArrayList<Fact>();
            List<?> containers = exactList(brewing, "containers");
            for (int index = 0; index < Math.min(containers.size(), MAX_RULES); index++) {
                if (!(containers.get(index) instanceof Ingredient ingredient)) {
                    partial = true;
                    continue;
                }
                var decoded = facts(ingredient, itemRegistry);
                containerFacts.addAll(decoded.values());
                partial |= !decoded.complete();
                if (containerFacts.size() > MAX_ALTERNATIVES) {
                    containerFacts.subList(MAX_ALTERNATIVES, containerFacts.size()).clear();
                    partial = true;
                    break;
                }
            }
            if (containers.size() > MAX_RULES) partial = true;

            Registry<Potion> potionRegistry = registries.registryOrThrow(Registries.POTION);
            var potions = new ArrayList<Holder<Potion>>();
            for (var entry : potionRegistry.entrySet()) {
                if (potions.size() == MAX_POTIONS) {
                    partial = true;
                    break;
                }
                var potionKey = potionRegistry.getKey(entry.getValue());
                if (potionKey != null && !"minecraft:empty".equals(potionKey.toString())) {
                    potions.add(potionRegistry.getHolder(entry.getKey()).orElse(null));
                }
            }
            potions.removeIf(java.util.Objects::isNull);

            List<?> containerMixes = exactList(brewing, "containerMixes");
            int containerCount = Math.min(containerMixes.size(), MAX_RULES - result.size());
            for (int index = 0; index < containerCount; index++) {
                Object mix = containerMixes.get(index);
                var from = mixValue(mix, "from");
                var ingredient = mixValue(mix, "ingredient");
                var to = mixValue(mix, "to");
                if (!(from instanceof Holder<?> fromHolder) || !(fromHolder.value() instanceof Item fromItem)
                        || !(ingredient instanceof Ingredient reagent) || !(to instanceof Holder<?> toHolder)
                        || !(toHolder.value() instanceof Item toItem) || !(fromItem instanceof PotionItem)
                        || containerFacts.stream().noneMatch(fact -> fact.stack() != null && fact.stack().is(fromItem))) {
                    result.add(opaque("container", index, "invalid-vanilla-mix"));
                    partial = true;
                    continue;
                }
                var reagents = facts(reagent, itemRegistry);
                if (!reagents.complete() || potions.isEmpty()) {
                    result.add(opaque("container", index, "unsupported-ingredient-or-potion-table"));
                    partial = true;
                    continue;
                }
                var base = digest("container|" + NativeSnapshot.itemIdentity(fromItem, itemRegistry) + "|"
                        + NativeSnapshot.itemIdentity(toItem, itemRegistry) + "|" + factKey(reagents.values()));
                result.add(new Rule("container", base, List.of(), reagents.values(), fromItem, toItem,
                        null, null, potions, null, false));
            }
            if (containerMixes.size() > containerCount) partial = true;

            List<?> potionMixes = exactList(brewing, "potionMixes");
            int potionMixCount = Math.min(potionMixes.size(), MAX_RULES - result.size());
            for (int index = 0; index < potionMixCount; index++) {
                Object mix = potionMixes.get(index);
                var from = mixValue(mix, "from");
                var ingredient = mixValue(mix, "ingredient");
                var to = mixValue(mix, "to");
                if (!(from instanceof Holder<?> fromHolder) || !(fromHolder.value() instanceof Potion fromPotion)
                        || !(ingredient instanceof Ingredient reagent) || !(to instanceof Holder<?> toHolder)
                        || !(toHolder.value() instanceof Potion toPotion)) {
                    result.add(opaque("potion", index, "invalid-vanilla-mix"));
                    partial = true;
                    continue;
                }
                var reagents = facts(reagent, itemRegistry);
                var bottles = containerFacts.stream().filter(fact -> fact.stack() != null
                        && fact.stack().getItem() instanceof PotionItem).toList();
                if (!reagents.complete() || bottles.isEmpty()) {
                    result.add(opaque("potion", index, "unsupported-ingredient-or-container-table"));
                    partial = true;
                    continue;
                }
                var fromId = potionRegistry.getKey(fromPotion).toString();
                var toId = potionRegistry.getKey(toPotion).toString();
                var base = digest("potion|" + fromId + "|" + toId + "|" + factKey(reagents.values()));
                for (Fact bottle : bottles) {
                    if (result.size() == MAX_RULES) {
                        partial = true;
                        break;
                    }
                    result.add(new Rule("potion", base, List.of(bottle), reagents.values(),
                            null, null, fromHolder, toHolder, null, null, false));
                }
            }
            if (potionMixes.size() > potionMixCount) partial = true;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            partial = true;
        }
        long total = 0;
        for (Rule rule : result) total = Math.min(Integer.MAX_VALUE, total + rule.count());
        return new Source(List.copyOf(result), partial, (int) total);
    }

    private static Projection project(Rule rule, int variant, RegistryAccess registries,
                                      Registry<Item> itemRegistry, Evidence.Scope scope) {
        if (rule.opaque()) return opaqueProjection(rule, scope);
        ItemStack input;
        ItemStack output;
        String identity;
        List<Fact> bottleFacts = rule.bottles();
        if (rule.kind().equals("custom")) {
            input = null;
            output = rule.output().copy();
            identity = "brewing:" + rule.kind() + ":" + rule.identity();
        } else if (rule.kind().equals("container")) {
            Holder<Potion> potion = rule.potions().get(variant);
            input = PotionContents.createItemStack(rule.fromItem(), potion);
            output = PotionContents.createItemStack(rule.toItem(), potion);
            var potionId = registries.registryOrThrow(Registries.POTION).getKey(potion.value());
            identity = "brewing|container|" + rule.identity() + ":" + potionId;
            bottleFacts = List.of(new Fact("item", NativeSnapshot.itemIdentity(input, itemRegistry), new Evidence.JsonObject(Map.of()), input));
        } else {
            Fact bottle = rule.bottles().get(variant);
            if (bottle.stack() == null || !(bottle.stack().getItem() instanceof PotionItem)) return opaqueProjection(rule, scope);
            input = PotionContents.createItemStack(bottle.stack().getItem(), rule.fromPotion());
            output = PotionContents.createItemStack(bottle.stack().getItem(), rule.toPotion());
            var potionRegistry = registries.registryOrThrow(Registries.POTION);
            identity = "brewing|potion|" + rule.identity() + ":" + bottle.identity() + ":"
                    + potionRegistry.getKey(rule.fromPotion().value()) + ":" + potionRegistry.getKey(rule.toPotion().value());
            bottleFacts = List.of(new Fact("item", NativeSnapshot.itemIdentity(input, itemRegistry),
                    new Evidence.JsonObject(Map.of()), input));
        }
        var inputComponents = input == null ? new ComponentCopy(new Evidence.JsonObject(Map.of()), false) : boundedComponents(input, registries);
        var outputComponents = output == null ? new ComponentCopy(new Evidence.JsonObject(Map.of()), false) : boundedComponents(output, registries);
        boolean bounded = inputComponents.bounded() || outputComponents.bounded();
        if (input != null) bottleFacts = List.of(new Fact("item", NativeSnapshot.itemIdentity(input, itemRegistry),
                inputComponents.value(), input));
        return record(rule, identity, bottleFacts, rule.reagents(), output, outputComponents.value(), bounded,
                itemRegistry, registries, scope);
    }

    private static Projection record(Rule rule, String identity, List<Fact> bottles, List<Fact> reagents,
                                     ItemStack output, Evidence.JsonObject outputComponents, boolean bounded, Registry<Item> itemRegistry,
                                     RegistryAccess registries, Evidence.Scope scope) {
        var slots = new ArrayList<Evidence.RecipeSlot>();
        if (!bottles.isEmpty()) slots.add(new Evidence.RecipeSlot("bottle", bottles.stream().map(NativeBrewingReader::alternative).toList()));
        if (!reagents.isEmpty()) slots.add(new Evidence.RecipeSlot("reagent", reagents.stream().map(NativeBrewingReader::alternative).toList()));
        boolean opaque = bottles.stream().anyMatch(fact -> fact.type().equals("unknown"))
                || reagents.stream().anyMatch(fact -> fact.type().equals("unknown"));
        var decoding = opaque ? Evidence.DecodingStatus.OPAQUE : Evidence.DecodingStatus.PARTIAL;
        Evidence.RecipeOutput recipeOutput;
        String outputItem;
        boolean outputKnown;
        if (output == null || output.isEmpty()) {
            recipeOutput = new Evidence.RecipeOutput("unknown", "unknown:brewing-output", new Evidence.JsonObject(Map.of()), null);
            outputItem = null;
            outputKnown = false;
        } else {
            outputItem = NativeSnapshot.itemIdentity(output, itemRegistry);
            recipeOutput = new Evidence.RecipeOutput("item", outputItem, outputComponents, output.getCount());
            outputKnown = true;
        }
        var constraints = new ArrayList<String>();
        constraints.add("brewing-batch=up-to-3-bottles; reagent=one-per-operation; fuel=unknown-per-operation; remainders=unknown");
        constraints.add("effective-server-rules=unknown");
        if (bounded) constraints.add("components=bounded");
        var outputs = List.of(recipeOutput);
        var payload = NativeSnapshot.recipePayloadJson(null, slots, outputs, List.of(), List.of(), constraints,
                decoding, new LinkedHashSet<>());
        var typed = new Evidence.RecipePayload(null, slots, outputs, List.of(), List.of(), constraints, decoding);
        var source = new Evidence.Source(scope, "native", "1.21.1", identity, scope.requestGeneration(), Instant.now(),
                Evidence.Activity.ACTIVE, opaque ? Evidence.Coverage.OPAQUE : Evidence.Coverage.PARTIAL,
                Evidence.Authority.LOADED, List.of("client-potion-brewing", rule.kind() + "-brewing-rule"));
        String title = outputKnown ? outputItem + " brewing transformation" : "Opaque brewing transformation";
        var record = new Evidence.Record(source, Evidence.RecordKind.RECIPE, title, payload, List.of(), typed);
        return new Projection(identity, record, outputKnown, outputItem, bounded || opaque);
    }

    private static Projection opaqueProjection(Rule rule, Evidence.Scope scope) {
        String identity = "brewing|opaque|" + rule.kind() + ":" + rule.identity();
        var output = new Evidence.RecipeOutput("unknown", "unknown:brewing-output", new Evidence.JsonObject(Map.of()), null);
        var constraints = List.of("brewing-batch=up-to-3-bottles; reagent=one-per-operation; fuel=unknown-per-operation; remainders=unknown",
                "effective-server-rules=unknown", "unsupported-brewing-rule=opaque");
        var payload = NativeSnapshot.recipePayloadJson(null, List.of(), List.of(output), List.of(), List.of(), constraints,
                Evidence.DecodingStatus.OPAQUE, new LinkedHashSet<>());
        var typed = new Evidence.RecipePayload(null, List.of(), List.of(output), List.of(), List.of(), constraints,
                Evidence.DecodingStatus.OPAQUE);
        var source = new Evidence.Source(scope, "native", "1.21.1", identity, scope.requestGeneration(), Instant.now(),
                Evidence.Activity.ACTIVE, Evidence.Coverage.OPAQUE, Evidence.Authority.LOADED,
                List.of("client-potion-brewing", "unsupported-custom-brewing-rule"));
        return new Projection(identity, new Evidence.Record(source, Evidence.RecordKind.RECIPE, "Opaque brewing transformation",
                payload, List.of(), typed), false, null, true);
    }

    private static Evidence.RecipeAlternative alternative(Fact fact) {
        return new Evidence.RecipeAlternative(fact.type(), fact.identity(), fact.components(), null, Evidence.Consumption.UNKNOWN);
    }

    private static ComponentCopy boundedComponents(ItemStack stack, RegistryAccess registries) {
        var components = NativeSnapshot.componentPayload(stack, registries);
        if (Evidence.serialize(components).getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= MAX_COMPONENT_BYTES) {
            return new ComponentCopy(components, false);
        }
        return new ComponentCopy(new Evidence.JsonObject(Map.of("encoding", new Evidence.JsonString("bounded"))), true);
    }

    private static Facts facts(Ingredient ingredient, Registry<Item> itemRegistry) {
        if (ingredient.getClass() != Ingredient.class || ingredient.isCustom() || ingredient.isEmpty()) {
            return new Facts(List.of(new Fact("unknown", "unknown:brewing-ingredient", new Evidence.JsonObject(Map.of()), null)), false);
        }
        var values = ingredient.getValues();
        if (values.length > MAX_ALTERNATIVES) return new Facts(List.of(new Fact("unknown", "unknown:brewing-ingredient",
                new Evidence.JsonObject(Map.of()), null)), false);
        var facts = new ArrayList<Fact>();
        for (Ingredient.Value value : values) {
            if (value instanceof Ingredient.ItemValue itemValue) {
                ItemStack stack = itemValue.item();
                facts.add(new Fact("item", NativeSnapshot.itemIdentity(stack, itemRegistry), new Evidence.JsonObject(Map.of()), stack.copy()));
            } else if (value instanceof Ingredient.TagValue tagValue) {
                facts.add(new Fact("tag", "#" + tagValue.tag().location(), new Evidence.JsonObject(Map.of()), null));
            } else {
                return new Facts(List.of(new Fact("unknown", "unknown:brewing-ingredient", new Evidence.JsonObject(Map.of()), null)), false);
            }
        }
        return facts.isEmpty() ? new Facts(List.of(new Fact("unknown", "unknown:brewing-ingredient",
                new Evidence.JsonObject(Map.of()), null)), false) : new Facts(List.copyOf(facts), true);
    }

    private static String factKey(List<Fact> facts) {
        return facts.stream().map(fact -> fact.type() + ":" + fact.identity()).sorted().reduce((left, right) -> left + "|" + right).orElse("");
    }

    private static Rule opaque(String kind, int index, String type) {
        return new Rule(kind, "opaque:" + index + ":" + type, List.of(), List.of(), null, null,
                null, null, null, null, true);
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
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private record Fact(String type, String identity, Evidence.JsonObject components, ItemStack stack) { }
    private record ComponentCopy(Evidence.JsonObject value, boolean bounded) { }
    private record Facts(List<Fact> values, boolean complete) { }
    private record Rule(String kind, String identity, List<Fact> bottles, List<Fact> reagents, Item fromItem,
                        Item toItem, Holder<Potion> fromPotion, Holder<Potion> toPotion,
                        List<Holder<Potion>> potions, ItemStack output, boolean opaque) {
        int count() {
            if (opaque) return 1;
            if (kind.equals("container")) return potions.size();
            if (kind.equals("potion")) return bottles.size();
            return 1;
        }
    }
    private record Source(List<Rule> rules, boolean partial, int count) { }
    private record Projection(String identity, Evidence.Record record, boolean outputKnown, String outputItem, boolean partial) { }

    record Page(List<Evidence.Record> records, int nextOffset, boolean exhausted, int scanned, boolean partial) {
        Page {
            records = List.copyOf(records);
        }
    }
}
