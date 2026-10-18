# All-adapter fixture and navigation feedback

Date: 2026-10-18.

Scope: complete the `Packsage` test profile for the current registered adapters, correct omitted adapter statuses, and make navigation failure feedback transient. Follow the [player checks](../testing/2026-10-18-all-adapter-fixture.md), existing [navigation contract](../specs/2026-10-14-historical-navigation.md) and [chat presentation contract](../specs/2026-10-13-modern-chat-ui.md). Thinking/request activity is outside the requested correction.

## Profile preparation

Profile: `C:/Users/hung1/curseforge/minecraft/Instances/Packsage`, Minecraft 1.21.1 and NeoForge 21.1.259. The earlier loader 21.1.202 was below Create/Ponder and Aeronautics requirements. Create 6.0.10 supplies Ponder 1.0.82; Aeronautics and Sable remain disabled. The existing nine active JARs, fixture world/resource pack and baseline settings were preserved.

With the instance closed, twelve additional JARs were copied from already-installed ATM10 files or its `All the Mods 10 - ATM10_1791354920` backup. Every copied file's SHA-256 matched its source; there are now 21 active JARs. The PackSage settings hash also remained unchanged. No worlds or configuration sets were copied from ATM10.

| Source | Added versions/content |
|---|---|
| Modonomicon | 1.120.4; NeoVitae 1.1.15 supplies a real guide; GeckoLib 4.9.2 satisfies its dependency. |
| GuideME | 21.1.19; Powah 6.2.10 supplies real guide pages; Cloth Config 15.0.140 satisfies its dependency. |
| Immersive Engineering | 12.4.2-194, including its manual. |
| Actually Additions | 1.3.26+mc1.21.1, with its Patchouli booklet using existing Patchouli 92. |
| Relics | 0.12.8; Curios 9.5.1 and OctoLib 0.6.2, with existing Architectury 13.0.11. |
| Artifacts | 13.2.3; the Reliquified Artifacts replacement add-on is absent so this fixture exercises the base reader. |

The selected framework/mod versions satisfy the current adapter gates. Published mod metadata and embedded book/manual resources establish dependency/content readiness, not successful live extraction.

## Reproduced UI defects

The adapter tooltip and runtime availability map both hard-coded only five knowledge providers plus history, omitting the seven newer guide sources despite their registration. Separately, navigation failures entered the durable runtime error list, which cleared only on world entry. This made `Navigation target is no longer available` persist throughout the session.

The two targeted `AssistantRuntimeTest` checks failed before correction: stale navigation must leave durable errors empty, and availability must include every currently supported adapter across world lifecycle states. Navigation feedback now lives on the screen with a five-second deadline, clears on subsequent navigation and conversation/world/scope changes, and ignores asynchronous results from older navigation attempts. The ordinary request error list is preserved.

`gradlew.bat test build` passed: 561 tests, 557 passed, four existing skips, zero failures/errors. `AssistantRuntimeTest` has 34 passing tests and `AssistantScreenStateTest` has 38. Checks cover expiry/replacement, session/world clearing, older navigation completions, preserved request errors, all thirteen status keys and a positive optional-provider availability result. One older session-navigation assertion initially still expected durable error text; it was corrected to observe transient feedback while retaining the original world/stale guards, then the full suite passed.

## Installed build and startup

The built JAR, `dist/packsage.jar`, its distribution metadata and the installed Packsage JAR all matched SHA-256 `33F6B9D612B5FE9C0F31FA48D19AEC19A16D41C0FFDFF6D3865EC52E4EEEF843`. Installation occurred with Minecraft closed; settings remained unchanged.

Native UI verification reached the Minecraft 1.21.1 / NeoForge 21.1.259 main menu showing 26 loaded mods (including nested libraries/mods). The existing `New World` fixture loaded after choosing Minecraft's `Create Backup and Load` option for the experimental-settings notice. No other profile was launched or changed.

The assistant tooltip was directly observed with all thirteen rows visible and green: Minecraft, JEI, FTB Quests, NeoForge configs, Patchouli, Modonomicon, GuideME, Immersive Engineering, Actually Additions, Relics, Artifacts, Ponder and Chat history. No assistant prompt was submitted during this setup check. The chat was left open for the player's functional tests.

## Acceptance boundary

Filesystem preparation, startup/world entry and the corrected tooltip are verified. Full adapter search/fresh-read acceptance and native rendering of timed navigation feedback remain player checks. The earlier [guide/manual acceptance](2026-10-15-guide-manual-adapters.md) used ATM10 and its Artifacts replacement branch; it does not establish the base Artifacts path in this minimal profile.

The player's earlier 19:04 Ponder request took 33 activity steps and returned a Minecraft item reference without the requested Ponder viewer link. That retrieval/performance failure is a separate follow-up; a green adapter status does not establish its functional acceptance.

## Follow-up corrections

Independent review found that new navigation feedback could render below the viewport in an idle overflowing transcript. Its regression first failed: a player following the bottom remained at the old offset after the feedback increased content height. Feedback now follows the new bottom only when the player was already following; scrolling up to read history preserves the offset. The reviewer confirmed both cases close the finding.

The combined follow-up build passed 566 tests: 562 passed, four existing skips, zero failures/errors. This includes the viewport regression and the [direct Ponder item lookup correction](2026-10-18-ponder-support.md). At the player's request, Minecraft was closed normally and the corrected JAR was installed with matching hashes and unchanged settings. The game remains closed; no live retry was performed.
