# v2 Prompt Rewrite and Full In-Game Acceptance Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Tasks 5–7 are live tasks: the orchestrator runs them through a Codex computer-use tester, not an implementer subagent.

**Goal:** Ship one coherent system prompt and tool-description set for every v2 tool, with tool-call timing logs, robust citation handling and cheap cross-stage cleanups. Then run the single end-of-branch in-game acceptance on the installed default prompt with the developer override cleared.

**Architecture:**
- **Policy split.** `GroundingInstructions.INSTRUCTIONS` holds the cross-tool decision policy: understand, find evidence, search and continue or stop, check feasibility, answer. `PackSageTools` descriptions hold only tool-local facts: what a tool reads, its arguments, units, coverage and continuation. When the two conflict or duplicate, the rule moves to the side that owns it.
- **Live loop.**
  1. Candidate 1 is drafted in code and installed.
  2. Later candidates go through the in-game developer editor, opened by middle-clicking the settings gear, with Minecraft kept open.
  3. The tested whole prompt is promoted to the code default, the override is cleared, and acceptance runs on the installed default.
- **Code tasks.** Timing goes into `RequestController`, with a small pure interval helper. The citation fix goes where the investigation shows the token goes wrong.

**Tech Stack:** Java 21, NeoForge 21.1.202 / Minecraft 1.21.1, JUnit 5, Log4j 2 test appenders.
- Full build, in bash: `JAVA_HOME=D:/Project/packsage/.tools/jdk/jdk-21.0.12.1 ./gradlew build -q`.
- One class: `JAVA_HOME=D:/Project/packsage/.tools/jdk/jdk-21.0.12.1 ./gradlew test --tests '<class>' -q`.
- Results are in `build/test-results/test/*.xml`.
- Install, with Minecraft closed, in PowerShell: `scripts/build.ps1 -ModsDirectory C:/Users/hung1/curseforge/minecraft/Instances/Packsage/mods`. It runs `test build`, copies the JAR and checks its SHA-256.

**Spec:**
- [v2 world model](../specs/2026-10-22-v2-world-model.md): Notes, Prompt and Acceptance 1–5.
- [v2 Minecraft completion](../specs/2026-10-25-v2-minecraft-completion.md): Acceptance 1–8.
- [coherent search policy](../specs/2026-10-19-coherent-search-policy.md).
- [request timing](../specs/2026-10-14-request-timing.md), for the logging rules.
- [persistent navigation](../specs/2026-10-18-persistent-navigation.md).
- The "Assistant prompt changes" section of `AGENTS.md` (`CLAUDE.md`).

Inputs that define the tools this plan's prompt must cover:
- [stage 1 item reading](2026-10-25-v2-item-reading-consolidation.md);
- [stage 2 progress and statistics](2026-10-22-v2-progress-statistics.md);
- [stage 3 world knowledge](2026-10-25-v2-world-knowledge.md), which adds `read_loot`, `locate`, `search_commands`, `read_command`, villager offers and `world-place` links;
- [stage 4 journal](2026-10-22-v2-journal.md), which adds `search_journal`, `remember`, `update_note`, `forget_note`, `journal-place` and `journal-note` links, and invisible notes.

The known live failures are in the [world reading validation](../validation/2026-10-22-v2-world-reading.md) and the [v2 note](../roadmap/2026-10-22-v2-minecraft-only.md) "Next plans" and "Open" sections.

**Order:** this is stage 5. Before Task 1, stages 3 and 4 must be merged into `feat/packsage-v2` with `git merge --no-ff`, and the full build must be green. Re-read every shared file before editing it. This plan names symbols, not lines.

**After this plan:** a final whole-branch code review by Codex `gpt-6-astra` and the merge decision. Neither is planned here.

## Global Constraints

- **Read-only.** Nothing in the world changes because PackSage read it. The assistant never runs commands, never performs gameplay actions and never announces note-saving.
- **Model-neutral prompt.** Do not add scenario answers, fixture names, coordinates or test-world facts to the prompt. Generalize each observed failure into a rule that applies to every tool it concerns. Integrate rules into existing sections rather than appending exceptions.
- **No duplicates or conflicts.** Each rule lives once, in the prompt or in a description. Direct conflicts between them are resolved when they are found, not left for later.
- **Links by default.** Every reply that relies on a navigable record offers its link: world block and entity, `world-place`, journal place and note, advancement and recipe. Coordinates also appear in the text for every located block, entity, place, note and structure. A link is omitted only for a concrete reason: no navigable target, a redundant link, or the player's preference. The assistant never invents destination IDs.
- **Timing logs** carry only timing and safe metadata: request id, call id, tool name, sizes, counts and error flags. They never carry arguments, results, item components or message text (request-timing spec).
- **Editor bound.** The prompt must fit `PackSageSettings.MAX_DEVELOPER_PROMPT_LENGTH` (65,536 chars), so the editor can hold it.
- **Live checks.** Run them in singleplayer unless stated otherwise. They must not use fixture state that the reply could only know from the tester.
  - Keep one PackSage model and effort fixed across candidates and the final run. The default is `gpt-6-sol`, medium reasoning, automatic search allowed and screenshot as already set, matching the world-reading rounds. Record the choice.
  - The player's JAR-copying workflow applies only to live checks. Implementers run every Java, Gradle and test command themselves.
- **Commits.**
  - Commit each work step as soon as it is written. Tests, code, docs and each bug fix are separate commits, made without waiting for tests to pass.
  - Generate every message with `$commit-message` from the staged index (`[Add]`/`[Chg]`/`[Fix]`/`[Rmv]`), with no Co-Authored-By or AI attribution.
  - Never use formatting tools. Never fast-forward merge.
- **Prompt tests are not behavioral proof.** Wording and name tests guard structure only. Model compliance is established only by the live tasks, from actual calls, returned evidence and replies.

## Review Focus

1. **A tool result that completes before the model exchange is installed.** These results take the pending path in `RequestController.begin`, which submits them on install. Expect exactly one `tool_result_sent` line per call, logged when the result is actually submitted, with `toolMs` measured from receipt. Test in Task 1.
2. **Citations from providers added by stages 3 and 4.** `ScreenText.CITATION_MARKER` lists only `native|config|history|world|progress`, so a correct `[journal:note:…]` citation would render raw, just like the malformed token. Expect every provider id registered at merge time to render as a chip. Test in Task 3.
3. **A whole-set question whose cursor runs for many pages,** such as "what do I have stored?" over 320 chests. The new continuation rule must keep paging while pages can change the answer, then stop and state what was not read. It must not loop to the call budget, and must not stop after page one. This is live probe Q4 in Task 5.
4. **A report mixed with a question,** such as "I built an iron farm here, why isn't it working?". Expect both a silent `remember` and a diagnosis, with no mention of the note. This is live probe Q8b in Task 5.
5. **A stale developer override that masks the promoted default.** Expect saving the editor unchanged after installing the promoted build to clear the property, because `DeveloperSettingsScreen` stores null when the draft equals the default. Then `config/packsage.properties` has no `developerPrompt` with Minecraft closed. Verified in Task 6.

---

## File Structure

```
src/main/java/dev/packsage/chat/ToolTiming.java          NEW   pure interval union for tool wall time
src/main/java/dev/packsage/chat/RequestController.java   MOD   tool_received / tool_result_sent / tool_summary logs
src/main/java/dev/packsage/model/GroundingInstructions.java  MOD  whole-policy rewrite (candidate 1, later the promoted text)
src/main/java/dev/packsage/model/PackSageTools.java      MOD   description edits integrated with the policy
src/main/java/dev/packsage/model/ReferenceAliasBackend.java  MOD?  only if the investigation places the defect in translation
src/main/java/dev/packsage/client/ScreenText.java        MOD?  provider list from registered ids; unresolved-token rendering
src/main/java/dev/packsage/world/WorldTools.java         MOD   run delegates to ServerReads.run
src/main/java/dev/packsage/world/minecraft/MinecraftTrades.java  MOD  offerCount beside offersStatus
src/test/resources/log4j2-test.xml                        NEW?  quiets INFO timing logs in tests (Task 2c decides)
src/test/java/dev/packsage/chat/ToolTimingTest.java       NEW
src/test/java/dev/packsage/chat/RequestControllerTest.java  MOD
src/test/java/dev/packsage/model/GroundingInstructionsTest.java  MOD
src/test/java/dev/packsage/model/PackSageToolsTest.java    MOD
src/test/java/dev/packsage/model/ReferenceAliasBackendTest.java  MOD?
src/test/java/dev/packsage/client/ScreenTextTest.java       MOD?
src/test/java/dev/packsage/world/minecraft/MinecraftTradesTest.java  MOD
docs/validation/<execution date>-v2-acceptance.md          NEW   the single acceptance record
CHANGELOG.md, docs/roadmap/2026-10-22-v2-minecraft-only.md, docs/roadmap/2026-10-13-todo.md,
docs/roadmap/2026-10-21-adapter-feature-todo.md            MOD
```

---

### Task 1: Tool-call timing logs

**Files:**
- Create: `src/main/java/dev/packsage/chat/ToolTiming.java`
- Modify: `src/main/java/dev/packsage/chat/RequestController.java`:
  - `startTool`;
  - `toolReady`;
  - the pending-result loop in `begin`, where `request.exchange = startedExchange`;
  - `finishModel`;
  - `ActiveRequest`.
- Test: `src/test/java/dev/packsage/chat/ToolTimingTest.java`, `src/test/java/dev/packsage/chat/RequestControllerTest.java`

**Interfaces:**
- Produces `final class ToolTiming`:
  - `record Interval(long startNanos, long endNanos)`;
  - `static long unionMillis(List<Interval> intervals)`. It returns the wall time covered by at least one interval, in whole milliseconds rounded down. Overlapping and nested intervals count once, and an empty list is 0.
- In `ActiveRequest`: `Map<String, Long> toolReceivedAtNanos` and `List<ToolTiming.Interval> toolIntervals`. Both are guarded by `stateLock`, as the other fields are.
- Log lines, in the existing `PackSage timing request=… phase=…` format:
  - **On receipt:** `startTool` logs `phase=tool_received call={callId} tool={name} step={n} event_ns={} elapsed_ms={since accepted}` after the call is admitted. It records `toolReceivedAtNanos`.
  - **On submission:** wherever `Exchange.submit(result)` is actually called, in `toolReady` and in the pending loop in `begin`, it logs `phase=tool_result_sent call={} tool={} event_ns={} toolMs={sent − received} resultBytes={UTF-8 length of serializedResult} error={result.error()}`. It appends an `Interval(received, sent)`.
  - **Dropped results:** a result dropped by the stale-request guards in `toolReady` logs `phase=tool_result_dropped call={} tool={} event_ns={}` once, with no interval.
  - **Summary:** `finishModel` logs `phase=tool_summary toolCalls={toolCalls.size()} toolWallMs={unionMillis} event_ns={}` immediately before the existing `model_terminal` line. The `model_terminal` line keeps its format unchanged.
- The tool name comes from `ModelBackend.ToolCall.name()`. `toolReady` needs the name, which it already receives as `call`.

- [ ] **Step 0: Confirm the start point.** Stages 3 and 4 are merged. Run the full build, and record the test count to report later.
- [ ] **Step 1: Write the failing tests.**
  - `ToolTimingTest.unionCountsOverlapOnce`: intervals of 0–10 ms, 5–20 ms and 30–40 ms give 30. Nested 0–50 ms and 10–20 ms give 50. An empty list gives 0.
  - `RequestControllerTest.toolTimingLogsReceiptAndSubmissionWithoutArguments`. Use the appender pattern from the accepted-stop test. A call `first`, `lookup`, `{"secret":"do-not-log"}` is completed with a result.
    - Expect one line containing `phase=tool_received call=first tool=lookup step=1`.
    - Expect one line containing `phase=tool_result_sent call=first tool=lookup` with `error=false` and `resultBytes=`.
    - Expect a `phase=tool_summary toolCalls=1` line before the `phase=model_terminal` line.
    - No captured line contains `do-not-log`.
  - `RequestControllerTest.resultReadyBeforeExchangeInstallLogsOneSendAtSubmission` (Review Focus 1). Drive the existing ordering in which a tool future completes before `begin` installs the exchange. If the fakes cannot express that ordering, test the pending loop through the closest existing ordering test. Expect exactly one `tool_result_sent` for the call.
  - `RequestControllerTest.staleToolResultLogsDroppedNotSent`: extend the scenario of `staleToolCompletionWithReusedCallIdCannotSubmitIntoTheNewRequest`. Expect `tool_result_dropped` and no `tool_result_sent` for the stale call.
- [ ] **Step 2: Run** `ToolTimingTest` and `RequestControllerTest`. Expect a compile failure, then failures.
- [ ] **Step 3: Commit the tests.**
- [ ] **Step 4: Implement** `ToolTiming` and the log points.
- [ ] **Step 5: Run both classes, then the full build.** Expect a pass with 0 failures in `build/test-results/test/*.xml`.
- [ ] **Step 6: Commit the implementation.**

### Task 2: Cheap cross-stage follow-ups

These come from `.superpowers/sdd/final-followups.md`. Each part is its own red/green/commit cycle. The rest of that file is deferred and listed in Task 8.

**Files:**
- Modify: `src/main/java/dev/packsage/world/WorldTools.java` (`run(BooleanSupplier, ServerWork.Slice)`), `src/main/java/dev/packsage/world/minecraft/MinecraftTrades.java` (`facts`)
- Create (2c, conditional): `src/test/resources/log4j2-test.xml`
- Test: `src/test/java/dev/packsage/world/WorldToolsTest.java`, `src/test/java/dev/packsage/world/minecraft/MinecraftTradesTest.java`

**2a. One server-read runner.**
- [ ] **Step 1: Write the test** `WorldToolsTest.runMapsFailuresLikeServerReads`. A slice throwing `IllegalArgumentException("bad")` fails with `Toolbox.Failure` code `INVALID_ARGUMENTS` and message `bad`. A slice throwing `IllegalStateException("x")` fails `EXTRACTION_FAILED`. The test uses the existing `WorldToolsTest.tools()` harness. Run it; it should already pass, because it pins the current behavior.
- [ ] **Step 2: Change** `WorldTools.run(BooleanSupplier, Slice)` to `return ServerReads.run(work, worker, worldOpen, slice);`. Delete the duplicated body. If `ServerReads.run` is still a fixed 60, keep `TIMEOUT_SECONDS` only where something else uses it.
- [ ] **Step 3: Run** `WorldToolsTest`, `ProgressToolsTest` and the world-knowledge tests. Expect a pass. Commit.

**2b. Bounded offers carry their total.**
- [ ] **Step 1: Write the test** `MinecraftTradesTest.boundedOffersReportTheTotalCount`, with a merchant whose stored `MerchantOffers` holds `MAX_OFFERS + 3` offers. Allocate it with `Unsafe` as in the existing test, and set `OFFERS` reflectively. Expect `offers` to hold 64 entries, `offersStatus` to be `"bounded"` and `offerCount` to be 67. Below the bound, `offerCount` is present and equals the size, and there is no `offersStatus`.
- [ ] **Step 2: Run it.** Expect a failure. Commit the test.
- [ ] **Step 3: Implement.** In `MinecraftTrades.facts`, add `offerCount` (`number(offers.size())`) whenever offers exist. Run the test, expect a pass, and commit.

**2c. Quiet routine timing logs in tests.**
- [ ] **Step 1: Investigate.** Run `AssistantRuntimeTest` and count the `PackSage timing` lines in its XML `system-out`. Find which Log4j configuration the test JVM uses: check for `log4j.configurationFile` in the test task's JVM arguments, or a configuration on the moddev test classpath.
- [ ] **Step 2a: If no outside configuration is forced,** add `src/test/resources/log4j2-test.xml`. Keep the root logger as the default console appender at its current level, and add a `PackSage` logger at `WARN`. Tests that attach an INFO appender (`RequestControllerTest`, Task 1) already set the level on `getLoggerConfig(PackSageLog.LOGGER.getName())`, so they keep working.
- [ ] **Step 2b: If a configuration is forced,** skip this part, and record why in the Task 8 deferred list.
- [ ] **Step 3: Run the full build.** Expect 0 failures, and `AssistantRuntimeTest`'s `system-out` with no `PackSage timing` lines. Commit.

### Task 3: Malformed-citation robustness

**Background:** Round 3 showed `[ref:e36be868.1.1f)` raw at the end of a reply.
- `ref:<nonce>.<request>.<entry>` is a `ReferenceAliasBackend` model-facing alias.
- `CitationText.append` opens a token at `[`. In mode 1 it waits for `]`, so a `)` close never resolves, and at `finished` the raw token is emitted unchanged.
- `ScreenText.CITATION_MARKER` then cannot match the `ref:` prefix.

Investigate before fixing, under `superpowers:systematic-debugging`.

**Files:**
- Read first:
  - `src/main/java/dev/packsage/model/ReferenceAliasBackend.java` (`CitationText`);
  - `src/main/java/dev/packsage/client/ScreenText.java` (`CITATION_MARKER` and its four uses);
  - the Round 3 saved turn: `C:/Users/hung1/curseforge/minecraft/Instances/Packsage/packsage/PackSage_Adapter_Filter_Live_Test-e341466b58d81012/83dc97a2-333f-4def-bdc5-825993d4d86c-82d2a2f75549aeeb.jsonl`, line 2.
- Modify, depending on the finding: `ReferenceAliasBackend.java`, `ScreenText.java`
- Test: `src/test/java/dev/packsage/model/ReferenceAliasBackendTest.java`, `src/test/java/dev/packsage/client/ScreenTextTest.java`

**Interfaces:**
- Classify the defect from evidence: the saved answer text, saved replay items, saved sources and whether alias `1f` was registered in that request.
  - **(G) generation:** the model wrote `)` for `]`.
  - **(T) translation:** a valid closing was split or lost across stream deltas, or the alias was not registered.
  - **(R) rendering:** the stored text is canonical, but the screen failed.

  Write the classification and the supporting evidence into the validation record's Task 3 section (Task 8).
- Fixes by finding. Apply every one that the evidence supports.
  - **G.** In `CitationText`, accept a bracket token that opens with `[`, holds exactly a registered alias, and closes with `)` instead of `]`. Translate it to `[<canonical>]`. Change nothing else: unknown text inside `[…)` stays verbatim, so prose such as `[see note)` is untouched. Also state the exact syntax in the prompt (Task 4): square brackets on both sides.
  - **T.** Fix the specific split or registration path. Add a split test for every delta boundary inside the token, following `everyCitationSplitExpandsToCanonicalTextAndTerminalReplayRetainsExactCanonicalResult`.
  - **R, or always as a guard.** In `ScreenText`, an unresolved `[ref:…]` or `[ref:…)` model alias is never shown raw. It renders as nothing, and any surrounding prose is kept. This applies only to the `ref:` alias shape `ref:[0-9a-f]{8}\.[0-9a-z]+\.[0-9a-z]+`, and does not change stored text.
- **Provider list (Review Focus 2).** Build the provider alternation of `CITATION_MARKER` from one constant, `static final List<String> CITATION_PROVIDERS`, holding every provider id registered after the merges: at least `native`, `config`, `history`, `world`, `progress` and `journal`. Add a test that this constant equals the provider ids `ClientAssistant` registers. If that registry is not reachable from a unit test, use a literal expected list with a comment naming the registration site.

- [ ] **Step 1: Investigate and classify.** Do not change code yet.
- [ ] **Step 2: Write the failing tests** that match the finding:
  - `ReferenceAliasBackendTest.knownAliasClosedWithParenthesisBecomesCanonicalCitation`: `[<alias>)` at the end of the answer, and mid-text followed by prose, gives `[<canonical>]`. Split variants come from `everyCitationSplit…`'s helper.
  - `ReferenceAliasBackendTest.unknownParenthesisClosedBracketTextStaysVerbatim`: `[see note)` and `[ref:ffffffff.9.9)` are unchanged.
  - `ScreenTextTest.unresolvedModelAliasIsNotShownRaw`: the text `Done. [ref:e36be868.1.1f)` renders the visible text `Done.`, with no span containing `ref:`.
  - `ScreenTextTest.citationsFromEveryRegisteredProviderBecomeChips`: `[journal:note:abc]` with a matching recorded source renders a chip, as `world` does.
- [ ] **Step 3: Run them.** Expect failures. Commit the tests.
- [ ] **Step 4: Implement** the fixes.
- [ ] **Step 5: Run** `ReferenceAliasBackendTest` and `ScreenTextTest`, then the full build. Expect a pass. Commit the fix.

- [x] **Candidate 5 follow-up: provider citation markup.** Translate each complete U+E200 `cite` U+E202 reference group into consecutive bracket citations for exact current-request aliases/references, dropping unknown references and delimiters. Preserve replay and web offsets across every two-delta split. Strip private-use characters on saved-reply display without rewriting stored text, retaining known chips even in mixed groups. Focused tests: 68 passed; full build: 1,057 tests, zero failures/errors, four skipped. Installed/live acceptance remains pending; the [Candidate 5 failure evidence](../validation/2026-10-25-v2-acceptance.md#candidate-5) is unchanged.

### Task 4: Whole-prompt rewrite and tool descriptions (candidate 1)

**Files:**
- Modify: `src/main/java/dev/packsage/model/GroundingInstructions.java` (`INSTRUCTIONS`), `src/main/java/dev/packsage/model/PackSageTools.java` (the description switch)
- Test: `src/test/java/dev/packsage/model/GroundingInstructionsTest.java`, `src/test/java/dev/packsage/model/PackSageToolsTest.java`

**Interfaces:**
- Consumes the merged tool list: the stage 1–2 tools, plus `read_loot`, `locate`, `search_commands`, `read_command`, `search_journal`, `remember`, `update_note` and `forget_note`. Expect 28 tools. Use the actual merged count.
- Produces: candidate 1 as the code default, and the anchor list in `GroundingInstructionsTest.KEY_RULES`, which Task 6 must still satisfy.

**How to write it.**
- Rewrite the whole `INSTRUCTIONS` text as one policy. Do not append to it.
- Keep the five existing section headings and their order:
  1. Understand the request;
  2. Find evidence for the decision;
  3. Search deliberately, then read what is missing;
  4. Check a plan against its requirements;
  5. Give a useful, supported answer.
- Fold in the stage 3 and stage 4 paragraphs: `GroundingInstructions` stage 3 Task 9 and stage 4 Task 13. Rewrite them where needed so no rule appears twice.
- Retain every existing contract named in the coherent-search spec:
  - the read-only action boundary;
  - current versus historical evidence;
  - source authority;
  - claim-specific coverage;
  - recipe and material accounting;
  - the scope of carried containers;
  - exact references and cursors;
  - visual targeting;
  - citations and navigation;
  - public-search privacy.

**Rules the policy must contain.** These are decisions; the wording is the implementer's. Each generalizes an observed failure or a new tool.

| # | Rule (generalized) | Source failure or tool |
|---|---|---|
| R1 | **Owner map in "Find evidence".** Each kind of question has one owning first read, and records give their details:<br>- current held, offhand, open-menu, targeted and ridden state uses the holder words with `inspect_item` and `list_items`;<br>- effects and bonuses use `search_registry active_effect` and `player_attribute`;<br>- surroundings and builds use `inspect_area`, `list_blocks` and `list_entities`, then `inspect_block` and `inspect_entity`;<br>- where items are uses `find_items`;<br>- mob state, villager memories and offers use `inspect_entity`;<br>- what happened before now uses `search_journal`;<br>- what the player told you uses `search_journal` notesOnly;<br>- advancements use `list_progress`, then `inspect_progress`;<br>- counters since the world began use `list_statistics`;<br>- drops and chest loot use `read_loot`;<br>- the nearest biome or structure uses `locate`;<br>- commands use `search_commands`, then `read_command`;<br>- recipes use `plan_recipe` and `search_recipe`;<br>- configs use `search_config`, then `read_config`;<br>- earlier chats use `search_history`, then `read_history`;<br>- rereads use `read_evidence`. | every tool |
| R2 | **Search before enumerating.** To find where items are, start with one `find_items` over the area. Use `list_items` only for a holder the player named or that a search returned. Use `inspect_item` only for the entries whose details matter. | prefer `find_items` |
| R3 | **Whole-set continuation.** When the question asks for a whole set (everything stored, every missing criterion, every offer), follow the useful `nextCall` while unread pages could change the answer. Then stop, and say what remains unread. This is the same continue, change or stop rule as for recipe search. One consolidated paragraph covers every paged tool. | broad storage cursor not continued |
| R4 | **Per-subject completeness.** When a reply answers "what can/which/what's left" over a returned set, it covers every relevant returned entry for each subject. Examples: each armor piece's `enchantmentEligibility`, each missing criterion. If the reply summarizes instead, it says it is summarizing and how many entries it left out. Exclusions shown in the record are respected. | armor omitted eligible enchantments |
| R5 | **Coordinates beside links.** Every located block, entity, place, note or structure the reply mentions gets its coordinates in the text, and its dimension when that differs from the player's, next to its link. | mule reply gave no coordinates |
| R6 | **Interpret time fields.** Game-time fields convert to elapsed player time against the same record's `gameTime`: `lastSlept`, `lastWorkedAtPoi`, `golemDetectedUntil`, journal `fromGameTime` and `toGameTime`, and criterion done times. The conversion is 20 ticks a second and 24,000 a Minecraft day. The diagnosis reasons from the results, such as "slept 3 days ago", and states when they do not explain the problem. | farm reply ignored `gameTime` |
| R7 | **Observed change needs two observations.** A claim that a value is changing, refreshing or growing needs two reads at different times. Example: a short effect duration that keeps refreshing. With one read, present it only as a possible cue. | effect-source refresh cue |
| R8 | **Notes.** When the player reports something they did, built, achieved or found, save it with `remember` without mentioning it, with `at` for a place they mean. Use `update_note` or `forget_note` for corrections, after `search_journal` notesOnly. Questions are not notes. A message that both reports and asks gets both the note and the answer. Notes are what the player said: check them against the journal or live reads when it matters, and never present them as confirmed by the game. | stage 4 |
| R9 | **World knowledge.** Trades come from `inspect_entity`; say "not generated yet" plainly. Loot is a definition: explain chance from rolls, weights and conditions, and never imply a roll. `locate` results are found or predicted, and the reply says which. Commands: explain what to type; you never run commands, and you say when the player cannot run one yet. | stage 3 |
| R10 | **Unavailable worlds.** When a tool reports the world, journal or progress unavailable, as on a multiplayer server, say so plainly and do not guess the state. | multiplayer checks |
| R11 | **Citation syntax.** Cite with the exact returned reference inside square brackets on both sides, `[reference]` or `[label](reference)`. Never emit a partial or altered reference. | malformed `[ref:…)` |
| R12 | **Batching examples** that cover the new tools: `inspect_area` with `search_journal` for a farm diagnosis; several `inspect_entity` ids in one turn; `list_progress` with `list_statistics` when both are asked. Keep the dependent-call rule. | batching contract |
| R13 | **Links by default,** extended to `world-place`, journal place and note, and `Open advancements`. A source citation does not promise a destination. | navigation contract |

**Tool-description edits.** Make these only where they are tool-local, and in the same pass:
- **`find_items`:** add "For a question about everything stored, follow nextCall until it ends or the answer cannot change, and say what remains unread."
- **`list_items`:** add "To locate an item across holders, use find_items first."
- **`inspect_entity`:** add that `lastSlept`, `lastWorkedAtPoi` and `golemDetectedUntil` are game ticks to compare with the record's `gameTime`. Keep stage 3's offers sentence, and add `offerCount` (Task 2b).
- **`inspect_item`:** add "enchantmentEligibility lists each enchantment that can still apply, with its conflicts".
- **`search_registry`:** add "one read shows a duration, not whether it refreshes".
- **Everywhere else:** remove any description sentence that repeats a prompt-level rule, such as citation or link rules.
- **Fallback:** every tool has its own description, and the default `"Read current evidence for …"` branch is never used. Keep it only as a guard.

- [ ] **Step 1: Write the failing tests.**
  - `GroundingInstructionsTest.policyNamesEveryDefinedToolAndOnlyDefinedTools`: every `PackSageTools.definitions()` name occurs as a whole word (`\bname\b`), and `PackSageToolsTest.undefinedToolNames(text)` is empty.
  - `GroundingInstructionsTest.policyKeepsKeyRules`, with `static final List<String> KEY_RULES` holding these short anchors, one per rule group:
    - `"Request independent reads together in one turn"`;
    - `"By default, include relevant navigation"`;
    - `"coordinates in the text"`;
    - `"every relevant returned entry"`;
    - `"follow the useful nextCall"`;
    - `"find_items first"`;
    - `"against the same record's gameTime"`;
    - `"two reads at different times"`;
    - `"without mentioning it"`;
    - `"Notes are what the player said"`;
    - `"you never run commands"`;
    - `"found or predicted"`;
    - `"not generated yet"`;
    - `"Minecraft does not record what applied an effect"`;
    - `"Do not state planner batches as operation counts."`;
    - `"square brackets on both sides"`;
    - `"multiplayer"`.
  - Replace `defaultInstructionsRouteCurrentStateEffectsAndPlans`. Keep its removed-sources regex assertion (`JEI|search_document|read_progress|Ponder|Curios|quests?|guides?`) in a test named `policyNamesNoRemovedSource`. Drop its sentence assertions, which `KEY_RULES` replaces.
  - `GroundingInstructionsTest.policyFitsTheDeveloperEditor`: `defaultInstructions().length() <= PackSageSettings.MAX_DEVELOPER_PROMPT_LENGTH`.
  - Broaden `PackSageToolsTest.undefinedToolNames` to the regex `\b(?:search|read|list|inspect|find|plan|update|forget)_[a-z_]+\b`.
  - `PackSageToolsTest.everyToolHasItsOwnDescription`: no description starts with `"Read current evidence for"`.
  - Rename the exact-count test to the merged count, for example `exposesExactlyTwentyEightToolsAndKeepsOptionalArgumentsOptional`, with `NAMES` in definition order.
  - Update `descriptionsGuideCommonSearchAndEvidenceReferences` to the edited phrases. Keep the assertions whose descriptions did not change.
- [ ] **Step 2: Run** `GroundingInstructionsTest` and `PackSageToolsTest`. Expect failures. Commit the tests.
- [ ] **Step 3: Write candidate 1 and the description edits.** Commit the prompt and the descriptions as separate commits.
- [ ] **Step 4: Run the full build.** Expect 0 failures. Record the prompt's character count and its o200k token count, from `GroundingInstructions.Tokenizer`, or from a one-off JUnit print that is removed before commit.
- [ ] **Step 5: Self-review the policy against the coherent-search spec and AGENTS.md "Assistant prompt changes".**
  - Each rule appears once.
  - Continue, change and stop conditions are explicit.
  - Search-coverage incompleteness is distinguished from record incompleteness.
  - No fixture facts appear.
  - Fix anything that fails, and commit.

### Task 5: Live candidate iteration (orchestrator, Codex tester `gpt-6.1-sol`)

The orchestrator briefs a Codex computer-use tester on `gpt-6.1-sol`, at xhigh reasoning, with this task, Task 7's fixture list and the validation record path. The tester controls the real native Minecraft window. In earlier rounds, `mcp__node_repl__js` imported `@oai/sky`, and `list_apps`, `get_window` and `activate_window` reached CurseForge and Minecraft. Gate on that first; no browser substitute is allowed. If the gate fails, stop and report that the tester is blocked.

**Setup:**
- [ ] **Step 1: Install.** With Minecraft closed, run `scripts/build.ps1 -ModsDirectory C:/Users/hung1/curseforge/minecraft/Instances/Packsage/mods`. Record the installed SHA-256, and confirm `config/packsage.properties` has no `developerPrompt`.
- [ ] **Step 2: Launch.** Launch through CurseForge (profile Packsage), and open the save folder `PackSage Adapter Filter Live Test`. Prepare the fixtures in the Task 7 fixture list that the probes need. Record the PackSage model and effort, and keep them fixed.

**The loop.** Run each candidate on the probe set below.
- Candidate 1 is the installed default.
- For candidates 2 and later, middle-click the settings gear, paste the whole candidate into the developer editor and save. Keep Minecraft open.

For every probe, ask in a **new chat**. Then read the saved conversation JSONL under `…/Instances/Packsage/packsage/<world>/` and `logs/latest.log`, and record:
- each call, with its arguments and call group (same model turn, and overlap from the Task 1 `tool_received` and `tool_result_sent` times);
- coverage, `nextCall` use and evidence returned;
- the verbatim reply;
- links rendered and links clicked, and whether they opened the intended destination;
- `toolWallMs` against `elapsed_ms`.

**Probe set:**

| Probe | Question (exact) | Pass when |
|---|---|---|
| Q1 | "how can I make my armor stronger?"<br>(fixture F-armor) | Each worn piece's eligible enchantments from its `inspect_item` `enchantmentEligibility` are all listed, or counted as a stated summary. Exclusions are respected, so the chestplate gets no second protection type. Each piece is cited. |
| Q2 | "what's in my mule?"<br>(near V2Mule, targeted) | The nested path is followed down to the bundle's diamonds. The mule's coordinates appear in the text beside its `Show` link. |
| Q3 | "where is my stone?"<br>(stone room) | `find_items` comes first, before any per-holder `list_items`. Both chests are given with coordinates and `Show` links, and the shulker path is named. |
| Q4 | "what do I have stored in this room?"<br>(at the 9-chest, 72-type fixture) | `nextCall` is followed while pages add types. The reply covers the 72 types, or states exactly what remains unread. It does not stop silently after page 1. |
| Q5 | "why isn't my iron farm working?"<br>(F-farm) | It cites the unlinked bed and the zombie death from `search_journal`. It converts `lastSlept` and `lastWorkedAtPoi` against `gameTime` into elapsed time and uses them. `inspect_area` and `search_journal` come in one call group. |
| Q6 | "where is my Haste coming from?"<br>(F-beacon) | It says Minecraft does not record the source. It uses the `ambient` flag and the beacon as a labeled deduction. Any "refreshing" claim is backed by two `active_effect` reads. |
| Q7 | "how do I make a potion of strength?" | A brewing route comes from `plan_recipe` or `search_recipe`. There is no mention of JEI, guides or quests, and no invented link. |
| Q8a | "I built an iron farm here"<br>(at F-farm) | `remember` is called with `at`. The reply does not mention saving or notes. |
| Q8b | "I built a sugar cane farm here, why is it so slow?" | Both `remember` and a live diagnosis happen. No mention of notes. |
| Q9 | "where is the nearest village?" | `locate` is used. The reply says found or predicted, and gives coordinates and a `Show` link. Clicking the link draws the column. |
| Q10 | "what does this villager sell?"<br>(targeting F-librarian) | Offers and prices come from `inspect_entity`, cited, with `costForPlayer` explained when present. |
| Q11 | "what should I do next for advancements?" | `available` entries come from `list_progress`, each with a working `Open advancements` link. |
| Q12 | "hi" | No tool calls and no scene description. |
| Q13 | "craft me a diamond pickaxe" | The read-only limit is stated with an offer to explain. No tool calls. |

Scan every reply in a candidate for raw `ref:` or a malformed citation. Any raw token fails that probe.

- [ ] **Step 3: Run candidate 1 on Q1–Q13.** Write a "Candidate 1" section in the validation record, with passing and failing probes in separate lists. Commit the record.
- [ ] **Step 4: Revise the whole policy for the failures.** For each failure, decide whether it is:
  - **prompt-addressable:** generalize it into or within an existing rule. Never add a probe-specific sentence. Record which rule changed and why;
  - **a tool-description gap:** batch these. They need a rebuild: quit Minecraft through Save and Quit, edit, build, install and relaunch;
  - **a code defect:** record it. The orchestrator decides between a `[Fix]` task under `superpowers:systematic-debugging`, with a failing test first, and leaving it as a known failure.

  Keep each candidate's full text in an ignored scratch file (`build/prompt-candidates/candidate-<n>.txt`), not in git.
- [ ] **Step 5: Run the next candidate through the editor.** Re-run every probe, not only the failed ones, because rules interact. Record and commit each candidate's section.
- [ ] **Step 6: Stop the loop** when a candidate passes Q1–Q13, or after candidate 6. In the second case, the orchestrator chooses the best candidate, by most probes passed with no regressions, and records the remaining failures as failures. They are never marked passed.

### Task 6: Promote the tested prompt and verify the installed default

**Files:**
- Modify: `src/main/java/dev/packsage/model/GroundingInstructions.java` (`INSTRUCTIONS`), and `PackSageTools.java` only if Task 5 batched description changes that are not yet in code
- Test: `GroundingInstructionsTest`, `PackSageToolsTest`

- [ ] **Step 1: Promote the final candidate.** Copy its text exactly from the saved override, `developerPrompt` in `config/packsage.properties`, read with Java `Properties` semantics. Paste it into `INSTRUCTIONS` as a text block whose value is identical. Watch the text block's trailing newline and indentation stripping, and the escaping of `\` and `"""`.
- [ ] **Step 2: Run** `GroundingInstructionsTest` and `PackSageToolsTest`.
  - If a `KEY_RULES` anchor fails, check whether the rule is still present in other words.
    - If it is, update the anchor to the promoted wording.
    - If it is not, the rule was lost: return to Task 5.
  - Commit the test update separately from the promotion.
- [ ] **Step 3: Run the full build.** Expect 0 failures. Commit the promotion.
- [ ] **Step 4: Install.** Save and Quit, then close Minecraft. Install with `scripts/build.ps1` and record the SHA-256.
- [ ] **Step 5: Verify the promoted text equals the override** (Review Focus 5).
  1. Relaunch with the override still saved.
  2. Middle-click the gear, and save the editor without changing the text. Because `DeveloperSettingsScreen` stores null when the draft equals `GroundingInstructions.defaultInstructions()`, the save clears the property only if the promoted default equals the tested text.
  3. Close Minecraft, and confirm `config/packsage.properties` has no `developerPrompt`.

  If the property is still present, the texts differ. Diff them, fix the promotion and repeat from Step 1.
- [ ] **Step 6: Run the installed-default smoke check.** Relaunch with no override and re-run Q1, Q4, Q5 and Q12. Expect the same pass results as the final candidate. Record the results under "Installed default" and commit the record.

### Task 7: Full acceptance on the installed default (orchestrator, Codex tester)

Every check runs on the Task 6 installed JAR, with no `developerPrompt` (check before and after) and in a new chat per question. Record each check with:
- the question;
- the calls, with arguments and call groups;
- coverage;
- the verbatim reply;
- links clicked and their destinations;
- timing;
- PASS, FAIL or PARTIAL.

Failures go in their own section. A check that cannot run, such as a blocked fixture, is recorded as NOT RUN with the reason.

**Fixture list.** Use game commands under 256 characters, and check each before typing. Keep the existing save fixtures (stone room, 9 lower chests, V2Mule, horse, farm villagers, 320 chests) and add:
- **F-armor:**
  - `/item replace entity @s armor.head with diamond_helmet`;
  - `armor.chest` with `diamond_chestplate[enchantments={levels:{"minecraft:protection":2}}]`;
  - plain `diamond_leggings`;
  - `armor.feet` with `diamond_boots[enchantments={levels:{"minecraft:feather_falling":1}}]`.
- **F-sword:** `/item replace entity @s weapon.mainhand with diamond_sword[enchantments={levels:{"minecraft:sharpness":3}}]`.
- **F-luck:** `/give @s rabbit_foot[attribute_modifiers={modifiers:[{type:"generic.luck",id:"packsage:test_luck",amount:2,operation:"add_value",slot:"offhand"}]}]`, kept in the main inventory, not held.
- **F-donkey:**
  - `/summon donkey ~ ~ ~ {Tame:1b,ChestedHorse:1b,SaddleItem:{id:"minecraft:saddle",count:1}}`;
  - `/item replace entity @e[type=donkey,limit=1,sort=nearest] horse.0 with iron_ingot 12`;
  - `/ride @s mount @e[type=donkey,limit=1,sort=nearest]`.
- **F-craft:** a crafting table opened, with 3 planks and 2 sticks placed by hand in a pickaxe shape.
- **F-librarian:** `/summon villager ~ ~ ~ {VillagerData:{profession:"minecraft:librarian",level:2,type:"minecraft:plains"}}` with a lectern, right-clicked once so the game generates offers.
- **F-fresh-villager:** an unemployed villager summoned beside a free lectern.
- **F-beacon:** `/fill ~-1 ~-1 ~-1 ~1 ~-1 ~1 iron_block` and `/setblock ~ ~ ~ beacon{primary_effect:"minecraft:haste"}`, under open sky.
- **F-farm:** the existing farm. Unlink one villager from its bed by breaking and replacing the bed elsewhere. On Easy difficulty, let a zombie kill a second villager: `/difficulty easy`, then `/summon zombie` next to it.
- **F-hidden:** `/advancement grant @s only minecraft:nether/all_effects minecraft:speed`.
- **F-datapack:** a generated datapack with 2,000 advancements under one tab. Generate it in a scratch folder outside the repo, copy it into the save's `datapacks`, then run `/reload`.
- **F-fresh-world:** a new world created for P4 and C7.
- **F-server:** a local vanilla 1.21.1 dedicated server on `localhost`, run from a scratch folder outside the repo with `online-mode=true`, for M-multiplayer checks.

**Checklist.** Questions are exact. "Links" means rendered and clicked unless a row says otherwise.

*World reading (world-reading validation and v2 note):*

| ID | Setup and question | Pass |
|---|---|---|
| W1 | Stone room: "where is my stone?" | World model 2: both the direct and the nested stone are found, the nested one by its path. Each chest has coordinates and a `Show` link that outlines it. |
| W2 | F-farm: "why isn't my iron farm working?" | World model 3 and journal 2: cites the unlinked bed and the zombie death, whose `search_journal` detail names the zombie. Reasons from `gameTime`. Note `golemDetectedUntil` if present. |
| W3 | F-armor: "how can I make my armor stronger?" | World model 4: the remaining enchantments for every piece, with exclusions respected. Swift Sneak and Thorns appear on the leggings when the evidence returns them. |
| W4 | V2Mule targeted: "what's in my mule?" | The chest, shulker, bundle and 3 diamonds. The mule's coordinates are in the text. |
| W5 | 9-chest fixture: "what do I have stored in this room?" | `nextCall` is continued. All 72 types are covered, or what remains unread is stated. |
| W6 | 320 chests, world running: "where are my diamonds?" | No visible stall (F3 frame graph), and a correct answer. |
| W7 | A W1 `Show` link after a full Minecraft restart | The outline appears on the same chest. |
| W8 | Mule: "how high can this mule jump?" | About 1.62 blocks from the inspected facts, cited. |

*Completion spec acceptance:*

| ID | Setup and question | Pass |
|---|---|---|
| C1 | F-sword held: "what enchantments can I still add?" | `inspect_item held`. Smite and Bane of Arthropods are excluded. Every other eligible entry is listed.<br>Then empty the hand and ask again: the reply says nothing is held first. |
| C2 | F-craft open: "what's in this crafting grid?" | `list_items opened`. The grid is listed, and the output preview is left out. |
| C3 | F-donkey ridden: "what's in my donkey?" | `list_items ridden`, listing 12 iron ingots. |
| C4 | F-luck: "which of my items gives luck?" | One `filter.tooltip` search finds the rabbit's foot. |
| C5 | F-librarian targeted: "what does this villager sell?" | Offers with prices.<br>Then F-fresh-villager, just employed, before an autosave: "what does this villager sell?" asked twice reports not generated yet both times. Right-clicking it afterwards opens normal trades. |
| C6 | "what does a zombie drop?" | From `read_loot`: rotten flesh, plus the rare drops with their conditions. No roll is implied. |
| C7 | F-fresh-world: "where is the nearest village?" | A position, found or predicted, said which, with a link that marks the place. The `saves/<world>/region` listing is unchanged before and after the call.<br>Repeat near a visited village and expect found. Reopen the saved link after a restart. |
| C8 | Cheats off: "how do I use /fill?" | Usage and argument types from `read_command`, and a note that the player cannot run it yet. |

*World model acceptance:*

| ID | Setup and question | Pass |
|---|---|---|
| M1 | At F-farm: "I built an iron farm here". Quit Minecraft fully, relaunch, then "where was my iron farm?" | The first reply has no mention of saving. The second gives the place with coordinates and a `Show` link that highlights it.<br>Then, elsewhere: "I moved my iron farm". `search_journal` notesOnly shows one note, and the old link highlights the new place. |
| M2–M4 | | Covered by W1, W2 and W3. |
| M5 | Strip-mine 2,000 sand quickly, then set off TNT | The F3 frame graph and `/tick query` mspt during mining show no visible change from idle. Ask "what did I just dig up and blow up?": a few sand runs whose counts sum to about 2,000, plus the explosion and its break runs. The world-close log summary shows `maxHookNanosPerTick` under 1 ms and `overflowed` 0. |

*Progress and statistics (v2 note list):*

| ID | Setup and question | Pass |
|---|---|---|
| P1 | "what advancements am I missing for Adventuring Time?" | The missing biomes by name, from `inspect_progress`, and an `Open advancements` link that opens the Adventure tab. |
| P2 | After mining placed diamond ore in survival: "how many diamonds have I mined?" | Exact `mined` counts for both ores. Picked-up diamonds may also be counted if they are clearly distinguished. |
| P3 | "how far have I walked?" | Blocks, or kilometres derived from them. No centimetres. |
| P4 | F-fresh-world: "how much ancient debris have I mined?" | An explicit zero. |
| P5 | F-hidden: "what hidden advancements do I have left?" | A count, with no names or criteria. |
| P6 | "how long have I played this world?" | `play_time` converted sensibly. |
| P7 | "what should I do next for advancements?" | `available` entries, each with a working link. |
| P8 | A saved advancement link after a restart | Its tab opens. |
| P9 | F-server: "what advancements am I missing?" and "how many blocks have I mined?" | The progress and statistics tools report unavailable, and the reply says so. |
| P10 | F-datapack: "what advancements am I missing?" | No visible stall. |
| P11 | The tool list in a `request_built` log line, and the saved JSONL `tools` | No `read_progress`. Earlier chats still show their saved quest sources and links. |

*Journal (stage 4 list):*

| ID | Setup and question | Pass |
|---|---|---|
| J0 | First world join after install | `saves/<world>/packsage/journal.db` and its `-wal` file exist, and the log has no sqlite native-library error. If loading fails, retry with `-Dorg.sqlite.tmpdir=<gameDir>/packsage/native` and record the result. |
| J1 | | Covered by M1. |
| J2 | | Covered by W2. |
| J3 | | Covered by M5. |
| J4 | Put stone into one chest and take iron from another: "what did I put in chests today?" | Deposits and withdrawals, with chest links and coordinates. |
| J5 | Go through a nether portal: "where did I enter the Nether?" | The portal position. Then earn an advancement and ask "when did I get it?": a time from the journal or the criterion done time. |
| J6 | Across every reply in Task 7 | No reply announces saving a note. "what do you remember about me?" lists the notes as what the player said. |
| J7 | F-server: "what did I do today?" | The journal tools report unavailable, and the reply says so. |

*Place-column visibility (stage 3 marker):*

| ID | Setup and question | Pass |
|---|---|---|
| L1 | In the Nether at the default render distance, untouched: "where is the nearest nether fortress?" | Click the link, close the chat and turn toward the result. The marker column is visible on screen for about 30 seconds, including when the fortress is more than 128 blocks away, where the column is drawn at the 128-block clamp. Record a screenshot. |
| L2 | In the Overworld at render distance 8 chunks: "where is the nearest village?", with the result more than 128 blocks away | The same: the column is visible at the clamp. Record whether fog or the render-distance edge hides it. |

*Prompt behaviors:*

| ID | Setup and question | Pass |
|---|---|---|
| E1 | | Q6 rerun on the installed default. |
| E2 | | Q7 rerun, plus "how do I craft a piston?": a recipe from `search_recipe`, with any navigable link offered and no JEI wording. |
| E3 | "hi" and "craft me a diamond pickaxe" | Same as Q12 and Q13. |
| E4 | "what effects do I have and where do my bonuses come from?" | `active_effect` and `player_attribute` in one call group, overlapping in the timing logs. |
| E5 | All replies | No raw `ref:` or malformed citation in the UI or the saved answer. |
| E6 | All replies | Each located thing has coordinates in the text and a link. Each omitted useful link has a concrete reason, or the check fails. |
| E7 | Three representative questions (W2, C7, P1) | The `tool_received`, `tool_result_sent` and `tool_summary` lines are present. Record model time against tool wall time. |

- [ ] **Step 1: Prepare the fixtures,** and record their commands and positions.
- [ ] **Step 2: Run W, C, M, P, J, L and E** in an order that keeps restarts batched: M1's restart serves W7, P8 and C7's saved link. F-server and F-fresh-world are separate sessions.
- [ ] **Step 3: Record the results.** Commit after each group.
- [ ] **Step 4: Handle failures.** For each failure, the orchestrator decides between:
  - a `[Fix]` code task, with a failing test, a fix, a rebuild and a recheck of that check on the installed default;
  - a prompt change, which reopens Task 5 for one candidate, then Task 6, and re-runs every Task 7 check that touches the changed rule;
  - leaving it as a recorded failure.

  Never check a failed item.
- [ ] **Step 5: Final state.** Record that `developerPrompt` is absent, the installed SHA-256 and the Minecraft state left behind.

### Task 8: Documentation

**Files:**
- Create: `docs/validation/<execution date>-v2-acceptance.md`. This is the single record for stages 1–5. Tasks 3 and 5–7 have already written sections in it.
- Modify:
  - `CHANGELOG.md`;
  - `docs/roadmap/2026-10-22-v2-minecraft-only.md`;
  - the `## v2 branch` section of `docs/roadmap/2026-10-13-todo.md`;
  - the v2 sections of `docs/roadmap/2026-10-21-adapter-feature-todo.md`;
  - `.superpowers/sdd/final-followups.md` (deferred items).

- [ ] **Step 1: Complete the validation record.** It holds:
  - the environment: repo HEAD, installed SHA-256, Minecraft and NeoForge versions, model and effort;
  - the Task 3 classification;
  - candidates 1 to n, each with its passing and failing probes and the rules it changed;
  - the promotion and override-clear evidence;
  - Task 7 with every check ID;
  - a "Failures and unverified behavior" section;
  - a final state.

  Commit.
- [ ] **Step 2: Add a `CHANGELOG.md` entry** under `### <execution date>`. It says what the player gets:
  - one prompt covering every v2 tool;
  - links and coordinates by default;
  - silent notes;
  - complete per-piece answers;
  - continued storage searches;
  - citation repair;
  - tool timing logs;
  - acceptance results, with any remaining failures named;
  - the automated test count.

  Commit.
- [ ] **Step 3: Update the v2 note.**
  - Check only the items that passed: stage 1 sub-items, Progress and statistics live checks, stages 3 and 4 sub-items, world reading "Villager time fields", and "Next plans" items 1–5 and the timing item.
  - Leave the failures unchecked, with a pointer to the record.
  - Replace the sentence saying results are recorded in `docs/validation/2026-10-22-v2-progress-statistics.md`, and the stage 3 and 4 plans' per-stage record paths where the note repeats them, with a link to the single acceptance record.
  - Resolve the "Open" bullet about JEI and quest prompt edits as tested (E2, Q7), or leave it with the reason.
  - Mark the `includeNested` for `list_items` idea as decided (see open question 3).

  Commit.
- [ ] **Step 4: Update both roadmaps' v2 sections.** Check only passing entries, and move them within the adapter feature checklist by status and feature, with one checkbox per mod and feature. Commit each file separately.
- [ ] **Step 5: Append to `.superpowers/sdd/final-followups.md`** a "Deferred after stage 5" list:
  - `EntityInspector` returning `Section`;
  - duplicate offer `components` and the ~192 KB worst case;
  - the `WorldTools` split;
  - the progress composite continuation;
  - 2c, if skipped.

  This file is not documentation under `docs/`. Commit it.
- [ ] **Step 6: Verify links and paths.** Check every relative link in the edited files, and make sure each plan task reference still points at an existing path. Fix anything broken and commit.

## Open design questions

1. **One acceptance record, or per-stage records.** The stage 2–4 plans each name their own validation file. Recommendation: one `docs/validation/<date>-v2-acceptance.md`, because the run is single and shared, with the v2 note and roadmaps linking to it. Task 8 Step 3 rewrites the old pointers.
2. **Probe pass threshold.** One pass per probe per candidate is a single sample of a stochastic model. Recommendation: accept one pass during iteration, but require every probe whose failure drove a rule change to pass again on the installed default (Task 6 Step 6 and Task 7). Record any flake as a failure.
3. **`includeNested` for `list_items`.** This was proposed in Round 1. Recommendation: do not add it. `find_items` already searches nested contents, and R2 routes location questions there. Revisit only if Task 7 shows a per-holder nested question failing.
4. **Multiplayer fixture.** A NeoForge client can join a vanilla 1.21.1 server, and PackSage needs no server side. Recommendation: a local vanilla dedicated server in a scratch folder. If the orchestrator cannot provide one, record P9 and J7 as NOT RUN instead of substituting LAN, which is still the integrated server.
5. **Unresolved `ref:` aliases hidden in the UI.** Hiding a token the model wrote could hide a real citation failure from the player. Recommendation: hide it only in rendering, keep the stored text unchanged, and count every occurrence in the validation record (E5).
6. **Candidate cap.** Recommendation: six candidates, then promote the best and record the remaining failures, so the loop stays bounded.
