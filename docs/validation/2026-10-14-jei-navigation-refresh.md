# JEI navigation refresh validation

Date: 2026-10-14. Status: automated checks and live navigation passed in all three profiles.

## Defect and correction

The [shared-reader trial](2026-10-14-capability-reader-alignment.md) exposed failed Recipe navigation in Evolution and ATM10. JEI emitted a capture-version-specific handle, while generic navigation revalidation attempted a fresh exact GET of that old handle. A new scan generated different handles and could not return the exact destination.

`316f424` retains up to 512 native recipe/category registrations in the active JEI runtime. Exact GET checks current visible membership of the same native objects with the original output focus, captures a fresh projection under the exact handle, and keeps world/resource/player-team/runtime scope checks. Request generation can advance. Generic navigation identity checks and model evidence authorization remain unchanged. Unretained handles, foreign scopes, hidden or removed/replaced registrations remain unavailable; old handle text is not parsed into a guessed recipe.

`84e1fda` separately corrected a diagnostic lambda compilation error. Independent review found that a prefix limit could reject valid targets from later cursor pages; `7180b74` restricts category lookup by the actual recipe type and searches that current focused category for exact membership. It decodes only the matched recipe. `bb811bb` separately corrected the test fixture's invalid null category-tooltip response; production partial-coverage behavior was preserved.

## Automated checks and artifact

- Java 21.0.12.1 / Gradle 8.11.1: focused `JeiProjectionTest` passed 17 tests, zero failures/errors/skips.
- Full `test build --no-daemon`: 415 tests across 36 suites; 411 passed, four existing skips, zero failures/errors. Intermittent Gradle client-socket serialization warnings did not prevent successful completion.
- The regression checks fresh output quantity and capture provenance, successful exact navigation after request generation advances, original output focus, a target emitted at cursor 2048, and rejection for unknown handles, foreign scopes and hidden/replaced/removed data.
- Independent review confirmed that the pagination finding was resolved and reported no other concrete defect. Exact membership can traverse the current focused recipe category on the client thread; it does not decode that category's other recipes.
- Build/distribution/three installed profile JAR hashes match: `85C6AEDF670B6F85EAD7722EEB1455B776D520E110CDF5760AA0DDC67A6F2D98` (4,648,374 bytes). All clients were closed before copying. The ATM10 trial was saved normally; original `OoO` remained unopened.

## Live checks

| Profile | Minecraft / NeoForge | JEI | Result |
| --- | --- | --- | --- |
| Packsage minimal fixture | 1.21.1 / 21.1.202 | 19.27.0.340 | Latest and older-after-follow-up Recipe links opened the correct JEI page. |
| FTB Evolution 1.45.0 | 1.21.1 / 21.1.250 | 19.57.0.450 | Recipe citation opened the correct four-torch JEI crafting page. |
| ATM10 8.2 trial copy | 1.21.1 / 21.1.251 | 19.57.0.446 | Recipe citation opened the correct four-torch JEI crafting page. |

Minimal fixture replay: `packsage/New_World-8a315c270917e038/bffb2bf9-4cd6-4d29-9f78-9a1a8e1807f3-f3b0f6737caac42c.jsonl`. `JEI_NAV_MIN_A` completed with one focused JEI search and a one-torch result matching the installed override. Clicking its Recipe citation opened the actual JEI Crafting page. After returning to chat, `JEI_NAV_MIN_B` completed with `NEXT`, no tool calls and request generation 2. Clicking the original Recipe citation again opened the same correct JEI page. No crafting or item transfer was performed.

Evolution replay: `packsage/PackSage_Evolution_Trial-2dc1b88ff37c5763/b9b53b70-3e22-4176-ae91-a0c0e95e901c-1f24e5c099a0393d.jsonl`. In the existing trial world, `JEI_NAV_EVO_A` completed with one focused `search_pack` call, a four-torch result and the exact `jei/1/1/minecraft:crafting/recipe-minecraft:torch-7` reference. Clicking Recipe opened the actual JEI Crafting page with four torches; Escape returned to PackSage. Used GPT-6-Luna / medium, Search off, Screenshot off and paused chat. No crafting or item transfer was performed.

ATM10 replay: `packsage/OoO_-_PackSage_Trial-814a1737fe55fba5/02c5b6fa-4c17-4f89-8f15-78eb283423e2-a2771324a2bdb4cb.jsonl`. In the backed-up trial copy, `JEI_NAV_ATM_A` completed with one focused `search_pack` call, a four-torch result and the exact `jei/1/1/minecraft:crafting/recipe-minecraft:torch-9` reference. Clicking Recipe opened the actual JEI Crafting page with four torches; Escape returned to PackSage. Used GPT-6-Luna / medium, Search off, Screenshot off and paused chat. No crafting or item transfer was performed; original `OoO` was not opened.

All three trial worlds were saved through their normal Save and Quit action, then their clients exited. A fresh window inventory confirmed no Minecraft test client remained open. Original `OoO` and its verified backup were left untouched.

This correction does not add persistent cross-restart JEI recipe identifiers. A handle must still have an exact retained registration in the active runtime.
