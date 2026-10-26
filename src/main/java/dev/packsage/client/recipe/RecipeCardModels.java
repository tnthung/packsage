package dev.packsage.client.recipe;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;

public final class RecipeCardModels {
    public static Optional<RecipeCardModel> fromRecord(JsonObject record) {
        try {
            var payload = record.getAsJsonObject("payload");
            var status = payload.get("decodingStatus").getAsString();
            if (!List.of("VERIFIED", "PARTIAL", "OPAQUE").contains(status)) return Optional.empty();
            var inputs = payload.getAsJsonArray("inputs");
            var outputs = payload.getAsJsonArray("outputs");
            var constraints = payload.getAsJsonArray("constraints");
            if (inputs == null || outputs == null || constraints == null || outputs.size() != 1) return Optional.empty();
            String type = "";
            boolean brewing = false;
            for (var constraint : constraints) {
                var value = constraint.getAsString();
                if (value.startsWith("recipeType=")) type = value.substring("recipeType=".length());
                if (value.startsWith("brewing-batch=")) brewing = true;
            }
            if (type.startsWith("minecraft:")) type = type.substring("minecraft:".length());
            var source = record.get("source");
            var sourceHandle = source != null && source.isJsonObject() ? source.getAsJsonObject().get("sourceHandle") : null;
            var reference = record.get("reference");
            brewing = brewing || sourceHandle != null && sourceHandle.isJsonPrimitive() && sourceHandle.getAsString().contains("brewing:")
                    || reference != null && reference.isJsonPrimitive() && reference.getAsString().startsWith("native:brewing:");
            var shape = payload.get("shape");
            var category = brewing ? RecipeCardModel.Category.BREWING : switch (type) {
                case "crafting" -> shape != null && shape.isJsonObject() ? RecipeCardModel.Category.CRAFTING_SHAPED
                        : shape != null && shape.isJsonPrimitive() && shape.getAsString().equals("shapeless") ? RecipeCardModel.Category.CRAFTING_SHAPELESS : null;
                case "smelting" -> RecipeCardModel.Category.SMELTING;
                case "blasting" -> RecipeCardModel.Category.BLASTING;
                case "smoking" -> RecipeCardModel.Category.SMOKING;
                case "campfire_cooking" -> RecipeCardModel.Category.CAMPFIRE;
                case "stonecutting" -> RecipeCardModel.Category.STONECUTTING;
                case "smithing" -> RecipeCardModel.Category.SMITHING;
                default -> null;
            };
            if (category == null) return Optional.empty();
            int width = 3;
            int height = 3;
            if (category == RecipeCardModel.Category.CRAFTING_SHAPED) {
                width = shape.getAsJsonObject().get("width").getAsInt();
                height = shape.getAsJsonObject().get("height").getAsInt();
                if (width < 1 || width > 3 || height < 1 || height > 3) return Optional.empty();
            } else if (category == RecipeCardModel.Category.CRAFTING_SHAPELESS) {
                width = height = inputs.size() <= 1 ? 1 : inputs.size() <= 4 ? 2 : 3;
                if (inputs.size() > 9) return Optional.empty();
            }
            var groups = new HashMap<String, List<RecipeCardModel.Stack>>();
            for (var input : inputs) {
                var object = input.getAsJsonObject();
                var group = object.get("groupId").getAsString();
                var alternatives = object.getAsJsonArray("alternatives");
                if (alternatives == null || alternatives.isEmpty()) return Optional.empty();
                var stacks = new ArrayList<RecipeCardModel.Stack>();
                for (var alternative : alternatives) stacks.add(stack(alternative.getAsJsonObject()));
                if (category == RecipeCardModel.Category.CRAFTING_SHAPED || category == RecipeCardModel.Category.CRAFTING_SHAPELESS) {
                    if (!group.startsWith("slot-")) return Optional.empty();
                    int index = Integer.parseInt(group.substring(5));
                    if (index < 0 || index >= width * height) return Optional.empty();
                    int row = index / width;
                    int col = index % width;
                    if (width == 1) col++;
                    if (height == 1) row++;
                    else if (height == 2 && width == 3) row++;
                    group = "slot-" + (row * 3 + col);
                }
                if (groups.put(group, List.copyOf(stacks)) != null) return Optional.empty();
            }
            groups.put("output", List.of(stack(outputs.get(0).getAsJsonObject())));
            var layout = RecipeCardLayouts.layout(category);
            var slots = new ArrayList<RecipeCardModel.Slot>();
            for (var position : layout.slots()) slots.add(new RecipeCardModel.Slot(position.role(), position.x(), position.y(), position.output(),
                    groups.getOrDefault(position.role(), List.of())));
            var texts = new ArrayList<RecipeCardModel.Text>();
            if (List.of(RecipeCardModel.Category.SMELTING, RecipeCardModel.Category.BLASTING, RecipeCardModel.Category.SMOKING, RecipeCardModel.Category.CAMPFIRE).contains(category)) {
                try {
                    if (payload.has("cookingTime") && payload.get("cookingTime").getAsInt() > 0)
                        texts.add(new RecipeCardModel.Text(payload.get("cookingTime").getAsInt() / 20 + "s", RecipeCardLayouts.COOKING_TEXT_X, category == RecipeCardModel.Category.CAMPFIRE ? RecipeCardLayouts.CAMPFIRE_TIME_Y : RecipeCardLayouts.COOKING_TIME_Y, true));
                } catch (RuntimeException ignored) { }
                try {
                    if (category != RecipeCardModel.Category.CAMPFIRE && payload.has("experience") && payload.get("experience").getAsFloat() > 0)
                        texts.add(new RecipeCardModel.Text(payload.get("experience").getAsFloat() + " XP", RecipeCardLayouts.COOKING_TEXT_X, RecipeCardLayouts.EXPERIENCE_Y, true));
                } catch (RuntimeException ignored) { }
            }
            return Optional.of(new RecipeCardModel(category, layout.width(), layout.height(), List.copyOf(slots), layout.caption(), List.copyOf(texts)));
        } catch (RuntimeException failure) {
            return Optional.empty();
        }
    }

    private static RecipeCardModel.Stack stack(JsonObject value) {
        var kind = switch (value.get("type").getAsString()) {
            case "item", "item_stack" -> RecipeCardModel.Stack.Kind.ITEM;
            case "tag" -> RecipeCardModel.Stack.Kind.TAG;
            case "custom" -> RecipeCardModel.Stack.Kind.CUSTOM;
            case "unknown" -> RecipeCardModel.Stack.Kind.UNKNOWN;
            default -> throw new IllegalArgumentException();
        };
        var identityValue = value.get("comparisonIdentity");
        var identity = identityValue != null && identityValue.isJsonPrimitive() ? identityValue.getAsString() : "";
        var components = value.getAsJsonObject("components");
        int count = value.has("quantity") && !value.get("quantity").isJsonNull() ? value.get("quantity").getAsInt() : 1;
        if (components == null || count < 1) throw new IllegalArgumentException();
        var matcherType = components.get("matcherType");
        var description = kind == RecipeCardModel.Stack.Kind.CUSTOM && matcherType != null && matcherType.isJsonPrimitive()
                ? matcherType.getAsString() : kind == RecipeCardModel.Stack.Kind.CUSTOM || kind == RecipeCardModel.Stack.Kind.UNKNOWN ? identity : "";
        return new RecipeCardModel.Stack(kind, identity, components.toString(), count, description);
    }

    private RecipeCardModels() { }
}
