# Reference actions and expanded tool parameters

Date: 2026-10-19

This follows the player's requests to distinguish source citations from links and show the parameters used by tools inside expanded activity details. The authoritative behavior is in the [chat UI specification](../specs/2026-10-13-modern-chat-ui.md) and [implementation plan](../plans/2026-10-13-modern-chat-ui.md). Earlier reference persistence and prompt acceptance remain recorded in the [reference reliability report](2026-10-19-reference-reliability-and-coherent-prompt.md).

## Reference actions

Navigation controls now use accent, bold `Open ...` labels; recorded-source details use neutral, regular `Source: ...` labels. Both retain the descriptive base label. Hover identifies the click action and preserves the existing bounded preview from the recorded evidence. Navigation retains priority when a reference has both a destination and evidence. Unbound references, ordinary text and code do not acquire actions.

Two regression tests reproduced the indistinguishable presentation: 41 screen-state tests ran with two expected failures before implementation. After the change, all 41 screen-state and 34 screen-text tests passed. Coverage checks exact identities, final-label measurement, wrapping/clipping, existing Open prefixes and unchanged plain/code content. These headless checks do not establish installed rendering or click behavior.

## Tool parameters

Implementation and lifecycle/persistence checks are in progress. Expanded rows will show the observed executed arguments as bounded literal structured text, separate from status details. Collapsed summaries retain their existing allowlist. Legacy saved rows must use exact call-ID matching rather than tool-name matching; missing or malformed arguments must remain explicit.

## Installed acceptance

Pending a completed build and installed checks. The previous Minecraft process completed the player's ME Controller request, persisted it, and exited normally before installation work. The pre-change properties SHA-256 is `AF7DA001C214347F01A17D76A991150D1728907B5EC16615FC2EE1126DAA5FA0`.

The intended checks are a saved answer with both reference actions, actual source-details and JEI navigation, legacy tool-argument recovery, and a fresh ordinary-player request showing arguments during/after tool activity. No gameplay crafting or inventory movement is required.
