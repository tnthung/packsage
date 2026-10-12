# Changelog

## Unreleased

### 2026-10-12

- Added the Minecraft 1.21.1 / NeoForge 21.1.202 client bootstrap, portable Java 21 build script and deterministic test fixture.
- Added direct ChatGPT sign-in, model selection, streamed replies, read-only tool exchanges, hosted search and a development probe screen.
- Added native recipe, tag, resource and inventory evidence, plus optional JEI, FTB Quests, config and Patchouli adapters with scoped sources and explicit coverage states.
- Added local JSONL conversations and screenshot assets, whole-turn history limits, cancellation and recovery from interrupted writes.
- Added assistant and settings screens, fresh gameplay context, removable screenshots, pause controls and validated source navigation.
- Added bounded tool dispatch, grounding instructions and client lifecycle wiring. The production entrypoint remains the development probe pending the live sign-in gate.
- Fixed stale account, world, history and tool callbacks; typed replay ordering; provider extraction, pagination and visibility handling; and the fixture's FTB chapter identity.
- Added valid provider-absent fixture archives and mocked multi-tool conversation coverage; refreshed quest progress at read time with isolated failures, exposed streamed text in the screen, and corrected vanilla pause-menu usage.
- Added bounded sign-out discovery/revocation retries, repeated sign-out outcome handling, nullable hosted-search replay and clickable probe citations.
- Fixed duplicate background rendering that blurred custom text in the probe, assistant and settings screens.
- Fixed sign-in browser launching in Minecraft's headless Java runtime by using the native Minecraft URL launcher.

Live Minecraft and ChatGPT route acceptance remain pending; automated checks do not establish those results.
