# Live UI correction plan

Date: 2026-10-13

Apply the [player feedback](../specs/2026-10-13-live-ui-corrections.md) in scoped commits. Preserve the pre-existing v1 plan edit and user sketches. Do not use formatting tools. The coordinator alone runs tests/builds, stages exact paths and generates commit messages from the staged index with the commit-message skill.

1. Diagnose and fix settings rendering against pinned widget contracts; test real bounds, focus and scrollbar dragging. Settings owner edits only its screen and tests.
2. Replace synthetic bold with a licensed real weight, adjust shared translucent palette and message spacing, and parse blockquotes without damaging citation offsets. Typography owner edits shared styling/font resources and text parser/tests.
3. Correct chat geometry, hover/icons/focus, word wrapping, inline row heights, composer alignment/clearing and activity placement. Trace pause publication through the runtime and correct the first-click state. Coordinator owns chat/editor/runtime files and their tests. Use native logical GUI dimensions.
4. Add the requested read-only config source panel using exact recorded source binding and replayed value snapshots, with a separate source action from gameplay navigation. Preserve the chat draft, current operational world and pause policy.
5. Verify focused checks and commit each functional group. Run the full suite/build from committed source, install with Minecraft closed, compare artifact hashes, and update validation and player checks. Do not claim automated checks prove live visuals.

## Failed-outcome follow-up, 2026-10-19

The brewing investigation exposed a complete saved provider error clipped by single-line outcome rendering. Reproduce the outcome's actual render bounds and returned conversation height, replace that single draw with the existing plain-text error wrapping pattern, then run the screen checks and full build. Check the saved failed turn in Minecraft after installing the matching artifact; no new provider request is needed.
