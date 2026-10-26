package dev.packsage.client.recipe;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;

import java.util.List;

public final class SlotStacks {
    public static final int CYCLE_MS = 1000;

    public static int cycleIndex(long millis, int size) {
        return (int) ((millis / CYCLE_MS) % size);
    }

    public record ResolvedSlot(List<ItemStack> stacks, boolean missing) { }

    public static ResolvedSlot resolve(RecipeCardModel.Stack stack, HolderLookup.Provider registries) {
        try {
            if (stack.kind() == RecipeCardModel.Stack.Kind.CUSTOM || stack.kind() == RecipeCardModel.Stack.Kind.UNKNOWN)
                return new ResolvedSlot(List.of(), false);
            var id = ResourceLocation.tryParse(stack.identity().startsWith("#") ? stack.identity().substring(1) : stack.identity());
            var items = registries.lookupOrThrow(Registries.ITEM);
            if (stack.kind() == RecipeCardModel.Stack.Kind.TAG) {
                if (id == null) return new ResolvedSlot(List.of(), true);
                var resolved = items.get(TagKey.create(Registries.ITEM, id)).stream().flatMap(tag -> tag.stream())
                        .map(holder -> {
                            var member = new ItemStack(holder, 1);
                            member.setCount(Math.clamp(stack.count(), 1, member.getMaxStackSize()));
                            return member;
                        }).toList();
                return new ResolvedSlot(resolved, resolved.isEmpty());
            }
            var item = id == null ? null : items.get(ResourceKey.create(Registries.ITEM, id)).orElse(null);
            if (item == null) return new ResolvedSlot(List.of(), true);
            var patch = DataComponentPatch.CODEC.parse(registries.createSerializationContext(JsonOps.INSTANCE),
                    JsonParser.parseString(stack.componentsJson())).result().orElseThrow();
            var resolved = new ItemStack(item, 1, patch);
            resolved.setCount(Math.clamp(stack.count(), 1, resolved.getMaxStackSize()));
            return new ResolvedSlot(List.of(resolved), false);
        } catch (RuntimeException failure) {
            return new ResolvedSlot(List.of(), true);
        }
    }

    private SlotStacks() { }
}
