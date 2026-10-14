# PackSage test pack selection

Date: 2026-10-12

Status: **Proposal**, based on **Confirmed publisher/project documentation only**. No pack was installed or launched, and no startup-time, memory or responsiveness measurement is claimed. Pack version drift means every fixture must be pinned before use.

## Selection rule

Daily development needs a small, deterministic fixture that loads quickly enough for repeated reload and navigation checks, contains deliberately chosen JEI/FTB Quests/Patchouli/config cases, and can be changed without inheriting a large pack's scripts and balance rules. An existing pack is useful as one secondary reality check, but marketing labels, archive size and mod count do not establish load time.

## Existing candidates

| Candidate | Confirmed source facts | Fixture assessment |
|---|---|---|
| **FTB Evolution** | The publisher identifies it as Minecraft 1.21.1 on NeoForge. Its official changelogs show FTB Quests updates, Patchouli and Modonomicon updates, and JEI fixes/recipe changes. [Pack page](https://www.feed-the-beast.com/modpacks/125-ftb-evolution) [1.21.1 changelog with FTB Quests/Patchouli/Modonomicon](https://www.feed-the-beast.com/modpacks/125/changelog/100029) [JEI and quest changes](https://www.feed-the-beast.com/modpacks/125/changelog/100049) | Best existing secondary candidate for the required three source families. It is a broad kitchen-sink pack, so use it only after the small fixture and pin one exact release. |
| **All the Mods 10** | The project repository describes ATM10 as an official 1.21.1 repository. Its changelog lists FTB Quests and JEI updates and contains Patchouli-conditioned content. [Project README](https://github.com/AllTheMods/ATM-10/blob/main/README.md) [ATM10 changelog](https://github.com/AllTheMods/ATM-10/blob/main/changelogs/CHANGELOG-ATM10-7.2-7.3.md) | Rich alternative secondary fixture. Confirm all required providers in the same pinned release manifest; no daily-loop startup advantage was established. |
| **FTB StoneBlock 4** | The publisher identifies it as Minecraft 1.21.1 on NeoForge with FTB Quests. Its changelogs show JEI and GuideME entries. [Pack page](https://feed-the-beast.com/modpacks/130-modpack) [JEI/FTB Quests changelog](https://feed-the-beast.com/modpacks/130/versions) [GuideME addition](https://feed-the-beast.com/modpacks/130/changelog/100156) | Plausible real-pack secondary for JEI, quests and GuideME, but Patchouli was not verified in the reviewed publisher sources. Do not select it for the required JEI/FTB Quests/Patchouli trio without a pinned manifest check. |

## Proposed fixture split

**Primary daily-development fixture — custom, minimal, and deterministic.** Assemble a small Minecraft 1.21.1 NeoForge instance with JEI, FTB Quests and its required FTB Library/Teams dependencies, Patchouli, Nature's Aura and the dependencies required by the selected artifacts. This is a test pack assembled from existing mods, not a new content mod or a balanced gameplay pack. Resolve and pin exact artifact versions together before calling the combination compatible.

Nature's Aura is the proposed real content mod. Its published 41.10 release targets NeoForge 1.21.1 and requires Patchouli. The matching source commit `70b93d37037f3559312834f0f42e824431090b64` supplies a real book, four custom JEI recipe categories and `ModConfigSpec` settings. Its build versions are source evidence, not a requirement that PackSage copy every dependency version. [Published release line](https://www.curseforge.com/minecraft/mc-mods/natures-aura), [version properties](https://github.com/Ellpeck/NaturesAura/blob/70b93d37037f3559312834f0f42e824431090b64/gradle.properties), [JEI plugin](https://github.com/Ellpeck/NaturesAura/blob/70b93d37037f3559312834f0f42e824431090b64/src/main/java/de/ellpeck/naturesaura/compat/jei/JEINaturesAuraPlugin.java), [book definition](https://github.com/Ellpeck/NaturesAura/blob/70b93d37037f3559312834f0f42e824431090b64/src/main/resources/data/naturesaura/patchouli_books/book/book.json), [config definitions](https://github.com/Ellpeck/NaturesAura/blob/70b93d37037f3559312834f0f42e824431090b64/src/main/java/de/ellpeck/naturesaura/ModConfig.java).

| Fixture content | Purpose |
|---|---|
| Vanilla data plus small datapack recipe/tag overrides | Known replaced/disabled recipes, tag alternatives, quantities and loaded-versus-shipped provenance. |
| Nature's Aura recipes and JEI categories | Actual third-party custom categories, catalysts, grouped inputs and projection/navigation behavior. |
| A small authored FTB quest chapter | Known dependencies, branching/gates, item references and current team/progress changes. |
| Nature's Aura book plus a small controlled resource override | Real Patchouli content/navigation, known text and resource precedence/reload checks. |
| Accessible registered config values and saved-file edits | Saved-versus-loaded observations and comments/default/range metadata; verify restart/server-scope cases separately where available. |
| A small saved test world with prepared items/blocks | Repeatable focused-object, component-variant, screenshot, pause and world-change checks. |

The FTB 1.21.1 source supplies the SNBT quest model, and NeoForge documents the 1.21.1 recipe resource/load contract. Author only the few data cases needed for acceptance rather than creating a full quest campaign. [FTB quest model](https://github.com/FTBTeam/FTB-Quests/blob/v2101.1.35/common/src/main/java/dev/ftb/mods/ftbquests/quest/BaseQuestFile.java), [NeoForge recipes](https://docs.neoforged.net/docs/1.21.1/resources/server/recipes/).

Begin without shaders, unrelated worldgen/content mods or a broad optimization-mod stack. This reduces changing variables; it does not itself establish a startup-time bound. Add a provider only when an acceptance case requires it. Later GuideME/AE2, Ponder and Modonomicon checks can use separate extensions of this fixture rather than increasing every v1 launch.

**Secondary real-pack fixture — FTB Evolution, one pinned release.** Use it for compatibility and provenance checks after the custom fixture passes. Confirm the downloaded manifest/mod list at acquisition time, then record the exact Minecraft, NeoForge, JEI, FTB Quests, Patchouli and pack versions. The research supports source presence, not startup performance or complete semantic coverage.

The custom fixture should be the normal edit/reload loop. The real pack should be a bounded compatibility pass, with unsupported or opaque records recorded rather than changing the shared fixture to fit the pack. No existing-pack choice should be described as “fast” until measured on the target machine.

Measure process launch to the main menu and entry into the same saved test world, keeping cold and repeat runs distinct. Record the resolved mod list, Java/heap settings and hardware alongside timings. The fixture proposal remains uninstalled and unbenchmarked.
