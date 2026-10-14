# Modern chat UI validation

Date: 2026-10-13

Initial source: `7538ee69ba9fa7622c684b713f1db610c355fde4` on `feat/packsage-v1`. Requirements: [chat/settings specification](../specs/2026-10-13-modern-chat-ui.md). Live checks: [Minecraft player checks](../testing/2026-10-13-modern-chat-ui.md).

## Automated evidence

- Focused client, assistant runtime, request controller and contracts run: 108 tests, no failures, errors or skips.
- `gradlew.bat -PpacksageBootstrapOnly=true compileJava`: successful against the final source.
- Exported committed source with `git archive`, then ran `scripts/build.ps1` from that isolated export: full `test build` successful. The 28 test suites report 294 tests: 292 passed, no failures or errors, two skipped symlink checks (`sessionCatalogIgnoresExternalJsonlLinks` and `latestMarkerLinkCannotRedirectConversationSelectionOutsideTheRoot`) where Windows did not permit link creation.
- Cached and working-tree `git diff --check`: clean. No formatting tools used.
- Independent reviews cleared the session/world/scope lifecycle, editor/settings behavior and final chat changes. The last chat fixes cover immediate Send-to-Stop before the first active view, the search placeholder and grouping renamed labels by stable world/server ID.
- JAR inspection found the custom font definition and TTF, all 16 control icons and no bundled JEI, FTB Quests, Patchouli or Nature's Aura classes.

The headless checks cover editor wrapping, selection, clipboard input, limits, caret movement and manual scrolling; layout bounds and draft preservation; session expansion/search/scrolling and read-only world isolation; inline references; account actions and model/effort choices. They do not prove actual Minecraft rendering.

## Installed artifact

Minecraft was closed before `scripts/build.ps1` installed the full artifact. Build metadata identifies Minecraft `1.21.1`, NeoForge `21.1.202`, Java `21.0.12.1`, Gradle `8.11.1` and ModDevGradle `2.0.147`.

SHA-256: `7E9EBE0DB2F123EC80AE4FD7EE29B22F131558DC15C3213752FA7CBAD2A5F66F`.

The exported build's `dist/packsage.jar`, repository `dist/packsage.jar` and installed `C:\Users\hung1\curseforge\minecraft\Instances\Packsage\mods\packsage.jar` have this same hash. Repository `dist/build-metadata.json` was copied from the verified build.

## Acceptance status

Source, automated checks, packaging and installation are complete. Actual Minecraft visual/input acceptance is pending the linked player checks, including GUI scales, font/icon rendering, pause visibility, inline navigation and the immediate Stop control through capture/model/tool phases. No live visual result is inferred from tests or the built JAR.

## Rendering correction after the first live screenshot

The player's screenshot showed blurred chat/sidebar content and controls while input fields stayed sharp. Pinned NeoForge `21.1.202` source confirms that `Screen.render()` calls `renderBackground()` before drawing registered widgets; that background method applies the fullscreen blur and native menu texture. Both custom screens called it after drawing their own content. The correction overrides the native background hook in chat and settings because their custom render paths already paint an opaque background first.

Correction source: `a029384eac6b75acc16940ef88b378e44fceebd4`. All 61 focused client tests passed. A fresh committed-source export passed `scripts/build.ps1`: 294 tests, 292 passed, no failures or errors, the same two symlink skips. Diff checks passed.

The corrected build supersedes the initial artifact above. Its exported, repository and installed JAR hashes all match `01BA7C69FAC7BD2A452A320E8022B80D7E14A777F6A310D0ED4685F2FC309631`. Minecraft was closed during installation. Actual rendering after restarting with this correction remains unverified; the first player check now explicitly covers sharp content and controls on both screens.
