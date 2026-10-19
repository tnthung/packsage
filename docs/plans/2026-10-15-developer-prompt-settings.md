# Developer prompt settings implementation

Implement [the developer prompt settings contract](../specs/2026-10-15-developer-prompt-settings.md) without changing adapters or game-query behavior.

1. Add bounded optional prompt persistence and default access; select the override when creating a model request.
2. Add a developer settings screen using the existing multiline editor and styles. Route middle-click on the settings gear to it; preserve ordinary left-click settings, reject unrelated clicks and expose the gesture in the tooltip. Keep draft/apply/cancel/reset and save-failure behavior explicit.
3. Add focused regressions for Unicode/multiline persistence, default reset, invalid input, next-request application and current-request stability, plus UI routing/draft behavior where existing screen tests support it. Commit each work step using staged-index messages; update the changelog.
4. Run the automated suite/build with repository Java 21, independently review the change, then verify the installed editor and changed-prompt behavior through computer-use. Record source guarantees separately from live acceptance. The interrupted gameplay-scenario batch remains incomplete and is not automatically accepted by this feature.
