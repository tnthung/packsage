# PackSage v2: native adapter readiness

Goal: close the remaining open items on the Minecraft (native) adapter so every implemented feature is fixed, reviewed and accepted live. Read with the [v2 world model specification](../specs/2026-10-22-v2-world-model.md), the [coherent search policy](../specs/2026-10-19-coherent-search-policy.md) and the open list in the [v2 roadmap](../roadmap/2026-10-22-v2-minecraft-only.md#open).

## Global constraints

- NeoForge 1.21.1, Java 21. Build with `JAVA_HOME=D:/Project/packsage/.tools/jdk/jdk-21.0.12.1 ./gradlew build -q`; the full suite must pass.
- Follow the existing style; no formatting tools. Commit each step with `[Add]`/`[Chg]`/`[Fix]`/`[Rmv]` messages and no AI attribution.
- Client-thread and server-thread work stays bounded (ServerWork 5 ms slices, no retained engine iterators). Coverage is PARTIAL only for a concrete stated reason.
- Code tracks do not edit `CHANGELOG.md` or the roadmaps; the orchestrator updates them after each merge.

## Code tracks

Each track runs in its own worktree from `feat/packsage-v2` and is merged with `--no-ff` after review.

### A. Recipe planning reaches every recipe

`NativeRecipeReader.planCandidates` copies only the first 512 recipes in manager order, so `plan_recipe` returns no routes whenever the target's recipes fall later (beacon, enchanting table and oak planks in vanilla). Replace the prefix scan with an output-keyed source: one cheap pass indexes recipe holders by result item, and the planner's `recipeOutputs` copies only the recipes for the identities it visits. Brewing candidates join the same index. Report PARTIAL only for a concrete limit actually hit (for example an index or visit cap), with the reason. Tests cover a target past the first 512 recipes, a multi-level plan whose intermediate recipes are late, and a cap that does trigger.

### B. Progress and statistics composite continuation

Readers that stop at their source-work budget return a prefix with a repeat note, and repeating can rediscover the same prefix (Astra final Minor 1, `ProgressTools`). Give composites a continuation that resumes after the last completed reader/record, through the shared `output:` path or a provider cursor, so repeated calls reach later readers. Tests prove exactly-once coverage across forced budget stops.

### C. Shared output and runtime minors

- Astra Minor 2: an unshrinkable first cursor record must not discard later records and the provider `nextCursor`; skip it with a counted omission, keep the continuation and use tool-neutral advice.
- Astra Minor 3: a failed output replay discards the tail it created.
- Astra Minor 4: a submission rejected because the world closed is removed from, or never enters, the ServerWork queue, without reopening the close/submission race.
- P11 auditability: the `request_built` timing line also logs the offered tool names.
- Move the bundled Open Font License out of the font resource directory so it remains in the JAR without being loaded as a font.

### D. Trade offer payload

`inspect_entity` trade offers duplicate item components, enchantment and potion data per offer, with an approximately 192 KB worst case. Share repeated item facts within one result (for example a per-result item table referenced by offers) so a full librarian or wandering trader fits comfortably, keeping offer order, prices and PARTIAL reporting.

### E. WorldTools split (after A–D merge)

Split `WorldTools` along its existing tool families without behavior changes.

## Review and live acceptance

1. Claude reviews each track's diff before its merge.
2. Astra reviews the whole post-merge branch once.
3. Sol runs one installed acceptance round through the debug bridge covering every unchecked v2 roadmap clause: the plan_recipe routes, composite continuation, M5 with its world-close counters, P9/J7 on a NeoForge 21.1.202 dedicated server, P11 tool names, journal event families, withdrawals and menu ownership, note anchors and forget/delete, flush on exit and rapid rejoin, raids, the wandering trader, pending-container loot, biomes, command discovery, evidence rereads, saved v1 citations, uncached POI, the trade payload and the two Part B prompt observations (unsupported fortress link, locate cursor reuse with a changed radius).
4. Failures loop back as fix tracks or prompt candidates, followed by a focused recheck.
