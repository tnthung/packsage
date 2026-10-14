# Behavior-driven Toolbox live checks

Use only `OoO - PackSage Trial` in the existing Packsage instance. The original `OoO` world is outside this test scope. Build, automated checks and JAR installation are performed by the assistant through `scripts/build.ps1`; the checks below verify the installed game through the computer-use skill.

These instructions supersede the old captured-menu workflow in [inventory and storage inspection](2026-10-14-inventory-storage-inspection.md). Expected behavior is defined by the [tool specification](../specs/2026-10-14-behavior-driven-tools.md). Record observed results separately in `docs/validation/2026-10-14-behavior-driven-toolbox.md`; this checklist is not evidence that a check passed.

## Catalog and fresh reads

Open PackSage with the configured shortcut. Keep the existing model, reasoning, search and screenshot preferences. Request individual tool families explicitly so the saved replay can establish the actual arguments and returned evidence.

The catalog contains `search_inventory`, `search_document`, `search_recipe`, `search_registry`, `read_evidence`, `read_progress`, `plan_recipe`, `list_configs`, `search_config`, `read_config`, `search_history` and `read_history`. The old `search_pack`, `read_inventory` and `read_storage` entries are absent.

Every call, including a continuation or an observed handle read, queries the current source. A cursor identifies the query, owner and next position; a reference identifies the source location. Neither contains previously returned contents. Pagination is a traversal of changing data, so changing the source between pages can change results. Unsupported or lost access must be explicit rather than answered from an earlier body.

## `search_inventory(target, filter?, cursor?, limit?)`

1. Ask for the player inventory with a small page limit, then continue with the returned cursor. Verify exact item IDs, counts, occupied slot locations and equipped armor against the inventory screen. Duplicate stacks remain separate.
2. Record an observed player slot reference. Move a different existing stack into that slot, then query it again. The same location must resolve its current stack. Restore the original arrangement after checking.
3. Open an existing vanilla container and press the PackSage shortcut while it is open. Search target `opened`; the underlying menu must remain synchronized. Return to the container, change an existing stack, reopen PackSage and search again. The new count or stack must appear. After closing the menu normally, a new opened-target call must report unavailable access.
4. Hold an existing bundle or shulker box and search target `item` with slot `mainhand` or `offhand`. Check direct contents or observed emptiness. An unsupported held item is unavailable, not evidence of an empty container. Restore the original held item afterward.
5. In private singleplayer, aim at an existing native chest and search target `targeted`. Check the server-read authority, current direct stock and target identity. No interaction, menu opening, loot generation or chunk loading should occur. Locked, blocked or pending-loot targets are unavailable.
6. If an existing suitable animal or vehicle is available, check a targeted chest boat, chest minecart or tame chest-bearing mount, then its directly ridden inventory. Dismounting or changing mounts invalidates the previous owner. Do not manufacture acceptance evidence for an unavailable fixture.
7. Exercise exact `ids` and `tags`, case-insensitive literal `names`/`tooltip`, and `sections`. Values within a field are alternatives; separate fields combine. Ghost slots, recipe previews and craftable-only projections do not count as stock. Unknown custom menu semantics remain partial.

The targeted/ridden endpoint is limited to an unpublished integrated server with one connected player whose UUID is the integrated-world owner. It does not claim compatibility with every protection mod's interaction policy. Remote or published sessions need a separate authorized adapter endpoint.

## `search_registry(registry, filter?, cursor?, limit?)`

Query `item`, `block`, `item_tag`, `enchantment`, `mob_effect` and `potion`, using exact familiar native IDs where possible. Check at least one modded entry and a continuation. These results describe registry definitions; active effects and stack enchantment/potion components remain instance evidence. Tag membership filters apply only to registries with that supported meaning; an unsupported filter must fail explicitly.

## Documents, recipes and references

- `search_document(query, filter?, cursor?, limit?)`: search a current FTB quest and Patchouli guide. Use a small limit, continue, and inspect source identity and partial coverage. Large nested content must stop at the work budget with a continuation or explicit extraction limit, rather than being fully copied first.
- `read_evidence(handles)`: reread observed quest, guide, registry, recipe or config evidence and verify its source identity. Unknown or stale handles must fail. A mixed batch must dispatch each handle to its owning adapter.
- `read_progress(handles)`: read current FTB team progress for observed quest/chapter handles. Unavailable progress returns a current status; it must not replay an earlier completion value.
- `search_recipe(filter?, cursor?, limit?)`: use an exact output item with a native recipe, then an installed JEI recipe. Check that unrelated outputs are excluded and recipe alternatives/unknown fields retain their meaning.
- `plan_recipe(identity, quantity, depth?)`: request a small known recipe plan. Check counts and partial routes against current recipe evidence. A useful decoded field in a partially decoded recipe must not be discarded solely because another field is unknown.

## Config and history

- `list_configs(limit?, cursor?)`, `search_config(text, limit?, cursor?)`, `read_config(handle, key?, section?, limit?)`: discover and read an observed config key. Saved and currently loaded values remain distinct; a subsequent call rereads both sources available to the adapter.
- `search_history(text, limit?, cursor?)`, `read_history(handles, maxChars?)`: find a prior trial conversation and read its observed handle. Start another request or enter another world and verify scope separation. Replacing a request excludes its own prior answer according to the existing history contract.

Restore any moved inventory stacks and original selection. Save and leave the trial world normally if a restart is needed. Record fixture limits, exact installed artifact hash, actual tool calls, automated results and live observations without treating one form of evidence as another.
