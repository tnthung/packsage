# Guide entry navigation validation

Scope: the [guide entry navigation specification](../specs/2026-10-21-guide-entry-navigation.md), its [implementation plan](../plans/2026-10-21-guide-entry-navigation.md) and its [API research](../research/2026-10-21-guide-entry-navigation.md).

## Automated acceptance

Each reader's navigation was built test-first. Every new test ran and failed for the missing behavior before its implementation was written.

| Commit | Change | Focused result |
|---|---|---|
| `b5652a4` | Fragment-aware `navigationTargets` overload, used by `GuideDocumentProvider` records | `GuideDocumentProviderTest`: 1 expected RED, then all passed |
| `2dcb559` | Modonomicon `book\|entry` destinations, including the legacy and intermediate readers | `ModonomiconGuideReaderTest`: 7 expected REDs, then 24/24 passed |
| `e1438a5` | GuideME page and plain-text heading destinations | `GuideMeGuideReaderTest`: 6 expected REDs, then 24/24 passed |
| `97c298e` | Immersive Engineering manual entry destinations | `ImmersiveEngineeringGuideReaderTest`: 5 expected REDs, then 35/35 passed |
| `2037770` | Actually Additions booklet entry destinations | `ActuallyAdditionsGuideReaderTest`: 4 expected REDs, then 7/7 passed |

These tests replace the native viewer with a recording opener. They cover:
- saved targets resolving after the content was recreated;
- `null` refreshes for a wrong provider and for malformed, removed, hidden or locked destinations;
- `STALE`, `UNSUPPORTED` and `UNAVAILABLE` outcomes with no native call;
- native failures reported as `ERROR`.

Java 21 `gradlew.bat test build` succeeded with 788 tests: 0 failures, 0 errors and 4 existing skips. `git diff --check` reported no problems. The artifact is `build/libs/packsage-0.1.0.jar`, SHA-256 `1FBDF6720FC01F44E148FB956819F0B62B5C6FA92D220D4B833A697944DED9E7`.

The default openers that call the real mod viewers are not exercised by automated tests. These are Modonomicon `BookGuiManager`, GuideME `GuidesCommon.openGuide` with `AnchorIndexer`, the Immersive Engineering `ManualScreen` and Patchouli `openBookEntry`. Their behavior is established only by installed checks.

## Final review correction

A fresh whole-branch review found no critical issues and one important issue. A plain-text GuideME heading containing `#` produced an anchor target that `Destination.parse` rejects, so the link could never open. Commit `123ac3c` omits such anchors. The regression `headingWithHashYieldsNoUnopenableAnchorTarget` failed first and then passed. The full Java 21 test and build then passed with 789 tests and 0 failures. The new artifact's SHA-256 is `ECD76026871A8D3F8488BDB5B953FB9ED923B7331785681C1C57C9392D9A3E6E`.

The reviewer confirmed every reflective call in the real openers against the installed JARs. Four minor issues are deferred:
- If the IE opener fails after `setScreen`, the manual stays open at its root.
- GuideME opening does not guard a null player.
- GuideME compiles the page again for each anchor refresh and open.
- Modonomicon resolution lacks a local `try/catch`.

## Installed acceptance

An attempt delegated to GPT-6-Luna was blocked before launching the game, because its computer-use calls failed (`cua.listWindows is not a function`). It made no observations.

GPT-6.1-Sol then ran the [player checks](../testing/2026-10-21-guide-entry-navigation.md) on the installed build `1FBDF672…`, in a new chat in **New World**, including a full Minecraft restart. Screenshots are kept outside the repository, under `%TEMP%\packsage-guide-navigation-2026-10-21`.

| Check | Observation | Result |
|---|---|---|
| Modonomicon | Neo Vitae reply linked "The Ara Vitae" (`neovitae:guide\|neovitae:altar/ara_vitae`). The link opened that entry before the restart and reopened it after. | Pass |
| GuideME page | Powah reply linked the Energizing page (`powah:book#powah:energy_blocks/energizing.md`). The link opened the page before the restart and reopened it after. | Pass |
| GuideME heading | The saved turn carries `…energizing.md#energizing` and the link opened the page. The installed Powah pages have only one heading each, a top-level title at the start of the page, so scrolling to a lower heading could not be observed. The reply also described the heading link as page-only. | Not established |
| Immersive Engineering | The reader returned "Immersive Engineering manual entries have not been loaded" because IE populates its manual entries lazily. The reply therefore had no manual link and cited GitHub instead. | Blocked (pre-existing reader limitation); retest pending |
| Actually Additions | The reply linked "Crystals and Reconstructor" (`actuallyadditions:crystals`). The link opened the entry before the restart and reopened it after. | Pass |
| Locked Modonomicon entry | Neo Vitae's own viewer refused the entry with "Requires read Entry: The Anima". The PackSage reply named the locked entry but offered no link to it, only links to the unlocked prerequisite. | Pass |

### Immersive Engineering retests

Sol reran the IE check on build `ECD76026…`, after opening the manual once by hand:
- The reply linked "Open Basic Tools manual entry". With an empty main hand, the link opened Basic Tools page 1.
- After a full restart, the saved link reported "Navigation target is no longer available" until the manual had been opened by hand. After that, it opened Basic Tools page 1 again.

Saved links therefore did not survive a restart. IE fills `contentsByName` only when the manual is first opened.

Commit `5f5757d` lets the player's click load an uninitialized manual through IE's own `getGui()` path. Model reads still do not load it. The regression `savedEntryLoadsAnUninitializedManualOnlyWhenThePlayerNavigates` failed first and then passed. The full test and build passed with 790 tests. The spec was updated in `9e612bf`, and build `1AA7CB5AE6400C2ECD962385F6611A5CD24FD1F3849F4197479225D8050FD7EE` was installed.

Sol's cold-session retest, with Minecraft freshly launched and the manual never opened by hand:
- The saved link opened Basic Tools page 1, and a repeated click opened it again.
- A new question about Treated Wood then produced "Open Manual: Treated Wood", which opened Treated Wood page 1.
- No "no longer available" message appeared.

The Treated Wood question came after the link click had already loaded the manual. Model reads before any manual load still find no entries; that pre-existing limitation is tracked separately in the roadmap.

| Check | Result |
|---|---|
| Immersive Engineering, including a saved link opened after a restart without the manual held | Pass |

Approximate reply times were 35 seconds to 3 minutes. No link opened a wrong destination or a "not found" page. The case where a saved link's destination later becomes locked was not exercised. An unrelated "Social Interactions are only available in Multiplayer worlds" toast appeared twice; its cause was not established.
