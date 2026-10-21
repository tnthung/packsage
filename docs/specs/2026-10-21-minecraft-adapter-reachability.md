# Minecraft adapter reachability and effect sources

Date: 2026-10-21.

Follows the [Minecraft adapter completion](2026-10-21-minecraft-adapter-completion.md) specification. Its [validation record](../validation/2026-10-21-minecraft-adapter-completion.md) and a manual ATM10 test found two gaps. First, several delivered reads were never reached because the assistant chose another tool. Second, the assistant could not explain modded effects or trace a bonus to its source without the web. This specification closes both gaps for the Minecraft adapter. The [behavior-driven tool design](2026-10-14-behavior-driven-tools.md) and the completion specification's shared rules still apply. Command support and third-party adapters remain out of scope.

## Held and selected item facts

`search_inventory` with an `item` target currently returns unavailable when the selected item is not a container. It now returns the selected item's own record first, in an `item` section. The record has the same payload as a player-inventory record, plus `enchantmentEligibility`, and its coverage follows the existing rules. Container contents, when present, follow as today. A selected empty hand still reports an empty selection. The `read_evidence` paths keep their eligibility field.

## Open menus in gameplay context

The gameplay context gains `openedMenu`: the open container menu's type ID, or `none`, when PackSage was opened over a container screen. Its title is included when it is plain text of at most 64 characters.

A `targeted` read of a block whose inventory is unsupported adds a `detail` hint when a live opened menu exists: `an opened menu is available; use target opened for its contents`. Coverage and outcome are unchanged.

## Player attributes

`search_registry` gains the `player_attribute` selector, beside `active_effect`. It enumerates the client player's current attribute instances. Every attribute in the client's attribute map is included, with `syncable` stating whether the server keeps it current on the client. `text`, `ids` (attribute IDs) and `tags` (attribute holder tags) filter as for other selectors. Records are sorted by attribute ID.

Each record has handle `player_attribute:<attribute id>` and payload:
- `attribute`, `name` (localized), `syncable`;
- `base`, `value` (the current computed value);
- `modifiers`: `{id, amount, operation}`, sorted by ID and capped at 64, with `modifiersStatus: "bounded"` beyond that.

The modifier `id` is the source key the game itself uses, for example `minecraft:effect.luck` for a status effect. A record is `COMPLETE` when `syncable` is true, and `PARTIAL` otherwise, because the client value may be stale. `read_evidence` rereads the current instance; an attribute the player no longer has returns the unknown-reference result.

## Effect descriptions

`mob_effect` and `active_effect` records add `description` when the current language has a translation for `<descriptionId>.description`, the convention used by effect-description mods and many effect mods. They also add `descriptionSource: "language:<key>"`. With no such translation the field is absent and `descriptionStatus: "not_provided"` is set. Formatting codes are stripped and the text is capped at 1,024 characters (`descriptionStatus: "bounded"`).

A weaker instance stacked under a stronger one is not sent to the client, so it is not reported.

Minecraft does not record which item, block or mod applied an effect, so there is no source field. The assistant instructions say so, and narrow the source using:
- `player_attribute` modifier IDs;
- `ambient` (beacon-like sources);
- a short duration that is repeatedly refreshed (equipment-like sources);
- a tooltip search of equipped and carried items.

## Assistant instructions and tool descriptions

These changes follow the repository's prompt rules. Iterate them in the in-game editor, validate them live, promote them to the code default, then verify the default with the override cleared. They extend the existing whole-prompt policy:

- An item question names a selected or held item: read it with the `item` target, or `read_evidence` on its inventory slot record's reference, before answering. Enchantment suitability comes from `enchantmentEligibility`.
- `openedMenu` in the gameplay context means its contents are read with target `opened`.
- Inventory and contents questions need an inventory tool read, even when the screenshot shows the entity or container.
- A plan, quantity or "how many" request for a known output uses `plan_recipe`. Brewing operation counts are not stated beyond the plan's `operationSemantics`.
- Effect questions:
  - read `active_effect`;
  - use `description` when present before external sources;
  - use `player_attribute` for bonus totals and modifier sources;
  - state that Minecraft does not record an effect's source.

## Live acceptance

Repeat the failed checks 2, 7, 8, 8b and 10 of the [completion checks](../testing/2026-10-21-minecraft-adapter-completion.md), plus check 4 as a regression. Add:
- a modded or described effect explained from its language description;
- a Luck total traced to its modifier IDs;
- an attribute bonus from an equipped item traced to that item's modifier ID.

The [testing instructions](../testing/2026-10-21-minecraft-adapter-reachability.md) list them.
