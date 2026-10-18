# All-adapter fixture checks

Date: 2026-10-18.

Use the `Packsage` Minecraft 1.21.1 profile with NeoForge 21.1.259 and the rebuilt PackSage JAR. Keep the existing fixture worlds, resource pack, settings and baseline JEI/FTB/Patchouli mods. Add the six guide/manual mods and their required dependencies, NeoVitae for real Modonomicon book content, and Powah for real GuideME content. Create supplies Ponder. Aeronautics and Sable can remain disabled. The base Artifacts check uses no Reliquified Artifacts replacement add-on.

The adapter tooltip must list all twelve registered knowledge providers plus Chat history, including unavailable sources with truthful status. A green status shows adapter availability; actual search and fresh-read results establish useful source coverage. Check saved tool calls/results as well as the answer.

| Adapter | Focused player check |
|---|---|
| Minecraft | Ask what items you are carrying and compare the observed slots/counts with your inventory. |
| JEI | Ask for an installed JEI piston recipe; compare its exact ingredients and output with the viewer. |
| FTB Quests | Ask for a fixture quest and its current progress; compare with the quest screen. |
| NeoForge configs | Ask for a current loaded config setting; verify the answer distinguishes effective values from saved-file fallback. |
| Patchouli | Find and read a passage from Nature's Aura or the existing fixture book. |
| Modonomicon | Find and read an installed passage from NeoVitae's guide, explicitly using Modonomicon. |
| GuideME | Find and read Powah's guide introduction or an installed item page, explicitly using GuideME. |
| Immersive Engineering | Open the Engineer's Manual normally once, then find and read its introduction through the assistant. |
| Actually Additions | Find and read the Atomic Reconstructor passage through the Actually Additions guide source. |
| Relics | Find and reread an installed relic ability description; it must stay documentation rather than a claim about rolled item stats. |
| Artifacts | Find and reread an installed artifact ability description with the replacement add-on absent. |
| Ponder | Find and reread a mechanical press scene's title/literal captions; click its component-viewer link. |
| Chat history | After a completed answer, ask the assistant to find that earlier exchange and confirm it is labeled historical. |

For each guide source, confirm the actual `search_document` result identifies the requested provider, then `read_evidence` rereads its exact returned handle with a fresh observation. Where search is partial, follow its unchanged continuation rather than treating the first page as complete absence. Missing shared/dynamic text and provider initialization requirements must remain explicit. The [guide/manual checklist](2026-10-15-guide-manual-adapters.md) and [Ponder checklist](2026-10-18-ponder-support.md) describe those source-specific limits.

Repeat the source-link click that produced `Navigation target is no longer available`. The red navigation feedback should appear briefly and disappear after five seconds while the chat stays open. Another navigation replaces or clears it; switching sessions/worlds clears it. Request failures and the normal Thinking/activity display retain their existing behavior.

Record the installed versions, matching JAR hash, actual adapter statuses and observed checks in the [validation record](../validation/2026-10-18-all-adapter-fixture.md). Setup/startup verification alone does not accept every adapter's retrieval behavior.
