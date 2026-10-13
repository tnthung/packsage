# Current question focus implementation plan

**Goal:** Make the latest submitted question explicit in model input while retaining conversation and fresh gameplay as background context.

**Spec:** [Current question focus specification](../specs/2026-10-13-current-question-focus.md). Existing contracts: [PackSage v1 specification](../specs/2026-10-12-v1.md) and [v1 implementation plan](2026-10-12-v1-implementation.md).

## Scope

- Update `GroundingInstructions` to instruct the model to prioritize the latest request, use history only when relevant, and answer greetings/social text briefly without unrelated evidence lookup. Preserve follow-up and evidence-refresh guidance.
- Emit the raw question and labeled gameplay observation as separate text contents in the current user message, in that order.
- Adapt and add focused request-shape coverage for history ordering, screenshot behavior, rich gameplay context, and NONE effort passthrough.

## Validation

- Review the generated request shape and regression assertions against the acceptance criteria.
- Run the focused `GroundingInstructionsTest` in the integration checkout after the other owned changes are joined.
- Confirm actual inference behavior with a live model request; prompt wording and unit tests alone do not establish semantic compliance.
