# Behavior-driven Toolbox validation

Date: 2026-10-14. Status: implementation, independent source review and final automated build passed; installation and live acceptance are waiting for the player to finish their current game session.

The [specification](../specs/2026-10-14-behavior-driven-tools.md) defines the contract. The [implementation plan](../plans/2026-10-14-behavior-driven-toolbox.md) and [live checklist](../testing/2026-10-14-behavior-driven-toolbox.md) track the requested work. Earlier validation documents describe older artifacts and do not establish acceptance of this migration.

## Automated checks

Portable Java 21.0.12.1 and Gradle 8.11.1 are used locally. No formatter is used.

| Check | Observed result | Status |
| --- | --- | --- |
| Bootstrap `-PpacksageBootstrapOnly=true test build --console=plain` at `d5b6fa6` | 72 tests, zero failures/errors/skips; build successful. Missing settings and grounding dependency includes were corrected before the successful run. | Passed |
| Full mod `test build --console=plain` at `ecd13b9` | 438 tests, zero failures/errors, four skips; build successful in 22 seconds. Opened-horse follow-up still needs its own check. | Passed checkpoint |
| Final full mod `test build --console=plain` at `3869ee3` | 439 tests, zero failures/errors, four skips; build successful in 29 seconds, including the opened-horse regression. | Passed |
| Final artifact identity and installation | Local full JAR SHA-256: `659226FD0C92647E016C9C554674DA86631A46F28938E639CE9710A90F6F7ABC`. Not installed: the player requested postponing live testing while playing. | Installation pending |

Four ConversationStore filesystem-link cases were skipped in this environment; the other 435 tests passed.

Focused regressions exercise ordered adapter fallback, current reference reads, UTF-8 output bounds, inventory location/owner changes, all six registry selectors, guide-page fragments, and large quest continuations. Intermediate failing runs are development evidence; they are not reported as final acceptance.

## Independent review

The independent reviewer inspected source and runtime paths from `c8a8a8e` onward. It did not run Gradle, install an artifact or operate Minecraft.

Resolved findings include discovery provider/kind ownership, missing-field absence claims, short guide cursors, current item-location contracts, guide text spanning fragment boundaries, retained quest fragments, unknown task/reward mechanics callbacks, bounded quest titles, advancing nested positions, JEI nullable filters and bounded current recipe membership checks. The reviewer cleared the optional-adapter changes through `aeef3d3`, subject to the native API cost boundary below. The late opened-horse finding was fixed in `0ebd365`: exact native handling avoids the null menu type and client-incompatible validity check, preserves equipment/cargo sections and binds references to the current menu instance. The reviewer closed source review with no unresolved material findings. The final automated regression also verifies fresh slot reads and rejection of a replacement menu with the same container ID.

The reviewed native server policy explicitly authorizes private integrated-world owner reads. It does not reproduce protection-mod GUI hooks. Same-ID item swaps at an unchanged location and container contract remain location-based; an ID or container-contract change invalidates continuation. A pre-existing recursive planner cap concern was declined as a migration blocker because current native recipes report unknown consumption and do not enter that recursion.

JEI's public native lookup API owns focus search, visibility filtering, category sorting and plugin lookup work. PackSage bounds its own inspection of returned categories/recipes and projection extraction, including retained-reference membership checks; these caps do not bound JEI's internal work or wall-clock time. The pinned public API has no raw paged membership endpoint. Retain native focus and visibility semantics rather than clone the recipe system or claim an end-to-end work bound.

## Installed live checks

Paused at the player's request on 2026-10-14 while they are playing. Wait for their explicit go-ahead before installation or further UI actions. Use only ATM10's `OoO - PackSage Trial`; the original `OoO` is outside the test scope. The CurseForge profile has been observed through `computer-use:computer-use`, but Minecraft has not been launched for this candidate and no live tool acceptance is claimed yet.

Record the final JAR SHA-256, installed versions, actual saved replay tool calls, visible inventory/menu observations, source changes between calls, restoration and fixture limits here after the live run.
