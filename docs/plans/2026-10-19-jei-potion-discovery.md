# JEI potion discovery order

Date: 2026-10-19.

The [source-label and JEI validation record](../validation/2026-10-19-source-labels-and-jei-brewing.md) tracks this follow-up to the [brewing contract](../specs/2026-10-19-brewing.md) and [persistent navigation contract](../specs/2026-10-18-persistent-navigation.md).

The installed diagnostic trial `c3cd75c3-d771-49ce-a34e-e6136bebb3af` advanced through category zero rather than looping. Seven text-search pages reached recipe 87; nine exact potion-output pages reached recipe 1,404. The trial was stopped after about five minutes and 34 tool calls, with no JEI record returned. The existing output precheck improves throughput but leaves brewing behind unrelated categories.

For SEARCH/RELATED queries with an exact output item of `minecraft:potion`, `minecraft:splash_potion` or `minecraft:lingering_potion`, visit JEI's public `RecipeTypes.BREWING` category first. Then visit every other category in its existing order. Keep output/component matching, shared extraction limits, source bounds, hidden-category capture, fresh visibility checks and navigation identities unchanged. The ordering is deterministic for the query bound to the opaque cursor. Text-only, other-output and GET behavior retain their existing order. Use the public typed category lookup and bounded streaming traversal; do not enumerate every ingredient variant or raise extraction caps.

1. Add a failing regression with unrelated crafting work before brewing. Require the first potion page to find brewing and later continuations to retain other potion-producing categories without duplicating brewing. Retain ordinary query-order coverage.
2. Implement the category ordering within the existing reader. Run the focused JEI suite, review the change and run the full test/build.
3. Install the hash-verified JAR with Minecraft closed. Repeat an ordinary Luna JEI request, inspect calls and returned records, and click its actual viewer control. Keep the already passing recorded-source check separate from viewer acceptance.
4. Update the validation record, changelog and roadmap with observed results and remaining limits. No prompt change is planned.
