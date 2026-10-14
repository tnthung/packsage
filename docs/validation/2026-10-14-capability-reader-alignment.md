# Capability reader alignment validation

Date: 2026-10-14. Status: live acceptance in progress.

## Build and automated checks

- Implemented the [alignment specification](../specs/2026-10-14-capability-reader-alignment.md) through the [implementation plan](../plans/2026-10-14-capability-reader-alignment.md).
- Written changes were committed separately: inventory reader `7da808e`, storage/model wiring `feec82b`, compile correction `5ab67c9`, storage regressions `1d1e6f1`, grounding expectation correction `bc80257`, serializer regression `e3cc024`.
- Java 21.0.12.1 with Gradle 8.11.1: full `test build` passed after the grounding expectation correction. The suite contained 415 tests, with four existing skips. The updated inventory serializer regression also passed in a focused run.
- Scope includes vanilla player inventory/equipment, selected empty hand locations, scoped item references, name/ID/tag/tooltip search, direct vanilla container/bundle contents and bounded paging. Curios, mod-specific storage, wireless-network readers and closed-container server reads remain unsupported.
- Independent code review found no demonstrated runtime defect. It identified missing live empty-hand/extraction evidence and a serializer-budget regression gap; the fixture probe and the serializer regression below address the covered cases. Arbitrary mod tooltip failure behavior remains bounded by explicit partial coverage, not universal mod compatibility.

## Installed build

Initial live candidate SHA-256: `B256227722ED71543C29B1220C29C71D861279FFA62508A429D8C2541F8D4320`.

Copied `build/libs/packsage-0.1.0.jar` to `mods/packsage.jar` in all three closed CurseForge profiles and verified matching hashes. ATM10 was observed at its title screen and exited normally. Its original `OoO` save and existing verified backup were left untouched; subsequent ATM10 tests use `OoO - PackSage Trial`.

| Profile | Minecraft / NeoForge | Live status |
| --- | --- | --- |
| Packsage minimal fixture | 1.21.1 / 21.1.202 | Inventory/search/container and existing adapter probes passed; paging-contract follow-up pending. |
| FTB Evolution 1.45.0 | 1.21.1 / 21.1.250 | Pending. |
| ATM10 8.2 | 1.21.1 / 21.1.251 | Pending. |

## Minimal fixture observations

World: existing creative `New World`. Installed JEI 19.27.0.340, FTB Quests 2101.1.36 and Patchouli 92. The deterministic recipe and guide overrides remain active.

Replay: profile-local `packsage/New_World-8a315c270917e038/5962c299-64c5-4e38-94a7-bf26f667b874-2fe0b96ff7cf55a8.jsonl`.

- `ALIGN_MIN_A` completed at the live client. `read_inventory` returned complete coverage, one occupied coal stack (64, hotbar 0), and explicit empty mainhand (hotbar 1) and offhand (offhand 0) locations with usable references.
- Four separate `search_inventory` calls using name, ID, tag and tooltip fields each found the same coal stack with complete coverage. The tag result included `#minecraft:coals`; tooltip text included `Coal`. The five initial tool calls were issued before their results, verifying a parallel model-call batch.
- `read_storage(target=item)` on the selected empty-mainhand reference returned unavailable/unknown item storage, rather than claiming an empty supported container.
- For the positive container fixture, used the creative world's setup command to place a shulker box in `inventory.0` with seven diamonds at component slot 3. Both hands remained empty. This only changed the fixture world.
- `ALIGN_MIN_B` read that main slot 0 through a fresh inventory reference. Storage reported complete contents: seven diamonds, owner count one, main slot 0, component slot 3, with no additional page.
- The same probe retrieved the loaded Nature's Aura fixture marker through Patchouli, refreshed all three fixture quests through `read_progress`, and retrieved the native torch override plus a complete navigable JEI reference. Native recipe semantics remained explicitly partial.
- One unnecessary model call used storage payload `nextOffset=1` as cursor `1` despite `nextCursor=null`; the reader correctly rejected it as unknown. Added a regression to remove that misleading internal offset from model output. The corrected build and a final fixture paging probe remain pending.

## Remaining acceptance

- Complete the opaque-cursor correction and final automated check.
- Repeat the fixture paging/container probe with the corrected JAR.
- Run real inventory/search/item-storage and existing-adapter probes in FTB Evolution and ATM10, recording actual results and limits.
- No multiplayer/LAN, Curios, mod storage/network integration, or universal tooltip-provider compatibility is established by these checks.
