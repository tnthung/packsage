package dev.packsage.knowledge.nativegame;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import dev.packsage.knowledge.Evidence;

import net.minecraft.client.Minecraft;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.block.Block;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/** An immutable copy of client-visible native evidence. */
public record NativeSnapshot(Evidence.Scope scope, List<Evidence.Record> records) {
    public NativeSnapshot {
        Objects.requireNonNull(scope);
        records = List.copyOf(records);
    }

    /** The caller supplies this copy from the Minecraft owning thread. */
    public static NativeSnapshot capture(Supplier<NativeSnapshot> owningThreadCopy) {
        return Objects.requireNonNull(owningThreadCopy.get());
    }

    /** Copies synchronized client evidence while executing on Minecraft's owning thread. */
    public static NativeSnapshot capture(Minecraft client, Evidence.Scope scope) {
        Objects.requireNonNull(client);
        Objects.requireNonNull(scope);
        if (!client.isSameThread()) throw new IllegalStateException("native snapshot must be captured on the client thread");
        if (client.level == null) return unavailable(scope, "client level unavailable");
        var level = client.level;
        var registries = level.registryAccess();
        Registry<Item> itemRegistry;
        Registry<Block> blockRegistry;
        try {
            itemRegistry = registries.registryOrThrow(Registries.ITEM);
            blockRegistry = registries.registryOrThrow(Registries.BLOCK);
        } catch (RuntimeException failure) {
            return unavailable(scope, "client registries unavailable: " + failure.getClass().getSimpleName());
        }
        var records = new ArrayList<Evidence.Record>();
        var tagMembers = new LinkedHashMap<String, LinkedHashSet<String>>();
        itemRegistry.getTags().forEach(pair -> {
            var tag = "#" + pair.getFirst().location();
            var members = new LinkedHashSet<String>();
            pair.getSecond().stream().forEach(holder -> members.add(itemIdentity(holder.value(), itemRegistry)));
            tagMembers.put(tag, members);
        });
        for (var entry : itemRegistry.entrySet()) {
            var item = entry.getValue();
            var identity = itemIdentity(item, itemRegistry);
            records.add(registryItem(item, identity, tagMembers, registries, scope));
        }
        for (var entry : blockRegistry.entrySet()) {
            var block = entry.getValue();
            var identity = String.valueOf(entry.getKey().location());
            records.add(registryBlock(block, identity, scope));
        }
        for (var entry : tagMembers.entrySet()) records.add(registryTag(entry.getKey(), entry.getValue(), scope));
        ResourceManager resourceManager = client.getResourceManager();
        for (RecipeHolder<?> holder : level.getRecipeManager().getRecipes()) {
            records.add(copyRecipe(holder, registries, itemRegistry, resourceManager, scope, null));
        }
        return records.isEmpty() ? unavailable(scope, "client native evidence empty") : new NativeSnapshot(scope, records);
    }

    static Evidence.Record copyRecipe(RecipeHolder<?> holder, net.minecraft.core.HolderLookup.Provider registries,
                                               Registry<Item> itemRegistry, ResourceManager resourceManager, Evidence.Scope scope,
                                               String exactOutputItem) {
        Recipe<?> recipe = holder.value();
        var decoding = Evidence.DecodingStatus.VERIFIED;
        var constraints = new ArrayList<String>();
        ItemStack output;
        try {
            output = recipe.getClass() == net.minecraft.world.item.crafting.SmithingTrimRecipe.class
                    ? ItemStack.EMPTY : recipe.getResultItem(registries);
        } catch (RuntimeException failure) {
            if (exactOutputItem != null) return null;
            output = ItemStack.EMPTY;
            decoding = Evidence.DecodingStatus.PARTIAL;
        }
        if (exactOutputItem != null && (output.isEmpty() || !exactOutputItem.equals(itemIdentity(output, itemRegistry)))) return null;
        var slots = new ArrayList<Evidence.RecipeSlot>();
        var tags = new LinkedHashSet<String>();
        boolean craftingRecipe = recipe.getClass() == net.minecraft.world.item.crafting.ShapedRecipe.class
                || recipe.getClass() == net.minecraft.world.item.crafting.ShapelessRecipe.class;
        boolean knownRecipe = craftingRecipe || recipe.getClass() == net.minecraft.world.item.crafting.SmeltingRecipe.class
                || recipe.getClass() == net.minecraft.world.item.crafting.BlastingRecipe.class
                || recipe.getClass() == net.minecraft.world.item.crafting.SmokingRecipe.class
                || recipe.getClass() == net.minecraft.world.item.crafting.CampfireCookingRecipe.class
                || recipe.getClass() == net.minecraft.world.item.crafting.StonecutterRecipe.class
                || recipe.getClass() == net.minecraft.world.item.crafting.SmithingTransformRecipe.class
                || recipe.getClass() == net.minecraft.world.item.crafting.SmithingTrimRecipe.class;
        if (!knownRecipe) decoding = Evidence.DecodingStatus.UNKNOWN;
        List<Ingredient> ingredients = recipe.getIngredients();
        if (recipe.getClass() == net.minecraft.world.item.crafting.SmithingTransformRecipe.class
                || recipe.getClass() == net.minecraft.world.item.crafting.SmithingTrimRecipe.class) {
            ingredients = new ArrayList<>();
            try {
                // NeoForge 21.1.202 exposes no accessors on these exact vanilla classes.
                // Their package-private fields are the smithing slots, each consumed once.
                for (String name : List.of("template", "base", "addition")) {
                    var field = recipe.getClass().getDeclaredField(name);
                    field.setAccessible(true);
                    ingredients.add((Ingredient) Objects.requireNonNull(field.get(recipe)));
                }
            } catch (ReflectiveOperationException | RuntimeException failure) {
                ingredients.clear();
                decoding = Evidence.DecodingStatus.PARTIAL;
                constraints.add("inputs=unknown:smithing-field-decoding-failed:" + failure.getClass().getSimpleName());
            }
            if (recipe.getClass() == net.minecraft.world.item.crafting.SmithingTransformRecipe.class)
                constraints.add("result keeps the base item's components");
            else constraints.add("output=unknown:dynamic base item with a trim determined by template and addition");
        }
        var remainders = new ArrayList<Evidence.RecipeOutput>();
        boolean ambiguousRemainders = false;
        int slotIndex = 0;
        for (Ingredient ingredient : ingredients) {
            var alternatives = new ArrayList<Evidence.RecipeAlternative>();
            var ingredientRemainders = new ArrayList<ItemStack>();
            int itemAlternatives = 0;
            boolean hasTagAlternative = false;
            if (ingredient.isEmpty()) {
                slotIndex++;
                continue;
            }
            if (ingredient.isCustom()) {
                decoding = Evidence.DecodingStatus.OPAQUE;
                ambiguousRemainders = craftingRecipe;
                alternatives.add(customAlternative(ingredient, registries, slotIndex));
            } else {
                for (Ingredient.Value value : ingredient.getValues()) {
                    if (value instanceof Ingredient.ItemValue itemValue) {
                        var stack = itemValue.item();
                        alternatives.add(new Evidence.RecipeAlternative("item", itemIdentity(stack, itemRegistry), componentPayload(stack, registries, constraints),
                                1, knownRecipe ? Evidence.Consumption.CONSUMED : Evidence.Consumption.UNKNOWN));
                        itemAlternatives++;
                        if (craftingRecipe && stack.hasCraftingRemainingItem()) ingredientRemainders.add(stack.getCraftingRemainingItem());
                    } else if (value instanceof Ingredient.TagValue tagValue) {
                        var identity = "#" + tagValue.tag().location();
                        tags.add(identity);
                        alternatives.add(new Evidence.RecipeAlternative("tag", identity, new Evidence.JsonObject(Map.of()), 1,
                                knownRecipe ? Evidence.Consumption.CONSUMED : Evidence.Consumption.UNKNOWN));
                        hasTagAlternative = true;
                    } else {
                        decoding = Evidence.DecodingStatus.OPAQUE;
                        alternatives.add(new Evidence.RecipeAlternative("unknown", "unknown:ingredient:" + slotIndex,
                                new Evidence.JsonObject(Map.of()), null, Evidence.Consumption.UNKNOWN));
                    }
                }
            }
            if (alternatives.isEmpty()) {
                decoding = Evidence.DecodingStatus.OPAQUE;
                alternatives.add(new Evidence.RecipeAlternative("unknown", "unknown:ingredient:" + slotIndex,
                        new Evidence.JsonObject(Map.of()), null, Evidence.Consumption.UNKNOWN));
            }
            if (!ingredient.isCustom() && craftingRecipe) {
                if (hasTagAlternative) ambiguousRemainders = true;
                else if (itemAlternatives > 0 && ingredientRemainders.size() == itemAlternatives) {
                    var remainder = ingredientRemainders.getFirst();
                    if (ingredientRemainders.stream().allMatch(stack -> ItemStack.isSameItemSameComponents(stack, remainder)
                            && stack.getCount() == remainder.getCount())) {
                        remainders.add(new Evidence.RecipeOutput("item", itemIdentity(remainder, itemRegistry), componentPayload(remainder, registries, constraints),
                                remainder.getCount()));
                    } else ambiguousRemainders = true;
                } else if (!ingredientRemainders.isEmpty()) ambiguousRemainders = true;
            }
            slots.add(new Evidence.RecipeSlot("slot-" + slotIndex++, alternatives));
        }
        var outputs = output.isEmpty() ? List.of(new Evidence.RecipeOutput("unknown", "unknown:output:" + holder.id(),
                new Evidence.JsonObject(Map.of()), null)) : List.of(new Evidence.RecipeOutput("item", itemIdentity(output, itemRegistry),
                componentPayload(output, registries, constraints), output.getCount()));
        if (output.isEmpty()) decoding = Evidence.DecodingStatus.PARTIAL;
        if (constraints.contains("components=unknown:stack-encoding-failed") && decoding == Evidence.DecodingStatus.VERIFIED)
            decoding = Evidence.DecodingStatus.PARTIAL;
        if (ambiguousRemainders && decoding == Evidence.DecodingStatus.VERIFIED) decoding = Evidence.DecodingStatus.PARTIAL;
        constraints.add("recipeType=" + recipe.getType());
        constraints.add("serializer=" + recipe.getSerializer());
        if (!knownRecipe || decoding == Evidence.DecodingStatus.OPAQUE) constraints.add("catalysts=unknown");
        if (ambiguousRemainders) constraints.add("remainders=ambiguous-by-ingredient-alternative");
        else if (!knownRecipe || decoding == Evidence.DecodingStatus.OPAQUE) constraints.add("remainders=unknown");
        Evidence.JsonValue shape = null;
        if (recipe instanceof net.minecraft.world.item.crafting.ShapedRecipe shaped) {
            try {
                int width = shaped.getWidth();
                int height = shaped.getHeight();
                var shapeValues = new LinkedHashMap<String, Evidence.JsonValue>();
                shapeValues.put("width", new Evidence.JsonNumber(BigDecimal.valueOf(width)));
                shapeValues.put("height", new Evidence.JsonNumber(BigDecimal.valueOf(height)));
                shape = new Evidence.JsonObject(shapeValues);
                constraints.add("shape=" + width + "x" + height);
                constraints.add("slot-index=row*width+column");
            } catch (RuntimeException ignored) {
                shape = null;
            }
        } else if (recipe instanceof net.minecraft.world.item.crafting.ShapelessRecipe) {
            constraints.add("shape=shapeless");
            shape = new Evidence.JsonString("shapeless");
        }
        var provenance = new ArrayList<String>();
        provenance.add("client-recipe-manager");
        if (resourceManager != null) {
            try {
                var recipeLocation = ResourceLocation.parse(holder.id().toString()).withPrefix("recipes/").withSuffix(".json");
                for (Resource resource : resourceManager.getResourceStack(recipeLocation)) provenance.add("resource-pack:" + resource.sourcePackId());
            } catch (RuntimeException ignored) {
                provenance.add("resource-provenance=unknown");
            }
        }
        if (provenance.size() == 1) provenance.add("resource-provenance=unknown");
        var coverage = decoding == Evidence.DecodingStatus.VERIFIED ? Evidence.Coverage.COMPLETE
                : decoding == Evidence.DecodingStatus.OPAQUE ? Evidence.Coverage.OPAQUE : Evidence.Coverage.PARTIAL;
        var source = new Evidence.Source(scope, "native", "1.21.1", holder.id().toString(), scope.requestGeneration(), Instant.now(),
                Evidence.Activity.ACTIVE, coverage, Evidence.Authority.LOADED, provenance);
        var payload = recipePayloadJson(holder.id().toString(), slots, outputs, remainders, List.of(), constraints, decoding, tags);
        if (shape != null) {
            var withShape = new LinkedHashMap<>(payload.values());
            withShape.put("shape", shape);
            payload = new Evidence.JsonObject(withShape);
        }
        var typed = new Evidence.RecipePayload(holder.id().toString(), slots, outputs, remainders, List.of(), constraints, decoding);
        return new Evidence.Record(source, Evidence.RecordKind.RECIPE, holder.id().toString(), payload, List.of(), typed);
    }

    private static Evidence.RecipeAlternative customAlternative(Ingredient ingredient, net.minecraft.core.HolderLookup.Provider registries,
                                                                 int slotIndex) {
        var custom = ingredient.getCustomIngredient();
        var type = custom == null ? null : net.neoforged.neoforge.registries.NeoForgeRegistries.INGREDIENT_TYPES.getKey(custom.getType());
        var typeId = type == null ? "unknown" : type.toString();
        var values = new LinkedHashMap<String, Evidence.JsonValue>();
        values.put("matcherType", new Evidence.JsonString(typeId));
        values.put("ingredient", codecJson(Ingredient.CODEC, ingredient, registries));
        var display = new ArrayList<Evidence.JsonValue>();
        try {
            if (custom != null) custom.getItems().limit(8).forEach(stack -> display.add(stackJson(stack, registries)));
        } catch (RuntimeException ignored) {
            values.put("displayItemsError", new Evidence.JsonString("unknown"));
        }
        values.put("displayItems", new Evidence.JsonArray(display));
        return new Evidence.RecipeAlternative("custom", "custom:" + typeId + ":" + slotIndex, new Evidence.JsonObject(values),
                null, Evidence.Consumption.UNKNOWN);
    }

    private static Evidence.Record registryItem(Item item, String identity, Map<String, LinkedHashSet<String>> tagMembers,
                                                net.minecraft.core.HolderLookup.Provider registries, Evidence.Scope scope) {
        var tags = new ArrayList<Evidence.JsonValue>();
        for (var entry : tagMembers.entrySet()) if (entry.getValue().contains(identity)) tags.add(new Evidence.JsonString(entry.getKey()));
        var payload = new LinkedHashMap<String, Evidence.JsonValue>();
        payload.put("registry", new Evidence.JsonString("item"));
        payload.put("id", new Evidence.JsonString(identity));
        payload.put("identity", new Evidence.JsonString(identity));
        payload.put("tags", new Evidence.JsonArray(tags));
        payload.put("components", componentPayload(new ItemStack(item), registries));
        var source = new Evidence.Source(scope, "native", "1.21.1", "item:" + identity, scope.requestGeneration(), Instant.now(),
                Evidence.Activity.ACTIVE, Evidence.Coverage.COMPLETE, Evidence.Authority.LOADED, List.of("client-registry:item"));
        return new Evidence.Record(source, Evidence.RecordKind.ITEM, new ItemStack(item).getHoverName().getString(), new Evidence.JsonObject(payload), List.of());
    }

    private static Evidence.Record registryBlock(Block block, String identity, Evidence.Scope scope) {
        var payload = new Evidence.JsonObject(Map.of("registry", new Evidence.JsonString("block"), "id", new Evidence.JsonString(identity),
                "identity", new Evidence.JsonString(identity)));
        var source = new Evidence.Source(scope, "native", "1.21.1", "block:" + identity, scope.requestGeneration(), Instant.now(),
                Evidence.Activity.ACTIVE, Evidence.Coverage.COMPLETE, Evidence.Authority.LOADED, List.of("client-registry:block"));
        return new Evidence.Record(source, Evidence.RecordKind.BLOCK, block.getName().getString(), payload, List.of());
    }

    private static Evidence.Record registryTag(String identity, Set<String> members, Evidence.Scope scope) {
        var values = new LinkedHashMap<String, Evidence.JsonValue>();
        values.put("registry", new Evidence.JsonString("item-tag"));
        values.put("id", new Evidence.JsonString(identity));
        values.put("members", new Evidence.JsonArray(members.stream().map(Evidence.JsonString::new).map(value -> (Evidence.JsonValue) value).toList()));
        values.put("tags", new Evidence.JsonArray(List.of(new Evidence.JsonString(identity))));
        var source = new Evidence.Source(scope, "native", "1.21.1", "tag:" + identity, scope.requestGeneration(), Instant.now(),
                Evidence.Activity.ACTIVE, Evidence.Coverage.COMPLETE, Evidence.Authority.LOADED, List.of("client-registry:tag"));
        return new Evidence.Record(source, Evidence.RecordKind.OTHER, identity, new Evidence.JsonObject(values), List.of());
    }

    static Evidence.JsonObject recipePayloadJson(String id, List<Evidence.RecipeSlot> slots, List<Evidence.RecipeOutput> outputs,
                                                          List<Evidence.RecipeOutput> remainders, List<Evidence.RecipeAlternative> catalysts,
                                                          List<String> constraints, Evidence.DecodingStatus decoding, Set<String> tags) {
        var values = new LinkedHashMap<String, Evidence.JsonValue>();
        values.put("canonicalRecipeId", id == null ? new Evidence.JsonNull() : new Evidence.JsonString(id));
        values.put("inputs", new Evidence.JsonArray(slots.stream().map(slot -> {
            var slotValues = new LinkedHashMap<String, Evidence.JsonValue>();
            slotValues.put("groupId", new Evidence.JsonString(slot.groupId()));
            slotValues.put("alternatives", new Evidence.JsonArray(slot.alternatives().stream().map(NativeSnapshot::alternativeJson)
                    .map(value -> (Evidence.JsonValue) value).toList()));
            return (Evidence.JsonValue) new Evidence.JsonObject(slotValues);
        }).toList()));
        values.put("outputs", new Evidence.JsonArray(outputs.stream().map(NativeSnapshot::outputJson)
                .map(value -> (Evidence.JsonValue) value).toList()));
        values.put("remainders", new Evidence.JsonArray(remainders.stream().map(NativeSnapshot::outputJson)
                .map(value -> (Evidence.JsonValue) value).toList()));
        values.put("catalysts", new Evidence.JsonArray(catalysts.stream().map(NativeSnapshot::alternativeJson)
                .map(value -> (Evidence.JsonValue) value).toList()));
        values.put("constraints", new Evidence.JsonArray(constraints.stream().map(Evidence.JsonString::new)
                .map(value -> (Evidence.JsonValue) value).toList()));
        values.put("decodingStatus", new Evidence.JsonString(decoding.name()));
        values.put("tags", new Evidence.JsonArray(tags.stream().map(Evidence.JsonString::new)
                .map(value -> (Evidence.JsonValue) value).toList()));
        values.put("components", new Evidence.JsonArray(outputs.stream().map(Evidence.RecipeOutput::components)
                .map(value -> (Evidence.JsonValue) value).toList()));
        return new Evidence.JsonObject(values);
    }

    private static Evidence.JsonObject alternativeJson(Evidence.RecipeAlternative alternative) {
        var values = new LinkedHashMap<String, Evidence.JsonValue>();
        values.put("type", new Evidence.JsonString(alternative.type()));
        values.put("comparisonIdentity", new Evidence.JsonString(alternative.comparisonIdentity()));
        values.put("components", alternative.components());
        values.put("quantity", alternative.quantity() == null ? new Evidence.JsonNull() : new Evidence.JsonNumber(BigDecimal.valueOf(alternative.quantity())));
        values.put("consumption", new Evidence.JsonString(alternative.consumption().name()));
        return new Evidence.JsonObject(values);
    }

    private static Evidence.JsonObject outputJson(Evidence.RecipeOutput output) {
        var values = new LinkedHashMap<String, Evidence.JsonValue>();
        values.put("type", new Evidence.JsonString(output.type()));
        values.put("comparisonIdentity", new Evidence.JsonString(output.comparisonIdentity()));
        values.put("components", output.components());
        values.put("quantity", output.quantity() == null ? new Evidence.JsonNull() : new Evidence.JsonNumber(BigDecimal.valueOf(output.quantity())));
        return new Evidence.JsonObject(values);
    }

    static Evidence.JsonObject componentPayload(ItemStack stack, net.minecraft.core.HolderLookup.Provider registries) {
        return componentPayload(stack, registries, null);
    }

    private static Evidence.JsonObject componentPayload(ItemStack stack, net.minecraft.core.HolderLookup.Provider registries,
                                                         List<String> constraints) {
        var encoded = codecJson(ItemStack.CODEC, stack, registries);
        if (encoded.values().containsKey("encoding") && constraints != null
                && !constraints.contains("components=unknown:stack-encoding-failed"))
            constraints.add("components=unknown:stack-encoding-failed");
        var components = encoded.values().get("components");
        return components instanceof Evidence.JsonObject object ? object : new Evidence.JsonObject(Map.of());
    }

    private static Evidence.JsonObject stackJson(ItemStack stack, net.minecraft.core.HolderLookup.Provider registries) {
        var encoded = codecJson(ItemStack.CODEC, stack, registries);
        return encoded;
    }

    private static <T> Evidence.JsonObject codecJson(com.mojang.serialization.Codec<T> codec, T value,
                                                      net.minecraft.core.HolderLookup.Provider registries) {
        try {
            var encoded = codec.encodeStart(registries.createSerializationContext(JsonOps.INSTANCE), value).result().orElse(null);
            return encoded != null && encoded.isJsonObject() ? (Evidence.JsonObject) jsonValue(encoded)
                    : new Evidence.JsonObject(Map.of("encoding", new Evidence.JsonString("unknown")));
        } catch (RuntimeException ignored) {
            return new Evidence.JsonObject(Map.of("encoding", new Evidence.JsonString("unknown")));
        }
    }

    private static Evidence.JsonValue jsonValue(JsonElement value) {
        if (value.isJsonObject()) return Evidence.deserializeObject(value.toString());
        if (value.isJsonArray()) return new Evidence.JsonArray(value.getAsJsonArray().asList().stream().map(NativeSnapshot::jsonValue).toList());
        if (value.isJsonNull()) return new Evidence.JsonNull();
        if (value.getAsJsonPrimitive().isBoolean()) return new Evidence.JsonBoolean(value.getAsBoolean());
        if (value.getAsJsonPrimitive().isNumber()) return new Evidence.JsonNumber(value.getAsBigDecimal());
        return new Evidence.JsonString(value.getAsString());
    }

    static String itemIdentity(ItemStack stack, Registry<Item> itemRegistry) {
        return itemIdentity(stack.getItem(), itemRegistry);
    }

    static String itemIdentity(Item item, Registry<Item> itemRegistry) {
        var identity = itemRegistry.getKey(item);
        if (identity != null) return identity.toString();
        var builtin = BuiltInRegistries.ITEM.getKey(item);
        return builtin == null ? "unknown:item" : builtin.toString();
    }

    private static NativeSnapshot unavailable(Evidence.Scope scope, String detail) {
        var source = new Evidence.Source(scope, "native", "1.21.1", "native:unavailable", scope.requestGeneration(), Instant.now(),
                Evidence.Activity.UNKNOWN, Evidence.Coverage.UNAVAILABLE, Evidence.Authority.UNKNOWN, List.of(detail));
        var payload = new Evidence.JsonObject(Map.of("status", new Evidence.JsonString("unavailable"),
                "detail", new Evidence.JsonString(detail)));
        return new NativeSnapshot(scope, List.of(new Evidence.Record(source, Evidence.RecordKind.OTHER, "Native evidence unavailable", payload, List.of())));
    }
}
