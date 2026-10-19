# Coherent search policy trials

Date: 2026-10-19.

Requirements: [policy specification](../specs/2026-10-19-coherent-search-policy.md). Execution: [implementation plan](../plans/2026-10-19-coherent-search-policy.md). Baseline: [Luna brewing investigation](../research/2026-10-19-luna-brewing-discovery.md).

## Method and policy

Used focused manual conversations in the original New World of the minimal Packsage profile: Minecraft 1.21.1, NeoForge 21.1.259, 34 mods, Luna Extra high, screenshot enabled and automatic public search enabled. No autonomous scenario runner, direct model API harness or gameplay mutations were added. Actual model tool calls, returned facts and final answers were inspected; hidden reasoning was not inspected. All completed trials below used local tools only, with no tool errors or public-search calls.

The candidate replaces the entire instruction policy with one flow: understand the latest request, identify the evidence gap, choose an appropriate literal query, assess search progress separately from decoded record completeness, check material feasibility, then answer from supported facts. Base output IDs and component variants guide discovery; relevant advancing continuations remain useful through empty pages, including ordinary recipes scanned before brewing. JEI supplies needed viewer evidence or facts native evidence cannot supply, rather than automatically replacing every partial native search. Existing references, fresh evidence, inventory scope, privacy, source authority, citations and read-only contracts remain in the whole policy.

Independent read-only Luna reviews checked the complete candidates and directly conflicting tool descriptions. Candidate 4 had no material coherence or lost-contract findings. These reviews establish policy consistency, not model compliance. Official [prompting guidance](https://developers.openai.com/api/docs/guides/latest-model#prompting-best-practices) informed clear priorities and workload-specific evaluation; no model-family capability claim substitutes for the local trials.

Candidate 1 used a saved override and one restart because the desktop API lacked held-modifier clicks. After the player requested the gesture change, [middle-click Advanced settings](2026-10-19-advanced-settings-middle-click.md) was implemented and installed. Candidates 2, 3 and 4 were entered as whole drafts through that editor with Minecraft kept open. Java Properties reads confirmed exact saved equality after each save.

| Candidate | Characters | SHA-256 of UTF-8 trial file |
| --- | ---: | --- |
| 1 | 10,703 | `279448488007658FD408D7B2056A2E359BCAD2106C9E9F3B138935B5E3059E4A` |
| 2 | 10,820 | `EC40E48E4E7113AB58BA02BEF8D2CA36AAB5C98916FE755E78B2934A066522F9` |
| 3 | 11,257 | `A4CAE5AC8C10AABB0EA95E5FAE52776B0FD81279A783C0E751E70C33E341FE08` |
| 4 | 11,368 | `2B0C4C581773BDA80D33D3F1EB278D4024E23A3FA7E4A838E00BFA6CF6D0510A` |

Character counts use the saved LF-normalized Java string. Trial files are ignored local artifacts; the promoted source is the durable prompt.

## Observed trials

Canonical conversations are local JSONL files under the instance's `packsage/New_World-8a315c270917e038` folder. Request IDs below identify individual turns, including follow-ups in the same conversation.

| Candidate / request | Observation |
| --- | --- |
| 1 / `32f55151-f7aa-4058-9614-70d8eb52c2b9` | Original ordinary/longer/stronger Strength question failed with provider `server_is_overloaded` after 12 local calls and 48.742 seconds. No final answer; this is an infrastructure failure, not a behavioral verdict. |
| 1 / `aab0d421-76b4-4218-94d8-acc94002d383` | Fresh unchanged retry completed in 140.823 seconds with 18 calls. Correct water/wart, awkward/blaze, redstone extension and glowstone strengthening from native evidence; batch/fuel/consumption qualifications preserved. It continued both full-name and shorter replacement queries, motivating candidate 2's query-selection clarification. |
| 2 / `99a1a391-5137-40ec-8d31-8fe96ba8b7a4` | Fresh original question completed in 155.035 seconds with 13 calls. All three Strength variants and durations were supported by native transformations and potion registry data. Fewer calls do not establish lower latency. |
| 2 / `72e302b4-ca0e-4d05-b51e-6b0b995429f1` | Fresh holdout asked for a longer-lasting splash Potion of Swiftness. Completed in 110.072 seconds with 15 calls. Correct awkward/sugar, redstone, then gunpowder route and eight-minute duration. The sequence begins from awkward potion rather than explaining its water/wart prerequisite. Exact native container evidence preserved the long-swiftness component through the splash conversion. |
| 2 / `fbf5fadf-7d1f-4d3b-9b68-7e990f7c333b` | Fresh eight-furnace feasibility question completed in 52.420 seconds with five calls. Enumerated carried inventory and read the discovered shulker location before claiming a shortage. Both scopes were complete: coal, oak wood/logs and a box of diamonds, with none of the accepted stone alternatives. Correct negative material conclusion and eight required input positions, with unknown consumption qualified. |
| 2 / `15f81515-e360-435e-bedf-77c8b0960cd7` | Count challenge completed in 22.769 seconds with one fresh recipe search. Correctly kept eight instead of accepting the player's remembered seven. Added an unasked grid-center claim; this is not accepted as explicit layout evidence. |
| 2 / `47ac5ba9-1793-4c1c-a4d0-9cf239fccefd` | Action-only follow-up completed in 37.629 seconds. It stated the read-only limit in its final answer, but three unnecessary reads preceded that answer. Candidate 3 separated action-only requests from information questions. |
| 3 / `820eb2b8-97c9-4ded-ad3a-406644374671` | Fresh count question failed with provider overload after registry and recipe reads. No final answer. |
| 3 / `b6468414-1910-49ed-8288-0030ab43c1f6` | Fresh unchanged retry completed with three reads and corrected eight versus seven, but still described outer slots and an empty center. Returned group labels/counts do not independently meet the prompt's explicit-layout requirement. |
| 3 / `e593e09b-9566-4982-b5ff-5bf4b1ce8429` | Same-conversation action request completed with zero tool calls and the read-only limit first. It then guessed 64 cobblestone from the screenshot, contrary to the earlier structured inventory, and repeated layout advice. Candidate 4 narrows action-only replies to the limit and an offer to help, excluding unrequested scene/material/execution assessment. |
| 4 / `e60705c6-b2a6-4966-b651-d0ce2cb89a32` | Repeated the unchanged action request in that conversation, retaining its earlier misleading reply. Passed: brief read-only limit and an offer to explain crafting, zero tool calls, no scene/material/layout claims. |

## Source and automated acceptance

Commit `8326961` promotes the entire candidate 4 to `GroundingInstructions.INSTRUCTIONS` and aligns the recipe/document tool descriptions. It removes the obsolete assertion requiring automatic JEI fallback on partial native lookup, retaining existing request composition, override persistence/snapshot and schema/continuation tests. No reader, schema, cursor, transport, model-default or gameplay implementation changed in this step.

Java 21 `gradlew.bat test build` passed in 35 seconds: 641 tests across 54 suites, zero failures/errors and four existing skips. A direct compiled `defaultInstructions()` comparison exactly matched the 11,368-character saved candidate. Manual style review and `git diff --check` passed; no formatting tools ran.

Minecraft saved and quit normally before `scripts/build.ps1` installed the final artifact. Its build reused the passing tasks. Built, dist and installed minimal-profile JARs matched SHA-256 `A3F593257640FF7A50CC1064295C2B9150179DDF56B3606C372516269FFAFB1B`.

## Installed-default acceptance still to finish

The final brewing trial with the developer override cleared remains pending. The current records demonstrate successful discovery on the earlier complete-policy candidates and expose remaining compliance problems; they do not establish universal reliability or an improvement in response time. The action retest passed after the final scope revision, but explicit-layout compliance remains unproven.
