# Natural player workflow corrections

Follow-up to [the six-scenario conversation report](2026-10-15-natural-player-conversations.md), implementing [the correction plan](../plans/2026-10-15-natural-player-workflow-fixes.md). Source checks and live conversational acceptance are separate.

## Source regressions and corrections

- Exact continuation calls preserve query, filters and limit and dispatch another live read. Invalid cursors provide an explicit fresh restart; output truncation removes continuation that could skip hidden records.
- Filtered player-inventory misses explicitly cover direct slots and equipment, excluding nested storage. Unfiltered discovery retains supported container references; contents remain separate live queries.
- Registry name lookup reaches a late vanilla entry without projecting unrelated records. Its indexed candidate bound is 4,096; item-tag iterator paging is unchanged.
- Native exact-output lookup reaches a recipe beyond candidate 512, resolves each output once and never projects ingredients for output mismatches. Text remains an AND filter. The 4,096-candidate boundary advances to a fresh read; text-only projection retains its 512-candidate bound.
- FTB search reaches the final quest of a 2,300-quest chapter through unchanged-query continuation. Prefix seeking uses the live indexed list and validates its preceding owner. Existing nested task ranges, owner replacement and exact evidence reads remain covered. Iterator-only dependency seek limits remain unchanged.
- JEI regressions exercise a canonical recipe beyond ordinal 2,048, continued progress, stale runtime rejection and a terminal seek ceiling. Review identified and closed category-prefix budget replay and canonical string allocation before byte reservation. The final regression also resumes category 200 to 201 and terminates an oversized canonical ID across a 202-category source without an unusable cursor.

The inventory/model focused run passed 32 tests. The FTB/native focused run passed 23 tests. The first full run exposed one stale fixture expectation after changing its category from opaque to known; the fixture now retains a separate opaque-category check. Final `test build` at `f7df61c` passed: 494 tests, 490 passed, four existing skips, no failures or errors. Existing JEI deprecation warnings and Gradle daemon socket noise did not prevent successful completion. `git diff --check` passed.

## Review and live status

Independent Luna review found no additional issues in the FTB, native, model-envelope or inventory-scope changes. It rechecked both JEI corrections and the final terminal-bound handling and found no remaining concrete defects. No formatter was used. Changes are committed as separate work steps; all tool reads remain live and no source-content cache was added.

Minecraft was saved and closed before installation. Build output, `dist/packsage.jar` and the ATM10 instance's `mods/packsage.jar` match SHA256 `8633AF2656F7357CEC39853F2717C370E6EF5AB376707CBF197B5267B4E232EA`. Natural-player retests are pending. Fresh independent player agents have prepared backpack, carried-material, machine-speed, stalled-furnace, fall-protection and progression scenarios using ordinary questions without tool instructions. Follow [the player protocol](../testing/2026-10-15-natural-player-conversations.md).

## Installed-build conversational retests

Retests use GPT-6-Luna at medium effort with screenshots in the trial world. Each scenario has its own fresh player-role agent and chat; follow-ups are the agent's ordinary player wording. Satisfaction is conversational acceptance, not proof of physical crafting or machine performance.

- Carried materials (`e9b99255-2cd0-4fbd-8664-c0b82b9bc76c`): “Can you help me find the redstone I packed? I can’t remember which container I put it in.” The answer found four redstone in hotbar slot six's bundle, distinguished the other inspected bundle/shulker from unread Pocket Storage Unit contents, and satisfied the player agent. This closes the explicit carried-material discovery failure.
- Backpack (`e538c890-5fdf-4760-9754-0de3631170b1`): “My inventory keeps filling up—what’s a simple, affordable backpack I can make?” The answer found a Sophisticated Backpacks recipe (one chest, four leather, four string) and gave a gathering route after “Where’s the easiest place to get the leather and string?” The player agent was satisfied. Acceptance is qualified: the first answer still said materials needed gathering without inspecting all carried containers, and called the live inventory read a “snapshot.” This wording does not establish a source cache, but its broad absence inference remains incorrect.
