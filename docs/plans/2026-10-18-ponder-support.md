# Ponder support implementation plan

**Goal:** Add Ponder scene documentation and item-level player navigation through the existing tools.

**Spec:** [Ponder support](../specs/2026-10-18-ponder-support.md).

**Architecture:** Reuse `GuideDocumentProvider` with a configurable candidate bound and optional reader navigation hooks. Add an isolated reflective `PonderGuideReader` for the inspected runtime; retain registration locators and cursors, never compiled scenes or returned bodies.

## Constraints

- No formatting tools or unrelated refactoring. Preserve existing uncommitted validation work.
- Ponder remains optional, version-pinned to `1.0.81+mc1.21.1`.
- Search reads at most four candidate storyboards per call. Evidence resolves one current storyboard per handle with fresh private localization; all captions remain explicitly partial.
- Keep model signatures and document type `guide` unchanged. Player navigation opens the component viewer, with scope/registration checks on the client thread.
- Commit each written work step immediately using a staged-index commit message, before waiting for test results.

## Work steps

- [ ] Add failing tests for reader-owned navigation targets, stale scope handling and a lower candidate bound; implement the minimal shared guide hooks.
- [ ] Add failing Ponder reader tests for bounded title discovery, distinct registrations, fresh captions and stale/removal behavior; implement the reflective reader and its bounded source contract.
- [ ] Register optional Ponder metadata/version support and document the source semantics in grounding/help text; test optional metadata without Ponder installed.
- [ ] Run focused tests and the full test/build commands, review the patch independently, and fix material findings with regression coverage.
- [ ] Record exact API evidence, automated results and live acceptance limits; update roadmap entries only to the delivered scope.

## Review focus

- Same-schematic registrations must never overwrite or silently retarget each other.
- A changed/removed registration or source generation must not replay captions or open another storyboard's component.
- Search limits must stop callback execution itself, including no-match searches.
- Compilation must use fresh localization and avoid global reload, scene-world setup and simulation.
- Navigation must not queue and block the client thread; item-level opening must not claim a scene deep link.
