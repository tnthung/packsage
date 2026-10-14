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

The first candidate loaded successfully with 566 mods and entered a new Survival world, **PackSage Evolution Trial**. Shaders remained disabled. ModernFix reported 259.43 seconds from menu to in-game and 408.99 seconds for its total load/open-world measurement; these are that mod's measurements, not instrumented PackSage latency. A process sample during the first query showed a 12.36 GiB working set. No synthetic fixture content was copied into Evolution.

The first six questions used GPT-6-Luna / medium, web Search off and Screenshot off. The test conversation is stored only in the Evolution instance under `packsage/PackSage_Evolution_Trial-2dc1b88ff37c5763/44f7c371-678f-48fb-ae5a-5bbb9e9e8c76-*.jsonl`; it was inspected for actual tool inputs/results.

| Check | Observed result |
|---|---|
| Torch recipe | Native loaded evidence returned four torches, coal-or-charcoal and one `#c:rods/wooden` ingredient with partial decoding. No JEI navigation target was returned. The model concatenated keywords into a literal search, reused a cursor after changing the query (correctly rejected), then used a broad search that hit the output cap. Completion was observed a little over two minutes after submission; per-call timings were not recorded, so latency is unattributed. |
| Getting Started | The initial question discovered the chapter and 36 raw quest IDs, then incorrectly attempted progress reads with undiscovered quest references. A diagnostic question searching the bare chapter ID subsequently retrieved quest references and current progress for six quests, their dependency chain and a config value in the same response. This confirms current progress retrieval but does not establish the initial question succeeds unaided. Smart-filter item tasks remain partial and are not a complete description of their matching rules. |
| Ars Nouveau guide | Both the first-spellbook question and an exact Ars Delight entry lookup returned no matching guide records under partial coverage. Patchouli's log and result detail report the unrelated `geneticsresequenced:guide` reload failure. Ars Nouveau 5.13.3 contains custom documentation code and a Patchouli compatibility handler; the installed addons extend its notebook. These observations do not establish a readable first-spellbook entry or an Ars navigation target. |
| Ars config | Saved `general.sylphManaCost` was 250, matching `config/ars_nouveau-common.toml`. The source was `SAVED_CONFIG`, partial, provider version `neoforge-21.1.250`, and loaded state unknown. Clicking the inline Config reference opened read-only source details with the same saved/loaded distinction. Other owners' config records also had unknown loaded values; the original query omitted the bridge diagnostic, so its cause remained unproven. |
| Citation display | The combined answer supplied Markdown local quest links and private provider citation markers, both displayed as raw text despite recorded navigation targets. This was a reproducible display defect. |

## Trial fixes and repeat-run results

`3ca9963` clarifies literal searches, query-bound cursors and quest discovery in tool descriptions and returns the loaded-config fallback diagnostic. `4b6a0b3` renders Markdown local links and private provider citation markers using only registered targets/sources, preserving code spans and web citation offsets. The focused tests and full baseline suite/build passed: 350 tests, 347 passed, three symlink privilege skips, no failures/errors. The original dependency matrix remains applicable to unchanged adapter calls.

The world was saved and Minecraft exited before replacement. The revised distribution and Evolution JAR hashes match: `4FAAFE210ED2186DB694D52CDC43A05DD348A7096BE741BF2B78B45AF6D7BED6`, built from `4b6a0b3`. Evolution was relaunched for affected live checks. ModernFix's second run reported 189.32 seconds from menu to in-game and 317.90 seconds for its total load/open-world measurement. A process sample showed an 11.46 GiB working set.

| Repeat check | Observed result |
|---|---|
| Combined quests/config | The natural-language question retrieved the Getting Started chapter's quests, current progress/dependencies for six quests, and the Ars config in the retained conversation. The quest and config search calls appeared together before their results in the recorded replay, confirming a parallel function-call batch. This was not a clean-session test. |
| Quest links | The six registered quest citations rendered as inline buttons. Clicking the first opened the actual FTB Quests **Punch A Tree** details, with the zero-progress dependency chain visible. Its valid-items view showed logs; the adapter's smart-filter wrapper is still only partial task semantics. No task submission or item transfer was performed. |
| Config diagnostic | Both search and direct read reported `unsupported NeoForge 21.1.250 FML 4.0.44 ConfigTracker catalog exceeded bounded extraction limits`. The live bridge therefore fell back because of a catalog bound, not the widened version gate. The exact exceeded bound was not recorded. FMLLoader reported 4.0.44 despite the inspected profile library being 4.0.45. Saved `general.sylphManaCost` remained 250; loaded values remain unknown. |
| Loaded guide/navigation | A combined torch/PneumaticCraft question retrieved the installed Patchouli `base_concepts/getting_started` entry, with complete coverage for that entry. Clicking its inline Guide button opened the actual **Getting Started** pages, matching the returned compressed-iron/pressure-system introduction. The earlier unrelated book reload error does not prevent every healthy book from being read. Ars first-spellbook coverage remains unproven. |
| Recipe comparison | The combined question returned native recipe evidence for outputs of 6, 8, 2 and 12 torches with partial decoding. Manual JEI inspection confirmed those outputs and a four-torch coal-or-charcoal/wooden-rod recipe. PackSage still returned no JEI navigation target, so recipe-reference navigation is not accepted by this trial. |
| Screenshot/history | With Screenshot on and Search off, the first answer identified position `8,75,-165` and Allium Shrubland (`biomeswevegone:allium_shrubland`), matching the game view. After respawning, `check again` in the same conversation retained the question and reported fresh position `24,70,-171` and the Draconic Evolution placed-item target at `24,71,-167`. The stored gameplay context and screenshot captured at 07:28:24 UTC confirm those values; the follow-up completed successfully. |

The test character was killed by a creeper while gameplay was running between screens, then respawned in this new trial world. Another explosion damaged the character near spawn. These events changed only the trial world; no existing player world was opened. The game's controls showed P conflicting with Social Interactions, Player Preferences, Insert Page Break, Place Item and View Pattern. P also triggered a placed-item action near spawn. PackSage was rebound to F8 in the Evolution profile; the controls reported no F8 conflict, and F8 opened chat successfully. Keypad 5 was briefly tried but conflicted with Narrate and was replaced before gameplay resumed. The world was saved and left at the title screen after the final check.

The focused pass establishes range acceptance, quest progress/navigation, a healthy Patchouli guide/navigation, saved-config provenance/source details and partial native recipe retrieval. It does not establish full real-pack acceptance: loaded-config capture exceeds the existing catalog bound, JEI reference retrieval is missing, Ars guide coverage is unproven, and smart-filter task semantics remain partial. Query completion was observed on the order of one to three minutes for the longer retrieval questions; no per-call timing was collected, so the delay is not attributed to a particular component.
