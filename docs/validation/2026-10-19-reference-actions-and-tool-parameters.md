# Reference actions and expanded tool parameters

Date: 2026-10-19

This follows the player's requests to distinguish source citations from links and show the parameters used by tools inside expanded activity details. The authoritative behavior is in the [chat UI specification](../specs/2026-10-13-modern-chat-ui.md) and [implementation plan](../plans/2026-10-13-modern-chat-ui.md). Earlier reference persistence and prompt acceptance remain recorded in the [reference reliability report](2026-10-19-reference-reliability-and-coherent-prompt.md).

## Reference actions

Navigation controls now use accent, bold `Open ...` labels; recorded-source details use neutral, regular `Source: ...` labels. Both retain the descriptive base label. Hover identifies the click action and preserves the existing bounded preview from the recorded evidence. Navigation retains priority when a reference has both a destination and evidence. Unbound references, ordinary text and code do not acquire actions.

Two regression tests reproduced the indistinguishable presentation: 41 screen-state tests ran with two expected failures before implementation. After the change, all 41 screen-state and 34 screen-text tests passed. Coverage checks exact identities, final-label measurement, wrapping/clipping, existing Open prefixes and unchanged plain/code content. These headless checks do not establish installed rendering or click behavior.

Independent review identified a target-only hover null dereference: the tooltip accepted a navigation hit without a source hit but still used the absent source's top position. Commit `6edbe50` anchors that case from the navigation hit. Subsequent review found no remaining behavioral defect or missed argument-copy path. Target-only hover passed both installed checks below.

## Tool parameters

Expanded rows show the observed executed arguments as literal structured text, separate from status details. Collapsed summaries retain their existing allowlist. Older saved rows recover arguments from an exact call-ID match in that turn's replay. The optional activity field preserves old constructors and logs. Capture and copies retain canonical arguments through completion, failure, retry cancellation, terminal transitions and Stop; saving/reloading retains them when failure details are normalized.

The argument regression slice ran 27 tests before implementation: three expected behavioral failures, four existing skips, and no compilation failure. These exposed missing live capture, dropped saved arguments and missing legacy display. After implementation, the complete controller, store, screen-state and screen-text suites passed. Tests cover nested/null values, same-name calls with different IDs, captured-field precedence, missing/malformed arguments, empty objects and explicit truncation.

Display accepts at most 16,384 input characters and 32 levels of nesting; the complete expanded tool body is limited to 2,048 characters with an explicit truncation marker. Invalid or unavailable arguments use readable fallback text. JSON is rendered through literal text, so parameter values do not become citation or navigation controls. Tool result bodies are not displayed as parameters. Preparation runs only for expanded rows.

Java 21 `gradlew test build` passed on production source `71d1a66`: 51 suites, 599 tests, 595 passed, four existing skips, no failures/errors. This first installed candidate had SHA-256 `6EA1CAC710D8A370C9C7BB8728563CE730281BCD9A19D60F94147E8C3482AD28`. A final manual style pass in `3fb204b` inlined single-use expressions; the full test/build then passed again in 28 seconds with the same counts. The final JAR SHA-256 is `7EDA9A079AEEEBC57F5CB0E371FC7A08E60DBC49B2C0A207B78C6C04F31A4B2D`. No formatting tools were used.

## Installed acceptance

The previous Minecraft process completed the player's ME Controller request, persisted it, and exited normally before installation work. Both installations verified Minecraft process absence before copying the JAR. The selected fixture was Packsage, Minecraft 1.21.1 / NeoForge 21.1.259, New World, GUI scale 2 at 1026 by 800. Model/effort and search/screenshot controls were preserved. The properties SHA-256 remained `AF7DA001C214347F01A17D76A991150D1728907B5EC16615FC2EE1126DAA5FA0`.

The first installed candidate started window `210961958`. The following were observed through the native UI:

| Check | Observed result |
| --- | --- |
| Older ME Controller chat `a1bda619-434e-4433-baef-9bdc70cba6a8-0d6dcd5d3aefa81c.jsonl` | Expanded the first registry tool. It showed Completed and Parameters with the original `ME Controller` text filter, item registry, provider, limit and explicit null fields. These older activity rows have no serialized-arguments field, so this exercises replay recovery. |
| Saved torch chat `e0670abe-4df0-4cd2-a7ec-b4c9da0fda86-ec2374551f68e704.jsonl` | Neutral Source controls and accent Open controls were visible together. Source hover said Click: view recorded source details and showed 64 coal in hotbar slot 1. Clicking opened that recorded snapshot. |
| The same answer's Open torch recipe | Hover identified the Open recipe action and retained the partial-recording notice. Clicking opened JEI with coal/charcoal over a stick producing one torch. No crafting or inventory movement occurred. |
| Fresh question: “how much coal have i got on me, incl any shulkers?” | Request `9c3bf711-eb66-4b38-8b1c-c47e88db3a75` completed in 14,334 ms with two tool calls and zero tool errors. Expanded rows showed the player target and limit, then the item target with the full canonical slot reference wrapping inside the readable width. The answer reported 64 coal and no coal in the inspected shulker. |
| Fresh-call persistence | Both saved activity argument strings exactly matched their replay calls. Switching away and reopening the chat retained the expanded parameter display. Saved file: `5813209c-59c0-4a38-8d6a-25bf3d73073a-c9e4e6e93bb88abf.jsonl`. |
| Fresh question: “can you link me to the minecraft wiki homepage?” | Request `f3047fb9-55d3-43e3-a899-bcdefc686d1f` completed in 22,539 ms with no PackSage tool calls. Its web citation had no recorded local source. Hover showed its Open action without a null dereference. No external browser was opened. |

Minecraft was then saved/exited normally, and the final style-aligned JAR was installed with process absence verified. A fresh process/window `107155806` loaded the same world. The newly saved coal conversation still displayed the original player target and limit beneath Completed / Parameters after expansion. The saved source-less web citation also showed its hover action without crashing. This restart performed no new model requests. The player's original ME Controller conversation was restored, with the world paused and no active request.

The built, installed and `dist/packsage.jar` hashes match the final hash above. Only `sha256` was changed in `dist/build-metadata.json`; other metadata was preserved. Native logs are retained under ignored `.tools/reference-actions/installed-live.log` and `final-restart-live.log`. The two unrelated pre-existing dirty validation documents were preserved.

Acceptance: the requested reference distinction and expanded tool parameters passed installed checks, including old saved calls and new calls after a fresh client restart. Running, failure, cancellation, retry, malformed/missing input and display bounds are covered by automated checks; live calls completed before the first inspection, so running-state rendering is not claimed as live proof. Arbitrary unbound Markdown links remain literal: the wiki answer included such text alongside its working web citation. That separate formatting/answer-quality limitation is not accepted as fixed by these changes. No gameplay crafting was performed.
