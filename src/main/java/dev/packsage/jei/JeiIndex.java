package dev.packsage.jei;

import dev.packsage.knowledge.Evidence;
import java.util.List;
import java.util.Optional;

public interface JeiIndex {
    record RecipeRef(String categoryUid, int ordinal) { }
    record ItemVariant(String itemId, Evidence.JsonObject components) {
        public ItemVariant(String itemId, String componentsJson) { this(itemId, componentKey(componentsJson)); }
        public String componentsJson() { return Evidence.serialize(components); }
    }

    static Evidence.JsonObject componentKey(String componentsJson) { return Evidence.deserializeObject(componentsJson); }

    long generation();
    List<RecipeRef> byFocus(JeiLayout.Role role, String itemId);
    List<RecipeRef> byVariant(JeiLayout.Role role, ItemVariant variant);
    default boolean hasSubtypes(String itemId) { return false; }
    List<String> categoriesMatching(String text);
    List<ItemVariant> itemsMatching(String text);
    boolean itemsMatchingTruncated();
    List<RecipeRef> inCategory(String categoryUid);
    Optional<RecipeRef> byRecipeId(String categoryUid, String recipeId);
    JeiLayout layout(RecipeRef ref);
}
