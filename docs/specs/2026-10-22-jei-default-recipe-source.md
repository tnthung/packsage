# JEI as the default recipe source

Date: 2026-10-22.

Amends the recipe discovery rules of the [behavior-driven tool design](2026-10-14-behavior-driven-tools.md). The [coherent search policy](2026-10-19-coherent-search-policy.md) still applies.

## Problem

`search_recipe` without a `provider` currently searches every registered recipe source in turn and combines the results: Minecraft's recipe manager first, then JEI. JEI lists the same vanilla recipes again, so one search scans both sources and can return each vanilla recipe twice. When JEI is not installed, the JEI source still takes part and reports unavailable, so a search that has read all native recipes ends PARTIAL rather than COMPLETE.

JEI also gives a fuller picture of modded recipes. Minecraft's generic recipe interface exposes only item ingredients and one result item. A mod's JEI category also shows byproduct outputs, fluids and the machines that run the recipe.

## Behavior

A `search_recipe` call without a `provider` searches exactly one source: the first registered recipe source that is available when the call runs. JEI is registered before Minecraft, so:

- when the JEI runtime is available, the search reads JEI only;
- when JEI is not installed, unsupported or its runtime is not ready, the search reads Minecraft's recipe manager only. Unavailable sources contribute no source-status note and do not make the result PARTIAL.

A `provider` still selects that one source, whether or not it is the default. Selecting an unavailable source returns its unavailable failure, as today. `provider: "native"` remains the way to reach recipe types that no mod registered with JEI.

Availability is checked on each call. A continuation is bound to the source chosen by the call that issued it. If the default source changes between pages, the continuation is rejected as not matching the query, and the search starts again.

Unqualified document discovery keeps combining all document sources. `plan_recipe` keeps reading Minecraft's recipe manager. Record contents, coverage labels, navigation targets and per-source bounds do not change.

## Model-facing text

The `search_recipe` description and the assistant instructions say that an unqualified search reads JEI when it is available, and Minecraft's recipes otherwise. They also say how to choose a source explicitly. They no longer tell the assistant to treat Minecraft's recipes as the primary source and JEI only as a viewer. These are prompt changes under the AGENTS.md rules, so they need live acceptance in game.

## Acceptance

- Automated: with an available JEI-like source registered first, an unqualified recipe search queries only it. With that source unavailable, only the next recipe source is queried and the result is COMPLETE when that source completes. A qualified search reaches the named source in both cases. Document discovery still combines sources.
- In game, with JEI installed: an unqualified recipe question makes JEI recipe searches and no native ones, and offers a JEI link that opens the recipe.
- In game, without JEI: the same question is answered from Minecraft's recipes, with no JEI-unavailable note.
