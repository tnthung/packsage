# Natural player workflow corrections

Address the failures in [the six-scenario conversation report](../validation/2026-10-15-natural-player-conversations.md), preserving the [behavior-driven tool contract](../specs/2026-10-14-behavior-driven-tools.md). Every query reads live sources; references and cursors retain only identity and position metadata. No whole-system snapshot, automatic nested inventory scan or new public tool is required.

1. Trace empty partial search pages and unchanged-query JEI cursor rejection. Reproduce the governing budget/position failures with focused regressions, then correct the source search paths without removing work limits or changing literal filter semantics.
2. Make inventory query scope and uninspected carried-container discovery explicit, including filtered zero-match results. Preserve one target per query and expose only current, resolvable references. Verify changed contents are reread.
3. Tighten continuation recovery and answer guidance: retain query/cursor identity, inspect relevant carried containers before broad absence or affordability claims, ask focused clarifications for vague goals, and distinguish observed attributes from inferred behavior. Prefer concrete next steps and bounded comparisons over unsupported cheapest-route claims.
4. Commit each test, fix and documentation step with staged-index commit messages; update the changelog as changes land. One writer owns each file set. Run focused regressions followed by the full automated suite/build and an independent review.
5. Install with Minecraft closed, then repeat affected natural-player conversations in the trial world through computer-use. Use fresh player-role agents, ordinary wording and adaptive follow-ups. Separate source guarantees, answer usefulness and physical gameplay validation; document remaining limits honestly.

Investigation is parallel and read-only until file ownership and exact fixes are established. No formatter; preserve unrelated work.
