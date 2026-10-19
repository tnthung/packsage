# Curios equipment and qualified citation validation

Scope: the approved [equipment contract](../specs/2026-10-19-curios-equipment.md), [implementation plan](../plans/2026-10-19-curios-equipment.md) and [API research](../research/2026-10-19-curios-equipment.md).

The supplied ATM10 screenshot exposes two separate problems. The stored answer contains an untranslated provider-qualified temporary citation. The inventory call is `search_inventory` with `target.type=player` and `limit=32`; its complete coverage is explicitly limited to direct vanilla player slots and equipment, so its inability to list equipped Curios is accurate for that build.

The citation regression first reproduced an unexpanded `[native:ref:...]`, then passed all 15 focused decorator tests. Independent review found that an alias-shaped Markdown label could consume the opening bracket before the parser recognized the destination. Both forms require exact current-request alias/provider validation rather than fuzzy source selection.

The native inventory RED run completed ten tests with one expected failure: `curiosSectionFiltersKeepSameIndexTypesDistinct` expected Ring and Necklace but received no records. The separate partial-coverage fixture retained its vanilla item. Implementation, final automated verification and installed acceptance are recorded below as they complete.

Installed Minecraft acceptance is pending. No game settings, inventory, installed JAR or saved conversation has been modified by this task. API inspection and automated fixtures are not live-world synchronization proof.
