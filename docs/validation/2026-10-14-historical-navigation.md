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

Passed live in ATM10 8.2, Minecraft 1.21.1, NeoForge 21.1.251, with 535 mods loaded. The client was saved and exited normally before installing the new JAR, then restarted. Only `OoO - PackSage Trial` was joined; the original `OoO` world was not opened.

- The installed JAR matched the build and distribution artifact: SHA-256 `329DBC6B163F0BB93232922EAF82049BB93AC729E59DEDD5C8AA4B554C4E9322`, built from `4315bf1d168f858f08362605106ab2887e99a0e9`.
- In restored session `6b8452d8-3c42-4ba0-aa53-61851e80477d`, references from two older replies opened the expected current FTB quest pages: **Vials and Sigils** and **Enchantment Library**. No new model lookup was needed first.
- A follow-up requesting `NAV_CHECK_OK` completed with that answer. An older **Getting better Gems** reference then opened its current quest page after the request generation changed. Historical answers remained intact.
- A temporary, explicitly synthetic history session referenced a missing quest. Clicking it produced **Navigation target is no longer available** without opening a quest page. At the transcript bottom, the entire line, including descenders, was visible above the composer in the 1026 by 800 client window.
- The temporary session was removed and its absence checked. Model preferences and inventory were preserved, no quest rewards were claimed, and the trial world was saved normally. Minecraft was left at the title screen.

This live pass covers FTB quest navigation and status visibility at the current UI scale. It does not establish installed acceptance for every provider or UI scale; the other provider and lifecycle boundaries are covered by the automated checks above.
