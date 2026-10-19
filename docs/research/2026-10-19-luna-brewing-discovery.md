# Luna brewing discovery investigation

Date: 2026-10-19.

The observed failures do not establish a general inability to follow instructions. The strongest supported explanation is a search-strategy failure made easier by competing partial-result guidance, literal matching that omits potion display names, and empty pages before brewing candidates are reached. A focused clarification changed Luna's pagination behavior without changing its model or effort. It did not produce a completed answer because the provider subsequently reported overload.

The earlier comparison, [recorded in brewing validation](../validation/2026-10-19-brewing.md), demonstrated different outcomes on Luna Extra high and Sol Medium. Changing both model and effort once did not identify the cause. The findings below supersede an unqualified attribution to model instruction-following ability.

## Method and retained evidence

The investigation audited the request construction and tool wrappers, compared saved executed arguments/results, checked literal matching against actual returned Strength records, and ran two additional manual requests in fresh chats. No autonomous scenario runner, prompt rewrite, recipe change or model API harness was introduced.

The installed CurseForge `Packsage` profile remained Minecraft 1.21.1 / NeoForge 21.1.259, with PackSage SHA-256 `3F7D95FE728758CE1826F893776BC35564D7B6ED9664C543CDDBE884CA446E22`. New trials used Luna Extra high, automatic web search and Screenshot on. The client closed normally during initial inspection and was relaunched before these two trials; both new trials then used the same play session. The default prompt was unchanged and the saved settings had no developer-prompt override. Sol Medium was restored afterward.

Saved trace files below are relative to the profile's `packsage/New_World-8a315c270917e038/` folder. The files contain canonical persisted calls; [ReferenceAliasBackend](../../src/main/java/dev/packsage/model/ReferenceAliasBackend.java) translates evidence references for the model and does not rewrite query cursors.

| Trial | Trace and request | Observed result |
| --- | --- | --- |
| Original Luna Extra high | `7a2a39a1-e19a-4087-8377-6c2ddb19c098-a8313a665bea5b86.jsonl`, request `d239a1d3-c60e-43e4-8f33-1da908f81826` | Sixteen local calls, no returned brewing records, no search continuation attempted. Used web brewing steps and separately cited local potion registry facts. |
| Original Sol Medium comparison | `c8b59d1f-7173-40ed-a545-11e162fadd73-c4b6dd2b9cc90b64.jsonl`, request `8aa342e0-e263-406f-a63e-2e07c2a95876` | Eleven local calls; continued and narrowed native searches to the three Strength transformations. No tool errors or web search. |
| Unchanged Luna Extra high repeat, 15:11 local | `8b7ef3b9-bef7-4a85-9abd-24b9942cd07b-2a1e306540399c96.jsonl`, request `dc7b9d55-891b-4d62-84e4-b60ba06c8494` | Twelve local calls; again missed brewing and used web guidance. Correctly followed one JEI continuation, so the failure was not a universal refusal or inability to paginate. |
| Luna Extra high with one clarification, 15:14 local | `b1e0fcef-a035-4d85-b75e-8ff8780727c0-875abc8741e2541d.jsonl`, request `91ce5ceb-c90c-4514-bb91-0cd5d8fcbe4a` | Eleven native recipe calls, ten consecutive exact continuation links, zero tool errors. Kept the literal phrase `Potion of Strength`; all eleven pages were empty/partial. Round twelve failed with provider `server_is_overloaded` after about 110 seconds. No answer was produced; this is a pagination-behavior observation, not an end-to-end pass. |

The last trial repeated the exact original question and appended only: "For this lookup, a native search with nextCall is unfinished discovery; continue it before applying the JEI fallback for partial evidence." It supplied no item ID, shortened search term or brewing answer. Model, effort and screenshot setting stayed unchanged. There was one trial per new condition; sampling and provider internals were not controlled.

## Competing search guidance

[GroundingInstructions](../../src/main/java/dev/packsage/model/GroundingInstructions.java), lines 38-41, says to continue relevant partial searches and use `nextCall` as supplied. It also tells the model to change source or approach when queries keep returning the same evidence.

The [recipe tool description](../../src/main/java/dev/packsage/model/PackSageTools.java), line 148, says "use JEI when native lookup is partial or unavailable." Unlike the adjacent document-search description, it does not explicitly describe continuation. "Partial" can describe unfinished discovery, incomplete decoding or authority limits; that fallback instruction does not distinguish them.

These are competing cues, not a requirement that the model cannot logically satisfy together. Sol pursued native continuations as well as checking JEI. The original Luna run switched from an empty partial native page to JEI and other sources. That sequence is compatible with the local fallback wording, even though it missed the useful discovery path. The clarification's ten exact native continuation links support the interpretation that making the priority explicit changes behavior. They do not reveal the model's internal reason or prove that this ambiguity is the sole cause.

## Literal matching and delayed discovery

[NativeRecipeReader](../../src/main/java/dev/packsage/knowledge/nativegame/NativeRecipeReader.java), lines 24-45, scans ordinary recipes before brewing and returns immediately while the ordinary scan remains incomplete. The per-call candidate bounds are 512 without an output-item filter and 4,096 with one, at lines 52-69. A relevant brewing rule can therefore sit behind repeated empty ordinary-recipe pages. The public response exposes a continuation but does not explain which source phase has been visited.

[NativeBrewingReader](../../src/main/java/dev/packsage/knowledge/nativegame/NativeBrewingReader.java), lines 63-70, applies a case-insensitive literal substring match to the title, source handle and serialized payload. Its supported record title at line 387 is the output item ID plus `brewing transformation`. The full localized potion name is not included.

Applying those searchable fields to the actual returned Strength records gives:

| Output potion component | `Potion of Strength` matches | `strength` matches |
| --- | --- | --- |
| `minecraft:strength` | No | Yes |
| `minecraft:long_strength` | No | Yes |
| `minecraft:strong_strength` | No | Yes |

Thus, simply following the original native query to the end would not make it match those records. The useful discovery path also needs a different literal query or searchable names. The earlier guided Luna retry and Sol comparison used native `outputItem=minecraft:potion` plus text `strength`; the matching page returned all three transformations. The new clarification deliberately did not supply those parameters and exposed the remaining full-name problem.

The original Luna explored fifteen empty searches across several providers rather than progressing one native search. The repeat also explored several sources and ingredient phrases. These actions establish an ineffective search strategy in this interface; they do not establish a lack of general exploration capability.

## Request transport and screenshot checks

The original accepted-request logs report:

| Request | Screenshot bytes | Initial HTTP body bytes | Initial inputs / tools |
| --- | --- | --- | --- |
| Original Luna | 1,777,137 | 2,389,920 | 2 / 12 |
| Original Sol | 1,780,234 | 2,394,054 | 2 / 12 |

Both had `screenshot=true` and no selected-conversation history. [AssistantScreen](../../src/main/java/dev/packsage/client/AssistantScreen.java), line 50, defaults Screenshot on; [GroundingInstructions](../../src/main/java/dev/packsage/model/GroundingInstructions.java), lines 63-73, attaches its image data URL. [ChatGptBackend](../../src/main/java/dev/packsage/model/chatgpt/ChatGptBackend.java), lines 327-417, serializes the instructions, tools, reasoning setting and image. Base64 expansion explains almost all of these roughly 2.4 MB bodies; the remaining envelope is about 20 KB. Body bytes are not a context-token measurement.

The audited code has no Luna-specific prompt or tool-definition branch here. Logs confirm the requested model/effort, the same twelve tools and successful provider/tool exchanges before the later overload. Source inspection and logs provide no evidence of missing instructions, a hidden tool set, replay truncation or a large text-history burden in these compared initial requests. They do not expose provider-internal prompting or prove that the irrelevant screenshot has no behavioral effect. Screenshot-off and effort-only effects were not tested; no context-overload conclusion is supported by the byte count.

## Cursor error is a separate observation

The earlier guided Luna retry's `INVALID_CURSOR` was not an unexplained invalidation of the supplied cursor. The result and `nextCall` contained `4e88248e-f6b8-489d-895d-ab86bbfb3397` (36 characters), while the attempted argument was `4e88248e-f6b8-489d-ab86bbfb3397` (31 characters), omitting `895d-`. Exact comparison is false. Restarting and correctly using the next cursor succeeded.

That is a concrete copying error. It does not explain the original source-switching decision, and the new trial's ten accepted exact copies show that long-cursor handling can succeed under the same Luna effort setting. No cursor validation was weakened or guessed correction attempted.

## Corrective direction and limits

The evidence supports correcting the interface before drawing a general model-capability conclusion:

1. Align recipe-tool guidance with the general evidence policy: distinguish an unfinished search with `nextCall` from incomplete recipe facts and unavailable support. Preserve bounded investigation rather than requiring exhaustive pagination.
2. Make native brewing coverage and literal text behavior clear, and include actual potion names in searchable facts while retaining distinct component identities.
3. Explain empty-page progress and bounds so changing a query is an informed choice rather than restarting an opaque scan. Keep user-facing source labels readable.

These are proposed corrections, not implemented changes. The investigation cannot identify an exact internal model motive, separate every effect of model versus effort, or establish a failure rate from this small sample. The provider overload is an infrastructure failure; the complete saved error was visually clipped by the outcome renderer, which the player requested as a separate UI fix. That display defect does not explain the prior recipe search choices or truncate the persisted error detail.
