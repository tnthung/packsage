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
