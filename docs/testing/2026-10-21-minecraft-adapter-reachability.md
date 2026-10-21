# Minecraft adapter reachability player checks

Date: 2026-10-21.

These checks exercise the [Minecraft adapter reachability specification](../specs/2026-10-21-minecraft-adapter-reachability.md) in the real client. They rerun the failed checks of the [completion checks](2026-10-21-minecraft-adapter-completion.md) and add checks for effect sources. The agent running them acts as the player through the Minecraft window using computer use. The agent runs Java, Gradle and automated tests separately; none of them belong in these checks.

## Prompt iteration

Several checks depend on assistant instructions and tool descriptions, which are prompt changes. Run every check twice:

1. Candidate prompt. Paste the candidate whole prompt into the developer override in the in-game editor (middle-click the settings gear), keep Minecraft open between candidates, and run the checks. Record the prompt state as `override:<candidate name>` for every result.
2. Installed default. After the tested whole prompt is promoted to the code default, install the built JAR, clear the developer override (confirm `config/packsage.properties` has no override), and run every check again. Record the prompt state as `default`.

Record both runs separately in the validation record. A check is accepted only when it passes on the installed default. Passing with an override shows the candidate works, but does not establish the default.

## Before you start

1. Install the built PackSage JAR through the normal closed-instance copying workflow, then start Minecraft and note the JAR hash.
2. Open the existing singleplayer world. It allows cheats, so use `/give`, `/effect`, `/summon`, `/setblock`, `/clear` and creative mode for setup.
3. Open a new PackSage chat for each check unless the check says otherwise, so earlier answers cannot supply evidence.
4. Every chat command must be under 256 characters. Measure each command before sending it; the commands below were measured. Where several entities of one type exist, address them by CustomName.
5. The singleplayer world pauses while the PackSage screen is open. Any check that waits for time to pass closes PackSage while waiting and reopens it afterward.
6. Mount animals with a normal right-click. `/ride` did not register as a ridden target in the completion run.
7. For every check record the prompt state, the question, the tool calls (registry, recipe, inventory and `read_evidence` calls), the returned evidence, the source pills and links, and the reply. A passing reply is grounded in fresh evidence from those calls, not in remembered game knowledge, and includes useful clickable links where the tools supplied targets.

## Reruns of completion checks

### 2. Eligibility, held item

Setup: `/give @s minecraft:diamond_pickaxe[enchantments={levels:{"minecraft:efficiency":3}}]` and hold it in the main hand. Ask: `Which enchantments can still go on the pickaxe I'm holding?`

Look for a `search_inventory` call with an `item` target (or a `read_evidence` call on the `inventory:` handle of the held slot) returning the item record with `enchantmentEligibility` containing `enchantable`, `supported` and `status`. The reply lists supported enchantments such as Unbreaking, Mending, Fortune and Silk Touch, does not misreport Fortune and Silk Touch as conflicting with Efficiency (they conflict only with each other), and shows Efficiency as already present at level 3. An already-enchanted item reports `enchantable: false` (the vanilla enchanting-table rule) while `supported` still lists what an anvil could apply; the reply must not claim nothing more can be added.

Repeat in the same chat with a plain diamond sword in hand: run `/give @s minecraft:diamond_sword`, select it, and ask the exact same question. The reply must follow the held sword (a fresh item read showing no existing enchantments) and must not keep describing the pickaxe.

Failed before because: the item target returned unavailable and the assistant never read `enchantmentEligibility`.

### 4. Active effects (regression)

Setup: `/effect clear @s`, then `/effect give @s minecraft:speed 40 1`. Ask: `What effects do I have and how long are they left?`

Look for a `search_registry` call with registry `active_effect` returning an `active_effect:minecraft:speed` record with `durationTicks`, `amplifier` 1 and `attributeModifiers`. The reply names Speed II and a remaining time consistent with the seconds elapsed.

Then close PackSage, leave the game running for at least 45 seconds, reopen the same chat and ask the same question. Expect a fresh `active_effect` search or `read_evidence` call, not a replay of the earlier record, and a reply that no longer lists Speed or says there are no active effects. A `read_evidence` on the expired handle returns the unknown-reference result. If the assistant only does a fresh search, record that `read_evidence` on the expired handle was not exercised, and optionally ask it to reread the earlier source.

### 7. Recipe previews and opened menus

Crafting table. Setup: `/give @s minecraft:crafting_table`, `/give @s minecraft:oak_planks 3`, `/give @s minecraft:stick 2`, then `/setblock ~ ~-1 ~ minecraft:crafting_table`. Open the table, arrange a wooden pickaxe in the grid (planks on the top row, sticks in the middle column below) and leave the screen open with the preview visible. Open PackSage over the screen. Ask: `What's in this crafting table?`

Look for the gameplay context to carry `openedMenu` (the crafting menu type, with a title when it is plain text of at most 64 characters), and for a `search_inventory` call with target `opened` returning an opened-menu observation whose details list `excludedProjections` containing the result slot as `recipe_preview`, with `COMPLETE` coverage. The reply lists the grid ingredients (3 oak planks, 2 sticks) and does not count the wooden pickaxe output as stored. If the assistant first reads the `targeted` block instead, the result should carry the `detail` hint `an opened menu is available; use target opened for its contents`, and the assistant should follow it. Record whether it did.

Stonecutter. Setup: `/give @s minecraft:cobblestone 4`, `/give @s minecraft:stonecutter`, then `/setblock ~ ~-1 ~ minecraft:stonecutter`. Open it, put the 4 cobblestone in the input, select the stairs recipe and leave the screen open. Ask: `What's in this stonecutter?` The output slot (menu slot 1) must also be excluded as `recipe_preview`, and the reply lists only the 4 cobblestone as input.

Failed before because: the assistant read the `targeted` block, got unsupported, and told the player to open the screen that was already open.

### 8. Brewing by name

Setup: none. In a new chat, with no hints about internals or item IDs, ask: `How do I brew a Potion of Strength?`

Look for a `search_recipe` call whose result titles use localized names (for example `Potion of Strength brewing transformation`) and whose `detail` shows the scan position (`scanned=... phase=... matches=...`). If a page is empty with a cursor, the assistant continues instead of concluding. The reply gives the input potion, reagent and result. This rerun also checks reachability: a request for a known output with quantity or plan intent should use `plan_recipe` (see 8b), and a plain "how do I" question should not spend an excessive number of paging calls. Record the number of `search_recipe` calls and the end-to-end time. The completion run made 24 calls and took about 2 minutes 20 seconds, so a count near that figure is a failure of choosing queries before paging, even if the answer is correct.

Failed before because: the check expected a `plan_recipe` follow-through that never happened, and the search paged through 24 calls.

### 8b. Brewing plan

Setup: none. In a new chat ask: `Plan how to brew 3 Potions of Strength.`

Look for a `plan_recipe` call whose brewing route record reports `batches: null`, `producedQuantity: null` and the `operationSemantics` text `brewing: up to 3 bottles per operation, one reagent per operation; operation count depends on bottle grouping and is not computed`. The reply explains that up to three bottles brew per operation with one reagent and does not state a computed batch or operation count as fact. In particular it must not say "use one three-bottle batch" or give a computed number of operations. The `plan_recipe` call may be preceded by lookups needed to resolve the output item; a plan with no `plan_recipe` call at all is a failure, whatever the reply says.

Failed before because: the assistant answered from recipe search results alone and stated a three-bottle batch as fact.

### 10. Targeted donkey and mule inventories

Setup: `/summon minecraft:donkey ~ ~ ~ {CustomName:'"Donkey"',NoAI:1b,Tame:1b,ChestedHorse:1b,SaddleItem:{id:"minecraft:saddle",count:1},Items:[{Slot:2,id:"minecraft:diamond",count:5}]}` and `/summon minecraft:mule ~3 ~ ~ {CustomName:'"Mule"',NoAI:1b,Tame:1b,ChestedHorse:1b,SaddleItem:{id:"minecraft:saddle",count:1},Items:[{Slot:2,id:"minecraft:iron_ingot",count:7}]}`. If an earlier Donkey or Mule exists, remove it first with `/kill @e[type=minecraft:donkey,name=Donkey]` and `/kill @e[type=minecraft:mule,name=Mule]`, because duplicates make targeting ambiguous.

With no screen open, look at Donkey so that it is the targeted entity and ask in a new chat: `What is this animal carrying?` Repeat in a new chat for Mule. Confirm the gameplay context names the intended entity as `targeted` before accepting the result.

Expect a `search_inventory` call with target `targeted` for each animal, returning a saddle section holding the saddle and a cargo section holding 5 diamonds (Donkey) or 7 iron ingots (Mule), with `COMPLETE` coverage. The reply names the same contents and does not report an armor section. A reply built from the screenshot alone (for example "carrying a chest, contents unknown") with no inventory call is a failure, even when the context shows the entity targeted.

Failed before because: the Donkey case made no tool call and answered from the image; the Mule case passed.

## New checks

### 14. Luck source

Setup: `/effect clear @s`, `/effect give @s minecraft:luck 120 2`, then `/give @s minecraft:golden_helmet[attribute_modifiers={modifiers:[{type:"minecraft:generic.luck",amount:3,operation:"add_value",id:"packsage:test_helmet_luck",slot:"head"}]}]`. Wear the helmet (equip it in the head slot). The effect lasts 120 seconds and the world pauses while PackSage is open, so ask promptly.

Ask: `Where does my Luck come from?`

Look for a `search_registry` call with registry `player_attribute` returning the `player_attribute:minecraft:generic.luck` record, with `modifiers` listing `minecraft:effect.luck` and `packsage:test_helmet_luck`, `base`, `value`, and `syncable`. The effect's contribution is whatever the record's `minecraft:effect.luck` modifier `amount` states; the reply uses the recorded amounts, not remembered ones. Expect `value` to equal `base` plus the listed modifier amounts. The reply attributes +3 to the helmet (`packsage:test_helmet_luck`) and the rest to the Luck effect, and says Minecraft does not record who or what applied the effect, so the effect's origin cannot be named from the game. Record the `syncable` value and the record's coverage (`PARTIAL` is correct when `syncable` is false). The reply may also use `active_effect` and a tooltip search of equipped items to narrow the source. It should not invent a source for the effect, such as a beacon or a potion, without evidence (an `ambient` flag or a duration pattern).

A second look in a fresh chat after `/effect clear @s` should show the effect modifier gone and only the helmet modifier remaining, confirming the read is fresh.

### 15. Effect description

Setup: none. Ask: `What does Luck do?`

Look for a `search_registry` call for `mob_effect` (or `active_effect` when one is active) returning the Luck record. Pass in either of two cases, and state which case was observed, because the minimal profile may not include an effect-description mod:

- Description present. The record has `description` and `descriptionSource: "language:<key>"` (the key being `<descriptionId>.description`). The reply uses that text as the game's description and cites it, before any external source.
- Description absent. The record has no `description` and carries `descriptionStatus: "not_provided"`. The reply handles this without inventing a game description: it may explain Luck from the record's declared attribute modifiers (the `generic.luck` modifier with its per-amplifier amounts) and say that no in-game description was provided, but must not present remembered text as the game's description. It does not need a web search to pass.

If `descriptionStatus: "bounded"` appears, the reply notes the description was truncated at 1,024 characters.

### 16. Held item, enchantment suitability

Setup: `/give @s minecraft:diamond_sword` and hold it in the main hand with no other enchanted sword selected. Ask: `What enchantments can go on this sword?`

Look for a `search_inventory` call with an `item` target (selected or held item) returning the item's own record in an `item` section, with `enchantmentEligibility`. The call is not allowed to return unavailable because the item is not a container. The reply lists the enchantments from `supported` (for example Sharpness, Smite, Looting, Unbreaking, Mending), notes the mutually exclusive damage enchantments, and does not answer from remembered game knowledge or from the screenshot alone.

Then select an empty hand slot and ask the same question. Expect an empty-selection result and a reply that says nothing is held, not an invented item.

## Recording

Record the actual artifact hash, profile, prompt state (`override:<candidate name>` or `default`), questions, tool and evidence observations, source pills and remaining limits in a document under `docs/validation/` with a date-prefixed name. Record failures separately from passing cases, and record candidate-prompt results separately from installed-default results. A passing answer does not establish behavior for third-party adapters or server-side rules the client cannot read, and it does not establish the effect-description case that was not observed.
