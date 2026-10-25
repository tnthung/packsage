# PackSage v2: completing the Minecraft adapter

Date: 2026-10-25.

Extends the [v2 world model](2026-10-22-v2-world-model.md). That specification still defines progress, statistics, the journal, notes and the prompt. This one adds what the Minecraft adapter needs before `feat/packsage-v2` merges back: one item-reading path instead of two, and the world knowledge the integrated server holds that no tool reads yet. Tracking stays in the [v2 note](../roadmap/2026-10-22-v2-minecraft-only.md).

The world model's rules apply throughout:
- singleplayer reads on the integrated server, paced across ticks;
- nothing in the world changes because PackSage read it;
- no policy limits;
- coverage on every result;
- persistent navigation for links.

## 1. One item-reading path

The legacy `search_inventory` tool (targets player, item, opened, targeted and ridden) overlaps `list_items`, `inspect_item` and `find_items`. During the live rounds, the model mixed the two paths and skipped the newer item facts. v2 keeps only the world tools. `search_inventory` and its readers are removed once the world tools cover everything below.

### Holders the model can name without an id

`list_items(holder)` and `inspect_item(path)` accept these words as a path root, besides `player`, `block:<place>` and `entity:<uuid>`:

| Word | Resolves to | When it cannot resolve |
|---|---|---|
| `held` | The player's selected hotbar item, as an item path: `inspect_item("held")` reads it, `list_items("held")` lists a held container or bundle, and steps can follow, such as `held>minecraft/container#0@…` | Empty hand: a result that says the hand is empty |
| `offhand` | The player's offhand item (an item path, like `held`) | Same |
| `targeted` | The block or entity under the crosshair | Nothing targeted: a result saying so, never an error |
| `ridden` | The entity the player rides | Not riding: a result saying so |
| `opened` | The menu the player has open | No menu: a result saying so |

The words resolve once, at call time. Returned paths use the concrete root (`block:…`, `entity:…`, `player:…`), so links and saved references stay stable. `opened` is the exception, because a menu has no stable identity. Its paths are valid only while that menu stays open, and they produce no link.

Gameplay context also gives the targeted place or entity UUID and the ridden entity UUID, so the model can name them directly too.

### Opened menus

`opened` reads the server player's open container menu, not the client's copy.

- **Sections:**
  - Non-player slots are grouped into sections by the slot's container, named as for the block or entity that owns them, for example `minecraft/container`, `minecraft/input` or `minecraft/saddle`.
  - Menus without a backing block, such as crafting tables, stonecutters, anvils, grindstones, looms, smithing tables, enchanting tables and cartography tables, use `minecraft/menu`.
  - The menu's own player-inventory slots are left out. `player` reads those.
- **Preview slots excluded:** result and preview slots, such as crafting output, stonecutter output, anvil, grindstone and smithing results, and merchant results, are not stored items. They are excluded from contents and listed by index under `excludedSlots`, as `search_inventory` does today.
- **Facts:** the result carries `menuType` (the menu type registry id) and the screen title when there is one.

### Player listing

- `minecraft/main` entries carry their slot index. The entry in the selected hotbar slot is marked `selected`.
- `minecraft/armor` entries carry `equipmentSlot` (`feet`, `legs`, `chest`, `head`).
- Every entry reports `emptySlots` for its section where slots apply.

### Item facts in `inspect_item`

`inspect_item` gains everything the legacy item record had:

- Raw item components in their encoded form, bounded like other payloads.
- A component fingerprint, so two stacks of the same id can be told apart.
- Full potion contents:
  - custom color and duration scale;
  - each effect's amplifier, duration, ambient, visible and icon flags;
  - an `effectsStatus` when the effect list is bounded.
- Tooltip lines, as the game shows them to the player with advanced tooltips off. A tooltip that throws marks `tooltipStatus` unknown instead of failing the read.
- `enchantmentEligibility` on every enchantable item. When nothing applies, the result says so explicitly; it never leaves the field out. The record is PARTIAL when the eligibility read was bounded.

`KindFilter` gains `tooltip`, a list of text fragments matched case-insensitively against tooltip lines. `list_items` and `find_items` accept it, so "what am I carrying that gives Luck?" is one search.

### What goes away

- **Tool:** `search_inventory`, with its schema, description and validation.
- **Read paths:** the `inventory:`, `inventory-summary:` and `storage:` `read_evidence` handles, and their reference aliases. World item handles (`item|…`) are already reread by the world tools.
- **Readers and tests:** `NativeInventoryReader`, `NativeStorageReader`, `NativeServerStorageReader`, `StorageReader`, `knowledge/inventory/InventoryReader`, and the toolbox and registry plumbing for inventory targets, together with their tests.
- **Kept:** `NativeItemFacts` and `NativeEnchantmentEligibility` stay as shared helpers for world reads and registries.

Saved chats keep their recorded sources and links. Only fresh rereads of removed handle kinds report the source as unavailable.

## 2. Review findings from world reading

These minor findings from the world-reading final re-review are fixed in the same work:
- **N2:** the paused-work flag can stay set.
- **N3:** a tick can run on the client thread after the server has stopped.
- **N4:** an early stop can make no progress.
- **N5:** `inspect_area` can drop the entity page's note.

## 3. More world knowledge

### Villager trades

`inspect_entity` on a villager or wandering trader adds its current offers. For each offer: the costs and the result, uses and maximum uses, price adjustments (special price, demand, price multiplier), XP, and whether it is out of stock.

Reading must not create trades. The game creates a villager's offers the first time something asks for them, so an entity whose offers have never been generated reports `offers: "not generated yet"` rather than generating them.

### Loot tables

`read_loot(table?, block?, entity?, place?, cursor?)` returns loot tables as the game defines them:

- **Input:** a loot table id, a block id (its drop table), an entity type id or a loaded entity UUID (its death table, such as a sheep's colour table), or a container `place` (its pending loot table and seed, if it has not been opened).
- **Output:** the table encoded with the game's own codec: pools, rolls, entries, conditions and functions. Item and tag references are readable ids.
- **Bounds:** large tables are bounded with PARTIAL coverage.
- **Missing table:** an unknown or empty table says so.
- **Sources:** tables come from the server's reloadable registries, so data-pack overrides apply.

Reading a table never rolls it. "What can drop from a zombie?" is answered from the definition; the model explains chance from the entries and conditions.

### Locating biomes and structures

`locate(biome?, structure?, center?, radius?)` finds the nearest biome or structure, as `/locate` does, but without generating or loading chunks.

- **Biomes** are sampled from the biome source, which needs no chunks. The result gives a position and the sampling resolution.
- **Structures** are reported in two kinds:
  - *found*: present in a chunk that was already generated;
  - *predicted*: placement and biome checks pass for an ungenerated chunk, so the structure should generate there.

  Each result says which kind it is. Predicted positions use the world seed. A candidate that would need its chunk loaded or generated to confirm stays predicted.
- **Bounds:** the search radius in chunks, defaulting to 100 and capped as the work budget allows. Ids accept tags such as `#minecraft:village`.
- **Output:** results give the dimension, position, distance from the center, and a `world-place|<dimension>|x,y,z` link. A place link draws a marker column at the place, clamped to 128 blocks from the player when farther, because the block there is usually not loaded. Up to 5 results come back, so "the nearest village I already visited" works from one call. Structure heights are estimates, and distances are horizontal, as `/locate` reports them.

### Commands

`search_commands(text?, cursor?)` and `read_command(name, cursor?)` read the server's command tree, the same one the game uses for suggestions, for the player's permission level. Each command lists its usage forms, its argument names and types, and whether the player can run it.

The assistant never runs commands; answers explain what to type. Running commands would change the world, which the world model's anti-goals rule out.

## Acceptance

All in singleplayer, with the default prompt and no developer override:

1. **Held item:** holding a partly enchanted sword, "what enchantments can I still add?" The reply comes from `inspect_item held` and respects exclusions.
2. **Opened menu:** with a crafting table open and items in its grid, "what's in this crafting grid?" The reply lists the grid and leaves out the output preview.
3. **Ridden mount:** riding a donkey with a chest, "what's in my donkey?" The reply lists the cargo through `ridden`.
4. **Tooltip search:** "which of my items gives luck?" One tooltip-filtered search finds the item.
5. **Trades:** at a librarian, "what does this villager sell?" The reply lists its offers and prices. A villager that just took its profession, asked before the next autosave, reports its trades as not generated yet, and asking again does not generate them. The game itself generates offers when it saves a villager.
6. **Loot:** "what does a zombie drop?" The reply comes from the zombie's loot table.
7. **Locating:** "where is the nearest village?" The reply gives a position, says whether it was found or predicted, and nothing generates.
8. **Commands:** "how do I use /fill?" The reply gives its usage from the command tree.
