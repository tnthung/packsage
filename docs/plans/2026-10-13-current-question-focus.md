# Current question focus implementation plan

**Goal:** Make the latest submitted question the final user message, with history and gameplay retained as separate background.

**Spec:** [Current question focus specification](../specs/2026-10-13-current-question-focus.md). Existing contracts: [PackSage v1 specification](../specs/2026-10-12-v1.md) and [v1 implementation plan](2026-10-12-v1-implementation.md).

## Scope

- Update `GroundingInstructions` to identify the final user message as the only current request, keep history and gameplay as background, and answer greetings/social text briefly without unrelated metadata or evidence lookup. Preserve follow-up and evidence-refresh guidance.
- Emit historical question/answer pairs first, then a separate USER background message with the labeled fresh observation plus image or screenshot status, then a final USER message containing exactly the raw question as its only text content.
- Adapt focused request-shape coverage to verify the four-item order with a prior exchange, screenshot attachment to the background message, exact final `hi` message, and NONE effort passthrough.

## Validation

- Review the generated request shape and regression assertions against the acceptance criteria.
- Run the focused `GroundingInstructionsTest` in the integration checkout after the other owned changes are joined.
- Confirm actual inference behavior with a live model request; prompt wording and unit tests alone do not establish semantic compliance.
