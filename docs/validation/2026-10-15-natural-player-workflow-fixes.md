# Natural player workflow corrections

Follow-up to [the six-scenario conversation report](2026-10-15-natural-player-conversations.md), implementing [the correction plan](../plans/2026-10-15-natural-player-workflow-fixes.md). Source checks and live conversational acceptance are separate.

## Source regressions and corrections

- Exact continuation calls preserve query, filters and limit and dispatch another live read. Invalid cursors provide an explicit fresh restart; output truncation removes continuation that could skip hidden records.
- Filtered player-inventory misses explicitly cover direct slots and equipment, excluding nested storage. Unfiltered discovery retains supported container references; contents remain separate live queries.
- Registry name lookup reaches a late vanilla entry without projecting unrelated records. Its indexed candidate bound is 4,096; item-tag iterator paging is unchanged.
- Native exact-output lookup reaches a recipe beyond candidate 512, resolves each output once and never projects ingredients for output mismatches. Text remains an AND filter. The 4,096-candidate boundary advances to a fresh read; text-only projection retains its 512-candidate bound.
- FTB search reaches the final quest of a 2,300-quest chapter through unchanged-query continuation. Prefix seeking uses the live indexed list and validates its preceding owner. Existing nested task ranges, owner replacement and exact evidence reads remain covered. Iterator-only dependency seek limits remain unchanged.
- JEI regressions exercise a canonical recipe beyond ordinal 2,048, continued progress, stale runtime rejection and a terminal seek ceiling. Review additionally identified category-prefix budget replay and canonical string allocation before byte reservation; these require correction before installation.

The inventory/model focused run passed 32 tests. The FTB/native focused run passed 23 tests. The first full run had 494 tests: 489 passed, four existing skips and one stale fixture expectation after changing its category from opaque to known. This is not final build acceptance.

## Review and live status

Independent Luna review found no additional issues in the FTB, native, model-envelope or inventory-scope changes. Its two JEI findings are accepted. No formatter was used. Changes are committed as separate work steps; all tool reads remain live and no source-content cache was added.

Minecraft was saved and closed in the trial workflow. Corrected-build installation and natural-player retests are pending. Fresh independent player agents have prepared backpack, carried-material, machine-speed, stalled-furnace, fall-protection and progression scenarios using ordinary questions without tool instructions. Follow [the player protocol](../testing/2026-10-15-natural-player-conversations.md).
