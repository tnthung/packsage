# Capability reader alignment implementation plan

**Goal:** Align existing model reads with shared inventory and slot-based storage capabilities.

**Architecture:** An `InventoryReader` returns scoped records and issues per-observation slot references. Its native implementation owns copied slot observations and validates them on resolution. `NativeStorageReader` consumes that native slot resolver while returning the same generic storage evidence contract; model dispatch never gains mod-specific calling conventions.

**Tech stack:** Java 21, Minecraft 1.21.1, NeoForge, existing evidence types and JUnit 5.

**Spec:** [Capability reader alignment](../specs/2026-10-14-capability-reader-alignment.md).

## Constraints

- No formatting tools, new dependencies, gameplay mutation or changes to unrelated Git state.
- Inventory search currently covers vanilla player slots and equipment; future mod/network sources remain explicit pending work.
- Preserve native section-local slot indices and existing opened/targeted storage access limits.
- Use distinct coherent commits, with messages generated from the staged index through `$commit-message`.

## Review focus

- Replacing a stack with the same item ID but different container contents invalidates its old reference.
- Separate hotbar/main/armor/offhand coordinates cannot resolve to another section.
- Empty hands still identify selected slots without becoming occupied-item observations.
- Failed tooltip extraction cannot turn no matches into proof of absence.
- Paging retains the original immutable observation and fails when filters or request scope change.

## Task 1: Shared inventory reader and search

**Files:** create `knowledge/inventory/InventoryReader.java` and `knowledge/nativegame/NativeInventoryReader.java`; modify `NativeSnapshot.java`; add/update their inventory tests.

**Interfaces:** `InventoryReader.Request(Operation operation, List<String> identities, String text, List<String> fields, int limit, String cursor, Evidence.Scope scope)`, with `Operation.READ` and `SEARCH`; `CompletionStage<Evidence.Result> read(Request request)`. `NativeInventoryReader` has production constructor `(Minecraft, Executor, Supplier<Evidence.Scope>)`, `clear()`, and client-thread `LocatedItem resolve(String slotReference, Evidence.Scope scope)`. `LocatedItem` identifies section, local slot, role and a copied stack. Keep test capture seams within the native implementation.

- [ ] Write and run failing tests for nested-content omission, field matching, malformed IDs, selected empty slots, scoped immutable references and stable bounded cursors.
- [ ] Implement capture, metadata bounds and native reference resolution, with `ItemStack` count/component comparison on a fresh lookup.
- [ ] Run focused inventory tests and record results. Do not run concurrent Gradle processes while another writer changes shared integration files.

## Task 2: Slot-based storage and model wiring

**Files:** `StorageReader.java`, `NativeStorageReader.java`, `PackSageTools.java`, `KnowledgeService.java`, `GroundingInstructions.java`, `ClientAssistant.java`, their tests.

**Interfaces:** `StorageReader.Target.ITEM` replaces `HELD`. `StorageReader.Request` adds nullable `String slotReference` immediately after target; item reads require it, opened/targeted reads reject it. The native storage constructor receives the native inventory slot resolver. KnowledgeService consumes `InventoryReader`, routes `read_inventory`/`search_inventory` requests, and passes item references to storage.

- [ ] Write/run failing schema and storage tests for item references, section identity, stale stack/component rejection and selected-item coverage.
- [ ] Replace hand-loop capture with selected-slot capture and bind storage continuations to the reference as well as filters/scope. Preserve existing opened/targeted regression cases.
- [ ] Wire both readers into ClientAssistant; clear inventory references with storage observations on invalidation. Update tool descriptions and grounding instructions.
- [ ] Run focused integration tests and full `gradlew.bat test build --no-daemon`; handle failures and commit the coherent inventory/storage alignment.

## Task 3: Live acceptance and documentation

**Files:** add `docs/validation/2026-10-14-capability-reader-alignment.md`; update roadmap completion limits and changelog.

- [ ] Use computer-use to save/exit any active client; copy the verified JAR to all three closed profiles and verify hashes.
- [ ] Launch minimal fixture, FTB Evolution and ATM10 sequentially. Exercise inventory/search/slot-storage through real chat tools and inspect replay logs. Check existing guide/quest/recipe access; preserve unsupported-source unknown results.
- [ ] Record pack versions, world used, observed queries/results, JAR hash and any live limitations. Mark only actually delivered roadmap capabilities complete.
- [ ] Run local Markdown-link checks and `git diff --check`; commit acceptance/docs separately.
