# Journal lifecycle and navigation fixes

## Status and scope

Implemented and committed in the main `D:/Project/packsage` checkout on `feat/packsage-v2`, starting at `94d196c`. All three defects were reproduced by failing automated tests before their production fixes. The full Java 21 build passes: 1,055 tests, 1,051 passed, 4 skipped, no failures or errors. A read-only Luna High review of `94d196c..32727a9` found no required correctness issues.

The built JAR was not installed. The running game, world, configuration, other worktrees and `build/prompt-candidates/` were not changed. No computer use, formatter, push or merge was used. Live access was limited to `GET /state` and reading `logs/latest.log` and the world's journal directory metadata. No token was printed.

## Root cause: journal never opened

The journal depended on observing a single `ServerStartedEvent`, but its listener was registered only when the assistant was constructed on the first client post-tick. Registering after that event left the journal without a session indefinitely: the existing tick handler refused to proceed when `journal.recording()` was false, so it could never recover. This produces the exact `the journal is closed` reason consumed by both `search_journal` and `remember`; it is not an asynchronous database-opening error.

Evidence and causal path:

- `src/main/java/dev/packsage/PackSage.java:42` registers the first-client-post-tick constructor callback; line 43 constructs `ClientAssistant`.
- `src/main/java/dev/packsage/client/ClientAssistant.java:128` constructs the journal, and line 129 registers its hooks on `NeoForge.EVENT_BUS`.
- At baseline `94d196c`, `src/main/java/dev/packsage/journal/JournalHooks.java:123` opened the journal only in `ServerStartedEvent`; its line 307 tick guard returned while recording was false. There was no registration-time recovery.
- The local primary source `build/moddev/artifacts/neoforge-21.1.202-sources.jar!/net/neoforged/neoforge/server/ServerLifecycleHooks.java:113` posts that event on `NeoForge.EVENT_BUS`. The listener uses the correct bus and the same journal instance supplied to the tools; neither is the defect.
- The same source artifact's `net/minecraft/server/MinecraftServer.java:674` posts the start notification before the tick loop; line 721 publishes `isReady` after a tick. In `net/minecraft/client/Minecraft.java:2091`, integrated-world loading waits for readiness with `runTick(false)`. Lines 1153-1161 call `tick()` only when that flag is true; line 1915 posts the client post-tick event. World startup can therefore complete before PackSage's first client tick. Initial Quick Play connection is one supported path (`Minecraft.java:662`). This source trace establishes the ordering gap without changing or inspecting launcher configuration.
- Current instance `logs/latest.log:85` starts the integrated server at **20:43:31.145**; lines 95-97 already show post-start server activity by **20:43:32.395**; line 98 logs the bridge starting at **20:43:32.841**, at the end of the assistant constructor. There are no journal lines in this session. The existing `saves/PackSage Adapter Filter Live Test/packsage/journal.db` was last modified at **19:09:30**, before this launch, and no WAL was present when inspected.
- Read-only `GET /state` confirmed save `PackSage Adapter Filter Live Test`, `minecraft:overworld`, `running=true`, `paused=false`. Candidate 2's Q5/Q8a/Q8b evidence in `docs/validation/2026-10-25-v2-acceptance.md:3107`, `:3108`, `:3109`, `:4161`, `:4664` and `:4740` records the closed-journal failures while this world was active.
- The earlier `999ac78` smoke result is recorded in `.superpowers/sdd/2026-10-25-v2-prompt-acceptance/progress.md:15`: offered/written 26/26, persisted events and successful journal search. `git diff 999ac78 bff164c -- src/main/java/dev/packsage/PackSage.java src/main/java/dev/packsage/journal/JournalHooks.java` is empty. Commit `df241e3` restored first-client-tick construction from the intermediate bridge implementation's FML-client-setup construction. It restored an existing startup-order assumption; it did not introduce a new SQLite or event-bus implementation.

Fix in `src/main/java/dev/packsage/journal/JournalHooks.java:100`:

- One idempotent server-thread start operation opens the per-world database and skips the same server or a stopped/stopping server.
- The normal start-event listener still opens an early-registered journal (`:132`).
- Registration queues recovery on the integrated server; an already-ready world opens without needing another tick, including while paused (`:328`). The existing failure boundary checks actual server-thread ownership and the current server identity.
- The server post-tick listener recovers the gap where the start event has fired but readiness was not yet published (`:310`). It does not reopen an existing session or retry a database-open failure on every tick. Existing stop/flush behavior remains in place.

Regression: `src/test/java/dev/packsage/journal/JournalHooksTest.java:61` exercises registration before, during and after start, a real temporary SQLite journal, queued recovery without another tick, readiness, the correct database path, preserved session/counters across repeated notifications and closure on stop. Baseline failed all three parameter cases: late cases remained closed; the early case reopened on a repeated start notification. The hostile-lookup test also accounts for the additional guarded registration lookup.

## Root cause: duplicate navigation targets

`src/main/java/dev/packsage/debug/MinecraftDebugAdapter.java:244` formerly required exactly one matching target object. Distinct source records in one reply can legitimately supply the same provider-owned destination, so chest and advancement links were rejected with HTTP 400 before runtime navigation was attempted.

The bridge now chooses the first target when all matches have the same provider and target id, and rejects absent matches or provider collisions. Source-handle differences do not make a destination different. Selection remains restricted to the requested reply; normal runtime source membership and click-time revalidation are unchanged (`src/main/java/dev/packsage/chat/AssistantRuntime.java:1277`).

`src/test/java/dev/packsage/debug/MinecraftDebugAdapterTest.java:30` exercises the real HTTP router, game adapter, saved conversation and assistant runtime with a local client executor and provider. Two duplicate-source cases (chest and advancement) failed at baseline with HTTP 400 instead of 200. After the fix both open exactly once using the first source. Missing ids and same-id/different-provider collisions remain HTTP 400 and perform no navigation. The model is never called.

## Root cause: air advertised a block destination

`src/main/java/dev/packsage/world/WorldTools.java:556` attached a block target unconditionally, even though it had just read an air state. The click-time resolver intentionally rejects air (`src/main/java/dev/packsage/world/WorldNavigation.java:174`), leading to the unavailable result at line 139.

Inspected air now retains its block facts and coordinates but carries no block-highlight target. Ordinary blocks retain their existing targets. `src/test/java/dev/packsage/world/WorldToolsTest.java:23` invokes real inspection/record construction against controlled loaded states: air, cave air and void air all failed at baseline because their records had targets; stone passed. All four now pass. Existing navigation tests retain click-time checks for ordinary blocks.

## Verification

Commands ran in Git Bash from the main checkout, with `export JAVA_HOME="D:/Project/packsage/.tools/jdk/jdk-21.0.12.1"`:

| Check | Result |
| --- | --- |
| Initial journal regression fixture | JUnit reported competing parameter resolvers; corrected parameter order in a separate commit before behavioral reproduction. |
| `./gradlew test --tests dev.packsage.journal.JournalHooksTest -q`, before production fix | 12 tests, 3 expected assertion failures: repeated start reset the session; during/after registration stayed closed. |
| Same journal suite, after fix | 12 passed. |
| `./gradlew test --tests dev.packsage.debug.MinecraftDebugAdapterTest -q`, before production fix | 4 tests, 2 expected failures: duplicate chest and advancement returned HTTP 400. Collision/missing cases passed. |
| `./gradlew test --tests dev.packsage.world.WorldToolsTest -q`, before production fix | 27 tests, 3 expected failures: all air variants advertised targets. Stone and existing cases passed. |
| Combined journal hooks, bridge adapter, world tools and world navigation suites | 52 passed. |
| `./gradlew build -q` | Exit 0; XML totals: 1,055 tests, 1,051 passed, 4 skipped, 0 failures, 0 errors. |
| Manual style pass and `git diff --check` | Passed; no formatter used. |

The build produced `build/libs/packsage-0.1.0.jar`. Installed acceptance remains pending: reopen a world with the new JAR, exercise `search_journal` and `remember`, verify duplicate chest/advancement bridge navigation, and confirm air inspection offers no broken Show link. Automated results do not claim that the still-running old build has changed behavior.

## Commits

- `15da81e` — add journal startup-order regressions.
- `6a45ce7` — correct JUnit fixture parameter order.
- `fbcc757` — recover journal startup and preserve one session per server.
- `fc0292f` — add bridge destination regressions.
- `f42f671` — add air-inspection regressions.
- `d9cd7fb` — accept repeated references to the same destination.
- `32727a9` — omit unusable air block targets.

This report and the roadmap follow-up are committed separately from the code fixes.
