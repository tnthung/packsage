# Current relic progression and rolled stats

The player approved exposing level, XP, rank/points and rolled ability values from the actual equipped relic through existing fresh item evidence. The [Curios equipment reader](2026-10-19-curios-equipment.md) supplies the location; generic serialized components alone do not establish effective Relics stats.

## Contract

- Enrich supported Relics item records in player inventory searches and fresh `read_evidence` reads. Preserve the existing tool, native slot reference, item identity, components, location and source controls. Include carried relics through the same item-record path.
- Add `relicInstance` with actual API version, coverage, level, experience, rank, points and an `abilities` map. Each ability exposes ability points and a `stats` map containing effective `value` and `quality` when readable. Use runtime ability/stat identifiers. Never label ability points as relic level or raw quality as effective value.
- Include numeric zero when a successful getter returns zero. Omit failed fields and report unknown or partial stats coverage; preserve readable item identity and ordinary inventory contents. Missing optional Relics support must not degrade unrelated items. Report bounds or unsupported APIs honestly.
- Resolve the current slot and use a defensive copy of its stack for every projection. Relics ability/stat getters may initialize components on the passed stack. Never call setters, change the player's item, use a default item instance, or retain stats between reads.
- Use a compatible API version range verified against local artifacts or primary source evidence. Keep the existing single Relics adapter identity; this adds no separate model tool or duplicate adapter-list entry.
- Bound each item to 16 abilities, 32 stats per ability and 128 stats total. Existing item/page/output bounds continue to apply. Do not add cooldown interpretation, upgrade actions, progression mutation or a general item-extension framework.

## Acceptance

Verify actual copied-stack values, explicit zero, two different instances, changed values on reread, failed/unsupported API access and bounded traversal. Compare nonzero level and XP plus an effective ability stat with the live Relics UI, move the same item into functional Curios, ask about its stats and repeat after a value change. Keep source/build checks distinct from installed evidence.
