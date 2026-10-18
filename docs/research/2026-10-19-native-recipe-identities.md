# Native identities for raw JEI recipes

The normal-player Diamatine trial found a remaining persistence limit: its recipe link opened in-session but failed after restarting Minecraft. Saved target metadata contained the temporary Java class/object-hash locator. Normal crafting entries in the same conversation already carried stable `recipe-v1` destinations.

The installed binaries provide a genuine identity without matching recipe contents:

- `actuallyadditions-1.3.26+mc1.21.1.jar`: `de/ellpeck/actuallyadditions/mod/jei/laser/LaserRecipeCategory.class` implements `IRecipeCategory<LaserRecipe>` and does not override `getRegistryName`. Its `LaserRecipe` implements native `Recipe<RecipeInput>`.
- The same JAR's `de/ellpeck/actuallyadditions/mod/jei/JEIActuallyAdditionsPlugin.class` reads native recipe holders and registers their exact `RecipeHolder.value()` objects with JEI. Installed `javap -v` output identifies that method reference in bootstrap entry 2.
- `data/actuallyadditions/recipe/laser/crystalize_diamatine_crystal.json` gives the native ID `actuallyadditions:laser/crystalize_diamatine_crystal`, diamond-tag input, Diamatine output and energy 60.
- `jei-1.21.1-neoforge-19.64.0.455.jar`: the default `mezz/jei/api/recipe/category/IRecipeCategory.getRegistryName` returns `RecipeHolder.id()` for holder objects and null otherwise. Unwrapping the holder therefore loses JEI's default name even though the native registry retains it.
- The resolved Minecraft source at `.tools/task4-sources/net/minecraft/world/item/crafting/RecipeManager.java` returns current `byName.values()` from `getRecipes()` and replaces its indexes in `replaceRecipes`.

The JARs inspected are under `C:/Users/hung1/curseforge/minecraft/Instances/Packsage/mods`. This is evidence for the exact installed versions, rather than a guarantee about every mod's registration strategy.

Use the explicit JEI registry name when present. Only for a null name on a native `Recipe<?>`, join the raw recipe to current native holders using `holder.value() == recipe`. One object with multiple native IDs is ambiguous. Build one lazy `IdentityHashMap` per capture, not a runtime cache or a native scan per JEI entry. Bound native enumeration at 65,536 holders with its own work-accounting key; exactly the limit may complete, while an unfinished scan discards all fallback results. Existing category/ID uniqueness, visibility and current-registration checks still apply.

Do not use `equals()`, output matching or semantic fingerprints. The old saved object hash contains no recoverable native ID and must remain unavailable. Newly emitted links can use the existing durable destination format; recipes genuinely absent from native holders and lacking JEI names stay temporary.

Acceptance requires raw-object discovery, reopening recreated objects with the same native ID, explicit JEI-ID precedence, ambiguous aliases, missing identity membership, same-content different objects, reload replacement, native-scan exhaustion and unchanged anonymous-object behavior. Installed proof must generate a new laser citation, restart Minecraft and open that saved citation; the previously saved hash-only link is not the acceptance target.
