# Ponder support player checks

Date: 2026-10-18.

These checks cover the [Ponder adapter](../specs/2026-10-18-ponder-support.md). Automated tests/builds are handled during development. No Java or Gradle commands are needed from the player.

## Preparation

With Minecraft closed, copy the tested `dist/packsage.jar` into the chosen instance's `mods` folder, replacing its existing PackSage JAR. Create already bundles Ponder; no separate Ponder or PonderJS installation is needed for Create's instructions. The inspected ATM10 runtime selects Ponder `1.0.82+mc1.21.1` from Create `6.0.10`. PackSage accepts the Minecraft 1.21.1 Ponder API family `[0.8.1,1.1)` and records the actual loaded version. Launch the instance and enter a world with Create available.

## Checks

1. Start a new conversation and retry `show me the usage of create presser in ponder`. Confirm the item is resolved to `create:mechanical_press`, then Ponder search uses that exact ID with provider `ponder`. It should return that item's registered scenes and offer a Ponder link without compiling earlier unrelated demonstrations. A call compiles at most four matching scenes; continuation may still be needed when the item has more scenes. Record the actual calls and elapsed time rather than expecting a fixed response duration.
2. Open the item's ordinary Ponder viewer and note an actual scene title. Ask PackSage to search installed Ponder documentation for that title. Confirm it can discover the title through document search, including continued pages where needed.
3. Ask PackSage to read the returned scene evidence and explain the demonstrated steps. Compare its title and literal captions with the viewer. Shared captions, substituted formatting values and visual-only instructions may be missing; those gaps must remain explicit. Demonstration timing must not become a claim about machine speed.
4. Click the returned Ponder link. Confirm it opens the item's ordinary viewer. The link does not promise to select one exact scene among that item's demonstrations. Merely searching or reading evidence must not open a screen or move/change world items.
5. Leave the world and enter another world before using a prior link. Confirm old-world navigation is rejected or refreshed through the current world rather than opening stale content.

Record the game/provider versions, prompts, relevant actual tool calls/results, observed viewer behavior and any missing captions in the [validation record](../validation/2026-10-18-ponder-support.md). Unit tests and API inspection do not substitute for these installed observations.
