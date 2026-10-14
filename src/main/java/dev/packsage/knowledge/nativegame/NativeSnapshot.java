package dev.packsage.knowledge.nativegame;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import dev.packsage.knowledge.Evidence;

import net.minecraft.client.Minecraft;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
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
            records.add(copyRecipe(holder, registries, itemRegistry, resourceManager, scope));
        }
        return records.isEmpty() ? unavailable(scope, "client native evidence empty") : new NativeSnapshot(scope, records);
    }

    /** Copies occupied vanilla player slots on demand; ordinary capture never reads the player inventory. */
    public static NativeSnapshot captureSelectedInventory(Minecraft client, Evidence.Scope scope, List<String> identities) {
        Objects.requireNonNull(client);
        Objects.requireNonNull(scope);
        Objects.requireNonNull(identities);
        if (!client.isSameThread()) throw new IllegalStateException("native inventory snapshot must be captured on the client thread");
        if (client.level == null) return unavailable(scope, "client level unavailable");
        var records = new ArrayList<Evidence.Record>();
        var registries = client.level.registryAccess();
        Registry<Item> itemRegistry;
        try {
            itemRegistry = registries.registryOrThrow(Registries.ITEM);
        } catch (RuntimeException failure) {
            return unavailable(scope, "client item registry unavailable: " + failure.getClass().getSimpleName());
        }
        if (client.player == null) return unavailable(scope, "client player unavailable");
        var requested = Set.copyOf(identities);
        var inventory = client.player.getInventory();
        for (int index = 0; index < inventory.items.size(); index++) {
            var stack = inventory.items.get(index);
            if (stack.isEmpty() || !requested.isEmpty() && !requested.contains(itemIdentity(stack, itemRegistry))) continue;
            var section = index < 9 ? "hotbar" : "main";
            var slot = index < 9 ? index : index - 9;
            records.add(inventoryRecord(scope, stack.copy(), itemRegistry, registries, section, slot,
                    index == inventory.selected ? "mainhand" : null, index == inventory.selected));
        }
        var armorSlots = List.of("feet", "legs", "chest", "head");
        for (int index = 0; index < inventory.armor.size(); index++) {
            var stack = inventory.armor.get(index);
            if (stack.isEmpty() || !requested.isEmpty() && !requested.contains(itemIdentity(stack, itemRegistry))) continue;
            records.add(inventoryRecord(scope, stack.copy(), itemRegistry, registries, "armor", index,
                    armorSlots.get(index), false));
        }
        for (int index = 0; index < inventory.offhand.size(); index++) {
            var stack = inventory.offhand.get(index);
            if (stack.isEmpty() || !requested.isEmpty() && !requested.contains(itemIdentity(stack, itemRegistry))) continue;
            records.add(inventoryRecord(scope, stack.copy(), itemRegistry, registries, "offhand", index, "offhand", false));
        }
        return new NativeSnapshot(scope, records);
    }

    static Evidence.Record inventoryRecord(Evidence.Scope scope, ItemStack stack, Registry<Item> itemRegistry,
                                           net.minecraft.core.HolderLookup.Provider registries, String section,
                                           int slot, String equipmentSlot, boolean selectedHotbar) {
        var identity = itemIdentity(stack, itemRegistry);
        var components = codecJson(DataComponentMap.CODEC, stack.getComponents().filter(type ->
                type != DataComponents.CONTAINER && type != DataComponents.BUNDLE_CONTENTS), registries);
        var payload = new LinkedHashMap<String, Evidence.JsonValue>();
        payload.put("id", new Evidence.JsonString(identity));
        payload.put("inventoryObservation", new Evidence.JsonString("occupied"));
        payload.put("section", new Evidence.JsonString(section));
        payload.put("slot", new Evidence.JsonNumber(BigDecimal.valueOf(slot)));
        payload.put("selectedHotbar", new Evidence.JsonBoolean(selectedHotbar));
        payload.put("name", new Evidence.JsonString(stack.getHoverName().getString()));
        payload.put("count", new Evidence.JsonNumber(BigDecimal.valueOf(stack.getCount())));
        payload.put("components", components);
        if (stack.has(DataComponents.CONTAINER) || stack.has(DataComponents.BUNDLE_CONTENTS)) {
            payload.put("nestedStorageContentsOmitted", new Evidence.JsonBoolean(true));
        }
        if (equipmentSlot != null) payload.put("equipmentSlot", new Evidence.JsonString(equipmentSlot));
        var source = new Evidence.Source(scope, "native", "1.21.1", "inventory:" + section + ":" + slot,
                scope.requestGeneration(), Instant.now(), Evidence.Activity.ACTIVE,
                components.values().containsKey("encoding") ? Evidence.Coverage.PARTIAL : Evidence.Coverage.COMPLETE,
                Evidence.Authority.LOADED, List.of("player-inventory", section + ":" + slot));
        return new Evidence.Record(source, Evidence.RecordKind.ITEM, identity, new Evidence.JsonObject(payload), List.of());
    }

    static Evidence.Record copyRecipe(RecipeHolder<?> holder, net.minecraft.core.HolderLookup.Provider registries,
                                               Registry<Item> itemRegistry, ResourceManager resourceManager, Evidence.Scope scope) {
        Recipe<?> recipe = holder.value();
        var decoding = Evidence.DecodingStatus.VERIFIED;
        var slots = new ArrayList<Evidence.RecipeSlot>();
        var tags = new LinkedHashSet<String>();
        int slotIndex = 0;
        for (Ingredient ingredient : recipe.getIngredients()) {
            var alternatives = new ArrayList<Evidence.RecipeAlternative>();
            if (ingredient.isEmpty()) {
                slotIndex++;
                continue;
            }
            if (ingredient.isCustom()) {
                decoding = Evidence.DecodingStatus.OPAQUE;
                alternatives.add(customAlternative(ingredient, registries, slotIndex));
            } else {
                for (Ingredient.Value value : ingredient.getValues()) {
                    if (value instanceof Ingredient.ItemValue itemValue) {
                        var stack = itemValue.item();
                        alternatives.add(new Evidence.RecipeAlternative("item", itemIdentity(stack, itemRegistry), componentPayload(stack, registries),
                                stack.getCount(), Evidence.Consumption.UNKNOWN));
                    } else if (value instanceof Ingredient.TagValue tagValue) {
                        var identity = "#" + tagValue.tag().location();
                        tags.add(identity);
                        alternatives.add(new Evidence.RecipeAlternative("tag", identity, new Evidence.JsonObject(Map.of()), 1,
                                Evidence.Consumption.UNKNOWN));
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
            slots.add(new Evidence.RecipeSlot("slot-" + slotIndex++, alternatives));
        }
        ItemStack output;
        try {
            output = recipe.getResultItem(registries);
        } catch (RuntimeException failure) {
            output = ItemStack.EMPTY;
            decoding = Evidence.DecodingStatus.PARTIAL;
        }
        var outputs = output.isEmpty() ? List.of(new Evidence.RecipeOutput("unknown", "unknown:output:" + holder.id(),
                new Evidence.JsonObject(Map.of()), null)) : List.of(new Evidence.RecipeOutput("item", itemIdentity(output, itemRegistry),
                componentPayload(output, registries), output.getCount()));
        if (output.isEmpty()) decoding = Evidence.DecodingStatus.PARTIAL;
        var constraints = new ArrayList<String>();
        constraints.add("recipeType=" + recipe.getType());
        constraints.add("serializer=" + recipe.getSerializer());
        constraints.add("catalysts=unknown");
        constraints.add("remainders=unknown");
        if (decoding == Evidence.DecodingStatus.VERIFIED) decoding = Evidence.DecodingStatus.PARTIAL;
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
        var coverage = decoding == Evidence.DecodingStatus.OPAQUE ? Evidence.Coverage.OPAQUE : Evidence.Coverage.PARTIAL;
        var source = new Evidence.Source(scope, "native", "1.21.1", holder.id().toString(), scope.requestGeneration(), Instant.now(),
                Evidence.Activity.ACTIVE, coverage, Evidence.Authority.LOADED, provenance);
        var payload = recipePayloadJson(holder.id().toString(), slots, outputs, List.of(), List.of(), constraints, decoding, tags);
        var typed = new Evidence.RecipePayload(holder.id().toString(), slots, outputs, List.of(), List.of(), constraints, decoding);
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

    private static Evidence.JsonObject recipePayloadJson(String id, List<Evidence.RecipeSlot> slots, List<Evidence.RecipeOutput> outputs,
                                                          List<Evidence.RecipeOutput> remainders, List<Evidence.RecipeAlternative> catalysts,
                                                          List<String> constraints, Evidence.DecodingStatus decoding, Set<String> tags) {
        var values = new LinkedHashMap<String, Evidence.JsonValue>();
        values.put("canonicalRecipeId", new Evidence.JsonString(id));
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

    private static Evidence.JsonObject componentPayload(ItemStack stack, net.minecraft.core.HolderLookup.Provider registries) {
        var encoded = codecJson(ItemStack.CODEC, stack, registries);
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
            return encoded != null && encoded.isJsonObject() ? (Evidence.JsonObject) jsonValue(encoded) : new Evidence.JsonObject(Map.of());
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

    private static String itemIdentity(ItemStack stack, Registry<Item> itemRegistry) {
        return itemIdentity(stack.getItem(), itemRegistry);
    }

    private static String itemIdentity(Item item, Registry<Item> itemRegistry) {
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
