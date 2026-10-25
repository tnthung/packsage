# Nether place-column visibility fix

2026-10-26, main checkout `D:\Project\packsage`, branch `feat/packsage-v2`.

## Result

The original L1 ground-level Nether failure is fixed in the installed client. The same fortress target and camera now show the cyan column through terrain. It remains visible at 28.123 seconds and is absent by 32.136 seconds. The distant Overworld L2-style village column also remains visible. Render distance stayed at 12 and the developer prompt override stayed cleared. Minecraft is left running in New World, in the Overworld at its original position.

## Root cause and source evidence

`WorldHighlighter` requested `RenderStateShard.NO_DEPTH_TEST`, assuming that it disabled depth testing. In Minecraft 1.21.1 this shard is a no-op for `GL_ALWAYS`. The highlight event runs while the translucent terrain layer's depth testing is still enabled, so the column was tested against terrain depth. The Nether's terrain and ceiling hid it in the failing camera view. An Overworld column can still be visible above terrain, explaining the earlier L2 pass.

Investigated the requested `build/moddev/artifacts/neoforge-21.1.202-sources.jar` directly:

- `net/minecraft/client/renderer/RenderStateShard.java:264` constructs `NO_DEPTH_TEST` with GL enum 519 (`GL_ALWAYS`). At lines 427-438, both setup and cleanup act only when the enum is **not** 519. Consequently neither callback changes the incoming depth-test state.
- `net/minecraft/client/renderer/RenderType.java:1336` gives the composite builder a default `LEQUAL_DEPTH_TEST`; translucent terrain uses that default. At lines 1143-1146, `draw` runs render-state setup, draws the mesh, then clears the state.
- `net/minecraft/client/renderer/LevelRenderer.java:1269-1271` sets up the terrain render type. At lines 1337-1338, `renderSectionLayer` dispatches the render-stage event **before** clearing that terrain state. PackSage listens at `AFTER_TRANSLUCENT_BLOCKS`.
- `net/minecraft/client/renderer/MultiBufferSource.java:84-99` shows that `endBatch(LINES)` synchronously builds the mesh and calls the render type's `draw`. The correction therefore applies at the actual draw, including any flush of the shared buffer.

The fog hypothesis was checked against both sources and the corresponding `neoforge-21.1.202-client-extra-aka-minecraft-resources.jar`:

- `FogRenderer.java:265-267` uses the thick-fog start/end values, with an end of at most 96 blocks. However, `FogRenderer.java:209-211` implements vanilla `setupNoFog` by setting only FogStart to `Float.MAX_VALUE`, exactly as the existing place-column code does.
- `ShaderInstance.java:453-458` copies the current RenderSystem fog start/end to the shader uniforms when drawing.
- `assets/minecraft/shaders/core/rendertype_lines.fsh:17-19` uses `linear_fog`. `assets/minecraft/shaders/include/fog.glsl:3-6` returns the input color whenever the finite vertex distance is at most FogStart. Neither the thick-fog end nor sphere/cylinder distance can fade the column with FogStart at MAX. The line shader uses vertex color, without a terrain lighting lookup.

Extracted source copies are retained in `build/nether-column-recheck/` for inspection. Compilation targets NeoForge 21.1.202; the existing CurseForge instance runs NeoForge 21.1.259. Both the pre-fix reproduction and post-fix acceptance used that same instance and runtime version.

## Reproduction and hypothesis check

Installed baseline SHA-256: `FC8C27671A0C52DDDF4C878A2F0C82381E424028E96623F7349F6A1BF5E3237F`.

At Nether player position `(130.5,70,116.5)`, yaw `-118.84266`, pitch approximately zero, submitted `where is the nearest nether fortress?`. Request `cc83f0f9-7cf0-447d-9f1d-fcd23dd86d69` completed and saved. It returned the predicted fortress `(544,128,-112)`, about 472 blocks away, with target `world-place|minecraft:the_nether|544,128,-112`. Navigation returned `OPENED`, but the [ground screenshot](../../../build/nether-column-recheck/before-ground.png) showed no column.

The screenshot check counted pixels in the central strip `500 <= x < 524`, with `R < 120`, `G > 150`, `B > 200`. A column required over 1,000 qualifying pixels. The baseline had **0**, yielding the expected **FAIL / exit 1**. This check is specific to these controlled screenshots and accompanies visual inspection.

Before changing code, raised only the player to about Y=180 at the same X/Z and direction, and navigated the same target. The [column became visible above the Nether roof](../../../build/nether-column-recheck/before-above-roof.png), with 5,633 qualifying pixels. Returned to the original ground position before installing the fix. This confirms that navigation reached the renderer and that the clamp, height, camera projection, fog override and cyan color could produce visible geometry in this dimension. After changing only the depth state, the identical ground camera showed the column, isolating terrain depth as the cause.

## Minimal fix and tests

In [WorldHighlighter.java](../../../src/main/java/dev/packsage/client/highlight/WorldHighlighter.java), replaced the no-op depth shard with an inline depth shard whose setup explicitly calls `RenderSystem.disableDepthTest()` and whose cleanup calls `RenderSystem.enableDepthTest()`. Cleanup restores the enabled state expected by the still-active translucent terrain layer. Both block outlines and place columns use this render type, matching their existing see-through contract. No geometry, color, fog range, target parsing, clamp or expiry policy changed.

[WorldHighlighterTest.java](../../../src/test/java/dev/packsage/client/highlight/WorldHighlighterTest.java) now also checks the actual L1 fortress and L2 village horizontal offsets at the relevant far planes, plus a coincident place. The existing tests retain nearby, boundary and tiny-render-distance coverage. Both pure helper tests pass. The live screenshot comparison supplies the rendering regression check; these unit tests do not simulate OpenGL.

Cleanly saved the world, exited Minecraft, then ran the exact requested build/install command:

```powershell
powershell -ExecutionPolicy Bypass -File D:\Project\packsage\scripts\build.ps1
```

**BUILD SUCCESSFUL in 48s**. XML totals: **1,068 tests, 1,064 passed, 4 skipped, 0 failures, 0 errors**, one additional passing test over the 1,063-pass baseline. `dist/packsage.jar` and installed `mods/packsage.jar` both hash to `7E0C517E7371E84901D14D9932E459597482AE7A02B1A3913BC632CCD603F311`. Relaunched via CurseForge and selected the original **New World** entry, whose save directory is `PackSage Adapter Filter Live Test`.

## Installed live verification

- **Nether L1: PASS.** Fresh identical question, request `76ebb0dd-3632-44cb-a88e-3e919c2ac253`, saved and completed. The same fortress target returned `OPENED`. The [fixed ground screenshot](../../../build/nether-column-recheck/after-nether.png) shows the column through the terrain at the original position and camera, with **5,541** qualifying cyan pixels. A separate timed navigation has the column present at [0.654s](../../../build/nether-column-recheck/nether-0s.png) and [28.123s](../../../build/nether-column-recheck/nether-28s.png), and absent at [32.136s](../../../build/nether-column-recheck/nether-32s.png). There was no pause or re-navigation during that interval.
- **Overworld L2-style: PASS.** At `(1148.5,140,1170.5)`, question `where is the nearest village?`, request `e6a21ca2-b5d9-460e-910c-a12b186a411e`, saved and completed. The same predicted plains village `(1872,66,864)`, about 786 blocks away, returned target `world-place|minecraft:overworld|1872,66,864`. Navigation returned `OPENED`; the [screenshot](../../../build/nether-column-recheck/after-overworld.png) clearly shows the column. This fixed-build check used render distance **12** as instructed. The original Part B result at 8 chunks remains separate historical evidence; 8 chunks was not rerun on this JAR.
- **Final state: verified.** New World / `PackSage Adapter Filter Live Test`, Overworld `(1003.5,250,1002.5)`, yaw 0, pitch 10; screen null, running true, paused false; render distance 12, empty main hand, F3 hidden. `GET /prompt` returned `text:null` before restart, after restart and at the final check. [Final screenshot](../../../build/nether-column-recheck/final-state.png).

Raw bridge replies, exact navigation results, state snapshots, timing captures, pixel counts, build log, test totals, test XML and build metadata are retained under `build/nether-column-recheck/`. Screenshots were copied after their asynchronous PNG writer released the file; initial immediate copy attempts encountered a file lock, without affecting the captured frames. No token is included in those artifacts.

The live results were appended as **L1 recheck** to the [acceptance record](../../../docs/validation/2026-10-25-v2-acceptance.md#l1-recheck) and committed separately. Remaining locate/saved-link and unrelated prompt acceptance items stay unchecked. No formatter, push, merge, other worktree or `build/prompt-candidates/` operation was performed.

## Commits

- `4937c69` — `[Add] cover Nether and Overworld place marker distance fixtures`
- `edbf6c0` — `[Fix] disable terrain depth testing for world highlights`
- `51fb746` — `[Add] record passing Nether column L1 recheck` (validation file only)
- This report and the matching changelog/roadmap status updates are a separate documentation commit.
