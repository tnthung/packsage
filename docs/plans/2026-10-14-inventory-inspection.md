# Inventory inspection implementation plan

Status: historical implementation plan. The [behavior-driven tool design](../specs/2026-10-14-behavior-driven-tools.md) proposes the next inventory contract; do not use these original tool-wiring steps as its migration plan.

**Spec:** [Inventory and armor inspection](../specs/2026-10-14-inventory-inspection.md).

1. Extend inventory capture and provider selection so an on-demand empty identity selection enumerates all occupied vanilla player slots, with explicit slot locations and merged components.
2. Extend the `read_inventory` schema/dispatch and grounding guidance to request enumeration without guessed IDs while preserving targeted lookups.
3. Run meaningful regression tests for capture, filtering, tool validation and dispatch, then run the full test/build suite. Follow existing style and do not run formatting tools.
4. Commit the inventory capability separately. Document the verified scope and any unverified live behavior.
5. Investigate opened-container lifecycle and client synchronization independently; implement container support only within the selected scope.
