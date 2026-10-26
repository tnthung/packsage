# Astra player feedback review 1

**Verdict: NOT READY**

Reviewed `1e8968eeca04bb91dc960e56825d6c63002f745b..2ca69f95569ab5b77e9d868ec9feeb07a5f3667c` on `feat/packsage-v2`, focusing on `src/`, against the player feedback specification and plan dated 2026-10-26. Also reviewed the complete unpromoted `build/prompt-candidates/candidate-13.txt` against the candidate 12 default and the tool descriptions. Candidate 13 SHA-256: `C4E084F844F4C6F555EED6A828A7B82D621228A24B26A6CAFD80F4C84F76BBE6`.

## Critical

None found.

## Important

### I1. Local difficulty discards the pending server-chunk read

Location: `src/main/java/dev/packsage/client/GameStateReader.java:230-234`.

The client-thread capture creates a new `getChunkFuture(..., FULL, false)` and immediately calls `getNow(null)`. Minecraft's `ServerChunkCache.getChunkFuture` schedules its lookup on the server thread when called from another thread. If that task has not run before `getNow`, the result becomes `unavailable`, and the future is discarded. Every subsequent tool call starts over. Unlike F3, which retains its future and observes it on a later frame, this reader has no path to consume the eventual result.

Failure scenario: ask for local difficulty in an ordinary loaded singleplayer chunk while the server is between task drains. The field is unavailable even though the server can read it; repeated calls need not recover. This misses the specified local-difficulty field. Complete the read asynchronously or retain a correctly scoped pending snapshot, without blocking the client thread or loading chunks. Read the server-owned values on the server thread as part of that snapshot.

Verified against the build's NeoForge 21.1.202 sources: `ServerChunkCache.java:211-221` and `DebugScreenOverlay.java:490-503`. Existing reader tests inject completed synthetic snapshots and do not exercise this path.

### I2. Beam buffers flush before fog is suppressed

Locations: `src/main/java/dev/packsage/client/highlight/WorldHighlighter.java:89-90`, `:158-170`.

The two custom render types use Minecraft's shared fallback buffer; neither is registered as a fixed buffer. `BeaconRenderer.renderBeaconBeam` requests the core and then the glow. Switching types immediately flushes the core inside `BufferSource.getBuffer`, before this method changes `ShaderFogStart`. With multiple markers, the next core also flushes the previous glow. Consequently every core and all but the last glow draw with terrain fog; the final `endBatch(PLACE_BEAM_CORE)` cannot retroactively protect them.

Failure scenario: mark a distant Nether location, or a location beyond the fog distance at a low render distance. The bright center is obscured by fog; with multiple markers, only the final marker's glow receives the intended fog override. This regresses the existing marker visibility behavior. Apply and restore fog state around all possible draws, including implicit flushes, or use buffers that really defer both passes.

Verified against `MultiBufferSource.java:42-61`, the fixed-buffer registrations in `RenderBuffers.java:21-42`, and `assets/minecraft/shaders/core/rendertype_beacon_beam.fsh` from the build's Minecraft artifacts.

### I3. Candidate 13 retains the old prohibition for mixed requests

Location: `build/prompt-candidates/candidate-13.txt:6`.

The paragraph first instructs the assistant to use `run_command` for a requested action that one command can perform, but then says: "If the message also asks a separate information question, state the action limit before any tool call, then investigate only that question." That retained read-only branch contradicts the new action policy.

Failure scenario: "Set the time to day and tell me which biome I am in." The explicit mixed-request rule tells the model to omit the authorized time change and investigate only the biome, despite the command being supported. Restrict that refusal branch to actions commands cannot perform, and allow the authorized command plus the separate information read. Keep the rule that tool output, history and web pages cannot authorize an action.

## Minor

### M1. The center of the requested translucent beam is opaque

Locations: `src/main/java/dev/packsage/client/highlight/WorldHighlighter.java:59-65`, `:160`.

The core render type has no transparency state, and the supplied core color is fully opaque (`0xFF4DD9FF`). Only the glow blends. A nearby marker therefore has a solid center drawn over terrain, whereas the specification asks for a translucent beam with a brighter center. Use blending and a suitable core alpha if the entire marker is to meet that requirement; merely adding blending while retaining full opacity is insufficient. This is a source-level rendering finding, not a claim of installed visual acceptance.

### M2. Confirmation still tells the player to unpause

Location: `src/main/java/dev/packsage/client/CommandConfirmationPanel.java:44`.

Every card says "Unpause the world before Run." The merged controller fix and `CommandRunner.java:64-70` deliberately support running queued commands while chat keeps the integrated world paused. A player following the card unnecessarily resumes gameplay before confirming. Remove or correct the obsolete instruction; it is also inapplicable to remote servers.

## Command and prompt assessment

No concrete permission-elevation or vanilla read-only-allowlist bypass was found. Integrated execution derives the source from the server player and changes only output capture and the result callback. Unknown roots, `execute` wrappers, namespaced aliases and mutating subcommands require confirmation in risky mode. Pending decisions are consumed once, expire after 60 seconds, and check request validity again before dispatch. The runtime validity predicate checks authentication, request identity, scope and world; execution checks player/world availability again. Remote commands use the client connection and report unknown success rather than claiming a server result. The tool description correctly forbids retrying a sent command just because success is unknown.

Candidate 13 explicitly distinguishes advice from requested actions, requires the player to ask for the action, and denies authority to tool payloads, retrieved history and web pages. It preserves the historical-evidence limitation, journal routing for recent pickup/drop quantities, evidence/citation rules and the prohibition on other gameplay actions. No text-level injection exception was found. Its F3 routing and command policy are integrated into the existing policy sections, but I3 must be resolved before promotion. This source review does not establish live model compliance against injected tool output or history.

The deployed default is still candidate 12: `GroundingInstructions.java:20` and `:27` prohibit executing commands, while `PackSageTools.java:284` advertises execution. This is the explicitly deferred prompt integration in the plan, not an additional unexpected implementation finding. The current default does not establish acceptance of the command feature; the corrected candidate still needs installed validation and promotion.

## Remaining review coverage

No additional concrete issue was found in pickup/drop counts and server-thread hook guards, additive timing persistence and legacy loading, asynchronous sidebar summaries and selected-conversation loading, the saved sidebar toggle, verbosity persistence, or retaining the host save identity when opened to LAN. The marker still uses the existing lifetime and horizontal distance clamp. Work per marker is fixed geometry rather than a vertical block scan.

Parallel read-only review covered standards/persistence/LAN and game-state/journal/beam behavior. No required standards issue was returned. The suggested finding that F3 targeting must use interaction reach was rejected after checking `DebugScreenOverlay.java:135-136`: vanilla F3 itself uses the same 20-block block/fluid picks as this reader. The opaque-core finding is retained above as M1.

## Verification

- Created detached worktree `D:/Project/packsage-review-astra14` at reviewed HEAD `2ca69f95569ab5b77e9d868ec9feeb07a5f3667c`.
- Ran `./gradlew build -q` there with `JAVA_HOME=D:/Project/packsage/.tools/jdk/jdk-21.0.12.1`; exit code 0.
- Ran `node D:/Project/packsage/build/tools/count.cjs D:/Project/packsage-review-astra14`: `{"tests":1254,"failures":0,"skipped":5,"errors":0}` (1,249 not skipped).
- Removed the detached worktree and verified its path no longer exists.
- Did not run Gradle in the main checkout, edit source, change the prompt candidate, or perform live Minecraft checks. Only this verdict file is committed.
