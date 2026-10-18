# Reliable references and coherent assistant instructions

The user prioritizes broken references and requires a fluent system prompt rather than accumulated warnings. The [failure trace](../research/2026-10-19-reference-reliability.md) identifies model copying of transport identities; the [behavior-driven contract](../specs/2026-10-14-behavior-driven-tools.md) retains strict canonical identity and current reads. The earlier [coherence plan](2026-10-15-coherent-assistant-instructions.md) governs the full prompt rewrite.

## Design

Wrap the actual model backend in a request-local translation boundary. The controller, knowledge adapters, persisted calls/results/answers and navigation remain canonical. Long model-facing evidence and inventory-location references become typed aliases such as `ref:7ac21f09.c.3` and `slot:7ac21f09.c.1`: one random eight-hex process nonce and monotonic base36 request/entry counters avoid reuse within the backend instance. Readable canonical evidence references of at most 32 characters remain unchanged. Aliases are locators, not authorization; every underlying live validation still runs.

Translate only defined reference fields and supported citation syntax. Do not rewrite guide prose, item IDs, arbitrary provenance, counts or cursors. Register only identities in accepted tool output, preserve the output ceiling after encoding and bound retained state without silent eviction. Verify exact wire replay before substituting retained canonical results. Streaming expansion must be correct at every token split and preserve web annotation ranges, steering and cancellation behavior. Unknown, altered, wrong-kind and prior-request aliases remain unresolved; no guessed correction is allowed.

Rewrite `GroundingInstructions.INSTRUCTIONS` as one ordered policy covering the player's goal and read-only boundary, claim-specific evidence, feasibility, focused reads and a useful answer. State each governing rule once. Preserve important source, coverage, inventory, recipe, reference, history, targeting and privacy contracts; remove repeated scenario warnings. A request-assembly test is not proof of model behavior.

## Ownership and checks

- Sol owns the backend decorator, its runtime wiring and focused tests because replay and streaming offsets require judgment across components. Root owns the full prompt and documentation. Writers have disjoint files; only root mutates Git or runs Gradle.
- First add a minimal pass-through seam and meaningful failing alias regressions; commit and observe the failure before implementing translation. Cover exact slot round trips, separate alias kinds, stale/foreign/unknown inputs, concurrent completions, unmodified source text, replay tampering, every citation split, labels/groups, web ranges, steering and canonical saved navigation/details.
- Review the whole prompt independently for coherence and lost contracts. Do not add tests that merely assert particular sentences.
- Run focused suites followed by `gradlew test build`, inspect changed files manually without formatting tools, and finish with `git diff --check`.
- Save/quit Minecraft normally before installing a matching JAR. Use focused manual player conversations for carried storage, fresh recipe links across turns, correction handling and causal uncertainty. Verify persisted canonical references and native navigation separately from the model's prose. Preserve existing settings and unrelated dirty documents.
