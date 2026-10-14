# Behavior-driven tools

Date: 2026-10-14.

Status: target design for implementation and review. This document defines the completed tool behavior. Implementation status and historical validation are tracked separately.

## Design rule

Organize model-facing operations around what the player wants to do. Select the subject or target, then apply optional filters. Native, mod-specific and version-specific readers supply the same behavior through their own verified access paths. Their class boundaries, payload formats and providers do not determine separate tools.

Equivalent requests and results share a contract. Distinct behavior or evidence guarantees remain explicit: finding an item is different from explaining how to obtain it, reading a guide is different from opening its page, and observing an inventory is different from moving its contents. Do not turn every domain into one universal tool or invent replacement APIs for unrelated working tools.

The [roadmap](../roadmap/2026-10-13-todo.md) groups work by final tool signature, then target or filter, then provider coverage. Behavior determines the contract; the tool signature is the unit used to organize implementation work. Completion checks track progress toward that contract rather than defining its scope. Keep one logical mod identity across its version-specific readers and feature contributions.

## Register adapters implementing a shared toolbox

Adapters implement a shared `Toolbox` interface whose methods correspond to the tool signatures. Each method defaults to `Err(NotHandled)`, so an adapter implements only the operations it supports. Register adapter instances with the adapter registry. Matching belongs inside each adapter's operation method; the dispatcher does not select an adapter from target types, mod identities or separate matching callbacks. Target schemas are adapter contributions used to describe and validate available inputs, not routing rules.

The intended shape, expressed in Rust-like pseudocode rather than a finalized Java API, is:

```rust
trait Toolbox {
    fn search_inventory(&self, request: &InventoryQuery, context: &ToolContext)
        -> Result<InventoryResult, ToolError>
    {
        Err(ToolError::NotHandled)
    }

    // Other tool operations follow the same optional implementation pattern.
}

impl Toolbox for MinecraftAdapter {
    fn search_inventory(&self, request: &InventoryQuery, context: &ToolContext)
        -> Result<InventoryResult, ToolError>
    {
        // Inspect the target and current subject here.
        // Return Err(NotHandled) if this adapter does not handle the request.
        // Otherwise read the current source and return Ok(...) or another Err(...).
    }
}

adapter_registry.register(MinecraftAdapter);
adapter_registry.register(Ae2Adapter);

// The dispatcher needs no adapter-specific branches.
for adapter in adapter_registry.adapters() {
    match adapter.search_inventory(request, context) {
        Err(ToolError::NotHandled) => continue,
        result => return result,
    }
}
Err(ToolError::NotHandled)
```

`Err(NotHandled)` is the only outcome that advances to the next adapter. `Ok(...)` means handled, including an empty result or an explicit partial/unavailable observation. Any other error means handled but failed and stops dispatch. An adapter recognizing its supported target must report lost access, unsupported filters or query failures as handled outcomes, not disguise them as `NotHandled`. If every adapter declines, expose an explicit unsupported outcome. An outer `Option` is unnecessary because `NotHandled` already expresses the decline case.

Adapters run in a defined registration order; the first outcome other than `NotHandled` wins. Register specialized adapters before broad fallback adapters when their supported inputs overlap. Each adapter inspects the request and resolves any current subject needed to decide whether it handles it. Shared live resolution utilities may be reused, but they do not choose the adapter. For a held AE2 terminal, adapters that do not support it decline and the AE2 adapter searches its source; a vanilla container item is handled by the Minecraft adapter. Adding support requires registering one more adapter, without changing dispatcher logic. Target resolution is not a second inventory search or a reason to collect all contents centrally.

The same optional-method pattern applies to the other adapter-backed tool operations. Each operation retains its own result-combination semantics; document and recipe discovery may combine multiple sources through an internal coordinating adapter that issues source-qualified requests through the same chain. Each individual dispatch still stops at the first handled outcome. Existing internal readers can remain implementation details behind an adapter. Java execution may remain asynchronous, but it must preserve the distinction between not handled and a handled success or failure.

## Read current data on every call

Every tool call reads its source at execution time. This applies to initial queries, repeated queries, reference-based reads and pagination across all tool families. Do not answer a new call from a retained inventory snapshot, an earlier tool result or a last-opened menu capture. If current data cannot be read, return unavailable or partial coverage for the affected source; do not fall back to old contents.

References identify what to resolve and cursors identify where to continue; neither stores a result set to replay. Revalidate the target, access and scope on every call, then read current data. Current means the latest state exposed by the verified source, such as the live synchronized client inventory or an authorized server read. Do not describe client-visible data as an instantaneous authoritative server read.

Results record when that call read the source. Conversation history and validation records may retain past results as historical evidence, but those records must not substitute for a fresh tool read. Tools explicitly inspecting history read the current history store; historical entries remain labeled as history.

## Find or inspect inventory contents

The intended single model-facing entry point is:

```ts
search_inventory(target, filter?, cursor?, limit?)
```

Omitting the filter enumerates the target's available contents. Supplying a filter selects matching contents from that same inventory. Player slots, opened inventories, selected items and world/entity inventories all use this operation.

Inventory means an item collection exposed by a player, block, entity, item or storage system. Equipment and cargo are sections of an inventory, not separate tools. An item's own attributes, abilities or progression remain item evidence rather than the contents it holds.

### Select the target

Target types are registered by adapters, not a closed list in the core tool. The native adapter registers the common player, opened, targeted, ridden and item selectors. Other adapters register the types they handle, such as an AE2 network target or an RS network target. There is no generic `system` target type.

Each target-type contribution supplies a unique type identity, its target-field schema and description. The tool exposes the available registrations so the model can form a valid target without guessing. Adapters recognize and resolve these targets inside their toolbox methods while the dispatcher follows the same `NotHandled` loop for every target. Unknown or unavailable types cannot silently fall back to another inventory. Registration must reject duplicate type identities.

Adapters own target-specific selection, access and query execution, while optional filters, pagination and result semantics remain shared. The dispatcher passes the complete request, including filter, cursor and limit, to each adapter it tries; it does not require an adapter to return a whole inventory for central filtering. References are scoped to their registered target type as well as the observed owner and session; an RS adapter must decline an AE2 target rather than interpret its reference. Adding a type must not require another model tool or a new core dispatch branch. This is an internal adapter extension point; a public third-party registration API is not required for this revision.

For shared selectors such as `item`, `opened`, `targeted` and `ridden`, each adapter's operation checks the current item, menu or entity/block identity and supported data contract as needed. A held terminal can therefore be handled by the AE2 adapter without the model naming AE2. The adapter that accepts the request searches the owning source directly under the dispatch rules above.

The following target shapes define selection behavior. The namespaced network types illustrate adapter extensions; the expanded report covers the Minecraft, JEI, FTB Quests, Patchouli, config and history adapters.

| Selector | Intended behavior | Access and identity |
| --- | --- | --- |
| `{ type: "player" }` | Inspect the local player's carried and equipped items. | Includes hotbar, main inventory, armor and offhand; registered equipment adapters can contribute further sections. Access is scoped to the local player. |
| `{ type: "opened" }` | Inspect a currently accessible opened inventory through its live access path. | May belong to a block, entity, item or terminal. If closing the menu ended synchronization, return unavailable instead of its last captured contents. Do not infer ownership from the current crosshair. |
| `{ type: "targeted" }` | Inspect the inventory of the block or entity under the crosshair. | Resolve the actual hit and verify access. An entity target can be a donkey with a chest, chest boat or chest minecart. Looking at it alone does not establish that its contents are synchronized. |
| `{ type: "ridden" }` | Inspect the inventory of the entity the local player is directly riding. | Adapters inspect the current mount or vehicle on every call, independently of the crosshair, and handle supported entities. Not riding or lacking live inventory access returns unavailable, not empty contents. |
| `{ type: "item", slot: "mainhand" }` or `{ type: "item", slot: "offhand" }` | Inspect contents accessible through the currently selected item. | A convenient slot selector; holding does not create a distinct storage capability. Empty hands remain distinguishable from empty supported containers. |
| `{ type: "item", slotReference: "..." }` | Inspect contents accessible through an item at a previously observed slot. | Revalidate the scoped reference and current stack. References identify live locations, including resolvable equipment and contained-item locations. |
| `{ type: "ae2:network", reference: "..." }` | Inspect a network through the AE2 adapter's registered handler. | Use an AE2 target reference returned by verified discovery or access evidence. The adapter validates its access path. |
| `{ type: "refinedstorage:network", reference: "..." }` | Inspect a network through the RS adapter's registered handler. | Use an RS target reference returned by verified discovery or access evidence. The adapter validates its access path. |

An item target may resolve to local container contents or to a connected system through a wireless terminal. The result identifies the actual source. The terminal must satisfy its provider's permission, connection, power and range rules; selecting it does not grant access.

Targeted entities, ridden entities and entity-owned opened menus are coverage cases of the same operation. Reuse the applicable entity reader after resolving the selector; riding does not require another copy of its inventory/search implementation. Minecraft entity inventory coverage includes mount equipment and chest cargo, chest boats and chest minecarts. Verify live access through each selector, including equipment versus cargo sections; the entity relationship alone does not establish access.

For `ridden`, dismounting removes the selected target and switching mounts changes it. A new query resolves the current mount; a continuation for a different or no-longer-ridden entity is invalid. Never fall back to the last mount or infer inventory access merely from the riding relationship.

One request selects one target. It does not scan every nearby inventory, recursively inspect nested containers or combine unrelated storage systems. A later explicit request can inspect a contained item only where the result provides a valid, resolvable reference. No fabricated slot references for aggregate network entries.

### Optional filtering

The filter shape uses optional `names`, `ids`, `tags`, `tooltip` and `sections` selections. These are shared semantics, independent of the reader:

- Names and tooltip values are literal case-insensitive substring matches, not regular expressions or executable queries.
- IDs and tags match exact validated namespaced identities. Tag spelling and normalization must be fixed in the implementation schema.
- Sections select locations such as carried slots, equipment or cargo using the section identities exposed by the reader. Section selection does not create a new inventory owner.
- Alternatives within one field are OR choices; populated fields combine with AND. Omitted or empty filters impose no restriction. Reject malformed values; never silently drop an unsupported selected field.
- Matching is against the actual observed item, including custom names and available instance tooltip text. Missing or truncated selected fields leave coverage partial; absence of a match is not proof of absence from unread data.

### Execute the search in the owning adapter

Shared filters define the requested behavior, not a mandatory PackSage search engine. Prefer the source's own live search, index, lookup and paging facilities when they can provide the requested semantics. For AE2 and RS, the adapter should translate the common query into the provider's supported search path rather than copy the network inventory and search it independently. Verify the installed version's access and matching behavior before claiming this support; this design does not assume a particular public search API exists.

A cheap equivalent implementation is acceptable: filter a small player inventory directly, use a provider's existing live index, or apply a bounded predicate to a small candidate set. Do not clone an entire storage system, build a parallel index, retain snapshots or materialize all network entries merely to answer a filtered query. A page limit alone does not make an exhaustive upstream scan cheap; bound the work as well as the output.

Preserve the common filter meaning when translating queries, including exact identities, literal text and AND/OR combinations. Cheap post-filtering of provider candidates is allowed, but a narrower provider search must not silently exclude valid matches. If the provider cannot express a filter and equivalent execution is not cheap, report that filter unsupported or coverage partial instead of ignoring it or doing an expensive full-system scan. No matches in a bounded candidate set do not establish absence from the whole inventory.

The source's own maintained live data structures are valid current sources; PackSage must query them anew on each call rather than replay earlier results. Use provider paging where available and compatible with the live-read contract. Unfiltered enumeration remains bounded and does not justify cloning the whole source.

Examples of the target contract:

```ts
search_inventory({ type: "player" })
search_inventory({ type: "opened" }, { names: ["iron"] })
search_inventory({ type: "targeted" }, { ids: ["minecraft:diamond"] })
search_inventory({ type: "ridden" }, { names: ["iron"] })
search_inventory({ type: "item", slot: "mainhand" })
search_inventory({ type: "item", slotReference: "observed-slot-reference" })
search_inventory({ type: "ae2:network", reference: "observed-ae2-reference" },
    { tags: ["minecraft:logs"] })
search_inventory({ type: "refinedstorage:network", reference: "observed-rs-reference" },
    { tags: ["minecraft:logs"] })
```

### Results and access

Return the resolved inventory owner, access path, observed sections, item identities and counts, the current call's read time and coverage. Preserve distinct stacks in distinct slots. Provide a scoped reusable slot reference only where that location can be resolved safely. Report aggregate system quantities as aggregates rather than inventing physical slots or summing overlapping observations.

Counts describe items actually stored in the selected inventory. If a provider exposes craftable-only entries, recipe previews or ghost/filter slots, exclude them from stored quantities; any supplementary projection must be explicitly distinguished from stock. Do not interpret a displayed item icon as proof that the inventory contains that item.

Preserve meaningful item variants when normalizing results or aggregate counts. Sharing a registry ID does not make differently enchanted, damaged or component-bearing items interchangeable. Retain distinctions supplied by the source; if its aggregate cannot distinguish variants, report that limitation rather than inventing individual stacks or instance attributes.

An empty supported inventory, a complete filtered result with no matches, an empty item selector and unavailable contents are different outcomes. Available owner identity does not establish available contents. An opened terminal display is a partial view unless its reader separately establishes complete system coverage.

Coverage describes the selected target, sections and fields. Unsupported sections or selected fields must remain explicit, even when another part of the request succeeds. Inventory contents do not establish item ability mechanics, quest completion, server loot rules or unsynchronized config values.

Keep output bounded, but read live contents again for every continuation call. A cursor may retain target identity, filters, scope and a continuation position, never frozen contents or precomputed result pages. Revalidate its target on each call; if the selector now identifies another owner or the continuation is no longer valid, reject it and require a new query. Do not silently switch inventories or replay the old page.

Items can change between calls. Do not promise that multiple pages form one consistent inventory state, report a cross-page total as a current total, or claim stable exactly-once enumeration while contents change. Use the reader's supported live continuation semantics and report their limits. If the reader cannot continue safely, require a new query rather than retaining a snapshot.

Item and slot references locate the intended target; they do not freeze its contents. Validate the current target and read its current stack data. A change in container contents must be visible on the next call, not rejected solely because the stack's components differ from an earlier copy. If the intended target cannot be identified safely, return unavailable or require a new selection. Reject expired, foreign or mismatched references.

Opened-inventory reads preserve a supported live access path while using PackSage. If access ends, return unavailable. A saved menu capture is not a fallback. Verify the menu lifecycle before accepting live opened support.

Invalidate references on world/resource/locale/team/provider-runtime changes and bound both reading and output. Read game state on its owning thread and identify synchronized-client versus server-read authority. Do not open menus automatically, unpack loot, move items or mutate gameplay. Server-only inventories require a verified authorized access path; a server companion is an implementation dependency, not another inventory query language.

## Discover documents, recipes and registry definitions

Discovery uses three signatures:

```ts
search_document(query, filter?, cursor?, limit?)
search_recipe(filter?, cursor?, limit?)
search_registry(registry, filter?, cursor?, limit?)
```

`search_document` discovers textual sources such as quests, chapters and guidebook entries. Its required `query` is a literal case-insensitive phrase; an empty query enumerates available documents within the same bounds. The filter has optional `types` (`quest`, including chapters, or `guide`) and `provider`. Omitted types are unrestricted; multiple types are alternatives. Preserve structured quest requirements, dependencies, tasks and rewards in the source records rather than flattening them into prose. `read_progress` reads live quest/chapter progression. FTB Quests supplies quest/chapter documents and Patchouli supplies guides. Additional adapters can contribute document types and sources through registration.

`search_recipe` discovers recipe definitions and viewer displays. Its optional filter contains `text`, exact `outputItem` and `provider`; populated fields combine with AND. Missing filters enumerate. Minecraft queries loaded recipe facts and exact output identities; JEI uses its native output-focus query. Input-ingredient search is outside this filter contract. Retain the distinction between loaded recipe facts and viewer projections, their source identities and decoding limits; finding a display does not establish a complete executable recipe.

`search_registry` discovers registered definitions in an explicitly selected registry. Minecraft handles `item`, `block`, `item_tag`, `enchantment`, `mob_effect` and `potion`. Its optional filter contains literal case-insensitive `text` for name/identity, exact `ids`, exact `tags` where the selected registry exposes entry membership, and `provider`. Alternatives within a field are OR choices; populated fields combine with AND. Read membership from the selected registry's live holder/tag data rather than restricting tags to items. `item_tag` enumerates tag definitions; select those by text/IDs instead of an entry-membership filter. Unsupported selected fields must be reported. Registry selectors are described by registered adapters, while matching stays inside their methods. Catalog discovery covers registered definitions; active effect instances, enchanted stacks and custom potion-item contents are resolved as live instance evidence.

Minecraft also handles `dimension` and `dimension_type`. `dimension` discovers actual runtime worlds: use the current private integrated server's level identities and already existing levels when the current player is its sole connected owner and it is unpublished; otherwise expose the connection's currently known dimension IDs as partial client knowledge. Revalidate scope and ownership on the server thread. Include the dimension type identity when available. Creation/removal is observed on the next call or reference read; never create a dimension, load chunks, or read saved world folders to discover one. Uncreated mod-specific dimension designs are outside this selector. A client-known ID does not establish full server metadata or exhaustive absence. Dimension tags are unsupported and must fail explicitly.

`dimension_type` queries the current session's synchronized dimension-type registry, including modded entries, and exposes bounded environmental rules such as height/minimum Y, logical height, skylight, ceiling, coordinate scale, bed/respawn-anchor behavior and fixed time. It is distinct from a dimension instance; multiple worlds may share a type. Use the registry's current tag membership for supported filters. References identify the registry and ID, never retained definitions. Neither selector promises interpretation of mod-specific generators or gameplay access permission.

All three searches return source records and observed references for `read_evidence`, with current read time, coverage and query-bound continuation. Every page reads current data. Use default limit 16 and maximum 64, with explicit source-work and output bounds. No result is evidence of complete absence when a source or selected field is unread. Configuration and historical exchanges remain under their dedicated tools and are not document-search sources.

Document-search budgets apply before traversal, extraction, copying and decoding, including nested tasks/rewards/pages and cursor seeking. Do not extract the whole source before applying the result limit. Stop with partial coverage and a metadata-only continuation at the next unread position. Large documents can yield labeled fragments with the same document handle and distinct field/page ranges; each fragment counts toward the result limit. Re-read each fragment and its visibility from the live source, and never retain earlier fragments or matches in a cursor. An indivisible field that cannot be read within a bounded operation remains explicitly unread. Evidence/progress reads share these extraction bounds; their signatures have no cursor, so report truncated fields as partial and use document search for continuation.

An omitted provider allows document or recipe search to combine the relevant registered sources; selecting a provider restricts that operation. A coordinating adapter issues bounded provider-qualified subrequests through the same registry. Candidate sources come from registration metadata, and matching remains inside source adapters. A source that accepts the request but cannot execute a selected filter returns a handled unsupported/partial outcome, not `NotHandled`.

Combined discovery propagates invalid arguments, invalid references/cursors, stale scope and unsupported-filter errors unchanged; accumulated results do not turn a failed request into success. Only source unavailability or extraction failures may become partial source-status contributions while other sources are queried. An explicitly partial successful source result retains its coverage.

The [expanded adapter pseudocode](../research/2026-10-14-toolbox-adapter-migration.md) describes the intended complete Toolbox interface, operation contracts, adapter implementations and conditional registration. Its separate migration appendix maps that result onto source changes.

## Organize the roadmap by tool signature

Use the final callable signatures as the roadmap's main headings. Under each signature, list target/filter/result work and adapter coverage. Retain old signatures only in implementation history or migration notes. A provider reader or a player-facing question does not create another tool.

The [roadmap](../roadmap/2026-10-13-todo.md) tracks implementation of these contracts:

| Tool | Roadmap responsibility |
| --- | --- |
| `search_inventory(target, filter?, cursor?, limit?)` | Inventory target resolution, live source queries, filters, item results and adapter coverage. |
| `search_document(query, filter?, cursor?, limit?)` | Quest/chapter and guide discovery, preserving text and structured source data. |
| `search_recipe(filter?, cursor?, limit?)` | Loaded recipe definitions and viewer displays, with text/output filters. |
| `search_registry(registry, filter?, cursor?, limit?)` | Item, block, item-tag, enchantment, mob-effect, potion and dimension-type definitions; current runtime dimension identities. |
| `read_evidence(handles)` | Resolve observed definition, instance or derived-query locators and read their source now. |
| `read_progress(handles)` | Current progress and visibility for observed quest/chapter references. |
| `plan_recipe(identity, quantity, depth?)` | Bounded route planning from current recipe facts, preserving alternatives and uncertainty. |
| `list_configs(limit?, cursor?)` | Accessible config value/key discovery. |
| `search_config(text, limit?, cursor?)` | Config-key/comment search. |
| `read_config(handle, key?, section?, limit?)` | Selected config reads through a live observed access path. |
| `search_history(text, limit?, cursor?)` | Search of the saved history store, read at call time. |
| `read_history(handles, maxChars?)` | Retrieve selected historical exchanges from their files at call time. |

These are named-argument summaries. Optional notation describes omitted or nullable arguments that impose no restriction or use the documented default. Navigation remains player-click behavior attached to evidence, and shared infrastructure remains an implementation dependency; neither creates a model tool. Hosted web search is supplied by the model backend.

Keep installed definitions, actual instance observations, display projections and external guidance distinguishable even when they contribute to one answer.

## Acceptance criteria

1. The public catalog and `Toolbox` expose the twelve signatures above. Adapter instances contribute schemas and source metadata, perform matching inside their methods, and require no dispatcher changes when registered. `NotHandled` advances; empty success, unavailable observations and real errors terminate that dispatch. Test duplicate registrations, overlapping ownership, all-declined requests and version selection under one logical mod ID.
2. Minecraft inventory queries resolve player, opened, item, targeted block/entity and ridden selectors, then inspect their live accessible contents. Mount equipment/cargo and chest-vehicle storage retain owner and section identity. Queries and continuations bind the same live owner/access path; switching mounts, menu closure or lost access cannot replay or silently retarget earlier contents. Read-only server access preserves authorization and avoids forced loads, menu opening or loot generation.
3. Registry discovery and evidence reads cover items, blocks, item tags, enchantments, mob effects and potions, including modded entries. Exact ID/tag and literal text filters run before paging. Standard definitions, active instances and undecoded custom mechanics remain distinguishable; unverified mechanics cannot become complete semantic claims.
4. Document search discovers quest/chapter and guide references while preserving structured fields. Evidence and progress reads resolve the live owning source, current visibility and player/team state. Combined searches include registered contributions without hardcoded adapter lists. Verify that unknown output items, invalid source cursors and stale scopes fail both qualified and combined calls; source unavailability/extraction failures retain partial coverage. Test large books, quests and individual documents with small result limits: budgets stop source extraction itself, nested continuations advance without retaining bodies, and unread fields cannot establish absence. Recipe search queries loaded recipes and JEI displays with text/output filters, retaining source authority and usable partial routes for bounded planning.
5. Config and history retain their dedicated operation contracts. Config reads distinguish saved, loaded and synchronized values; history reads preserve historical identity and exclude replaced exchanges. Neither returns a retained body after its source is changed, removed or unavailable.
6. Change source data between first, repeated, reference-based and continuation calls across every tool family; each reads the source anew. Cursors contain only owner/query/scope/position metadata. Field/section limitations and work bounds remain explicit, so bounded no-match results cannot establish absence from unread data. The assistant runs automated checks; live access and compatibility observations are recorded separately.

## Historical implementation and migration references

- [Capability reader alignment](2026-10-14-capability-reader-alignment.md) and its [completed implementation plan](../plans/2026-10-14-capability-reader-alignment.md) describe the delivered split contracts.
- [Capability reader validation](../validation/2026-10-14-capability-reader-alignment.md) records tested player search and observed-slot item contents.
- [Inventory and storage validation](../validation/2026-10-14-inventory-storage-inspection.md) records opened-menu behavior and the Pocket Storage display gap.
- [Targeted-container research](../research/2026-10-14-targeted-container-reading.md) records block/container access evidence. Entity inventories and adapter-defined targets require their own access verification during implementation.
