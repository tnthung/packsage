# Installed Ponder API

Date: 2026-10-18.

Scope: the installed ATM10 artifact and existing PackSage document/navigation paths, supporting the [Ponder specification](../specs/2026-10-18-ponder-support.md). This is API inspection, not installed Minecraft acceptance.

## Artifact

`C:/Users/hung1/curseforge/minecraft/Instances/All the Mods 10 - ATM10/mods/ponderjs-neoforge-1.21.1-2.4.0.jar` contains `META-INF/jarjar/ponder-neoforge-1.0.81+mc1.21.1.jar`. Its jar-in-jar metadata identifies `net.createmod.ponder` and artifact version `1.0.81+mc1.21.1`. The extracted nested JAR was inspected with `javap -p` and `javap -p -c`; no dependency was installed or downloaded for this inspection.

## Source contract

- `PonderIndex.getSceneAccess().getRegisteredEntries()` exposes a collection of component-to-`StoryBoardEntry` associations. Entries expose component, namespace, schematic, tags, ordering and storyboard callback. Neither component nor schematic alone uniquely identifies a storyboard.
- `PonderSceneBuilder.title(String, String)` assigns the actual scene ID and registers `header`. Thus a source reader cannot infer the actual ID/title from registration metadata alone.
- `PonderSceneRegistry.compileScene(PonderLocalization, StoryBoardEntry, PonderLevel)` creates a scene and executes that entry's callback. Passing fresh localization and `null` level is the installed library's own language-generation route. It avoids schematic loading, scene-world setup, `begin()` and simulation. It still runs mod code, which can fail or access a world eagerly.
- `PonderLocalization.specific` is a public map keyed by actual scene ID. `PonderScene.registerText` registers `text_1`, `text_2`, and subsequent literal templates. Read numbered keys in numeric order, with the current client language and the callback's raw fallback text.
- `sharedText` does not populate that per-scene map. Formatting arguments and custom dynamic text are not completely represented. A literal-caption reader therefore reports partial extraction even when its returned text fits the bound.
- Avoid component-wide `compile` and global `generateSceneLang`/`provideLang` during reads. The former sets up schematic worlds and starts scenes; editing mode can reload global registrations/localization. The latter traverses all registered callbacks and may re-register providers.
- `PonderUI.of(ResourceLocation)` opens the scenes associated with a component. There is no public single-scene-ID factory; the scene-list constructor is protected. A navigation link must describe the component viewer.

The current upstream [scene registry](https://raw.githubusercontent.com/Creators-of-Create/Ponder/mc1.21.1/dev/common/src/main/java/net/createmod/ponder/foundation/registration/PonderSceneRegistry.java), [localization](https://raw.githubusercontent.com/Creators-of-Create/Ponder/mc1.21.1/dev/common/src/main/java/net/createmod/ponder/foundation/registration/PonderLocalization.java) and [storyboard interface](https://raw.githubusercontent.com/Creators-of-Create/Ponder/mc1.21.1/dev/common/src/main/java/net/createmod/ponder/api/registration/StoryBoardEntry.java) corroborate the broad structure. The installed bytecode governs exact version support and localization details.

## PackSage integration

`GuideDocumentProvider` already owns bounded live document search, metadata-only cursors and source-qualified fresh evidence reads. Optional reader navigation hooks preserve the other guide sources' unsupported defaults. `AssistantRuntime.navigate` refreshes evidence, then calls `KnowledgeService.open` in its client-executor callback; the Ponder reader additionally checks the owning thread before opening a screen. No queue-and-block bridge is needed.

Search limits count storyboard callbacks as well as returned results. Removed/replaced registrations must invalidate their old handles. Literal captions describe authored demonstrations; scene animation ticks do not establish machine throughput or an operating world machine.
