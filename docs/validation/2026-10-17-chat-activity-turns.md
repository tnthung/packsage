# Chat activity turn validation

Date: 2026-10-17

Spec: [thinking and tool turns](../specs/2026-10-17-chat-activity-turns.md). Plan: [implementation](../plans/2026-10-17-chat-activity-turns.md). Player checks: [Minecraft interaction](../testing/2026-10-17-chat-activity-turns.md).

## Automated result

- Source tested and built: `505982ca222dacffff9cec0fecf9eb5ec37591ad`.
- Java: bundled JDK `21.0.12.1` under `.tools/jdk`.
- Focused stream/backend/controller/store/screen checks passed after the header-layout correction: 150 tests, 4 skipped, zero failures or errors.
- Final `gradlew.bat test build`: successful; 510 tests across 49 suites, 506 passed, 4 skipped, zero failures or errors.
- Checks cover summary-only streaming, reasoning effort preservation including None, ordered concurrent tools and individual outcomes, failure/cancellation, activity persistence and legacy logs, automatic/manual expansion, wrapping, and visible/clipped header clicks.
- The initial focused run exposed the headless font's missing glyph lookup in a new wrapping test. Headers now use the existing message-layout path, which also supplies their click bounds; the test exercises that production layout directly.
- Bounded independent review found no concrete behavior regression. Its single-use helper finding was corrected by the header-layout change. No formatting tools were used.
- `git diff --check` and the feature source/document diff check passed. The new documentation's relative links resolve.

## Initial artifact and acceptance

- Built JAR: `build/libs/packsage-0.1.0.jar` (4,892,485 bytes).
- SHA-256: `221DFE6DCBFB73D9537D3D81846704A48BDF34EF19736DDACBC0BBF447206588`.
- Automated acceptance: passed. Minecraft rendering, actual ChatGPT account summary delivery, and live player clicks remain pending the linked player checks. The JAR was not installed into a running or stored Minecraft instance.
- Existing edits to `2026-10-15-developer-prompt-settings.md` and the untracked `2026-10-16-prompt-tally-retest.md` were preserved outside these commits.

## Response group correction

The player's screenshot showed four individually collapsed tool headers still occupying four rows. The corrected disclosure boundary is the whole response's activity list: one `4 steps` header hides every child row while the answer remains visible. Opening the group reveals the existing per-step detail controls.

- Source tested and built: `736afcc`.
- Regression tests were committed before implementation. Their first run confirmed the missing group-header method. The first implementation compile exposed a duplicate local coordinate; that fix was committed separately before rerunning checks.
- Focused `AssistantScreenStateTest`: 33 tests passed. It covers the count header, response-level lifecycle with completed children, stable request identity, manual toggles across step-count changes and completion, failed/cancelled groups, and existing clipping/wrapping checks.
- Final `gradlew.bat test build`: successful; 514 tests across 49 suites, 510 passed, 4 skipped, zero failures or errors.
- Built JAR: `build/libs/packsage-0.1.0.jar`; SHA-256 `919441E80499CC65200DDAB42DBAC786D2A55168CBAA78131887F5DFC81C62A5`.
- With Minecraft closed, copied the corrected JAR to `C:\Users\hung1\curseforge\minecraft\Instances\All the Mods 10 - ATM10\mods\packsage.jar` and verified its SHA-256 matches the built JAR. The previous installed JAR was preserved under ignored `.tools/test-backups/`.
- Future live checks use the existing ATM10 `OoO - PackSage Trial` world. The earlier minimal Packsage-profile launch did not establish ATM10 acceptance.
- Live verification remains pending: two captures targeting the returned CurseForge window showed a different foreground application's image, including after refreshing the window binding. No clicks were issued from those mismatched captures, and ATM10 was not launched during this correction.
