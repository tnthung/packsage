# JEI recipe planning

Date: 2026-10-22.

Extends [JEI as the default recipe source](2026-10-22-jei-default-recipe-source.md) and [JEI output focus](2026-10-22-jei-output-focus.md) to `plan_recipe`. The roadmap rule that a JEI display alone does not establish a plannable recipe still applies to coverage: JEI routes plan quantities from JEI's displayed slots, and stay partial where those slots cannot establish a complete requirement.

## Problem

`plan_recipe` reads only Minecraft's recipe manager, and only its first 512 ordinary recipes plus brewing candidates. In the minimal fixture that leaves out about two thirds of the 1,539 ordinary recipes, so every live plan was PARTIAL with `bounded-live-recipe-scan`. Native inputs are all marked consumption-unknown, so the planner never follows an input to the recipe that makes it: a native plan lists the recipes for the requested item and stops.

Because the planner reads only native recipes, the assistant's brewing answers started with native planner evidence and then kept searching native recipes, even with JEI installed.

JEI looks up recipes by output item through its index, and marks displayed input slots as consumed and catalysts as reused. A planner works backwards one ingredient at a time, which is that lookup.

## Behavior

### Source selection

`plan_recipe` gains an optional `provider`, with the same choices as `search_recipe`. Without it, the plan uses the default recipe source: JEI when its runtime is available, otherwise Minecraft, as for `search_recipe`. With it, only that source plans; selecting an unavailable JEI returns its unavailable failure. Native planning is otherwise unchanged.

### JEI planning

A JEI plan runs the existing route planner (`RecipePlanner`) over a JEI recipe source instead of the native index. The planner's rules for routes, alternatives, batches, cycles, depth, node and branch bounds, catalysts, consumption and coverage are unchanged.

For each item the planner asks about, the JEI source runs one JEI output search for that item ID: the same reader, output focus or bounded scan, visibility checks and shared read budget as `search_recipe`. It keeps the first page of up to 16 recipes. Each item is looked up at most once per plan, and a plan makes at most 64 lookups. A lookup that returns more results, fails or is cut by a bound makes the plan PARTIAL. The detail names the bound.

Before planning, JEI records are normalized so the planner can match them:
- Item inputs and outputs use the plain item ID as their comparison identity, without JEI's `|componentsHash=` suffix. Components stay as captured, so an input matches the outputs whose captured components are equal.
- Records from JEI's `minecraft:brewing` category carry the native brewing constraint (`brewing-batch=up-to-3-bottles; reagent=one-per-operation; fuel=unknown-per-operation; remainders=unknown`), so the planner withholds batch counts as it does for native brewing.
- Records that JEI marks OPAQUE keep their outputs but no inputs, as in search results, so they appear as routes without becoming complete.

The whole plan runs as one task on the client thread. Bounds keep that task short: 64 lookups, 16 recipes per lookup, the planner's own node and branch limits and the shared read budget.

### Plan records

JEI plan routes are JEI recipe records with the planner's route fields added: `requestedQuantity`, `batches`, `producedQuantity` and, for brewing, `operationSemantics`. They keep their JEI recipe references and navigation targets, so a route links to its JEI recipe. Unlike native routes, they do not use `plan|` references: `read_evidence` on a JEI route rereads the current recipe, without the route fields.

### Coverage

JEI does not report remainders, and every JEI recipe lists its category's workstations as catalysts, which the planner treats as unresolved conditions. JEI plan routes therefore stay PARTIAL, as native routes already do. A later change can decide when JEI facts justify a complete material plan.

## Model-facing text

The `plan_recipe` description states the default source and the `provider` choice, and drops the claim that the live recipe scan is bounded for every plan. This is a prompt change under the AGENTS.md rules.

## Out of scope

Workstation handling, remainders and the JEI component-coverage label are unchanged. Text-only planning and planning by tag are not added.

## Acceptance

- Automated: with JEI planning, a two-level plan follows a consumed input to its own JEI recipe; a brewing record withholds batches; a lookup cut by a bound makes the plan PARTIAL; the 64-lookup limit holds; each item is looked up once. Dispatch: no provider plans with JEI when it is available and native otherwise; `provider: "native"` plans natively with JEI available; `provider: "jei"` without JEI fails as unavailable.
- In game, with JEI: `How do I craft a torch?` and `How do I brew a Potion of Strength?` produce JEI plan routes with working JEI links when the assistant plans. A plan for a torch follows the stick to its planks recipe.
- In game, without JEI: the same questions plan natively as before.
