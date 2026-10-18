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

The two targeted `AssistantRuntimeTest` checks failed before correction: stale navigation must leave durable errors empty, and availability must include every currently supported adapter across world lifecycle states. The UI correction and fresh build/startup checks remain pending in this preparation record.

## Acceptance boundary

Filesystem preparation is verified. Full adapter search/fresh-read acceptance, native navigation feedback expiry, and the corrected tooltip still require the rebuilt artifact and live observations. The earlier [guide/manual acceptance](2026-10-15-guide-manual-adapters.md) used ATM10 and its Artifacts replacement branch; it does not establish the base Artifacts path in this minimal profile.
