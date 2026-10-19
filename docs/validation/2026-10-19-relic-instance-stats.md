# Current relic stats and citation follow-up validation

Scope: the approved [instance contract](../specs/2026-10-19-relic-instance-stats.md), [plan](../plans/2026-10-19-relic-instance-stats.md), [API evidence](../research/2026-10-19-relic-instance-stats.md) and [player checks](../testing/2026-10-19-relic-instance-stats.md). Earlier installed equipment enumeration, type/index separation, empty reads, freshness and cosmetic exclusion are recorded in the [Curios report](2026-10-19-curios-equipment.md).

## Observed gap and implementation

Live request `51b2ec4d-9427-479c-a8c9-85cae4ae4a6b` could identify the equipped Springy Boot but reported that level/XP were unavailable because generic serialized `leveling` was empty. It reported stored quality values separately from effective stats. Its canonical item citation opened recorded Springy Boot evidence at Feet slot 0, so the ordinary source-control path worked for that answer.

The new optional reader uses the actual player and a copy of the freshly resolved stack. Native inventory capture and `read_evidence` both include typed `relicInstance` fields; ordinary item metadata and native location references remain intact. Successful zero values remain explicit, and failed fields retain partial/unknown coverage. The single Relics adapter supports the verified API family `[0.12.0,0.13.0)`, preserving the actual loaded version. This does not implement cooldown interpretation or upgrade actions.

The meaningful RED run followed corrections to a test fixture that attempted to create an item after Minecraft froze its registry. Tests now use registered stacks with a test-only public API root through the same projection method. The first projection run passed 49 of 50 focused tests and exposed an aggregate-budget fixture failure. Independent review then caught that stopping all abilities at 128 stats discarded later readable point counts. Review regressions reproduced that loss and missing known-empty maps; `11b054b` preserves independent ability reads within the 16-ability cap, bounds stat getters to 32 per ability/128 total, and retains explicit successfully empty maps. The bounded review recheck found both issues resolved.

The separate live spaced-citation repro, `[Springy Boot] (ref:f6e54518.3.1)`, now normalizes an exact current-request alias to a contiguous canonical Markdown link. Unknown, wrong-provider, slot, prose and code forms retain their behavior. Twenty focused citation tests cover every stream split, replay, standalone/adjacent brackets and web annotation offsets; independent review found no remaining actionable defect. The old malformed saved response is unchanged.

## Automated build and installation

Java 21 `gradlew test build` completed successfully in 25 seconds after the review correction: 53 suites, 627 tests, 623 passed, four existing skips, zero failures and zero errors. No formatting tools were used. Changed source was inspected manually and `git diff --check` passed at the automated checkpoint.

The verified artifact, distribution and minimal installed JAR all have SHA-256 `2BA9D395582FBFFC7EED821FEB0E39DB8AB4E771ED40AA96A62A1915FD9AAAE2`. Minecraft was saved and exited normally, minimal-process absence was verified, `dist/packsage.jar` was refreshed from `build/libs/packsage-0.1.0.jar`, its ignored metadata checksum was updated, and it was copied to `C:/Users/hung1/curseforge/minecraft/Instances/Packsage/mods/packsage.jar`. The matching minimal profile was then relaunched. Settings retain SHA-256 `50EFFFD1284FBD4C99B0606F8120880542E3EE9EA6CBA3BCD80D0B47DF411D0B`.

## Installed fixture and acceptance

The minimal profile is Minecraft 1.21.1, NeoForge 21.1.259, Curios 9.5.1+1.21.1 and Relics 0.12.8. The temporary boot was set to item-local level 2 and XP 123, then the player opened its Relics panel by holding Left Shift. The UI showed level 2, `123/300.0` XP and `41.0%` progress. After `/relics player research bounce true`, a second player-assisted panel check showed the Bounce description's `73%` force. Research is stored in this player's research map and is shared by copies of that ability; it is separate from the boot's level/XP. The same stack was returned to functional `curios:feet:0`; the necklace remains cosmetic-only.

Installed model checks passed in session `0104432d-dd33-4921-a10f-72c45733bbff-11a2feee49d63577`. The first question did not name the item or supply its values; the follow-up did not supply the changed XP. Both executed a new `search_inventory` call targeting the player with the `curios` section filter and returned complete equipment and `relicInstance` coverage.

| Request | Fresh evidence and visible answer | Acceptance |
| --- | --- | --- |
| `e7944ced-2151-44af-9358-9c8ec7b29fb4`, level/XP and current rolled stats | Capture `2026-10-19T03:58:27.805371700Z` identified Springy Boot at `curios:feet:0`, level 2, XP 123, rank 0, relic points 0 and Bounce points 0. The answer reported level/XP and all five effective values with their qualities. | Nonzero progression matched the Relics UI. The visible `Source: Springy Boot` control opened recorded Springy Boot evidence at Feet slot 0 with the matching capture time and canonical handle. No temporary alias appeared. |
| `c3c931f1-f855-4a9b-b700-ae8768cdb01f`, changed-XP follow-up | The same stack was moved to hotbar slot 4, changed with `/relics relic experience SET 137`, then returned to functional Feet slot 0. Capture `2026-10-19T04:01:42.311633100Z` reported level 2 and XP 137 with a new native inventory observation. The answer reported those current values and rendered a source control. | Freshness passed: the answer used 137 rather than the previous 123 without receiving the new number in the question. |

The first capture and visible answer agreed on these Bounce values. The UI's rounded `73%` force agrees with effective power `0.725`; other values are reported in the API's units.

| Stat | Effective value | Quality |
| --- | --- | --- |
| Radius | 1.0 | 0 |
| Damage | 3.75 | 5 |
| Stun | 0.25 | 0 |
| Damage modifier | 0.12 | 7 |
| Power | 0.725 | 9 |

Both final answers used canonical native inventory destinations and rendered source controls. The first control's recorded evidence interaction was checked directly. The model emitted ordinary Markdown in these final turns; spaced-destination normalization remains established by the deterministic citation regressions rather than a forced installed spaced-output trial. Earlier plain labels and the unnecessary empty-result placeholder remain model-output limitations recorded in the Curios report.

The installed calls exercised `search_inventory`; the integrated `read_evidence` path was source-reviewed and automated-tested but was not separately invoked in this live session. Failure, unsupported-version and traversal-bound behavior was checked with automated fixtures, not by breaking the installed instance. Live compatibility is established for Relics 0.12.8 with Curios 9.5.1+1.21.1; this does not establish an Accessories bridge or Artifacts progression implementation.

Minimal remains running with the temporary Springy Boot equipped at Feet slot 0, level 2 and XP 137, and the temporary necklace cosmetic-only. Original fixture contents and settings were preserved. The player's Bounce research change remains in the player research map; it was not item-local or reset. The final conversation is open for review.
