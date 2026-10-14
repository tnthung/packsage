# Storage inspection implementation plan

Status: historical implementation plan. The [behavior-driven tool design](../specs/2026-10-14-behavior-driven-tools.md) proposes the next inventory contract; do not use these original tool-wiring steps as its migration plan.

**Spec:** [Storage inspection specification](../specs/2026-10-14-storage-inspection.md).

1. Add the internal storage request/reader boundary and native immutable observations with bounded continuation paging. Verify capture and freshness behavior with meaningful tests.
2. Add the configured-shortcut entry path from container screens: capture before normal close, then use the ordinary chat-opening path. Clear retained observations on lifecycle invalidation.
3. After inventory changes settle, add `read_storage` schema, knowledge dispatch and grounding guidance without changing the legacy knowledge constructor contract.
4. Run focused tests and the full test/build suite; review style and diffs without formatting tools. Commit storage support separately from inventory support.
5. Package the verified artifact. Any live installation requires a normal Minecraft shutdown first; test only the backed-up trial world and record proven behavior and unavailable cases separately.
