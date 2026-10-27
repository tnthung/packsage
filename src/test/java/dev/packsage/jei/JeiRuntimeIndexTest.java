package dev.packsage.jei;

import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JeiRuntimeIndexTest {
    @Test
    void hiddenStableRecipesResolveForReadsCardsAndBookmarksButNotNavigation() {
        var fake = new FakeJeiRuntime();
        var inHiddenCategory = new Object();
        var hiddenInVisibleCategory = new Object();
        fake.category("mod:hidden_machine", true, inHiddenCategory);
        fake.category("mod:machine", false, new Object(), hiddenInVisibleCategory);
        fake.registryNames.put(inHiddenCategory, ResourceLocation.parse("mod:secret"));
        fake.registryNames.put(hiddenInVisibleCategory, ResourceLocation.parse("mod:tucked"));
        fake.hiddenRecipes.add(hiddenInVisibleCategory);
        var index = fake.index();

        var hiddenCategory = index.byRecipeId("mod:hidden_machine", "mod:secret");
        assertEquals(Optional.of(new JeiIndex.RecipeRef("mod:hidden_machine", 0)), hiddenCategory);
        assertSame(inHiddenCategory, index.resolve(hiddenCategory.get()).orElseThrow().recipe());
        assertTrue(index.resolveVisible(hiddenCategory.get()).isEmpty());

        var hiddenRecipe = index.byRecipeId("mod:machine", "mod:tucked");
        assertEquals(Optional.of(new JeiIndex.RecipeRef("mod:machine", 1)), hiddenRecipe);
        assertSame(hiddenInVisibleCategory, index.resolve(hiddenRecipe.get()).orElseThrow().recipe());
        assertTrue(index.resolveVisible(hiddenRecipe.get()).isEmpty());
    }
}
