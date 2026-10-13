# UI surfaces and current question validation

Date: 2026-10-13

Contracts: [modern chat UI](../specs/2026-10-13-modern-chat-ui.md), [current question focus](../specs/2026-10-13-current-question-focus.md).

## Changes and automated checks

- `d95b9c9`: shared surfaces use tinted fill and outline sprites with curved alpha masks and native nine-slice scaling. Shader tint is restored after drawing; blending is enabled after each tint change. Existing bounds, GUI coordinates and scissor remain in use.
- `4b796b5`: Copy/Edit labels use measured horizontal centering. The `Ps` label is centered, and the history marker is reduced from 5 by 5 to 3 by 3 inside the avatar so it does not cover the label. Simple tooltips use six-pixel vertical padding and retain wrapping; structured tooltips retain their existing sections.
- `9b5a8a6`: the current question is its own first text content, followed by explicitly labeled gameplay background. Instructions prioritize the latest request, ask for brief social replies without unrelated lookups, and retain relevant-history and fresh-evidence guidance for follow-ups.

`./gradlew.bat test build --console=plain` succeeded in 21 seconds with 377 tests: 373 passed, four Windows symlink skips, no failures or errors. The test report includes the updated regression with enchantment history, current question `hi`, rich gameplay background, screenshot bytes and `ReasoningEffort.NONE`. It verifies input shape and effort passthrough, not model semantic compliance.

Both PNG masks are 64 by 64 with 16 by 16 logical nine-slice dimensions and four-pixel borders. Pixel inspection found transparent corners, opaque straight edges, an opaque fill interior and a transparent outline interior. Both masks and their metadata are present in the built JAR. The rendering path was checked against the local Minecraft/NeoForge sources, including GUI atlas discovery and tint/blend ordering. This is source and asset evidence, not a rendered Minecraft observation.

No formatting tools were used. `git diff --check` passed. Unrelated existing documentation edits and reference images were preserved.

## Reported greeting behavior

The installed ATM10 artifact matched the prior adapter-indicator build. The saved settings selected `gpt-6-luna` and effort `none`. The saved `hi` turn is a separate completed request with six new local tool calls: five pack searches and one evidence read concerning the prior pickaxe/enchantment topic. The cancelled partial turn is excluded from automatic history; two earlier completed exchanges fit the configured window. The final answer is a new enchantment recommendation, rather than a replay of the cancelled response.

The screenshot was captured 0.223 seconds after submission. The turn and screenshot were persisted approximately 117 seconds after submission. The stored record has no provider completion timestamp, so this interval cannot isolate inference, HTTP/tool continuation, local tool work or persistence time. Source inspection confirmed request/generation guards and explicit serialization of `none`; no stale cross-request event defect was established.

The focus correction addresses the model continuing background topics. It does not establish a latency bound or guarantee that the real model will comply.

## Artifact and remaining live checks

`dist/packsage.jar` contains the verified source at `9b5a8a611da8079f6d6b0311cb2c7305eeb6f582`. SHA-256: `249D601C9CCA6A2D15E983912F735FBB2FBCFF0601EE6832EC5FB98EB92EC27E`.

ATM10 was still running, and the native computer-use tool was no longer exposed in this session. Its installed JAR was not replaced. Live acceptance remains open for curved corners at the player's GUI scale, unobscured avatars, compact tooltip padding, centered Copy/Edit labels, and a new `hi` request with Luna/none in the existing enchantment session. The latter should give a brief greeting without enchantment lookups; its elapsed time must be observed separately. Relevant follow-ups such as `check again` must still retrieve fresh evidence.

Once Minecraft is closed, install only into the selected ATM10 test profile and compare hashes. Use only the existing backed-up `OoO - PackSage Trial` world. No original world, inventory or quest state was changed during this work.

## Live follow-up after desktop control returned

The desktop tool became available again in the next turn. The trial world was saved and Minecraft exited normally; the distribution JAR was copied into ATM10 and both hashes matched `249D601C9CCA6A2D15E983912F735FBB2FBCFF0601EE6832EC5FB98EB92EC27E`. The restarted 535-mod client loaded only `OoO - PackSage Trial` at the unchanged GUI scale 2.

Chat and settings surfaces visibly have curved, continuous corners. The `Ps` text is fully visible beside its smaller status marker, including the active response row. Copy/Edit labels appear horizontally centered. Model, effort, Search and Screenshot tooltips fit one line with compact vertical padding. The selectors opened and closed normally; Search and Screenshot were toggled twice and restored to their original off/on states. Settings showed the populated account and behavior controls, and Back returned to the same chat.

A greeting was submitted in the existing enchantment session using Luna/none, screenshot on and web search off. The raw submission was `hi` followed by a newline because the configured Enter shortcut inserts a newline; Send accepted it and cleared the input. The prompt-only fix failed this live check: the request was still waiting at 86.8 seconds and, by the 150.2-second observation, had completed with another pickaxe/enchantment recommendation.

Size inspection of the saved request found a 2,499,155-byte automatic gameplay context, of which the held-item value alone was 2,498,690 bytes. The earlier greeting had the same sizes. `GameplayContextCapture` appended the Java debug representation of the entire component map, expanding registry-backed data. This proves an excessive automatic input payload; it does not isolate all causes of the observed delay. A native component serialization correction and another live greeting check are required before accepting this behavior.
