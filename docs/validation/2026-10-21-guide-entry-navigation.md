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

## Installed acceptance

Pending. Use the [player checks](../testing/2026-10-21-guide-entry-navigation.md).
