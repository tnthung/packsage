# Natural conversation prompt stress test

Date: 2026-10-18.

Exercise the installed assistant through ordinary Minecraft chat to improve the [behavior-driven instructions](../specs/2026-10-14-behavior-driven-tools.md). Use the existing [natural-player workflow](2026-10-15-natural-player-conversations.md): a fresh independent player agent receives a private gameplay goal and only actual assistant replies, chooses its wording and adaptive follow-ups, and never sees tool traces or implementation details. Serialize UI actions. The operator audits saved evidence separately.

Use the currently running `Packsage` fixture profile and `New World`, retaining GPT-6-Luna Extra high, screenshots enabled and automatic web search off. These differ from the older ATM10/Medium protocol; record them and keep them fixed between baseline and candidate trials. Begin with the shipped default and no developer override. Do not fabricate gameplay actions, possessions, machine state or successful outcomes. The user is AFK; the operator handles all PC interaction and automated checks.

Cover at least twelve independent conversation arcs, normally up to three player messages each, plus fresh regression repeats where a prompt change is justified. Let follow-ups depend on the actual reply rather than prescribing tool calls or complete scripts. Include:

- Ordinary greetings, vague goals and context-dependent follow-ups.
- Typos, abbreviations, missing punctuation and approximate mod/item names.
- Crafting quantities, changed quantity constraints, intermediate materials and shared resources.
- Pack-specific rules challenged with remembered vanilla behavior.
- Portable storage, carried-container discovery and currently owned materials.
- Progression directions, current quest state and corrections to the intended goal.
- Machine setup, troubleshooting and an unavailable observation or operating condition.
- Ponder demonstrations and guides from several installed mods.
- Ambiguous names, unsupported or absent content and impossible requests.
- Changed preferences, subject switches, concise next-step requests and stale historical claims.

For every completed turn record its conversation file, request ID, exact player question, answer, useful evidence, unsupported claims, tool count and elapsed time. Judge separately: intent handling, factual grounding, recipe/resource arithmetic, practical usefulness, clarification quality, and citation/navigation behavior. Player satisfaction alone is not factual acceptance. A green provider status, a plausible reply or a passing unit test is not live behavioral proof.

Compare claimed recipe quantities and requirements with the returned complete recipe, not remembered Minecraft defaults. Compare inventory claims with fresh direct and resolved-container evidence; distinguish assumed access from established material capacity. Check that partial search results are not misreported as absence. Inspect native navigation for representative cited recipes/guides. Keep failures caused by provider extraction distinct from prompt failures.

Make prompt changes only after identifying recurring, evidence-backed failures. Preserve tool contracts and read-only boundaries. Improve the general decision policy rather than teaching specific fixture answers. Retest failed questions in fresh chats and use new conversation arcs as holdouts. Do not add an autonomous runner or assertion tests that merely check prompt wording. Record results and remaining limits in `docs/validation/2026-10-18-natural-prompt-stress-test.md`; run the existing automated suite/build for shipped-source changes.
