# Curios equipment inspection

The player can ask which relics or accessories they are wearing. Extend the existing `search_inventory` player target with functional equipment exposed through a compatible Curios API. The approved scope excludes cosmetic slots and item movement.

## Contract

- Include occupied functional Curios slots in an unfiltered player read. Preserve item identity, name, count and merged component metadata through the existing item projection.
- Identify each location with section `curios:<slot type>`, its type-local zero-based slot index and equipment slot type. `filter.sections` accepts `curios` for all functional slots or the exact `curios:<slot type>` section. Existing vanilla section selection retains its behavior.
- Read current handlers on the client thread for every initial query, continuation and evidence/reference lookup. Copy stacks before projection. A reference identifies its observed location, not permanently the item that once occupied it; removed types and out-of-range indices become unavailable.
- An absent Curios mod leaves ordinary vanilla reads complete within their reported scope. An explicit Curios-only request without compatible support is unavailable. A loaded but unsupported or unreadable integration reports unknown accessory coverage while preserving successfully read vanilla records; it cannot establish that no relics are equipped.
- Use the functional `getStacks()` handler, never cosmetic stacks or rendering flags as evidence that an item is equipped. Preserve slot type distinctions when two types have the same index.
- Support the verified Curios API family with a version range, preserve the actual loaded version, and expose one Curios entry in the hardcoded adapter list. Other accessory systems are covered only to the extent that they expose their equipment through that Curios API.
- Reuse the current item, pagination, scope, output-size and reference-expiry contracts. This does not add a model tool, target type, equipment mutation, event listener or ability/progression interpreter.

## Acceptance

Automated checks cover distinct slot types, section filtering, functional-only reads, fresh changed equipment, unavailable/failed integration, bounds and current reference resolution. Run the focused suites and full automated build without formatting tools. Record API evidence and results separately from installed Minecraft acceptance.
