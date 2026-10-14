# Current question focus specification

Date: 2026-10-13

Status: approved implementation scope.

## Purpose

Keep the player's latest submission distinct from retained conversation and automatically captured gameplay context. This prevents background material from being presented as if it were the question, and gives the model a clear instruction to answer the latest request.

## Requirements

- Replay each selected historical question and completed answer in its existing user/assistant role and order.
- After history, add a separate USER background message containing the explicitly labeled fresh gameplay observation and screenshot, screenshot-omission note, or no-screenshot note as applicable. Keep this message available as evidence for relevant answers.
- Finish model input with a USER message whose only content is the exact raw submitted question. This is the only current request; history and the preceding background message are context, not additional questions.
- Use gameplay background only when it is relevant to the latest request. Do not volunteer item or scene summaries unless the player asks for them or they are needed to answer.
- Answer greetings and social text with only a short direct greeting or social reply. Do not summarize gameplay metadata, continue an earlier topic, or retrieve unrelated evidence.
- Resume an earlier topic only when the latest request asks for a continuation or otherwise depends on it. Explicit follow-ups such as `check again` continue to use relevant history while refreshing current pack facts through evidence tools.
- Evidence-first grounding for pack claims, tool definitions, search policy, output budget, and reasoning-effort selection remain unchanged. The screenshot policy remains unchanged; included images and omission/absence metadata stay in the separate background USER message.
- This prompt guidance cannot guarantee model behavior; inference behavior requires a live model check.

## Acceptance

A request-shape regression uses a prior enchantment exchange, current question `hi`, rich fresh gameplay context, screenshot, and `ReasoningEffort.NONE`. It verifies four input items in order: the unchanged historical user/assistant pair, a separate USER background message with labeled gameplay and image, and a final USER message containing only the exact text `hi`. It also verifies that effort remains NONE and follow-up and evidence-grounding instructions remain present.
