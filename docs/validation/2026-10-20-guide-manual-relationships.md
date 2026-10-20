# Guide and manual relationship extraction validation

Date: 2026-10-20.

Scope: six structured relationship capabilities added to the existing guide/manual document sources. This report separates source inspection, automated checks, and installed acceptance. All six features remain pending implementation and installed acceptance.

## Installed artifacts and source inspection

Installed JARs in `C:/Users/hung1/curseforge/minecraft/Instances/Packsage/mods`:

| Source | Installed version | SHA-256 |
|---|---|---|
| GuideME | 21.1.19 | `2CE254EDB931C3C3CF81D69ABAF8F4CE5B871BF85E64984F366BF044173BD66A` |
| Immersive Engineering | 12.4.2-194 | `45942985A4A4AEBF265B8E22A0C54A96208637471F36F2532FF5D4911322DEBC` |
| Modonomicon | 1.120.7 | `1166EB113230BBC69A3E9464262195A336B29401EF03BFD47BFE86FD558578DA` |

The same GuideME and Immersive Engineering artifacts are present in `D:/Project/packsage/.tools/all-adapter-fixture`; their hashes match the installed copies. The fixture also contains Modonomicon 1.120.4 (SHA-256 `35D055E524C3491126EBE874984E8D77CB18460DE4479D03A36D9F00404CC726`); the current installed 1.120.7 artifact is not in that fixture.

Current reader source inspection establishes these access shapes, not native relationships or range-wide compatibility:

| Source | Existing native access shape | Relationship work introduced here |
|---|---|---|
| Modonomicon | Existing reader uses current book/category/entry/page structures, localized text holders, and current unlock/visibility checks. Earlier compatibility evidence compared 1.120.4 and 1.120.7 text-holder, manager, unlock-check, page, and title/body-holder descriptors. | Entry-to-item links, embedded recipe references, and prerequisite relationships. Exact spotlight-return/display-state cutovers and legacy visibility behavior remain at the compatibility checkpoint below pending the separate source investigation. |
| GuideME | Current reader calls `guideme.Guides.getAll()`, accesses each guide's private `pages` and `developmentPages` fields, and invokes `getLanguage()` on parsed-page values. | Page-to-item associations and recipe-tag relationships. |
| Immersive Engineering | Current reader calls `ManualHelper.getManual()`, reads `contentsByName`, invokes `showNodeInList` and `getTreeNode`/`getTitle`/`getLocation`, then reads localized manual text resources. | Manual recipe references while preserving formatting tokens and source entry identity. |

These shapes were inspected in the current PackSage readers and current installed artifacts. They do not establish that all requested relationship fields are present, complete, or exposed in the same way at the admitted version floors.

## Compatibility evidence checkpoint

The currently selected range floors are GuideME `21.1.0` and Immersive Engineering `12.0.0-182`; Modonomicon's current candidate range is `[1.98.1,2.0.0)`. The existing compatibility report records the first two floors from official release-line/changelog evidence, not direct comparison of the relationship-bearing APIs at those exact floors. The official [GuideME releases](https://github.com/AppliedEnergistics/GuideME/releases) identify releases by Minecraft line; the [Immersive Engineering 1.21.1 changelog](https://github.com/BluSunrize/ImmersiveEngineering/blob/1.21.1/changelog.md) identifies 12.0.0-182 as the first release for that line.

Floor artifact/source member comparison is not yet complete: no GuideME 21.1.0 or IE 12.0.0-182 floor artifact has been inspected in this checkpoint. The current artifact member evidence above must not be generalized to the full version ranges. No compatible implementation split is selected yet. A separate researcher is determining the exact Modonomicon spotlight-return and display-state transitions and older native visibility behavior; this checkpoint deliberately leaves those decisions open until that evidence arrives.

## Feature status

| Feature | Source inspection | Automated result | Installed acceptance |
|---|---|---|---|
| Modonomicon entry-to-item relationships | Pending | Pending | Pending |
| Modonomicon recipe references | Pending | Pending | Pending |
| Modonomicon prerequisites | Pending | Pending | Pending |
| GuideME page-to-item relationships | Pending | Pending | Pending |
| GuideME recipe-tag relationships | Pending | Pending | Pending |
| Immersive Engineering manual recipe references | Pending | Pending | Pending |

## Automated checks

Baseline only, before relationship implementation: Java 21 `gradlew.bat test --console=plain` succeeded with 644 tests, 0 failures, 0 errors, and 4 skipped. This does not validate any relationship feature.

## Installed acceptance

No relationship extraction or navigation acceptance has been performed for these six features. Existing text search and fresh evidence-read checks for the guide/manual adapters do not establish structured relationship extraction. Record each feature's observed source, returned relationship, completeness, and navigation behavior here only after an installed check.
