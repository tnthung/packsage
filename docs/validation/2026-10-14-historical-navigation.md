# Historical navigation and status clipping checks

Date: 2026-10-14.

## Causes and corrections

- Navigation previously required the original target to have the latest request scope and remain in that request's model-evidence cache. A follow-up cleared that cache and advanced the request generation. Recorded local references now perform an exact current-provider GET lookup and open only the same source/destination validated under the current scope. Navigation authorization is bounded separately from model evidence handles.
- Transcript height excluded the initial eight-pixel inset. At the bottom scroll position, its last row extended beyond the scissor. Including that inset places the transcript end four pixels inside the viewport. Status placement and composer geometry remain unchanged.

## Automated evidence

- Two behavioral regressions failed before navigation implementation: the service's historical target refresh and opening the first turn's reference after a follow-up.
- All 26 existing chat UI tests passed with the scroll correction.
- The final focused run passed 79 tests across chat UI, knowledge service, runtime and integration.
- The full `gradlew.bat test build --console=plain` run passed: 404 tests discovered, 400 passed, four existing skips, zero failures/errors.
- Tests cover restored local references, preservation of the historical answer, unregistered references, exact current source/destination matching, navigation-only handle rejection by model `read_evidence`, bounded authorization and late completion after new chat, resource invalidation or world changes. Existing tests retain other-world read-only and external URL validation.
- Independent read-only review found no concrete behavioral defect. `git diff --check` passed. No formatter was used.

## Installed acceptance

The game was running when the build finished. Installed navigation and rendered glyph visibility have not yet been checked with this artifact. Source math and headless tests are not live Minecraft proof.

To check the installed build: click quest references from two different older replies, send a follow-up and click the first reply's reference again. Open a restored same-world session and click its recorded quest reference. Confirm the expected current quest opens; missing destinations still produce a readable status without changing the answer. At the transcript bottom, confirm the entire status line and its descenders fit above the composer.
