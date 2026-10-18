# Ponder API compatibility

Date: 2026-10-18.

Scope: installed ATM10 artifacts, official published source history and existing PackSage document/navigation paths, supporting the [Ponder specification](../specs/2026-10-18-ponder-support.md). This is API inspection, not installed Minecraft acceptance.

## Artifact

`C:/Users/hung1/curseforge/minecraft/Instances/All the Mods 10 - ATM10/mods/ponderjs-neoforge-1.21.1-2.4.0.jar` contains `META-INF/jarjar/ponder-neoforge-1.0.81+mc1.21.1.jar`. Its jar-in-jar metadata identifies `net.createmod.ponder` and artifact version `1.0.81+mc1.21.1`. The initial inspection used the extracted nested JAR with `javap -p` and `javap -p -c`.

The same instance's `create-1.21.1-6.0.10.jar` embeds `META-INF/jarjar/ponder-neoforge-1.0.82+mc1.21.1.jar`. Its jar-in-jar metadata requests `[1.0.82+mc1.21.1,)`, and `logs/latest.log` identifies Create as the parent of the selected Ponder `1.0.82+mc1.21.1` (discovery line 545, mod list line 1052). The reflected signatures and relevant compilation/localization bytecode match the inspected `1.0.81` copy. Create supplies Ponder without a separate player installation; PonderJS adds scripting support. Upstream Create also [declares Ponder as a bundled dependency](https://github.com/Creators-of-Create/Create/blob/mc1.21.1/dev/build.gradle).

## Compatible version range

Accept `[0.8.1,1.1)` on Minecraft 1.21.1. Compare the numeric version after removing the exact `+mc1.21.1` suffix when present; older artifact labels are bare versions. Reject other Minecraft suffixes and malformed labels. Preserve the complete observed runtime version in status and evidence rather than reporting one inspected release for every installation.

The [older official Maven metadata](https://maven.createmod.net/net/createmod/ponder/Ponder-Common-1.21.1/maven-metadata.xml) uses `net.createmod.ponder:Ponder-Common-1.21.1` and starts at `0.8.1`. Source JARs inspected from that coordinate were `0.8.1`, `0.8.7`, `0.9.9`, `0.9.23`, `1.0.24`, `1.0.43`, `1.0.56`, `1.0.64` and `1.0.69`. The [0.8.1 source JAR](https://maven.createmod.net/net/createmod/ponder/Ponder-Common-1.21.1/0.8.1/Ponder-Common-1.21.1-0.8.1-sources.jar) already contains every entry point used by this adapter:

- `PonderIndex.getSceneAccess()` and `getLangAccess()`, with `getRegisteredEntries()` returning component-to-storyboard associations.
- Storyboard component/namespace/schematic/tag access, public `PonderLocalization.specific`, `PonderSceneRegistry.compileScene(PonderLocalization, StoryBoardEntry, PonderLevel)` with the nullable-level language-generation route, and `PonderScene.getId()`.
- Current-language `<namespace>.ponder.<scene path>.header` and `text_N` lookup, tag name/description lookup, and `PonderUI.of(ResourceLocation)`.

The [new coordinate's official metadata](https://maven.createmod.net/net/createmod/ponder/ponder-common/maven-metadata.xml) lists Minecraft 1.21.1 releases `1.0.79`, `1.0.80`, `1.0.81`, `1.0.82`, `1.0.83`, `1.0.84`, `1.0.85` and `1.0.87` with the Minecraft suffix. All eight source JARs were inspected; the six central registry, storyboard, scene, localization and UI source files have identical hashes across them. This coordinate's `.79` floor reflects artifact history, not the introduction of the API.

The inspected samples retain the required signatures and localization behavior from `0.8.1` through `1.0.87`. Later changes add unrelated rendering/plugin behavior and overloads. No upstream API stability guarantee or observed breaking release was found. The upper bound `1.1` is the next-family audit boundary: accepting later `1.0.x` versions is a compatibility policy, not a claim that unpublished versions were tested. Releases between the older inspected samples were not individually runtime-tested. Minecraft's own dependency checks still govern the game and loader requirements of each installed Ponder artifact.

## Source contract

- `PonderIndex.getSceneAccess().getRegisteredEntries()` exposes a collection of component-to-`StoryBoardEntry` associations. Entries expose component, namespace, schematic, tags, ordering and storyboard callback. Neither component nor schematic alone uniquely identifies a storyboard.
- `PonderSceneBuilder.title(String, String)` assigns the actual scene ID and registers `header`. Thus a source reader cannot infer the actual ID/title from registration metadata alone.
- `PonderSceneRegistry.compileScene(PonderLocalization, StoryBoardEntry, PonderLevel)` creates a scene and executes that entry's callback. Passing fresh localization and `null` level is the installed library's own language-generation route. It avoids schematic loading, scene-world setup, `begin()` and simulation. It still runs mod code, which can fail or access a world eagerly.
- `PonderLocalization.specific` is a public map keyed by actual scene ID. `PonderScene.registerText` registers `text_1`, `text_2`, and subsequent literal templates. Read numbered keys in numeric order, with the current client language and the callback's raw fallback text.
- `sharedText` does not populate that per-scene map. Formatting arguments and custom dynamic text are not completely represented. A literal-caption reader therefore reports partial extraction even when its returned text fits the bound.
- Avoid component-wide `compile` and global `generateSceneLang`/`provideLang` during reads. The former sets up schematic worlds and starts scenes; editing mode can reload global registrations/localization. The latter traverses all registered callbacks and may re-register providers.
- `PonderUI.of(ResourceLocation)` opens the scenes associated with a component. There is no public single-scene-ID factory; the scene-list constructor is protected. A navigation link must describe the component viewer.

The current upstream [scene registry](https://raw.githubusercontent.com/Creators-of-Create/Ponder/mc1.21.1/dev/common/src/main/java/net/createmod/ponder/foundation/registration/PonderSceneRegistry.java), [localization](https://raw.githubusercontent.com/Creators-of-Create/Ponder/mc1.21.1/dev/common/src/main/java/net/createmod/ponder/foundation/registration/PonderLocalization.java) and [storyboard interface](https://raw.githubusercontent.com/Creators-of-Create/Ponder/mc1.21.1/dev/common/src/main/java/net/createmod/ponder/api/registration/StoryBoardEntry.java) corroborate the structure. Published versioned sources and the installed bytecode provide the compatibility evidence above.

## PackSage integration

`GuideDocumentProvider` already owns bounded live document search, metadata-only cursors and source-qualified fresh evidence reads. Optional reader navigation hooks preserve the other guide sources' unsupported defaults. `AssistantRuntime.navigate` refreshes evidence, then calls `KnowledgeService.open` in its client-executor callback; the Ponder reader additionally checks the owning thread before opening a screen. No queue-and-block bridge is needed.

Search limits count storyboard callbacks as well as returned results. Removed/replaced registrations must invalidate their old handles. Literal captions describe authored demonstrations; scene animation ticks do not establish machine throughput or an operating world machine.

## Observed item-lookup failure

The saved Packsage request `9a86f2b2-c0b0-4ee7-8061-f4231a913c9a` started at 19:04:05.986 Taipei with `show me the usage of create presser in ponder`. Its 33 activity steps contained 15 Ponder document searches and one native item-registry search. The last Ponder evidence was observed at 19:05:11.516, about 65.5 seconds after request start. The item lookup found `create:mechanical_press`, but the subsequent guide searches did not select that component.

Text queries repeatedly returned empty partial pages with continuation cursors. An empty query returned four unrelated shaft/encasing-shaft scenes and their component navigation targets. Those titles establish successful compilation of those early registrations; the trace does not prove that the press association was reached or that its callback failed. The final answer had a non-navigable Minecraft item record and no press-scene Ponder target.

The reader compiled up to four entries in global registration order before the shared guide provider applied the literal query. Changing phrases restarted that work. A known item ID needs an exact-component candidate path before title compilation, using the existing live registration metadata and public query string. This keeps the callback limit and normal title discovery while avoiding repeated unrelated pages for an identified item. It requires no new upstream Ponder API or retained index.
