# History adapter implementation

Spec: [model-searchable conversation history](../specs/2026-10-13-history-adapter.md). Preserve the unrelated v1 plan, sketches and roadmap state. No formatting tools or computer use.

- [x] Added bounded text-only search/read APIs on the serialized store I/O path, including label changes, typed cursors/locators, oversized continuation, partial diagnostics and disk revalidation. All 24 store tests passed or skipped as expected: 20 passed, four Windows symlink skips. Independent review corrections for actual header IDs, catalog-limit coverage and final-page exhaustion are included. Commit storage independently.
- [ ] Add the request-scoped history adapter, `search_history`/`read_history` schemas, runtime dispatch, activity labels and grounding instructions. Return excerpts and bounded reads as historical records; test handle/cursor/scope validation, runtime wiring and access beyond automatic history limits. Commit model integration separately.
- [ ] Review the scope, I/O and stale-record invariants; run focused tests and the final automated suite/build. Build distribution without modifying the running Minecraft installation. Record verified behavior and remaining live-test status.
