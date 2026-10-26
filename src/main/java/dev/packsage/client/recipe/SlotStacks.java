package dev.packsage.client.recipe;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;

public final class SlotStacks {
    public static final int CYCLE_MS = 1000;

    public static int cycleIndex(long millis, int size) {
        return (int) ((millis / CYCLE_MS) % size);
    }

    public static List<ItemStack> resolve(RecipeCardModel.Stack stack, HolderLookup.Provider registries) {
        if (stack.kind() == RecipeCardModel.Stack.Kind.CUSTOM || stack.kind() == RecipeCardModel.Stack.Kind.UNKNOWN) return List.of();
        var id = ResourceLocation.tryParse(stack.identity().startsWith("#") ? stack.identity().substring(1) : stack.identity());
        var items = registries.lookupOrThrow(Registries.ITEM);
        if (stack.kind() == RecipeCardModel.Stack.Kind.TAG) {
            if (id == null) return List.of();
            return items.get(TagKey.create(Registries.ITEM, id)).stream().flatMap(tag -> tag.stream())
                    .map(holder -> {
                        var resolved = new ItemStack(holder, 1);
                        resolved.setCount(Math.clamp(stack.count(), 1, resolved.getMaxStackSize()));
                        return resolved;
                    }).toList();
        }
        var item = id == null ? null : items.get(ResourceKey.create(Registries.ITEM, id)).orElse(null);
        if (item == null) {
            var missing = new ItemStack(Items.BARRIER);
            missing.set(DataComponents.CUSTOM_NAME, Component.literal("Missing: " + stack.identity()));
            return List.of(missing);
        }
        try {
            var patch = DataComponentPatch.CODEC.parse(registries.createSerializationContext(JsonOps.INSTANCE),
                    JsonParser.parseString(stack.componentsJson())).result().orElseThrow();
            var resolved = new ItemStack(item, 1, patch);
            resolved.setCount(Math.clamp(stack.count(), 1, resolved.getMaxStackSize()));
            return List.of(resolved);
        } catch (RuntimeException failure) {
            var missing = new ItemStack(Items.BARRIER);
            missing.set(DataComponents.CUSTOM_NAME, Component.literal("Missing: " + stack.identity()));
            return List.of(missing);
        }
    }

    private SlotStacks() { }
}
