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

Implementation and automated results are pending. No installed status, guide search or fresh-read acceptance is claimed for the corrected artifact.
