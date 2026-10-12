# Model controls and follow-up validation

Date: 2026-10-13

The player supplied five screenshots from the installed `629a95b` candidate. Before this fix, its installed SHA-256 remained `B8A7692C1409F22369B9C5135AB4EAC2D3A6FA8D88983D65E40D2378EF7CAA00`.

| Observation | Evidence | Status before this fix |
|---|---|---|
| Nature's Aura guide | Two valid guide searches returned `stale or invalid evidence scope`. | Failed |
| Config read | `general.fieldCreatorRange = 24`, with loaded and saved values distinguished. | Player-observed pass |
| Quest progress | Initial coal/lantern/charcoal tasks were 0/1; a later read reported Gather complete at 1/1 coal and both downstream prerequisites satisfied. | Player-observed pass |
| Follow-up | `check again` reported that the earlier question was absent despite visible local history. | Failed |
| Settings | The player reported no Back button and requested model/reasoning controls. | Requested correction |

The fixture resource pack is now selected in `options.txt`. Settings retain 10 history turns and a 12,000 historical budget. Automatic search is currently enabled; the per-send Search control independently requests explicit lookup.

Guide failure is an output-binding defect: Patchouli supplied an entry source handle but page/lifecycle-qualified navigation source handles. The tool boundary correctly requires each target to bind to its record's exact source handle. The fix keeps the entry source binding and moves page/lifecycle identity into the navigation target ID; scope and lifecycle validation remain required.

The latest completed quest exchange had a persisted conservative history estimate of 22,931, above the configured 12,000 budget. It was excluded from model input while still displayed locally. Historical question/final-answer messages now replace historical tool payload replay, following the [conversation requirements](../specs/2026-10-12-v1.md#6-gameplay-context-and-conversations); local logs and the current typed exchange remain complete.

The existing backend already accepts multiple client calls and returns all results in one continuation. It previously omitted explicit `parallel_tool_calls` and reasoning effort. Requests now explicitly enable parallel calls. Settings preserve Default effort by omission and offer explicit choices for future requests, with Back and Escape returning to the existing conversation. Account model selection is restricted to the visible authenticated catalog. The generic [Responses reference](https://developers.openai.com/api/reference/resources/responses/methods/create) defines parallel calls and reasoning controls; the [SIWC catalog](https://developers.openai.com/siwc/token-sharing-open-source/models-and-inference) does not advertise per-model effort choices. Live acceptance of explicit parameters remains a player check.

## Automated checks

- Initial model/settings/history RED: 49 focused tests, exactly three expected failures.
- Guide boundary RED: 13 Patchouli tests, exactly the new end-to-end source-binding regression failed.
- Review regression RED: exact historical questions, open-screen publication of an unavailable configured model, and rejection of noncanonical guide target IDs failed as expected. A separate effort-snapshot failure came from a legacy test request constructor and was corrected in the fixture.
- Reasoning-rejection RED: 44 backend tests, exactly two expected failures. An HTTP reasoning rejection disabled later search; a streamed reasoning rejection disabled later image input. Both regressions require explicit capability identification before caching a rejection.
- Historical-selection RED: six focused tests, exactly the newest completed question/answer with partial local-only tool replay was incorrectly excluded. Selected-history validation must follow the question/answer input contract; active tool-pair validation remains required.
- Explicit-parameter RED: 46 backend tests, exactly two failures when a reasoning rejection mentioned search or images. Normalized explicit parameters now take precedence over explanatory wording, and absent capabilities cannot be disabled by unrelated failures.
- Final coordinator suite: 266 tests, zero failures/errors, one known Windows-symlink skip. Bootstrap `compileJava` and `git diff --check` passed.
- Independent production-source review: approved, with no remaining important or minor functional findings.

## Committed candidate

| Commit | Change |
|---|---|
| `6724760` | Exact Patchouli entry source binding with canonical page/lifecycle navigation IDs. |
| `7c2bad2` | Model/reasoning settings, Back/Escape, explicit parallel calls and isolated capability rejections. |
| `5d67c52` | Bounded historical question/answer messages and completed-turn selection independent of local-only tool pairs. |

The `5d67c52a0e6e59226e218570e37d775efef238e4` candidate was built from a committed-source archive through `scripts/build.ps1`, excluding the pre-existing implementation-plan edit. Minecraft was closed before installation. The archived test/build passed with 266 tests, zero failures/errors and one Windows-symlink skip.

Installed `C:\Users\hung1\curseforge\minecraft\Instances\Packsage\mods\packsage.jar` and repository `dist/packsage.jar` have the same SHA-256:

```text
180C2E6730A6C6CC05A0D354EBBC80DDD22E00E9B7E5DD08E240C39328081D0C
```

The JAR contains 199 PackSage classes, no bundled JEI/FTB/Patchouli classes, and manifest `Implementation-Version: 0.1.0`. Live guide navigation, follow-up recall and explicit parameter acceptance remain open; use the [focused player checks](../testing/2026-10-13-model-controls-followups.md). G3, representative-pack and LAN/remote acceptance remain separate open gates.

## Follow-up screenshots: assistant history wire format

The player supplied three further screenshots after this installation. Guide lookup now returns `Aura, conceptually` from Patchouli and quotes the fixture marker correctly. A navigation button is present; the screenshots do not show it opening the book, so live navigation remains pending.

Every subsequent quest/config question fails before tool execution with HTTP 400: `invalid_value`, invalid `input_text`, supported `output_text` or `refusal`, at `input[1].content[0]`. Repeating the question preserves the same failure because the prior completed guide answer remains selected as history. This is a request-format regression, not evidence of a quest/config-provider failure.

The model-input tests previously checked the internal question/answer items without validating the assistant history's serialized wire shape. Generic assistant `Message` serialization emits `input_text`, while the accepted assistant output-message form uses `id`, completed status and `output_text` with an annotations array. The [Responses input union](https://developers.openai.com/api/reference/resources/responses/methods/create) and [TypeScript schemas](https://developers.openai.com/api/reference/typescript/resources/responses) describe these distinct input/output-message forms; the observed SIWC rejection governs this route.

The correction uses the existing assistant output-message wrapper for the historical answer only. It retains exact question/answer text and the last completed non-commentary message ID/phase when observed, with a stable local structural ID for legacy turns lacking an observed message. Historical tool payloads, reasoning, media and citation offsets remain excluded. Current typed tool replay remains complete.

Correction regression: two focused tests failed as expected before implementation, at the serialized assistant message and the persisted-turn integration boundary. Final coordinator suite: 268 tests, zero failures/errors and one known Windows-symlink skip. Bootstrap compilation and diff checks passed; independent source review approved the bounded wire correction.

The source fix is committed separately as `1076b6b` (`[Fix] send historical answers as completed assistant output messages`). The committed-source archive `1076b6baaa88dfc1a1b8cb6f08f713897fefdaa1` passed `scripts/build.ps1` test/build with 268 tests, zero failures/errors and one Windows-symlink skip. Minecraft was closed before installation. Installed and repository JAR SHA-256 values match:

```text
AAC56CACB4BD98D1DCB23FD1C07379786E8A6CA00AE652854CAE8C1ED08DEC09
```

Live acceptance of the corrected second-turn request, including synthetic IDs for legacy turns, remains unverified. Retry the quest/config question in the same conversation, then send `check again`; a fresh New chat is not required to repair the saved history.
