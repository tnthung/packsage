# Ponder support validation

Date: 2026-10-18.

Scope: [specification](../specs/2026-10-18-ponder-support.md), [implementation plan](../plans/2026-10-18-ponder-support.md) and [API compatibility evidence](../research/2026-10-18-ponder-api.md).

## Automated evidence

Java 21.0.12.1, Minecraft 1.21.1 and NeoForge 21.1.202 remain the build/test baseline. The optional adapter accepts the Minecraft 1.21.1 Ponder API family `[0.8.1,1.1)` using numeric comparison after normalizing the known Minecraft suffix. Inspected API samples span `0.8.1` through `1.0.87`; `1.1` is the next-family audit boundary, not an observed breaking release. Each installed artifact's own game/loader requirements still apply; the inspected recent bundled artifacts require NeoForge 21.1.206 or later. No Ponder dependency was added to the base distribution.

- `gradlew.bat test build`: successful; 558 tests, 554 passed, four existing skips, zero failures/errors. The result XML includes `PonderGuideReaderTest` (11), `GuideDocumentProviderTest` (12), `ClientAssistantTest` (8) and `ScreenTextTest` (33). The final range-correction build completed in 32 seconds. Existing JEI deprecation/unchecked-operation warnings remain in the project; the incremental final build emitted none.
- Focused checks cover four-callback title/no-match searches, fresh caption rereads, same-schematic registrations, removed/replaced handles, pagination without skipped lookahead entries, bounded copied text, numeric caption order, localized display-text matching, partial-evidence completeness and navigation scope/thread checks.
- Ponder citation rendering was observed failing before adding the provider marker and then passed in the full suite, covering both ordinary and grouped citation forms. Optional-provider metadata was also observed failing before registration and passed afterward.
- Independent review checked the reflected APIs against the exact nested JAR. Its findings about incomplete evidence claiming completeness and repeated title/header output were corrected; regression assertions cover both behaviors.
- A combined-discovery regression first reproduced repeated provider pages exceeding the four-callback limit. Callback accounting now lives in the shared tool-call budget; the full suite verifies four callbacks per combined search call and correct continuation on the next call.
- The configured-version regression first failed on `1.0.81+mc1.21.1`: the generic version parser rejected its suffix, and the initial adapter also pinned one release while Create's selected copy was `1.0.82+mc1.21.1`. The corrected gate passes historical bare versions and current labels throughout the configured family, rejects versions below `0.8.1`, `1.1.0` and other Minecraft suffixes, and accepts later `1.0.x` labels by policy. The synthetic `1.0.99` assertion tests that policy, not an unpublished library.
- A fresh-evidence regression covers the actual runtime version (`1.0.87+mc1.21.1` fixture) in source provenance. The missing version-aware constructor was observed failing test compilation before implementation; the full suite now passes with no fixed release reported for every installation.
- Official source history was inspected at nine older releases and all eight new-coordinate Minecraft 1.21.1 releases through `1.0.87`; the relevant reflected surface and localization behavior are compatible. Installed `1.0.81`/`1.0.82` bytecode inspection also agrees. These are API checks, not runtime extraction acceptance across those releases.

The versioned source inspection and unit fixtures do not establish runtime extraction of every Create/PonderJS callback. Some callbacks may fail with a null scene world, use shared/dynamic captions, or perform custom code outside the literal-text source contract.

## Initial artifact and installed status

The successful build produced `build/libs/packsage-0.1.0.jar`, copied to `dist/packsage.jar`.

SHA-256: `18D4B61B024981EE8333E972271E3CD4FDAAD52FEC49A2012CC82ED3DFE88877`.

The built JAR, distribution copy and `dist/build-metadata.json` hashes match. The JAR contains `PonderGuideReader` and does not bundle Ponder library classes.

The initial artifact was not copied into a Minecraft instance during that implementation step. A later [all-adapter fixture check](2026-10-18-all-adapter-fixture.md) installed a newer build and observed all thirteen adapter statuses. That startup check does not establish Ponder retrieval or viewer acceptance.

## Exact-component correction

The player's `show me the usage of create presser in ponder` request (`9a86f2b2-c0b0-4ee7-8061-f4231a913c9a`) began at 19:04:05.986 Taipei time. Its saved trace contains fifteen Ponder document searches and one native item search, within 33 displayed activity steps. The last Ponder result arrived about 65.5 seconds after the request began. Every named search returned no records with partial coverage and continuation; a blank search found unrelated shaft scenes. The native lookup found `create:mechanical_press`, but no press Ponder record or viewer target reached the answer. This is not evidence that the press callback failed: the trace does not show it being reached.

Ponder search now treats a valid fully-qualified registry ID as an exact component selection before copying scene metadata or compiling titles. Model and tool guidance resolve the item's ID, use that query with provider `ponder`, and cite the returned Ponder source for its viewer. Other guide readers and natural-language/title discovery retain their existing path. The change removes unrelated storyboard callbacks from known-item lookup; model response time and individual mod-owned callback duration remain unbounded.

Four new Ponder regressions cover a press after unrelated registrations, a terminal no-association result, matching-only pagination, fresh caption reads with the same scoped viewer target, and continuation through the real knowledge-service coordinator. The initial three tests failed before the query path existed. Review of the first implementation exposed a cursor advance before callback-budget rejection; the coordinator regression reproduced missing remaining scenes, and the corrected cursor retains them for the next public call.

`gradlew.bat test build` passed after correction: 566 tests, 562 passed, four existing skips, zero failures/errors. `PonderGuideReaderTest` has fifteen passing tests, and `AssistantScreenStateTest` has 39. The latter includes the navigation-feedback viewport regression described in the [fixture validation](2026-10-18-all-adapter-fixture.md).

Independent review of the correction and cursor follow-up found no additional material defect. The built JAR, refreshed `dist/packsage.jar` and distribution metadata match SHA-256 `2B2911C6119BAC759722AFB431A8E7B0D37A47A35438DF1A9E84BE68A8D4D11B`.

At the player's request, Minecraft saved the world, quit to title and exited normally. After verifying the instance process had stopped, the corrected JAR was installed into `Packsage/mods/packsage.jar`; its hash matched the distribution and settings remained unchanged. The game was left closed and no live test was performed. The player's natural-language retry, actual tool count/timing, caption correspondence and clicked viewer remain pending the [player checks](../testing/2026-10-18-ponder-support.md).

The player subsequently authorized live testing. The installed and distribution hashes were rechecked and matched, but desktop input failed before profile launch: both coordinate clicks and the accessible CurseForge Play control returned `SendInput sent 0 of 1 events; GetLastError=87`. Reobserving the launcher did not restore input. Minecraft did not launch and no new Ponder, viewer or navigation-expiry acceptance was obtained; this is a desktop-control blocker rather than an adapter result.

## Installed mechanical-press retry

After the player opened Minecraft, desktop control worked again. Minecraft 1.21.1 / NeoForge 21.1.259 showed 26 loaded mods, and the existing `New World` fixture loaded. All thirteen adapter status rows were visible and green. The installed JAR still matched the correction hash above. GPT-6-Luna and Extra high effort were preserved.

A fresh conversation repeated the original question exactly: `show me the usage of create presser in ponder`. Saved request `8f10a72a-4997-4e91-ac40-1852b8cd0973` began at 21:25:33.422 Taipei time and finished with eleven displayed activities and five actual tool calls:

1. Block registry search `presser`: no matches.
2. Item registry search `presser`: no matches.
3. Block registry search `press`: nineteen matches, including `create:mechanical_press`.
4. One Ponder document search with query `create:mechanical_press`, provider `ponder` and guide type: two records, both navigable, with a terminal cursor.
5. One evidence read for both exact returned Ponder references: fresh scene titles and literal captions, with Ponder version `1.0.82+mc1.21.1` in provenance.

The returned registrations were `ponder:57:7632b7e8` for scene `create:mechanical_press`, titled `Processing Items with the Mechanical Press`, and `ponder:58:4ef2278a` for scene `create:mechanical_press_compacting`, titled `Compacting items with the Mechanical Press`. Both target the component viewer `create:mechanical_press`. Evidence described placing or dropping items on a Depot, automatic processing on a belt, compacting items in a Basin, optional Blaze Burner heat and the filter slot. Coverage remained partial for the documented caption omissions; terminal discovery did not claim complete scene evidence.

The last evidence observation was 21:26:06.086, 32.664 seconds after submission; completed-turn persistence was about 42.9 seconds after submission. The earlier failed trial used sixteen tools and 33 activities, with its last Ponder evidence about 65.5 seconds after submission. The new run reached both scenes with one Ponder search, followed by one fresh evidence read. These are single observed trials with unchanged model settings, not a response-time guarantee.

The visible answer included two Ponder links. Clicking the first opened the native component viewer on `Processing Items with the Mechanical Press`; its displayed scene contained the press, Depot and belt. The live caption `When items are provided on a belt...` matched the freshly returned literal caption. Search and evidence reading left the chat open; only the link click opened the viewer.

The saved conversation is `f9fa953a-eadf-4a8d-8e05-e226d3595f3b` in the Packsage profile. The known-item discovery, fresh caption read, answer link and component-viewer check passed. Broader title-only discovery, every scene/caption, other Ponder releases and cross-world link behavior remain outside this live trial. No source/settings changes or additional build were needed; the assistant was left open on the successful answer.

## Delivered limits

Ponder documents remain `guide` records under the existing tools. Search reads current registrations and executes at most four storyboard callbacks per call; evidence executes one current callback per requested handle with fresh private localization. Individual callback duration cannot be guaranteed. No read calls global reload, component-wide compilation, scene-world setup or simulation.

Caption extraction is partial: literal templates are bounded and ordered, while shared text, formatting substitutions, visual instructions and arbitrary callback mechanics are not decoded. Navigation opens the component's ordinary Ponder viewer only after a player click and does not promise a single-scene selection. Old-world and removed-registration targets cannot open stale content.
