package dev.packsage.jei;

import dev.packsage.knowledge.Evidence;
import java.util.List;

public record JeiLayout(String categoryUid, String categoryTitle, String recipeId, int ordinal, long generation,
                        boolean hidden, boolean readable, Evidence.DecodingStatus nativeDecoding, List<Slot> slots, List<String> recipeTooltip) {
    public enum Role { INPUT, OUTPUT, CATALYST, RENDER_ONLY }

    public record Slot(int index, Role role, List<Ingredient> alternatives, List<String> extraTooltip) { }

    public record Ingredient(String type, String identity, String componentsJson, long amount, String remainderIdentity) { }
}
