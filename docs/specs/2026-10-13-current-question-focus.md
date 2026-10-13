# Current question focus specification

Date: 2026-10-13

Status: approved implementation scope.

## Purpose

Keep the player's latest submission distinct from retained conversation and automatically captured gameplay context. This prevents background material from being presented as if it were the question, and gives the model a clear instruction to answer the latest request.

## Requirements

- The current user message starts with the exact raw submitted question as its own text content.
- Fresh gameplay observation follows as a separate, explicitly labeled background text content. It remains available to ground relevant answers.
- Selected historical turns remain before the current user message, with their existing user/assistant role and replay ordering.
- Prior conversation is context. Resume an earlier topic only when the latest request asks for a continuation or otherwise depends on it. Short greetings and social messages receive a short direct reply without unrelated evidence lookup.
- Explicit follow-ups such as `check again` continue to use relevant history while refreshing any current pack facts through evidence tools.
- Evidence-first grounding for pack claims, screenshot inclusion or omission, tool definitions, search policy, output budget, and reasoning-effort selection remain unchanged.
- This prompt guidance cannot guarantee model behavior; inference behavior requires a live model check.

## Acceptance

A request-shape regression uses a prior enchantment exchange, current question `hi`, rich fresh gameplay context, screenshot, and `ReasoningEffort.NONE`. It verifies that the current user message's first content is exactly `hi`, history remains ordered, gameplay appears separately as labeled background, the screenshot is preserved, and effort remains NONE. Existing follow-up and evidence-grounding instructions remain present.
