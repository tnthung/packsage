# FTB Evolution trial evidence

## Installed target and API audit

The user's CurseForge profile completed installation of FTB Evolution 1.45.0 for Minecraft 1.21.1 / NeoForge 21.1.250. Its `saves` folder was empty before the trial. The target loader libraries contain FML 4.0.45. PackSage's loader range change was committed as `597c454`; the packaged-metadata boundary test passed.

| Runtime file | SHA-256 |
|---|---|
| `architectury-13.0.11-neoforge.jar` | `9CC92F2C09533FC5482C60F993BD891C655A0E32B637037CDCB7C2B23ADEDEEB` |
| `ftb-library-neoforge-2101.1.37.jar` | `624C2D2D1CBADB946D967E17757BD5B2D129D4D3ED71F2DA0C70607D04476AC2` |
| `ftb-quests-neoforge-2101.1.36.jar` | `6A39982C4B3AFC00B1E788041A4F0D394E1659E3B3A6E5D078CD790BC36D0018` |
| `ftb-teams-neoforge-2101.1.11.jar` | `E6BCD670493624E6906DF872A9C6AF4A33FE6405CAC57A32C59CD4CD5838C655` |
| `jei-1.21.1-neoforge-19.57.0.450.jar` | `238B20C943BB7C1E4ED387B2D1BC463C656F44AB7C388DA1EEE148F54D383B52` |
| `Patchouli-1.21.1-93-NEOFORGE.jar` | `959AF52ED6640C316C3A8469203420BE4AEEA11AD6603890BA83BF48F5D9F993` |

The target JEI artifact matched the inspected official Maven artifact. The Patchouli artifact was inspected from Curse Maven file 7730942 and matched the installed file. Compared with the fixture versions, the public methods used by the adapters remained present. Patchouli changed a `BookEntry` constructor used only by a test fixture; production does not call it. JEI marked three used methods for removal, but they still compile in 19.57.0.450.

The ConfigTracker audit found the required `INSTANCE`, `configSets`/`configsByMod`, ModConfig getters, `ILoadedConfig.config()`, ModConfigSpec/ValueSpec/Range methods and NightConfig `valueMap()` unchanged. This signature audit supports the attempted compatibility ranges; it does not establish extraction completeness in the real pack.

## Automated checks

The final full suite/build passed on both the fixture dependency set and the Evolution dependency set: each ran 349 tests, with 346 passed, three Windows symlink tests skipped, zero failures/errors. The Evolution run used `-Pneo_version=21.1.250 -Pjei_version=19.57.0.450 -Ppatchouli_file_id=7730942`. Its initial test compilation exposed the older Patchouli constructor in a fixture; that fixture now supports both public signatures and the unchanged capture assertions passed in both runs.

Adapter ranges and observed-version provenance were committed as `f824a48`. The final candidate was compiled on the older fixture baseline and installed into Evolution while no game process was running. Distribution and installed JAR SHA-256 match: `8B6A44FDC980416313C6A6662507CF96DFDBEB8128F17A3D59B4777BF4F9C02F`. Existing user preferences were copied into the new profile's previously absent PackSage settings file; no credentials or conversation logs were copied.

Independent review found no remaining material defect after non-null recipe-payload preservation was tested and the reflective config bridge's linkage failures were converted to unsupported results for saved-file fallback.

## Live checks

The candidate is installed. Use the [real-pack checklist](../testing/2026-10-13-ftb-evolution.md); startup/world entry, retrieval/navigation and responsiveness remain pending at this checkpoint. No synthetic fixture content was copied into Evolution.
