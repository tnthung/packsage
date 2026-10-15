# Cross-mod workflow corrections

Implement the findings from [ordinary player workflow checks](../validation/2026-10-15-cross-mod-workflows.md) within the existing [behavior-driven tool contract](../specs/2026-10-14-behavior-driven-tools.md). Preserve live source reads, bounded extraction, source ownership and public tool signatures.

1. Catch up the changelog for delivered Toolbox, inventory, dimension and guide work without upgrading narrow tests into full workflow acceptance.
2. Reproduce the AA traversal failure and preserve useful diagnostics without bypassing budgets, visibility or reload checks. The regression disproved the initial textless-page hypothesis and identified byte-reserve exhaustion, including later-page seeks.
3. Consolidate the system prompt around short literal searches, bounded continuation/recovery, exact recipe quantities, current carried-storage targets and source/coverage-aware answers. Avoid hardcoded test cases and unnecessary tool calls for ordinary conversation.
4. Commit each written step with its changelog entry, then run focused regressions and the full automated suite/build. Independently review the changed reader and prompt.
5. Install with Minecraft closed and rerun natural player questions in the trial world: cross-mod setup, fall-safety comparison, and carried-container crafting with a changed bundle. Never prescribe tool calls in acceptance prompts. Record actual replay evidence and any remaining limitations.

The natural retest exposed repeated recipe arithmetic errors despite correct slot evidence. Add a derived `requiredInputTotals` to serialized recipe evidence only when every returned input slot is a singleton consumed `item` or `item_stack` with a known positive quantity. Group exact type, comparison identity and full components; sum safely. Omit the whole tally for choices, unknown quantities or other semantics. Preserve raw slots, catalysts, remainders, constraints and decoding status. The tally covers returned inputs and cannot upgrade partial evidence into a complete recipe. This is presentation of current evidence, not a new recipe solver or public operation.

Reader and prompt work have disjoint write owners; the parent owns Git, Gradle, changelog and validation documentation. No formatter. Prompt improvements are not proof of model reliability; distinguish automated source guarantees from observed live answer quality.
