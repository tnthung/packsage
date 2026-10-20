package guideme.compiler.tags;

import java.util.Set;

public final class RecipeCompiler {
    private static Set<String> tagNames = Set.of("Recipe", "RecipeFor", "RecipesFor");

    public Set<String> getTagNames() { return tagNames; }
    public static void setTagNamesForTest(Set<String> names) { tagNames = Set.copyOf(names); }
}
