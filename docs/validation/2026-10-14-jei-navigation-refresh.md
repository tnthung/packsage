# JEI navigation refresh validation

Date: 2026-10-14. Status: automated checks passed; live navigation pending.

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
| Packsage minimal fixture | 1.21.1 / 21.1.202 | 19.27.0.340 | Pending. |
| FTB Evolution 1.45.0 | 1.21.1 / 21.1.250 | 19.57.0.450 | Pending. |
| ATM10 8.2 trial copy | 1.21.1 / 21.1.251 | 19.57.0.446 | Pending. |

This correction does not add persistent cross-restart JEI recipe identifiers. A handle must still have an exact retained registration in the active runtime.
