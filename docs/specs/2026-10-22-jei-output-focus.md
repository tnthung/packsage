# JEI output focus for recipe search

Date: 2026-10-22.

Follows [JEI as the default recipe source](2026-10-22-jei-default-recipe-source.md). Its [live checks](../validation/2026-10-22-jei-default-recipe-source.md) showed that JEI output searches are slow and can miss modded categories within a page.

## Problem

A JEI `search_recipe` with `outputItem` walks every JEI category recipe by recipe and keeps recipes whose output slots contain the item. The walk stops at the per-call read budget, so a common item such as the torch needed three calls, and Nature's Aura's Natural Altar Infusion recipe for Gold Powder was never reached before the assistant stopped at the crafting route.

JEI indexes recipes by ingredient, which is what its R (recipes) key uses. The reader once used that index with an output focus on a plain stack of the item. That was removed because JEI keys the index by a variant-aware identity: a plain `minecraft:potion` stack is the water bottle, so its focus missed Strength potion outputs ([earlier record](../validation/2026-10-19-source-labels-and-jei-brewing.md)). JEI has no focus that matches every variant of an item ID.

## Behavior

For a JEI `search_recipe` or JEI read with `outputItem`, the reader decides per query whether a focus is exact:

- It computes JEI's recipe identity (`IStackHelper.getUidForStack` with `UidContext.Recipe`) for a plain stack of the item, and for every stack of that item in JEI's ingredient list.
- When every listed stack has the plain stack's identity, the item has no variants that JEI distinguishes. The reader applies one OUTPUT focus on the plain stack to its category and recipe lookups, so JEI returns only the categories and recipes that produce the item.
- When any listed stack has a different identity, such as potions, the reader keeps the current bounded scan, including the brewing-first order for potion outputs.
- When the ingredient list, stack helper or focus factory is unavailable or throws, the reader keeps the current bounded scan.

Everything else is unchanged: the output-slot check on each projected recipe, component-variant matching, coverage and decoding labels, page and budget bounds, cursors, hidden-recipe capture, visibility checks and navigation identities. Searches without `outputItem` keep the current scan.

A cursor stays bound to the query that issued it. The focus decision depends only on the query and the current JEI runtime, so a continuation within one runtime generation repeats the same decision.

## Limits

An item JEI tracks variants for, but whose ingredient list shows only the plain variant, is treated as variant-free. A recipe that outputs an unlisted variant of such an item is not found by the focused search. JEI's own recipe view has the same reach. Text-only searches could later use JEI's ingredient search to find items, then focus on them; that is outside this change.

## Acceptance

- Automated: for a variant-free output, the category and recipe lookups receive one OUTPUT focus and a matching recipe that the old walk would reach only after the budget is found on the first page. For an output whose listed variants differ, no focus is applied and the potion regression of the earlier record still passes. A missing ingredient manager falls back to the scan.
- In game, with JEI: `How do I craft a torch?` finds the JEI torch recipe in one recipe search. `How do I make Gold Powder?` returns the Natural Altar Infusion recipe alongside crafting. `How do I brew a Potion of Strength?` still finds the brewing records.
