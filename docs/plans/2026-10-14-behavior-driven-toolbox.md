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

- [x] Add failing tests for default decline, ordered fallback, empty/partial success, real-error propagation, duplicate registration and metadata contributions.
- [x] Introduce twelve methods returning `CompletionStage<Evidence.Result>`, structured asynchronous failures, scoped reference/context data and adapter metadata. Register instances, not matching callbacks.
- [x] Pass the focused registry tests.

### 2. Public schemas and grounding

Files: `model/PackSageTools.java`, `model/GroundingInstructions.java` and corresponding tests.

- [x] Pin the new twelve-tool catalog and nested target/filter validation with failing tests.
- [x] Supply adapter-contributed target/registry/provider metadata to tool definitions and validation; preserve existing limits and model-backend nullable schema requirements.
- [x] Update grounding to use discovery families, unified inventory selection and fresh references.
- [x] Pass focused model tests.

### 3. Service composition, references, config and history

Files: `KnowledgeService.java`, `client/ClientAssistant.java`, `chat/AssistantRuntime.java`, `chat/HistoryAdapter.java`, `config/ConfigProvider.java`; corresponding integration tests.

- [x] Test metadata-driven document/recipe discovery, strict request-error propagation, mixed-owner refresh and live config/history reads.
- [x] Replace provider/target switches with registry calls; keep only reference metadata and compose bounded discovery inside a coordinating adapter.
- [x] Register history with replacement exclusion, retain version gates/status adapters, and preserve navigation/lifecycle checks.
- [x] Pass focused service/runtime/config/history tests.

### 4. Native inventory and registries

Files: `nativegame/NativeProvider.java`, `NativeInventoryReader.java`, `NativeSnapshot.java`, `inventory/InventoryReader.java`, `storage/NativeStorageReader.java`, `StorageReader.java`, `client/ClientAssistant.java`; native reader tests.

- [x] Test filter combinations, live pagination, location references, all five selectors and six registry kinds.
- [x] Implement native adapter operations, standard definitions and current instance evidence; keep registry definitions distinct from actual stacks/effects.
- [x] Replace captured opened-menu reuse with a maintained live menu or explicit unavailability. Read integrated-server containers/entities on the server thread with identity/access/loot guards; remote unsupported paths remain unavailable.
- [x] Preserve direct item component reads, equipment/cargo sections and component-distinct variants.
- [x] Pass focused native tests.

### 5. Optional adapters and bounded document extraction

Files: `jei/JeiProvider.java`, `ftb/FtbProvider.java`, `patchouli/PatchouliProvider.java` and corresponding tests.

- [x] Test fresh handle reads, JEI output focus, document type/provider matching and bounded extraction/continuation on large documents.
- [x] Move matching into adapter methods. Refresh live source identities for reads/navigation without replaying old bodies.
- [x] Charge shared document budgets before traversal/copy/decode and keep nested continuation metadata only. Preserve structured fields and unknown custom semantics.
- [x] Pass focused provider tests.

### 6. Full checks and live verification

Files: updated testing/validation records under `docs/testing/` and `docs/validation/`, plus this progress checklist.

- [x] Run the full JUnit suite/build with the portable Java 21 environment; fix failures.
- [x] Obtain an independent review of the implementation and resolve material findings.
- [x] Produce/install the full candidate and verify artifact identity. Final `test build` ran separately; after the player resumed access, the same tested JAR was copied directly to ATM10 without rerunning the installer script.
- [x] Use the computer-use skill to launch/restart the fixture instance and test the tool catalog, inventory selectors/freshness, registry kinds, document/recipe discovery and reference refresh.
- [x] Record exact observed results and any environment-dependent coverage limits; finish with `git diff --check`.

## Progress and decisions

- Initial inspection: current dispatch is centralized in `KnowledgeService`; history is special-cased in `AssistantRuntime`; opened storage captures before closing the menu; native slot resolution compares old stack contents. These paths require behavioral migration, not only renaming tools.
- Initial server decision: support authoritative integrated-server reads with pure access checks, and keep remote-server-only contents unavailable unless a verified endpoint exists. No generic remote debug/NBT query will be used.
- Runtime migration is implemented. The user explicitly authorized separate implementation commits on 2026-10-14; subsequent live findings are tracked separately from the initial acceptance checkpoint.

- Private native endpoint policy: server reads are authorized only for an unpublished integrated server with exactly one connected player, whose UUID matches both the captured client and the integrated singleplayer profile. This is an explicit local owner read policy, not a claim to reproduce third-party protection interaction hooks. Revalidate on the server thread; inspect only already loaded targets under reviewed native contracts; deny locks, pending loot, excessive range, changed ownership and unsupported contracts. Remote/published sessions need a separate authenticated endpoint. Server-derived records use `SERVER_READ` authority and identify this policy.

- Final local checkpoint `3869ee3`: 439 tests, zero failures/errors, four skips; full build passed. Source review closed through `0ebd365`. Installation and live testing were initially paused at the player's request and then resumed with their go-ahead. See the [validation record](../validation/2026-10-14-behavior-driven-toolbox.md) for the artifact hash, observed acceptance and limits.

- Installed live run and prepared-fixture follow-ups completed after the player resumed access: 22 requests, 96 tool calls, all twelve signatures exercised. Positive chest, minecart, bundle/shulker and donkey evidence, remaining fixture gaps, scan-bound limitations and discovered defects are recorded in validation. Follow-up corrections and their own acceptance do not retroactively change the initial artifact's results.
