# Chat opening: real selection path and CPU preparation

Branch: `t3/chat-open-latency`. Base: `feat/packsage-v2` at `f9b2bf613cebb3f6ed146fb27c3d2b4f0377de63`.

## Finding and behavior

The missing dominant measured stage was the bridge's explicit conversation lookup. The [installed recheck](2026-10-25-v2-acceptance.md#o1---five-opening-timings-recorded) supplied `conversationId` on every timed open. `CHAT_SCREEN` forwarded to `SCREEN_OPEN`, which called `runtime.sessions()`, enumerated every world and read every summary, then filtered for one ID. The earlier [offline profile](2026-10-26-chat-ui-player-feedback-fixes.md) opened the latest conversation and measured a two-summary page; it never executed that lookup. Separating the catalog executor did not remove a bridge worker's explicit wait for the entire catalog.

The copied-data scan took 2,528.7 ms for the recheck selection and 2,136.2 ms for the large-chat selection. An earlier first sample took 10,538.2 ms. These are separate filesystem/JVM samples, not live-game timings or percentiles. The original installed HTTP samples remain 1,811 / 1,831 / 1,802 / 1,753 / 1,784 ms; they included selection and did not measure frame paint.

The bridge now resolves one summary in the active world's directory. It opens the screen before the selected conversation body finishes loading. Its response's `open=true` means the screen exists; it does not assert that history is ready. The profile separately waits for readiness and prepares the selected content, so the improvement is not inferred from an earlier HTTP response alone. Unknown and foreign-world IDs still fail. The `client.level` guard remains in place.

Other confirmed avoidable work:

- `openSession` reread a loaded idle conversation on each selection. Loaded holders now retain their controller and body. A regression counts foreground operations: reopening reads the selected summary, without rereading the body.
- `AssistantScreen.tick` expanded history by four turns every tick until the whole conversation participated in rendering. Idle ticks now retain the current window. Scrolling upward to its top requests four older turns and preserves the visible message's position when they are prepended.
- Each assistant bubble measured the same layout twice per frame. Text wrapping measured every growing prefix through `font.width`, repeatedly visiting the same glyphs. Layout now uses Minecraft's width-bounded substring operation; the renderer and profile share `prepareConversation`, which prepares the requested window newest first and caches turn tokens, user wrapping, citation preparation and assistant layout. Width, turn replacement, selection, sources and navigation changes invalidate the relevant work. Unchanged frames reuse it. Offscreen inline runs are skipped when drawing.
- History preview was recomputed every frame. It is cached until the view, history settings, model selection or edit state changes. Review found that Escape also needed to invalidate it when canceling an edit; the fix and inclusion regression are included.

The 10 ms poll was conditional, not a fixed 1.8-second sleep. Chat opening no longer waits in that polling loop. Other bridge operations retain their existing waits.

There is no stored screenshot thumbnail/texture renderer in the current chat screen. Conversation loading restores screenshot metadata; it does not call `readMedia`, decode images, or create textures. The media directory therefore contributes no image decoding to this opening path. This change adds no media loading or texture allocation. GPU/font-atlas work remains outside the headless benchmark.

## Traced entry points and permanent instrumentation

The key from gameplay and the key from a container both reach `ClientAssistant.openAssistantScreen`, then `AssistantRuntime.openScreen`, screen construction, and Minecraft's screen initialization. `openScreen` itself does not reopen a session. Initial world attachment starts earlier in `enterWorld` through `openLatest`, holder/provider setup and controller attachment. Explicit bridge selection uses `openSession`. Sidebar pages remain independent catalog work.

Permanent INFO lines use the existing `PackSage timing` style, monotonic `start_ns` / `event_ns` and elapsed milliseconds:

- `client_open_request`: entry to the common key/bridge client opening method; `client_screen_set`: return from `Minecraft.setScreen`.
- `bridge_request`, `bridge_selection`, `bridge_readiness`, `bridge_response`: bridge dispatch and scoped lookup. Readiness reports its boolean without waiting. Bridge request/response logging is limited to screen-opening operations.
- `conversation_store_read`: `open_latest`, `open_selected`, and the scoped summary lookup, including asynchronous queue time.
- `holder_setup_done` and `conversation_attached`: provider/controller setup and selected body attachment, isolating these from disk parsing and client callback delays.
- `screen_constructed`, `screen_init_done`, `screen_prepare_done`, `first_frame_rendered`, and `sidebar_page_done`: screen stages. Screen lines carry an `opening` monotonic ID; selection lines carry the conversation ID. `screen_prepare_done` includes its preparation duration and elapsed time since construction. `first_frame_rendered` records completion of the first screen render call and whether it still showed loading; it is not a GPU presentation timestamp.

Logs contain identifiers, durations, counts and state flags, not questions, replies, tool arguments or media contents. Live runs can correlate the stages by IDs and monotonic timestamps. Actual key-event dispatch, font glyph atlas creation, backdrop drawing and first frame completion still need the installed game's new log lines; none were measured here.

## Disposable data and profile

The instance was read once with `Copy-Item` into:

```text
C:/Users/hung1/AppData/Local/Temp/packsage-open-real-72c5dc029e8a4294a3a573eb39c75db7/packsage
```

All marker changes, cache writes and benchmark reads target this temporary copy. The instance folder was never modified. The fresh copy has 447 JSONL conversations, including 376 in the reported world, and 240 media files totaling 62,336,783 bytes in that world. This differs from the earlier 443/372-chat snapshot because four more conversations existed at copy time.

Selections:

- Recheck chat: `7730f3ed-121c-49ff-8001-8c7b3f0ccd43`, 20,013 bytes, one turn.
- Largest chat: `18754de8-7bad-4079-b9eb-dbf2fac6e6a2`, 3,173,873 bytes, two turns.

`ChatOpeningProfileTest` now calls the same `prepareConversation` used by rendering, including real bubble widths, user wrapping, history selection, turn token counts, current-view navigation reconciliation, saved source labels and replay URL citations. `ScreenPreparationFont` uses Minecraft's real `StringSplitter` and deterministic variable glyph advances without font atlases. It counts glyph visits and checks that a repeated preparation does no additional measurement. Real provider construction is represented by an empty `KnowledgeService`; the new runtime timings expose its installed cost.

The second half invokes the actual `MinecraftDebugAdapter.execute(CHAT_SCREEN, ...)` route with a headless client fixture. It first loads another chat, then measures a cold selected-body request and a loaded-holder reopen. It records screen response, selected readiness and production preparation separately. Only this fixture supplies client dispatch; there is no HTTP socket, real frame scheduler, drawing, GPU or Minecraft process. The opt-in test enforces 300 ms bounds for cold attachment plus preparation and explicit-ID readiness plus preparation on its copied input.

### Before and after stages

Recheck chat, milliseconds:

- Full-catalog bridge lookup: **2,528.7 before**, versus **9 cold / 5 reopen** for the scoped lookup after.
- Initial widget setup: **56.7 before / 65.6 after**; selected attachment from initial opening: **101.6 / 106.8**.
- Initial attachment plus preparation: **129.5 before / 129.5 after**. The before profile manually called selected text routines; the after profile uses the complete shared preparation method. Its preparation stage is **22.7**, and repeated preparation is **0.017**.
- Assistant/user glyph visits: **1,571 before / 562 after**, then zero additional visits for unchanged preparation.
- Actual bridge after: cold request response/readiness/prepared **18.7 / 18.7 / 20.6**; loaded reopen **19.1 / 19.1 / 21.1**. These worker-observed response times may include a selected read that the fixture pumped while the worker returned; the readiness log records `false` at cold screen creation.
- Initial two-summary page after: **31.2**; sidebar construction **0.6**; next ten summaries **108.3**. No full catalog lies on the selected path.

Largest chat, milliseconds:

- Full-catalog bridge lookup: **2,136.2 before**, versus **8 cold / 6 reopen** after.
- Initial widget setup: **55.8 before / 45.6 after**; selected attachment: **190.0 / 192.3**.
- Initial attachment plus preparation: **221.9 before / 235.3 after**. Again, before is the incomplete manual preparation harness, and after includes the production navigation/citation reconciliation. Its preparation stage is **43.1**, and repeated preparation is **0.017**.
- Glyph visits: **4,062 before / 1,729 after**, then zero additional visits for unchanged preparation.
- Actual bridge after: cold response/readiness/prepared **54.8 / 54.8 / 59.3**; loaded reopen **21.5 / 21.5 / 26.2**. The cold selected-file operation logs **36 ms**; the loaded reopen performs no selected-body read.
- Initial two-summary page after: **34.3**; sidebar construction **0.3**; next ten summaries **107.9**.

Tokenizer startup happens before opening, as in the previous fix: 127.5 / 139.7 ms in the after samples. The synthetic glyph regression previously visited 7,800 glyphs for 1,100 characters and now stays below four visits per character. The 20-turn idle regression previously grew from four to twenty turns; it now stays at four until scrolling requests more.

The baseline screen measurements used the original screen source at `1edb04ee` with real-width/double-layout calls. The legacy catalog API was unchanged throughout. Loaded-holder reuse was already present in the later catalog baselines, so their reported reopen durations are not a before measurement of redundant body I/O. The first earlier sample recorded a 6.6 ms reread; no claim is made that rereads dominated these small selected files.

Raw XML is preserved beside the temporary copy as `before.xml`, `before-o1.xml`, `before-large.xml`, `after-o1.xml` and `after-large.xml`. The first file contains the initial 10,538.2 ms catalog sample. Individual samples have warm filesystem/JIT differences and are not interchangeable frame-time percentiles. No new installed-game latency or one-frame acceptance claim is made.

### Reproduction

Use a fresh disposable copy beneath the system temp directory, never the instance path:

```powershell
$env:JAVA_HOME='D:/Project/packsage/.tools/jdk/jdk-21.0.12.1'
$env:PACKSAGE_OPENING_DATA='<temporary copy>/packsage'
$env:PACKSAGE_OPENING_CONVERSATION='7730f3ed-121c-49ff-8001-8c7b3f0ccd43'
./gradlew test --rerun --tests '*ChatOpeningProfileTest' -q
```

Repeat with the large-chat ID. `--rerun` is necessary when only the environment selection changes. The profile changes only the copied `latest` marker.

## UI follow-ups and verification

The sidebar list now starts at y=84, four pixels after the New chat button's y=80 bottom, replacing y=108. Scissor bounds, scroll limits, hit testing and narration share that top offset. Expanded tool bodies split Tool / Parameter / Result / Error / Elapsed keys into `ModernUi.MUTED` spans and values into `ModernUi.TEXT` spans, preserving wrapping and multiline values. These fixes have separate commits and small offset/color regressions.

At code commit `1b015c27`, Java 21 full verification:

```powershell
$env:JAVA_HOME='D:/Project/packsage/.tools/jdk/jdk-21.0.12.1'
./gradlew build -q
node D:/Project/packsage/build/tools/count.cjs .
```

Build exit code 0. **1,282 tests; 1,276 passed; six skipped; zero failures; zero errors.** The opt-in profile separately passed for both selections. Four skipped tests require Windows symlink support, one requires an external grounding candidate, and one is the opt-in profile. The first full run exposed the old deletion-gate fixture's dependency on redundant reopening; the fixture now selects an unloaded target and retains its rejection, send gate and world-change assertions.

Observed RED cases include tick-driven history growth, excessive glyph visits, missing shared preparation, sidebar y=108 instead of 84, missing colored body spans, and stale history inclusion after Escape. The delegated bridge worker's initial RED attempt hit a shared test-results lock; no claim is made of a successful preimplementation bridge RED run. The bridge regressions passed in focused and full runs, including held selected I/O, catalog avoidance, unknown/foreign IDs and loaded body reuse.

Luna's first read-only review found the edit-cancel preview defect; it was fixed with the inclusion regression. The second review approved the follow-ups with no required issues. A manual style pass and `git diff --check` were clean. No formatter, push, merge, installation, computer use or game launch occurred. `CHANGELOG.md`, `docs/roadmap` and `model/GroundingInstructions.java` are unchanged.

## Commits

- `3908b504` [Add] profile explicit conversation lookup and headless glyph measurement
- `27833b29` [Add] regressions for lazy history and linear glyph measurement
- `1edb04ee` [Add] profile real bubble widths and Minecraft text splitting
- `717a6443` [Fix] lay out older turns on scroll and measure text with linear splitting
- `b51ee1a1` [Add] regression for reusable screen preparation and cache invalidation
- `59c8d737` [Fix] reuse screen preparation and prepare requested history newest first
- `91c44a68` [Add] regressions for direct bridge selection and nonblocking screen opening
- `06875f79` [Fix] exercise pending selection and verify loaded conversations avoid rereads
- `e0e7e72f` [Fix] open chats through scoped lookup without catalog scans or readiness waits
- `626f7c75` [Add] permanent chat opening stage timings
- `9ffc9730` [Add] regression for sidebar spacing below New chat
- `c4c93ad0` [Add] regression for distinct expanded tool field label colors
- `d7cfd9e3` [Fix] remove the unused sidebar gap below New chat
- `24f6ee3c` [Fix] distinguish expanded tool field labels from their values
- `a4a67cd7` [Chg] verify history anchoring when scrolling requests older turns
- `9147e53c` [Fix] retain the bridge world guard during asynchronous chat opening
- `84a5b34a` [Add] benchmark shared screen preparation and the actual chat bridge route
- `a6873b37` [Add] regression for history preview after canceling an edit
- `4ce494f7` [Chg] assert real history inclusion when canceling an edit
- `08442a2e` [Chg] exercise the deletion gate with an unloaded conversation
- `5cf30d75` [Fix] restore history inclusion after canceling an edit
- `1b015c27` [Add] isolate holder setup and selected attachment in opening timings
