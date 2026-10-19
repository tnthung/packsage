# Reference reliability and coherent prompt validation

Scope: [failure trace](../research/2026-10-19-reference-reliability.md), [implementation plan](../plans/2026-10-19-reference-reliability-and-coherent-prompt.md) and [behavior-driven contract](../specs/2026-10-14-behavior-driven-tools.md).

## Baseline and regression

The installed baseline is SHA-256 `C6DD1751F858AB63088246DEEB9B03C06083865ECA0BA8A579416C5EF1F4C299`. The live fixture and settings are unchanged from the [45-turn stress test](2026-10-18-natural-prompt-stress-test.md): GPT-6-Luna Extra high, screenshot on, automatic search off and no developer prompt override. Properties hash is `AF7DA001C214347F01A17D76A991150D1728907B5EC16615FC2EE1126DAA5FA0`. The baseline JAR is retained under ignored `.tools/reference-reliability/before-packsage.jar`.

Commit `d8ced40` adds a pass-through backend seam and two behavior regressions. Java 21 `gradlew test --tests dev.packsage.model.ReferenceAliasBackendTest --tests dev.packsage.model.GroundingInstructionsTest` completed 11 tests in 44 seconds: the two new reference tests failed at their alias assertions, while all nine request-assembly tests passed. The failures reproduce the missing compact-reference behavior, not a compiler problem. The fixtures include observed tool calls before submitting their results so subsequent replay validation exercises a realistic exchange.

## Whole-prompt review

The instruction block was replaced as a whole, following request, evidence, feasibility, investigation and answer decisions. Its final reviewed text is 1,533 whitespace-delimited words versus 1,888 before rewriting. The action boundary is at the start; proof, uncertainty and reference rules have one governing location instead of added scenario warnings.

Independent review identified four concrete issues: ordinary crafting questions could be mistaken for action requests, reported actions needed attribution rather than unqualified present-state claims, shortage conclusions needed complete requirements or a conclusive lower bound, and retrieved history needed citation. These were corrected in the relevant paragraphs. A final review found one repeated reported-action sentence; removing it left no other reported actionable contradiction or material lost contract. These are source-review findings, not proof of improved model behavior.

## Implementation and automated checks

Commit `36c5982` implements model-only typed aliases in `ReferenceAliasBackend`. Accepted tool results register exact canonical references; defined read arguments and supported answer citations translate at the model boundary. Canonical results remain exact in controller replay, saved sources, answers and navigation. Unknown, altered, wrong-kind and prior-request aliases do not resolve. Source prose, identities, counts, provenance and cursors are preserved. Wire replay is checked before canonical substitution; citation streaming, web ranges, steering and explicit resource limits have focused regressions.

The first 134-test focused run exposed a separate real lifecycle bug: opening a saved session after New chat left `newChatPending` set, hiding saved sources/navigation and rejecting navigation before the provider opened it. Commit `eedaab8` clears that flag only after a successful current session load, behind the existing generation/epoch guards. The original failing workflow remains in the integration regression. Its subsequent history assertion incorrectly inspected user-message content instead of the existing assistant-output history representation; `4a021a3` corrects that test without changing production history.

The final focused run passed all 134 tests in five suites, with no skips, failures or errors. Java 21 `gradlew test build` then passed in 23 seconds: 51 suites, 590 tests, 586 passed and four existing skips, with no failures or errors. Independent Sol review reported no remaining actionable findings in alias translation, exact replay, streaming, web offsets, retry, callback/cancellation handling, the session fix or the whole prompt's retained contracts. No formatting tools were used.

Built source is `4a021a3`. Built and installed JARs both have SHA-256 `35C80A4C87DD6CA3EBB312D7CA0960A583A371FFDB4C8691DD92931F5BD47D16`. Minecraft was saved and exited normally; process absence was verified before copying. The settings hash remains unchanged and no developer prompt override is present. Fresh installed conversation acceptance remains pending at this checkpoint. Historical corrupted references are not repaired by guessing a similar source.

## Installed private three-turn arc

In a fresh installed conversation, an independent private player completed all three turns with GPT-6-Luna Extra high, screenshot on, automatic search off and the same properties. The player saw each actual answer before choosing the next question. Every turn completed with zero tool errors. The saved session is `37373c3c-60a6-4451-9ef4-d1ff6dcc68eb-7048e1f42d65e2e8.jsonl`:

| Exact question | Observed answer and evidence | Calls | Request ID | Accepted-to-model-terminal |
|---|---|---:|---|---:|
| “do i have any diamonds in my stuff?” | Found 7 diamonds in a carried shulker. | 2 | `25866de8-f457-4e2e-8b10-b84c6f97aa43` | 16,179 ms |
| “what’s something useful i can craft with those diamonds?” | Recommended a diamond pickaxe, using 3 diamonds and 2 sticks, leaving 4 diamonds. The answer cited a fresh shulker-contents result and the native pickaxe recipe. | 5 | `03de4a7c-56ed-4c78-b982-a8385e0526d6` | 41,780 ms |
| “how do i make the sticks?” | Explained that stripped oak wood makes 4 planks and 2 vertically arranged planks make 4 sticks. | 2 | `a63367f7-5da9-4391-a946-a088d2c52b69` | 32,094 ms |

Local citations bind to exact same-turn sources. Both nested-slot calls exactly matched the prior accepted current-turn slot reference. Persisted calls, results and answers remained canonical; no request-local `ref:` or `slot:` alias leaked. Opening the fresh item-storage chip showed correct canonical metadata. Its source details view has no payload renderer for native storage records, so the UI check verified source identity without displaying the item contents. Clicking the stick-recipe chip visibly opened JEI with 2 vertical planks producing 4 sticks; no crafting or inventory movement occurred. The serialized JEI evidence omits coordinates for slots 2 and 5 and has partial coverage, so the model's placement claim is unsupported by its available data even though the viewer displayed the correct recipe. This remains a live-evidence limitation.

The player rated the diamond, pickaxe and stick guidance useful, while remaining unsure whether the diamonds must be removed from the shulker before crafting. No actions were fabricated. This records the completed three-turn arc only; broader prompt reliability and restart proof remain ongoing.

## Typo, challenge and action regression on the first alias build

The same installed JAR then received these controlled ordinary-player follow-ups in session `456c6528-85fa-482e-9116-1345cecdb1c3-24009b3244a0784a.jsonl`. These supplement the independent arc rather than representing another blind player:

| Exact question | Observed result | Calls | Request ID | Accepted-to-model-terminal |
|---|---|---:|---|---:|
| “i wana make 64 torhces using what ive got on me. whats the plan?” | Resolved the typos, used the pack's one-torch yield, and correctly calculated 64 coal, 8 logs, 32 planks, 64 sticks and 56 remaining logs. All five citations bind. | 9 | `e5fa40b4-7ef2-48e8-a466-6dbbb8f13bb0` | 76,990 ms |
| “wait dont torches make 4 at a time? are you sure about using all my coal?” | Reread both loaded and viewer recipes, but treated remembered vanilla output as a discrepancy, said “Good catch” and unnecessarily made the supported pack yield conditional on a trial craft. Both citations bind to current evidence. | 5 | `6f5c8bef-bed7-4bcd-872e-8074439a15a2` | 60,924 ms |
| “ok craft them for me” | Stated its action limit after five reads, repeated that statement in commentary/final text, and corrected the preceding yield uncertainty. Four citations bind; the torch citation is malformed and remains raw unbound syntax. | 5 | `eb9cb55c-e642-455a-9a41-b671bb8f9bc2` | 51,844 ms |

All turns completed with zero tool-result errors. The six-turn candidate batch totals 28 calls. The malformed citation is `[Torch recipe](jei/1/10/minecraft:crafting/recipe-minecraft:torch-1)`, copied from the exact `payload.viewerHandle` rather than the full record reference. The model boundary had aliased `reference` and `source.sourceHandle` but left that duplicate operational locator exposed. It is a concrete remaining reference failure, separate from historical UUID corruption. The first candidate's log is retained in ignored `.tools/reference-reliability/first-alias-live.log`.

Commit `e4b2629` adds the matching-viewer-handle regression; its single-test run failed at the expected alias assertion in 14 seconds. Commit `dcc0e42` translates only a matching JEI `viewerHandle` using the record's evidence alias. All 13 decorator tests then passed in 12 seconds. A targeted source audit also identified typed slots inside storage `owners[]` and `entries[].payload`; the plan now includes those known containers. Further correction-build checks remain pending. The unsupported placement, false correction and late action boundary above remain model-behavior failures; this work does not claim universal prompt reliability.

## Correction-build verification

The storage-summary regression in `5e3dfa6` failed at the expected owner-slot assertion in 13 seconds. Commit `415d72b` adds bounded traversal of only `owners[]` and `entries[].payload`. The alias/runtime suites passed all 49 tests, then `gradlew test build` passed 592 tests in 51 suites (588 passed, four existing skips) in 23 seconds. Independent Sol review found no actionable issue in either structural correction. This JAR's SHA-256 was `47F67666CADEB5B99E48CBB37B8A4631BE8DF8CA43702B68B2D08C85ED44FB8B`.

Two further manual turns on that correction build are saved in `e0670abe-4df0-4cd2-a7ec-b4c9da0fda86-ec2374551f68e704.jsonl`:

| Exact question | Observed result | Calls | Request ID | Accepted-to-model-terminal |
|---|---|---:|---|---:|
| “how do i make a stack of torches with what ive got? can you show the recipe?” | Correct one-torch pack yield and 64-coal/8-log plan. All five citation occurrences bind to same-turn sources. Clicking Torch recipe opened JEI showing coal above a stick producing one torch. | 5 | `6a714b70-3512-416f-9851-48acba750cf0` | 42,484 ms |
| “ok craft them for me” | The recorded action-limit commentary precedes the first tool call. It still repeats the limit in the final answer, but all five citations bind, including the torch recipe that failed in the previous batch. | 4 | `46bb4b78-b24b-481f-bab2-24dd73553d59` | 47,253 ms |

Both turns completed with zero tool-result errors and canonical saved results, arguments and answers. No bare JEI citation or wire alias leaked into either answer. Neither turn inspected item storage; nested summary traversal is covered by the automated regression rather than claimed as live proof. The log is retained in ignored `.tools/reference-reliability/corrected-alias-live.log`. Across both new candidate batches, eight manual turns used 37 tool calls. The earlier 45-turn stress journal remains separate evidence from earlier builds.

## Saved-link compatibility and final build

The saved malformed torch link has an exact JEI source and durable destination recorded in that same answer. The rendering compatibility contract therefore recognizes only bare `jei/` Markdown destinations matching one distinct same-answer JEI identity. A target must additionally match its provider, handle and scope. Missing sources, changed characters, cross-provider collisions and target-only matches remain unbound. No saved text or live reader validation is rewritten.

The new `ScreenTextTest` regression in `ffe8bc3` failed as expected; 33 other tests passed. After `e0bb495`, all 34 screen-text tests passed in 14 seconds. The final production code is `61a71c6`; Java 21 `gradlew test build` passed in 27 seconds: 51 suites, 593 tests, 589 passed, four existing skips and no failures/errors. A test-only style alignment in `542d087` then passed all 34 screen-text tests again in 13 seconds. No formatting tools were used.

The final built and installed JAR SHA-256 is `F980A02E54D790A85BE171CD14B722833339A138F325F3F4273C5EE70DC02A60`. Minecraft was saved/exited and process absence verified before installation. The settings hash remains `AF7DA001C214347F01A17D76A991150D1728907B5EC16615FC2EE1126DAA5FA0`. Independent Luna review of the saved-link recovery reported no actionable findings.

## Final installed acceptance

The final artifact started a new Minecraft process/window (`123605494`, replacing `138023338`) and loaded the same world. These checks used saved answers; the fresh restart log contains zero backend starts and zero model-terminal events:

| Saved answer | Observed native destination after restart |
|---|---|
| Corrected torch answer `46bb4b78-b24b-481f-bab2-24dd73553d59` in `e0670abe-4df0-4cd2-a7ec-b4c9da0fda86-ec2374551f68e704.jsonl` | Its existing torch chip opened JEI with coal above a stick producing one torch. |
| Formerly malformed torch answer `eb9cb55c-e642-455a-9a41-b671bb8f9bc2` in `456c6528-85fa-482e-9116-1345cecdb1c3-24009b3244a0784a.jsonl` | After New chat, session search reopened the answer with active source chips. Torch recipe opened the correct one-torch JEI recipe. The saved answer still contains the original bare `jei/1/10/...` Markdown destination, proving display recovery without rewriting history. This also verifies the saved-session state fix in the installed client. |
| Earlier Diamatine answer `7fa69683-9ae8-46b7-b8ac-48a2eaab57bf` in `bbd773b4-0aca-415f-bb52-ebdf628d1a9e-901a89040ca54f71.jsonl` | Atomic Reconstructor recipe opened the native diamond-to-Diamatine viewer, using the previously saved native-holder destination. |
| Earlier Ponder probe in `eef677b1-7015-46e0-a354-c84b8ff1e246-f311626224b89e79.jsonl`, source `ponder:57:6508a422` | Open Ponder launched the animated Processing Items with the Mechanical Press viewer. This historical plumbing probe is not counted as an independent natural-player conversation. |

No crafting, item movement or machine operation occurred. The fixture remains unchanged, and Minecraft is left paused in PackSage with the corrected conversation visible. The final restart log is retained in ignored `.tools/reference-reliability/final-restart.log`.

The built, installed and `dist/packsage.jar` hashes all match the final hash above. Only `sha256` changed in `dist/build-metadata.json`; its remaining metadata was preserved. Distribution outputs remain ignored by Git. The two unrelated pre-existing dirty validation documents were preserved.

Acceptance: the demonstrated reference-copy failures, duplicate JEI locator leak, saved-session visibility bug and exact saved bare-JEI representation are resolved with automated regressions and installed navigation proof. The prompt is a reviewed whole, with its original size reduced by about 19%. This does not establish universal model reliability: unsupported placement, false correction under challenge, duplicate boundary text, variable lookup cost and a late boundary in the earlier candidate remain recorded. Corrupted identities and genuinely anonymous old destinations are not guessed into another target.

Final repository verification after the test-style change reran `gradlew test build`: 51 suites and all 593 tests completed in 35 seconds, with 589 passed, four existing skips and no failures/errors. The JAR remained byte-identical to the installed/distribution artifact. All 48 relative documentation links in the changed documents resolve. Manual style review and `git diff --check` passed; only the two unrelated pre-existing validation documents remain dirty.

The later [reference-action and tool-parameter UI changes](2026-10-19-reference-actions-and-tool-parameters.md) supersede this installed artifact. That report records the current build hash and subsequent installed checks; the hashes and checks above describe this prompt/reference checkpoint.

## ATM10 provider-qualified alias regression

The player's October 19 10:17 relic question is saved in ATM10 session `b40ad954-fec1-4d48-b43a-ff31dd5e9813-cfdcd7d1488d6b23.jsonl`, request `c7a05b3a-5870-4ac2-b463-97e107afd5bc`. The answer contains `[native:ref:a6e39be8.1.j]` after Eternal Steak. Its recorded inventory result contains the canonical offhand reference `native:inventory:3980a235-aef5-4b89-a75d-4e71998fb3f5:offhand:0`. The alias translator recognized a bare `ref:` alias but did not recognize an additional matching provider qualifier; the UI therefore displayed its ordinary unbound-citation fallback.

The focused regression failed with the same form: expected a canonical `native:inventory:...` citation, received `native:ref:...`. After adding exact provider validation at the citation boundary, all 15 decorator tests passed, including every stream split and canonical completed replay. Wrong providers, changed/unknown aliases and qualified slot aliases remain unresolved. Independent review then identified alias-shaped Markdown labels as another parsing case; its correction and final verification are recorded with the [Curios equipment validation](2026-10-19-curios-equipment.md).

This corrects newly translated answers. The old saved response has no persisted alias registry and is not repaired by selecting a similar source. The same screenshot's missing accessory coverage is a separate capability gap, addressed by the [Curios equipment specification](../specs/2026-10-19-curios-equipment.md).
