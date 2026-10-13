# Chat controls implementation plan

Date: 2026-10-13

Spec: [chat controls and context visibility](../specs/2026-10-13-chat-controls.md).

1. Extend settings persistence and custom settings controls for the send shortcut and collapsed-session count. Verify valid/invalid values and field/hit geometry; commit separately.
2. Share historical budget/selection and role estimates between runtime and UI. Clamp known model limits, expose preview data, and verify mixed outcomes, whole-turn quota boundaries and unknown limits. Add safe latest-exchange replacement with world/session/latest-ID guards and reload tests. Inspect and test bounded HTTP 503 retry behavior; commit each independent functional slice.
3. Add Copy/Edit actions, shortcut behavior, inclusion dots/token labels and an equal-width wrapping composer toolbar with context meter, model/effort dropdowns, compact toggles and extra context. Use the same geometry for draw and hit testing; preserve read-only/active guards, draft state, inline sources and scrolling. Verify interaction and narrow layouts; commit independently.
4. Run the complete automated suite, review the bounded change, build/install committed source, and record artifacts and focused Minecraft checks. Preserve the unrelated v1 plan and user sketches.

Ownership: settings worker owns settings classes/tests; root owns custom chat/editor/tests/docs; backend worker owns runtime/controller/store/budget/backend classes and their tests. Only root runs Gradle and Git. Do not overlap file ownership.
