# Ponder support validation

Date: 2026-10-18.

Scope: [specification](../specs/2026-10-18-ponder-support.md), [implementation plan](../plans/2026-10-18-ponder-support.md) and [installed API evidence](../research/2026-10-18-ponder-api.md).

## Automated evidence

Java 21.0.12.1, Minecraft 1.21.1 and NeoForge 21.1.202 remain the build/test baseline. The optional adapter accepts the inspected Ponder version `1.0.81+mc1.21.1`; that provider's own metadata requires NeoForge 21.1.206 or later when installed. No Ponder dependency was added to the base distribution.

- `gradlew.bat test build`: successful; 555 tests, 551 passed, four existing skips, zero failures/errors. The result XML includes `PonderGuideReaderTest` (9), `GuideDocumentProviderTest` (12), `ClientAssistantTest` (7) and `ScreenTextTest` (33).
- Focused checks cover four-callback title/no-match searches, fresh caption rereads, same-schematic registrations, removed/replaced handles, pagination without skipped lookahead entries, bounded copied text, numeric caption order, localized display-text matching, partial-evidence completeness and navigation scope/thread checks.
- Ponder citation rendering was observed failing before adding the provider marker and then passed in the full suite, covering both ordinary and grouped citation forms. Optional-provider metadata was also observed failing before registration and passed afterward.
- Independent review checked the reflected APIs against the exact nested JAR. Its findings about incomplete evidence claiming completeness and repeated title/header output were corrected; regression assertions cover both behaviors.

The versioned source inspection and unit fixtures do not establish runtime extraction of every Create/PonderJS callback. Some callbacks may fail with a null scene world, use shared/dynamic captions, or perform custom code outside the literal-text source contract.

## Artifact and installed status

The successful build produced `build/libs/packsage-0.1.0.jar`, copied to `dist/packsage.jar`.

SHA-256: `0C22DE1535144323D5C729623265A0ABE254C7752FBEDCDE3C3220FC0C167BAC`.

This task did not copy the artifact into a Minecraft instance or launch the game. Installed title search, caption correspondence and actual viewer opening remain pending the [player checks](../testing/2026-10-18-ponder-support.md).

## Delivered limits

Ponder documents remain `guide` records under the existing tools. Search reads current registrations and executes at most four storyboard callbacks per call; evidence executes one current callback per requested handle with fresh private localization. Individual callback duration cannot be guaranteed. No read calls global reload, component-wide compilation, scene-world setup or simulation.

Caption extraction is partial: literal templates are bounded and ordered, while shared text, formatting substitutions, visual instructions and arbitrary callback mechanics are not decoded. Navigation opens the component's ordinary Ponder viewer only after a player click and does not promise a single-scene selection. Old-world and removed-registration targets cannot open stale content.
