# Reference actions and expanded tool parameters

Date: 2026-10-19

This follows the player's requests to distinguish source citations from links and show the parameters used by tools inside expanded activity details. The authoritative behavior is in the [chat UI specification](../specs/2026-10-13-modern-chat-ui.md) and [implementation plan](../plans/2026-10-13-modern-chat-ui.md). Earlier reference persistence and prompt acceptance remain recorded in the [reference reliability report](2026-10-19-reference-reliability-and-coherent-prompt.md).

## Reference actions

Navigation controls now use accent, bold `Open ...` labels; recorded-source details use neutral, regular `Source: ...` labels. Both retain the descriptive base label. Hover identifies the click action and preserves the existing bounded preview from the recorded evidence. Navigation retains priority when a reference has both a destination and evidence. Unbound references, ordinary text and code do not acquire actions.

Two regression tests reproduced the indistinguishable presentation: 41 screen-state tests ran with two expected failures before implementation. After the change, all 41 screen-state and 34 screen-text tests passed. Coverage checks exact identities, final-label measurement, wrapping/clipping, existing Open prefixes and unchanged plain/code content. These headless checks do not establish installed rendering or click behavior.

Independent review identified a target-only hover null dereference: the tooltip accepted a navigation hit without a source hit but still used the absent source's top position. Commit `6edbe50` anchors that case from the navigation hit. Installed target-only hover remains to be checked.

## Tool parameters

Expanded rows show the observed executed arguments as literal structured text, separate from status details. Collapsed summaries retain their existing allowlist. Older saved rows recover arguments from an exact call-ID match in that turn's replay. The optional activity field preserves old constructors and logs. Capture and copies retain canonical arguments through completion, failure, retry cancellation, terminal transitions and Stop; saving/reloading retains them when failure details are normalized.

The argument regression slice ran 27 tests before implementation: three expected behavioral failures, four existing skips, and no compilation failure. These exposed missing live capture, dropped saved arguments and missing legacy display. After implementation, the complete controller, store, screen-state and screen-text suites passed. Tests cover nested/null values, same-name calls with different IDs, captured-field precedence, missing/malformed arguments, empty objects and explicit truncation.

Display accepts at most 16,384 input characters and 32 levels of nesting; the complete expanded tool body is limited to 2,048 characters with an explicit truncation marker. Invalid or unavailable arguments use readable fallback text. JSON is rendered through literal text, so parameter values do not become citation or navigation controls. Tool result bodies are not displayed as parameters. Preparation runs only for expanded rows.

Java 21 `gradlew test build` passed on production source `71d1a66`: 51 suites, 599 tests, 595 passed, four existing skips, no failures/errors. The resulting JAR SHA-256 is `6EA1CAC710D8A370C9C7BB8728563CE730281BCD9A19D60F94147E8C3482AD28`. No formatting tools were used.

## Installed acceptance

Installed checks are pending. The previous Minecraft process completed the player's ME Controller request, persisted it, and exited normally before installation work. The pre-change properties SHA-256 is `AF7DA001C214347F01A17D76A991150D1728907B5EC16615FC2EE1126DAA5FA0`.

The intended checks are a saved answer with both reference actions, actual source-details and JEI navigation, legacy tool-argument recovery, and a fresh ordinary-player request showing arguments during/after tool activity. No gameplay crafting or inventory movement is required.
