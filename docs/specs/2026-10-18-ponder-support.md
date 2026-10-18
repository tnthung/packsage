# Ponder support

Date: 2026-10-18.

Extend the existing [behavior-driven tools](2026-10-14-behavior-driven-tools.md) with installed Ponder documentation. Keep `search_document` and `read_evidence` unchanged; scenes are authored `guide` documents from logical provider `ponder`. Navigation remains a player click.

## Installed contract

ATM10's Create `6.0.10` embeds Ponder `1.0.82+mc1.21.1`; its PonderJS `2.4.0` also embeds `1.0.81+mc1.21.1`, and the installed log selects Create's newer copy. Create supplies Ponder without a separate player installation. Its registered storyboard entries expose component, namespace, schematic and tags, but not the scene ID or title. Multiple storyboards can share a schematic. Do not infer scene identity from a schematic path.

The installed library's language-generation path calls `PonderSceneRegistry.compileScene(localization, entry, null)`. A fresh private `PonderLocalization` collects the scene header and numbered literal caption templates without loading a schematic, creating a scene world, starting simulation or reloading global registration. This executes one mod-owned callback; callback duration and arbitrary custom behavior cannot be bounded by PackSage. Shared text, formatting arguments and custom dynamic instructions are not completely represented by that collection.

## Behavior

- Discover current registered storyboard associations and search component identity/name, schematic, registered tags and the current localized scene title. Bound discovery to four candidate entries per call; continuation rereads the registry rather than retaining scenes or text.
- Resolve source handles using registration identity as well as component/namespace/schematic, keeping distinct storyboards separate and rejecting removed or replaced registrations. Source generations, locale and world scope invalidate continuations as appropriate.
- Title discovery may execute one storyboard per candidate through the private localization path. Caption bodies are returned only by explicit evidence reads, which execute one current storyboard per requested handle. Never call Ponder's global reload, language generation, component-wide compile or scene simulation to read evidence.
- Evidence returns the current scene ID, title and literal caption templates in their numbered order within existing text/work budgets. Mark extraction partial because shared captions, formatting arguments, visual instructions and arbitrary callback mechanics are not decoded. Presentation ticks are not machine throughput.
- Attach a navigation target for the component's ordinary Ponder viewer, clearly identified as item-level navigation. The public factory does not expose a single-scene deep link. Revalidate scope and registration before opening; opening is client-thread-only and happens only after the player clicks.
- Accept the Minecraft 1.21.1 Ponder API family `[0.8.1,1.1)`, including bare historical versions and current `+mc1.21.1` labels. The required API is present in inspected releases from `0.8.1` through `1.0.87`; `1.1` is the next-family audit boundary, not an observed breaking release. Keep the actual loaded version in evidence provenance. Missing/unsupported versions remain optional and unavailable; failures must not prevent other adapters from loading. See the [API history](../research/2026-10-18-ponder-api.md) for the inspected samples and forward-compatibility policy.

## Acceptance

Automated checks cover discovery/title matching, duplicate schematic registrations, fresh caption rereads, callback count/output bounds, invalid continuation and removed registration behavior, optional loading and click navigation scope checks. Run the full Java test/build commands. Record automated evidence separately from live Minecraft acceptance; do not mark installed behavior accepted from unit tests alone.
