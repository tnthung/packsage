# Behavior-driven Toolbox validation

Date: 2026-10-14. Status: implementation validation in progress; final full-suite and installed live acceptance are pending.

The [specification](../specs/2026-10-14-behavior-driven-tools.md) defines the contract. The [implementation plan](../plans/2026-10-14-behavior-driven-toolbox.md) and [live checklist](../testing/2026-10-14-behavior-driven-toolbox.md) track the requested work. Earlier validation documents describe older artifacts and do not establish acceptance of this migration.

## Automated checks

Portable Java 21.0.12.1 and Gradle 8.11.1 are used locally. No formatter is used.

| Check | Observed result | Status |
| --- | --- | --- |
| Bootstrap `-PpacksageBootstrapOnly=true test build --console=plain` at `d5b6fa6` | 72 tests, zero failures/errors/skips; build successful. Missing settings and grounding dependency includes were corrected before the successful run. | Passed |
| Final full mod `test build` | Pending after optional-adapter review fixes. | Pending |
| Final artifact identity and installation | Pending. The build script installs the minimal Packsage profile; ATM10 needs an explicit copy of the same full artifact while Minecraft is closed. | Pending |

Focused regressions exercise ordered adapter fallback, current reference reads, UTF-8 output bounds, inventory location/owner changes, all six registry selectors, guide-page fragments, and large quest continuations. Intermediate failing runs are development evidence; they are not reported as final acceptance.

## Independent review

The independent reviewer inspected source and runtime paths from `c8a8a8e` onward. It did not run Gradle, install an artifact or operate Minecraft.

Resolved findings include discovery provider/kind ownership, missing-field absence claims, short guide cursors, current item-location contracts, guide text spanning fragment boundaries, retained quest fragments, and unknown task/reward mechanics callbacks. The final optional-adapter checkpoint still needs review and automated confirmation, including indirect quest-title callbacks, advancing nested positions, JEI nullable filters and bounded current recipe membership checks.

The reviewed native server policy explicitly authorizes private integrated-world owner reads. It does not reproduce protection-mod GUI hooks. Same-ID item swaps at an unchanged location and container contract remain location-based; an ID or container-contract change invalidates continuation. A pre-existing recursive planner cap concern was declined as a migration blocker because current native recipes report unknown consumption and do not enter that recursion.

## Installed live checks

Pending. Use only ATM10's `OoO - PackSage Trial`; the original `OoO` is outside the test scope. The CurseForge profile has been observed through `computer-use:computer-use`, but Minecraft has not been launched for this candidate and no live tool acceptance is claimed yet.

Record the final JAR SHA-256, installed versions, actual saved replay tool calls, visible inventory/menu observations, source changes between calls, restoration and fixture limits here after the live run.
