# Guide adapter API compatibility

Date: 2026-10-19.

Scope: correct the exact-version rejections under the current `AGENTS.md` adapter policy and the [guide adapter plan](../plans/2026-10-15-guide-manual-adapters.md). Keep the existing logical providers, source traversal and unavailable/partial behavior.

## Installed evidence

The current `C:/Users/hung1/curseforge/minecraft/Instances/Packsage/mods` contains Artifacts 13.2.5, Modonomicon 1.120.7, GuideME 21.1.19, Immersive Engineering 12.4.2-194, Actually Additions 1.3.26+mc1.21.1 and Relics 0.12.8. Reliquified Artifacts is absent. The ATM10 profile has the same six versions and Reliquified Artifacts 1.0.9.

The original gates accept only Artifacts 13.2.3 and Modonomicon 1.120.4, explaining both reported red statuses. The six readers also report historical hardcoded versions in source evidence. The replacement path additionally rejects every Reliquified release except 1.0.8 and every Relics release except 0.12.8.

`javap` comparisons of the retained fixture and current JARs establish matching required descriptors for:

- Artifacts 13.2.3 and 13.2.5: `ModDataComponents.TOOLTIP_ORDER`, `EquipmentAbility.addToTooltip(TooltipWriter)` and the four-argument tooltip-writer constructor.
- Modonomicon 1.120.4 and 1.120.7: text-holder strings, book-manager readiness/maps, category/entry/page unlock checks, entry pages and text-page title/body holders.

Reliquified Artifacts 1.0.9's embedded NeoForge metadata requires Artifacts `[13.2.5]` and Relics `[0.12.3,)`. Those are the add-on's own loading constraints; NeoForge enforces them independently of PackSage. PackSage still needs the Relics reader API when selecting that replacement path.

## API ranges

| Provider | Selected range | Evidence and boundary limits |
|---|---|---|
| Modonomicon | `[1.98.1,2.0.0)` | The required API exists in the earliest 1.21.1 tag found, [1.98.1 text holder](https://github.com/klikli-dev/modonomicon/blob/release/v1.21.1-1.98.1/common/src/main/java/com/klikli_dev/modonomicon/book/BookTextHolder.java), [book manager](https://github.com/klikli-dev/modonomicon/blob/release/v1.21.1-1.98.1/common/src/main/java/com/klikli_dev/modonomicon/data/BookDataManager.java) and [unlock manager](https://github.com/klikli-dev/modonomicon/blob/release/v1.21.1-1.98.1/common/src/main/java/com/klikli_dev/modonomicon/bookstate/BookUnlockStateManager.java). Current fixture/update descriptors match. The next major boundary is a forward compatibility policy, not an observed break. |
| GuideME | `[21.1.0,21.2.0)` | The Minecraft 1.21.1 release line is recorded in the [official releases](https://github.com/AppliedEnergistics/GuideME/releases). The reader accesses private page maps, so structural changes within this line remain a compatibility risk. |
| Immersive Engineering | `[12.0.0-182,13.0.0)` | The [1.21.1 changelog](https://github.com/BluSunrize/ImmersiveEngineering/blob/1.21.1/changelog.md) identifies 12.0.0-182 as the first release for this Minecraft line. The installed manual API was inspected at 12.4.2-194. |
| Actually Additions | `[1.3.0,1.4.0)` | The [official changelog](https://github.com/Ellpeck/ActuallyAdditions/blob/main/CHANGELOG.md) identifies 1.3.0 as the 1.21.1 port. The reader selects the loaded Patchouli booklet rather than calling an AA-specific Java booklet API. |
| Relics | `[0.12.8,0.13.0)` | The current 0.12.8 ability/template/mode API is directly inspected. Earlier compatible API floors have not been established; the next minor line is a forward compatibility boundary, not a demonstrated break. |
| Artifacts | `[13.2.3,14.0.0)` | Direct reader signature agreement at 13.2.3 and 13.2.5. Earlier 13.x history includes component-package changes, so the component migration alone does not establish a 13.0 floor. The next major line is a forward compatibility boundary, not a demonstrated break. |
| Reliquified Artifacts replacement | `[1.0.8,2.0.0)` with the supported Relics range | The prior 1.0.8 replacement path and installed 1.0.9 retain Relics-based item definitions. The source owns the Artifacts provider identity/version; the loader owns declared dependency checks. |

These ranges admit compatible release families instead of rejecting every untested patch. They do not claim binary testing of every historical or future release, proven incompatibility of every excluded older version, or exhaustive earliest API boundaries. Existing reflection failures remain explicit. No separate reader is needed for the two reported updates because their required API descriptors match.

## Automated and installed acceptance

The six exact pins are replaced by the ranges above. Registration passes the detected, unmodified version string into all seven guide-reader factories, including Ponder. Provider status and evidence retain that actual version, including Minecraft suffixes. The Reliquified path supplies the loaded Artifacts version to its Relics-based reader and uses inline add-on/Relics range checks. Existing FTB Quests and Patchouli factories retain their two-argument contracts.

The regression `ClientAssistantTest.guideAdapterCompatibilityRangesAcceptCurrentAndBoundaryReleases` first failed at the installed Modonomicon 1.120.7 input against the original exact pin. It now passes alongside accepted floors, updated patches, excluded upper bounds and malformed/foreign-version cases. Reader tests cover observed status versions and suffix-preserving source metadata.

Independent review caught a regression in the first correction: the shared reflective loader also serves FTB Quests and Patchouli, whose factories take two arguments. The new `optionalMinecraftFactoryLoadsTwoArgumentProvidersAndVersionedGuideProviders` test exercised the real production loader and failed on FTB loading before the correction. The loader now preserves the two existing contracts while passing the actual version to guide factories. Review rechecked this fix and reported no remaining concrete behavior defect.

With the repository's portable Java 21, `gradlew.bat --offline test build` completed successfully. JUnit XML totals: **605 tests, 601 passed, four skipped, zero failures/errors**. Both new ClientAssistant regressions passed. The final source/diff style pass used no formatter.

`build/libs/packsage-0.1.0.jar` and `dist/packsage.jar` match SHA-256 `3CFFEAFDFF9C803D8DD4F2575658DA8CA364FEB45811374E446C9E82FB8803BD`. The distribution metadata records that hash. No installed instance JAR was replaced during this correction.

No installed tooltip, guide search or fresh-read acceptance is claimed for the corrected artifact. The live ModList-based Reliquified branch has no direct automated acceptance test; its range checks and loaded-version forwarding were source-reviewed, while installed 1.0.9 metadata supplies dependency evidence. These limits remain separate from the passing automated suite.
