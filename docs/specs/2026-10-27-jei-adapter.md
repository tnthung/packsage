# JEI adapter

## Why

Mods add their own machines and rituals, such as a Nature's Aura altar, a Create mixer or an Immersive Engineering press. The game's own recipe data only tells PackSage part of those recipes: native reading takes the item ingredients and one result, and loses byproducts, fluids and the machine. JEI already knows how every mod with a JEI plugin lays out its recipes. With JEI behind it, the model can read a mod machine recipe, plan through it and show it in chat exactly as the player would see it in JEI.

The player also uses JEI's bookmark list beside the inventory to keep track of what they are working on. The model should be able to add to it when asked.

## Goals

- **Read mod machine recipes.** With JEI ready, `search_recipe` reads recipes from JEI: every slot (item and fluid inputs, all outputs, the machine), plus JEI's standard tooltip text, which often carries chance lines.
- **JEI is the default recipe source.** A search that names no source reads only JEI while JEI is ready, otherwise only the game's own recipe data. The model can still name a source.
- **"What uses this item".** A new `inputItem` filter answers uses questions from either source.
- **Plan through mod machines.** `plan_recipe` expands mod machine steps using assumed consumption (below) and marks those steps as assumed.
- **JEI draws the cards.** While JEI is present, every recipe card in chat, vanilla included, is drawn by JEI itself; native cards are the fallback.
- **JEI interaction in chat.** R, U and A over items in cards and chips, and clicks on card slots and chips, behave as they do in JEI.
- **Bookmarks.** The model can add, remove and check item and recipe bookmarks.
- **Persistent links.** JEI recipe links reopen the same recipe after reconnects, reloads and full restarts, following the [persistent navigation contract](2026-10-18-persistent-navigation.md).

## Non-goals

- Per-mod readers for values a mod only draws on screen, such as Create heat or Mekanism energy bars. Later per-mod adapters can add these.
- Listing every bookmark. JEI offers no way to read the whole list; "what did I bookmark?" is unsupported for now.
- Bookmarks on JEI older than 19.53.
- Merging native and JEI results into one record per recipe.
- Recipe transfer (JEI's "+" button that moves items into a crafting grid).
- Support for recipe viewers other than JEI (EMI, REI).

## Anti-goals

- Presenting assumed consumption as fact. Every count that rests on the convention must be visibly marked as assumed, in the records and the plan.
- Counting chance outputs as guaranteed in plan totals.
- A slow JEI search. Item searches must use JEI's own index, not scan every category; the v1 adapter's category scan took about 40 seconds for a torch.
- Hard-linking JEI. PackSage must load and work fully without JEI installed.
- Silent bookmark changes. The model never adds a bookmark of its own idea without offering first, and never removes one except on the player's request.
- Reaching into JEI internals for bookmarks. Bookmark support uses JEI's public API only.

## Decided by the player

- One adapter covering JEI `[19.27, 20)`. A second implementation is only justified by an API break.
- JEI is the only recipe source read by default while it is ready; native is the fallback, with no automatic fallback after a JEI miss.
- `inputItem` exists for both sources, with the same meaning.
- Text search matches JEI category names and item display names through JEI's item-list search. Text inside slots is not matched.
- Mod machine recipes whose slots were read are PARTIAL; OPAQUE is kept for recipes whose slots cannot be read.
- Assumed consumption, as described below, including the container rule and the AE2-style role convention.
- Plans expand through mod machine steps with assumed consumption. Fluids are listed as needs the planner cannot expand. Chance outputs never count toward totals; they are listed as possible extras.
- In a mod machine step only the planned item counts, at its shown quantity and marked assumed guaranteed; the step's other outputs never count (decided after the builder check).
- Hidden JEI recipes are included and marked hidden.
- JEI draws every card while present; oversized cards shrink to a readable minimum, below which the chat shows the caption, the chips and an "Open in JEI" link.
- R opens recipes, U opens uses and A bookmarks over items in cards and chips. Left click on a card slot opens recipes, right click opens uses, and clicking a chip opens that item's recipes.
- JEI links are saved by category and recipe ID; recipes without a stable ID get a link marked temporary.
- One `bookmark` tool with `add`, `remove` and `check`, for items and recipes, requiring JEI 19.53 or newer and reporting unavailable otherwise. The model adds when the player asks and offers when the idea is its own. It removes only when asked.
- Prompt and tool-description changes go through the in-game editor loop and live acceptance as part of this work.
- Core checks run on the Packsage instance with Nature's Aura; its JEI is updated to a 19.6x build for the bookmark checks (player approved). A spot check runs on ATM10 with Create, Mekanism and Immersive Engineering.

## Current state

These facts are from the `feat/packsage-v2` code on 2026-10-27. Paths are relative to `src/main/java/dev/packsage/`.

- **Recipe search.** `search_recipe` takes `filter {text?, outputItem?, provider?}`, `limit` and `cursor` (`model/PackSageTools.java`). `provider` is built from adapters whose `Toolbox.Metadata.recipes()` is true; today only `native`. `KnowledgeService.Discovery` already picks one source for an unqualified recipe search: the first registered recipe source that is enabled (`knowledge/KnowledgeService.java`, around `:146-150`). Registering JEI before native, and having JEI's `enabled()` follow runtime readiness, makes JEI the default without changing Discovery.
- **Records.** `Evidence.RecipePayload(canonicalRecipeId, inputs, outputs, remainders, catalysts, constraints, decodingStatus)`. Slots are `RecipeSlot(groupId, alternatives)`; alternatives carry `type`, `comparisonIdentity`, `components`, `quantity` and `consumption {CONSUMED, REUSED, UNKNOWN}`. `Coverage {COMPLETE, PARTIAL, OPAQUE, UNAVAILABLE, ERROR}`. Native recipe records have empty navigation targets, and `NativeProvider.open` is unavailable.
- **Planner.** `knowledge/RecipePlanner.java` has a pluggable `RecipePlanner.Source`; native's is `NativeRecipeReader.PlanSource`. Only `CONSUMED` item alternatives with a quantity count toward requirements; anything else makes the route PARTIAL, and a non-empty catalyst list makes a route incomplete. `plan_recipe` is not routed by Discovery; the first adapter that handles it wins.
- **Cards.** `client/recipe/RecipeCardSource` is `Optional<RecipeCard> card(RecipeCardModel)`. `RecipeCard` has `width`, `height`, `draw` and `hoverAt`, and no click method. `RecipeCardModel.Category` lists only the vanilla categories. `AssistantScreen` hard-codes `NativeRecipeCardSource`, and `ScreenText.recipeCards` only accepts `native` records. Clicks inside a card panel are swallowed; chip clicks call `navigate`. There is no R, U or A handling.
- **v1 reference.** `origin/feat/packsage-v1` holds the old adapter (`knowledge/jei/PackSageJeiPlugin.java`, `JeiProvider.java`) and its optional-loading pattern (a `ModList` check plus reflective loading, version range check). It is background only; v1 decisions do not carry over.

## JEI facts

From `javap` diffs of `jei-1.21.1-neoforge-19.27.0.340` and `19.64.0.454`:

- No API member a recipe reader needs was removed or changed between 19.27 and 19.64: `IJeiRuntime`, `IRecipeManager` (`createRecipeLookup`, `createRecipeCategoryLookup`, `createRecipeLayoutDrawable`), `IRecipeLookup` (`limitFocus`, `limitTypes`, `includeHidden`, `get`), `IFocusFactory`, `IRecipeCategory` (`getRecipeType`, `getTitle`, `getRegistryName`), `IRecipeLayoutDrawable`, `IRecipesGui`, `IIngredientFilter`, `ISubtypeManager`.
- `IRecipeSlotView.getDisplayedIngredients()` and `getTagKey()` and `IRecipeCategory.getSourceRecipeIds` exist only in later versions; the adapter must not call them. Slot tooltips come from `IRecipeSlotDrawable.getTooltip(...)`, present in both.
- `IJeiRuntime.getBookmarkManager()` returns `IBookmarkManager` from JEI 19.53.0 (`specificationVersion` 19.53.0): `contains`, `add` and `remove` for an `ITypedIngredient` or a `(RecipeType, recipe)` pair. There is no list method. Bookmark changes are saved immediately, per world.
- JEI slot roles are INPUT, OUTPUT, CATALYST and RENDER_ONLY. JEI has no consumed flag, no chance field and no remainder marking. AE2's JEI integration (the AE2 JEI Integration addon on 1.21.1) encodes every INPUT slot as consumed, drops CATALYST and RENDER_ONLY, and counts every OUTPUT at its full amount.

## Design

### Loading

The adapter lives in its own package, loaded only when `ModList` reports JEI in `[19.27, 20)`, through the same reflective pattern v1 used. No core class references JEI types. The JEI plugin holds the runtime across available and unavailable generations; the provider's `enabled()` follows runtime readiness on every call. The JEI provider is registered before native.

### Recipe search

- **`outputItem`**: an OUTPUT focus lookup through JEI's index. For items JEI distinguishes by subtype (potions, enchanted books and similar, reported by `ISubtypeManager`), the lookup covers the item's known subtypes from JEI's ingredient list rather than a single default stack, so a search for `minecraft:potion` finds Strength potion recipes.
- **`inputItem`** (new, both sources): an INPUT focus lookup in JEI, and the matching scan in native. Native's scan honors the same subtype meaning as its existing `outputItem` scan.
- **`text`**: matches JEI category titles, and item display names through `IIngredientFilter`; matching items then become OUTPUT focus lookups. Results are deduplicated per recipe.
- **Hidden recipes** are included and carry a `hidden` constraint.
- **Paging** is bounded per call, with a cursor bound to the JEI runtime generation, as Discovery cursors already are to their source list. A cursor from an earlier runtime generation is rejected with an error that tells the model to search again.

### Records

A JEI recipe record uses the existing `RecipePayload`:

- Inputs come from INPUT slots, outputs from OUTPUT slots and catalysts from CATALYST slots. RENDER_ONLY slots are left out of the payload. Fluid alternatives carry `type=fluid` with millibucket quantities.
- Each slot keeps the tooltip lines JEI adds beyond the ingredient's own tooltip (for example chance lines). The recipe also keeps the category title and any recipe-level tooltip text. Values the category only draws are reported in a constraint as not read.
- Chance outputs are marked when a slot's tooltip states a chance; the chance text is kept verbatim. The adapter does not parse chance from mods that only draw it.
- **Coverage.** Vanilla categories that JEI's built-in plugin draws map to the same decoding native uses, and are COMPLETE when native would be. Mod categories whose slots were read are PARTIAL. A recipe whose slots cannot be read is OPAQUE.
- `canonicalRecipeId` is the recipe's registry ID from `IRecipeCategory.getRegistryName` when it has one.

### Assumed consumption

For mod categories:

- INPUT alternatives are `CONSUMED`, marked assumed.
- CATALYST alternatives are `REUSED`.
- **Container rule.** An item input whose item declares a crafting remainder (the water bucket leaves a bucket) uses up only its contents, and the container is a remainder that returns. One bucket is needed for any number of crafts; the contents are a per-craft need (see Planning).
- Every record and plan step that relies on these rules carries an explicit assumed marker that the model sees, so its reply can say, for example, "assuming the altar consumes them".

### Planning

`plan_recipe` gets a JEI `RecipePlanner.Source` and is routed so that it plans from the default recipe source, JEI while ready and native otherwise, with the same provider override as search.

- Assumed CONSUMED inputs count toward requirements. Steps that used them are marked assumed, and the plan's coverage reflects that.
- REUSED catalysts are listed once per plan as needed equipment, not multiplied by batches; they no longer make the route incomplete when they come from the convention.
- Fluid requirements are listed with their total millibuckets as needs the planner does not expand.
- **Only the planned item counts.** In a mod machine step, the output matching the item being planned counts at its shown quantity, marked assumed guaranteed, unless its tooltip states a chance. Other outputs of the step never count toward any requirement in the plan; they are listed as possible extras. Many mods only draw chances on screen, so a byproduct cannot be told apart from a guaranteed result. Example: for 16 iron dust from a grinder showing "1 iron ore → 2 iron dust + 1 gold dust", the plan grinds 8 ore, assuming 2 dust each, and lists gold dust as a possible extra even if the plan also needs gold dust.
- An output whose tooltip states a chance never counts toward totals; it is listed as a possible extra.
- **Containers are not refilled by the planner.** Under the container rule, the plan reports the filled-item uses (for example "water bucket's contents ×8") as needs it does not expand, plus the container once as reused equipment. It does not build routes that refill a returned container (design call after the builder check, to avoid reworking the planner for one narrow case).

### Cards

- A JEI card source wraps `IRecipeLayoutDrawable` (`setPosition`, `drawRecipe`, `drawOverlays`, `getItemStackUnderMouse`). While JEI is present it is tried first for every card, vanilla included; a native record is matched to JEI's recipe by recipe ID. The native source is the fallback.
- `ScreenText.recipeCards` accepts JEI records as well as native records.
- **Oversized cards** scale down to fit the chat column, down to a readable minimum. Below the minimum, the card is replaced by its caption, the item chips and an "Open in JEI" link.
- The caption rule from the recipe-card spec applies to JEI cards too: one line under the card naming the station or category.

### Interaction

`RecipeCard` gains click and key handling for the item under the mouse:

- R opens the item's recipes, U opens its uses and A toggles its bookmark, over card slots and over item and result chips.
- Left click on a card slot opens recipes; right click opens uses.
- A click on an item chip or result chip opens that item's recipes.
- Without JEI, none of these do anything, as today.

### Navigation

JEI recipe links store the category's recipe type ID and the recipe's registry ID, and resolve against the current JEI runtime when clicked, so a saved link reopens the same recipe after reconnects, reloads and full restarts while the pack and recipe remain. A recipe with no registry ID gets a link marked temporary. Item links open the item's recipes by item ID and components.

### Bookmarks

A new `bookmark` tool, offered only while JEI 19.53+ is ready:

- `action`: `add`, `remove` or `check`.
- Target: an item ID with optional components, or a recipe reference returned by an earlier `search_recipe`.
- `check` returns whether that item or recipe is bookmarked.
- On older JEI or when JEI is not ready, the tool is not offered; a saved call reports unavailable with the reason.
- Policy (prompt): add directly when the player asks to bookmark; offer, without adding, when the idea is the model's own; remove only on the player's explicit request.

### Prompt

The `search_recipe` and `plan_recipe` tool descriptions and the system prompt describe JEI as the default recipe source, `inputItem`, mod machine recipes and their assumed consumption, and the `bookmark` tool and its policy. The changes are reviewed with the whole prompt as one decision policy, iterated through the in-game editor and promoted after live acceptance, as the repository rules require.

### Roadmap

- The v2 note records that native card and chip clicks do nothing until this adapter lands.
- Native's remaining unchecked items stay unchecked; the note records that native work is closed for now and those items are deferred.
- The tool roadmap and adapter feature checklist gain JEI v2 entries under the adapter-status grouping.

## Left to the builder

- Page sizes, per-call budgets and subtype-expansion bounds, within the performance anti-goal.
- The readable minimum card scale.
- Exact constraint and marker field names in records and plans, provided the model can see "assumed", "hidden", "chance" and "not read".
- How JEI's runtime readiness is observed and how the runtime generation is tracked.
- How plan routing reaches the default source (Discovery routing or registration order).
- Whether the native `inputItem` scan shares code with the `outputItem` scan.

## Acceptance

Automated:

- Record projection from fixture JEI layouts: roles, fluids, catalysts, hidden, tooltip lines, chance marking, coverage per category kind.
- Assumed consumption and the container rule, including the one-bucket-for-eight-crafts case.
- Planner totals with assumed steps, reused catalysts counted once, fluids and container contents listed unexpanded, chance outputs excluded, and only the planned item credited in a mod machine step.
- Default-source selection and cursor rejection across runtime generations.
- `bookmark` offered only with JEI 19.53+; argument validation.
- PackSage loads and the full test suite passes without JEI on the classpath.

Live, Packsage instance (JEI updated to 19.6x for bookmark checks):

- A torch and a Strength potion search each answer from JEI on the first call, without a category scan.
- A Nature's Aura altar recipe is found, read as PARTIAL with assumed consumption, shown as a JEI-drawn card, and planned through.
- An `inputItem` question ("what uses gold powder?") answers from JEI.
- R, U, A and clicks over cards and chips open recipes, uses and bookmarks as specified.
- Saved JEI links reopen the same recipe after a full Minecraft restart.
- `bookmark` add on request, offer-not-add on the model's own idea, check, and remove on request; bookmarks appear in JEI's list.
- With JEI removed while Minecraft is closed, the same recipe questions answer from native with no JEI note, and cards are native.
- The prompt candidate passes the live question set, then the installed default with the developer override cleared.

Live, ATM10 (JEI 19.64): spot checks of one Create, one Mekanism and one Immersive Engineering recipe for slots, fluids, catalysts, card drawing and an assumed-consumption plan.
