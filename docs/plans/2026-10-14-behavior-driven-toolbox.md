# Behavior-driven Toolbox implementation plan

> **For agentic workers:** Execute the approved design task by task, using test-first changes and one final independent review. The user has lifted the earlier no-commit restriction; commit each implementation step separately using the staged-index commit-message skill.

**Goal:** Make the running mod expose the twelve behavior-driven tools through registered adapters, with fresh source reads, and verify the result in Minecraft using the computer-use skill.

**Architecture:** `Toolbox` supplies default asynchronous `NotHandled` methods. `AdapterRegistry` performs first-handled dispatch; source adapters own matching and extraction. `KnowledgeService` retains only scoped reference/continuation metadata and composes mixed-reference reads. Discovery combines registered document/recipe contributions with bounded source-qualified subrequests.

**Tech stack:** Java 21, Minecraft 1.21.1, NeoForge 21.1.202, Gradle/JUnit, existing JEI/FTB Quests/Patchouli integrations.

**Spec:** [Behavior-driven tools](../specs/2026-10-14-behavior-driven-tools.md) and [expanded adapter design](../research/2026-10-14-toolbox-adapter-migration.md).

## Global constraints

- Preserve unrelated working-tree/index contents. Commit each authorized work step separately; do not reset or use formatting tools.
- Keep all twelve public signatures; remove the old `search_pack`, `read_inventory` and `read_storage` public entries.
- Only `NotHandled` advances to another adapter. Invalid arguments/references/cursors, stale scope and unsupported filters propagate through combined discovery.
- Read every source at call time. Cursors/references contain identity/query/position metadata, never saved result bodies. Temporary call-local copies are allowed.
- Inventory targets include player, opened, item, targeted block/entity and the directly ridden entity. Use native contracts for modded IDs too.
- Preserve source authority, partial coverage, exact ID/tag semantics, component variants and read-only access. No automatic opening, chunk loading, loot generation or gameplay mutation by tools.
- Use a verified logical-server path for unsynchronized contents. The integrated server can supply that path; remote servers without an authorized endpoint return explicit unavailable coverage.
- Run automated checks locally, then build/install and run live checks through `computer-use:computer-use`. Record automated and live evidence separately.

## Review focus

- A registered adapter returning empty success or a real failure must prevent fallback; adding a target/source must not require dispatcher edits.
- Changing an item in a previously observed slot or changing contents between pages must produce fresh data; changing the owner must invalidate continuation.
- Closing an opened menu, dismounting, pending loot, locked containers and unavailable remote-server contents must never replay old contents or perform interactions.
- Invalid source cursors/output identities must fail combined discovery; large individual quests/books must stop extraction itself and continue without cached fragments.
- Mixed-owner handles, config entry aliases, history replacement exclusion, recipe-plan references and navigation must retain their intended identity and scope.

## Tasks

### 1. Toolbox contract and registry

Files: new `knowledge/Toolbox.java`, `knowledge/AdapterRegistry.java`; `KnowledgeProvider.java`; new `ToolboxTest.java`.

- [ ] Add failing tests for default decline, ordered fallback, empty/partial success, real-error propagation, duplicate registration and metadata contributions.
- [ ] Introduce twelve methods returning `CompletionStage<Evidence.Result>`, structured asynchronous failures, scoped reference/context data and adapter metadata. Register instances, not matching callbacks.
- [ ] Pass the focused registry tests.

### 2. Public schemas and grounding

Files: `model/PackSageTools.java`, `model/GroundingInstructions.java` and corresponding tests.

- [ ] Pin the new twelve-tool catalog and nested target/filter validation with failing tests.
- [ ] Supply adapter-contributed target/registry/provider metadata to tool definitions and validation; preserve existing limits and model-backend nullable schema requirements.
- [ ] Update grounding to use discovery families, unified inventory selection and fresh references.
- [ ] Pass focused model tests.

### 3. Service composition, references, config and history

Files: `KnowledgeService.java`, `client/ClientAssistant.java`, `chat/AssistantRuntime.java`, `chat/HistoryAdapter.java`, `config/ConfigProvider.java`; corresponding integration tests.

- [ ] Test metadata-driven document/recipe discovery, strict request-error propagation, mixed-owner refresh and live config/history reads.
- [ ] Replace provider/target switches with registry calls; keep only reference metadata and compose bounded discovery inside a coordinating adapter.
- [ ] Register history with replacement exclusion, retain version gates/status adapters, and preserve navigation/lifecycle checks.
- [ ] Pass focused service/runtime/config/history tests.

### 4. Native inventory and registries

Files: `nativegame/NativeProvider.java`, `NativeInventoryReader.java`, `NativeSnapshot.java`, `inventory/InventoryReader.java`, `storage/NativeStorageReader.java`, `StorageReader.java`, `client/ClientAssistant.java`; native reader tests.

- [ ] Test filter combinations, live pagination, location references, all five selectors and six registry kinds.
- [ ] Implement native adapter operations, standard definitions and current instance evidence; keep registry definitions distinct from actual stacks/effects.
- [ ] Replace captured opened-menu reuse with a maintained live menu or explicit unavailability. Read integrated-server containers/entities on the server thread with identity/access/loot guards; remote unsupported paths remain unavailable.
- [ ] Preserve direct item component reads, equipment/cargo sections and component-distinct variants.
- [ ] Pass focused native tests.

### 5. Optional adapters and bounded document extraction

Files: `jei/JeiProvider.java`, `ftb/FtbProvider.java`, `patchouli/PatchouliProvider.java` and corresponding tests.

- [ ] Test fresh handle reads, JEI output focus, document type/provider matching and bounded extraction/continuation on large documents.
- [ ] Move matching into adapter methods. Refresh live source identities for reads/navigation without replaying old bodies.
- [ ] Charge shared document budgets before traversal/copy/decode and keep nested continuation metadata only. Preserve structured fields and unknown custom semantics.
- [ ] Pass focused provider tests.

### 6. Full checks and live verification

Files: updated testing/validation records under `docs/testing/` and `docs/validation/`, plus this progress checklist.

- [ ] Run the full JUnit suite/build with the portable Java 21 environment; fix failures.
- [ ] Obtain an independent review of the implementation and resolve material findings.
- [ ] Run `scripts/build.ps1` to produce/install the candidate; verify its artifact identity.
- [ ] Use the computer-use skill to launch/restart the fixture instance and test the tool catalog, inventory selectors/freshness, registry kinds, document/recipe discovery and reference refresh.
- [ ] Record exact observed results and any environment-dependent coverage limits; finish with `git diff --check`.

## Progress and decisions

- Initial inspection: current dispatch is centralized in `KnowledgeService`; history is special-cased in `AssistantRuntime`; opened storage captures before closing the menu; native slot resolution compares old stack contents. These paths require behavioral migration, not only renaming tools.
- Initial server decision: support authoritative integrated-server reads with pure access checks, and keep remote-server-only contents unavailable unless a verified endpoint exists. No generic remote debug/NBT query will be used.
- Runtime migration is in progress. The user explicitly authorized separate implementation commits on 2026-10-14; automated/live results will be recorded as they run.
