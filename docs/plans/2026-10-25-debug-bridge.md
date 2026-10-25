# Debug bridge implementation

Source: [debug bridge specification](../specs/2026-10-25-debug-bridge.md).

1. [x] Test and implement a bounded HTTP parser, authenticated router, configuration gating and loopback listener/token file lifecycle in `dev.packsage.debug`.
2. [x] Reuse the chat runtime, settings and navigation paths through a separate Minecraft adapter. Await client/server work only from bridge workers, with bounded timeouts. Wire startup and shutdown and restore background-pause preferences.
3. [x] Add endpoint examples and a changelog entry, run focused tests and the full build, review the changed files and record the results in the assigned report.
4. [x] Fix round 1 review findings: screen-default chat flags, reply-owned navigation, pause preference persistence, first-tick startup, per-socket error isolation, stopped-server handling, normal menu/entity interactions, null world state and shared screen/prompt save paths. Cover testable regressions and update the endpoint guide; keep live checks pending.

Game-thread behavior and rendered screenshots remain subject to later live acceptance. Do not access installed instances or other worktrees.
