package dev.packsage.jei;

import dev.packsage.knowledge.Evidence;
import java.util.List;

/**
 * nativeInputs is how many ingredient positions of the native recipe behind a vanilla layout are not blank, or null when no native
 * recipe was read. A blank position is the native empty ingredient; an empty tag is not blank.
 */
public record JeiLayout(String categoryUid, String categoryTitle, String recipeId, int ordinal, long generation,
                        boolean hidden, boolean readable, Evidence.DecodingStatus nativeDecoding, List<Slot> slots, List<String> recipeTooltip,
                        Integer nativeInputs) {
    public enum Role { INPUT, OUTPUT, CATALYST, RENDER_ONLY, CATEGORY_CATALYST }

    public record Slot(int index, Role role, List<Ingredient> alternatives, List<String> extraTooltip) { }

    public record Ingredient(String type, String identity, String componentsJson, long amount, String remainderIdentity) { }

    public JeiLayout(String categoryUid, String categoryTitle, String recipeId, int ordinal, long generation, boolean hidden, boolean readable,
                     Evidence.DecodingStatus nativeDecoding, List<Slot> slots, List<String> recipeTooltip) {
        this(categoryUid, categoryTitle, recipeId, ordinal, generation, hidden, readable, nativeDecoding, slots, recipeTooltip, null);
    }
}
