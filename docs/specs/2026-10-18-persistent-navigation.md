# Persistent source navigation

Date: 2026-10-18.

Extend the existing [chat navigation](2026-10-13-modern-chat-ui.md) so saved destinations can reopen after reconnecting to the same world, restarting Minecraft or reloading a provider. Preserve the historical answer and its evidence binding; opening a destination does not refresh the facts in that answer.

## Contract

- Keep the existing serialized `NavigationTarget(scope, providerId, sourceHandle, targetId)`. `sourceHandle` binds the historical citation to its recorded source; `targetId` identifies the destination independently of the old source object's lifetime.
- Add provider-owned asynchronous navigation resolution. The default retains exact source-handle resolution for providers with stable handles. A provider may resolve a durable destination into a new source handle and current scope. Only that resolved target is authorized for opening; resolution must not add historical references to the model's observed evidence.
- Keep the runtime's saved target/source membership, selected-world/read-only checks, current scope, selection generation and late-result guards. A destination from another world's read-only conversation cannot open. A changed request generation, resource generation or runtime alone must not expire a resolvable destination.
- Revalidate current registration, visibility, unlocks and availability before opening. Removed, hidden, inaccessible, malformed or ambiguous destinations fail explicitly. Never silently substitute another recipe, page or component.

## Destinations

- JEI recipes use category ID plus canonical recipe ID when the source exposes one. Resolve against current registered recipes without retaining the old recipe object; reject duplicate matches and exhausted lookup budgets. Categories can use their stable category ID. Recipes without a reliable persistent identity retain explicitly temporary navigation rather than pretending an object hash is durable. Migrate old canonical recipe handles where the saved handle unambiguously contains both IDs.
- Ponder uses the exact component resource ID already stored in existing targets. Resolve any current registration for that component and create a fresh source-bound target. Continue opening the ordinary component viewer, without claiming a single-scene deep link or executing storyboards during navigation resolution.
- Patchouli uses book ID, entry ID and page index, without persisting the provider lifecycle as destination identity. Accept canonical legacy lifecycle-suffixed destinations, then revalidate the current entry/page and issue a current target. The current viewer still enforces visibility and unlock checks.
- FTB Quests retains its existing stable quest ID and current-file resolution. External web destinations retain the recorded validated HTTP(S) URL; their old evidence scope alone does not invalidate a saved URL.

## Verification

Automated regressions must exercise saved targets with a recreated provider/runtime or replaced registration, different source handles, ordinary request progression, legacy target migration, duplicate/removed/malformed/hidden targets, foreign-world refusal and late completion. Include a conversation-store round trip so persistence is checked across serialized data, not just retained Java objects. Run focused suites followed by the full Java 21 test/build and `git diff --check`. No formatter or unrelated source changes. Record source/build results separately from any installed Minecraft observation.
