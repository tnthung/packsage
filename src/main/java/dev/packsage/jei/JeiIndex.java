package dev.packsage.jei;

import java.util.List;
import java.util.Optional;

public interface JeiIndex {
    record RecipeRef(String categoryUid, int ordinal) { }
    record ItemVariant(String itemId, String componentsJson) { }

    long generation();
    List<RecipeRef> byFocus(JeiLayout.Role role, String itemId);
    List<RecipeRef> byVariant(JeiLayout.Role role, ItemVariant variant);
    List<String> categoriesMatching(String text);
    List<ItemVariant> itemsMatching(String text);
    boolean itemsMatchingTruncated();
    List<RecipeRef> inCategory(String categoryUid);
    Optional<RecipeRef> byRecipeId(String categoryUid, String recipeId);
    JeiLayout layout(RecipeRef ref);
}
