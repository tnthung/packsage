# Target-state Toolbox and adapter pseudocode

Date: 2026-10-14.

Status: source-grounded target-state design and Rust-like pseudocode. The final behavior is specified first; a short appendix records current-code migration points.

This expands the [tool design](../specs/2026-10-14-behavior-driven-tools.md) into a complete target-state contract for Minecraft, JEI, FTB Quests, Patchouli, config and saved history. It includes accessible Minecraft entity inventories and all six agreed registries. Other mod adapters such as AE2, RS and Curios remain outside this supported set.

The code blocks are Rust-like explanatory pseudocode. Named extraction steps describe their work inline; they are not proposals to create a Java helper for every line. Async/thread execution is shown where it affects correctness. `Result` represents a completed asynchronous outcome: Java can preserve `CompletionStage` around an explicit handled/not-handled result.

## Completed architecture

The target has one `Toolbox` method per model tool and a registry of independently registered adapters. Source code cited below grounds the access paths, data fields and compatibility gates; the contract describes the completed design rather than the present Java routing.

Operation ownership and matching live inside adapter methods. Only `Err(NotHandled)` tries the next adapter. A handled empty result, unavailable observation or real error terminates that dispatch.

`search_document` returns quests/chapters and guides; `search_recipe` returns current recipes; `search_registry` enumerates one Minecraft registry. Config discovery remains behind `list_configs`, `search_config` and `read_config`.

Two operations compose source reads:

- `DiscoverySearchAdapter` owns only unqualified `search_document` and `search_recipe` fan-out. Its candidate sources come from adapter operation metadata; it issues provider-qualified subrequests through the same registry, and source adapters match those requests themselves. `search_registry` dispatches directly to the Minecraft adapter.
- `read_evidence(handles)` and `read_progress(handles)` accept mixed-owner batches. The tool boundary validates the batch and dispatches one handle at a time through the same chain. It does not inspect provider IDs to choose an adapter.

These are internal composition rules, not extra model tools. They let first-success dispatch preserve multi-source discovery.

## Full tool interface and shared data

All twelve model-facing signatures are represented here. Defaults mean an adapter has no claim to unrelated tools.

```rust
enum ToolError {
    NotHandled,
    InvalidArguments(detail),
    StaleScope,
    InvalidReference(detail),
    InvalidCursor(detail),
    Unavailable(detail),
    UnsupportedFilter(detail),
    ExtractionFailed(detail),
}

type ToolResult = Result<EvidenceResult, ToolError>;

// Per invocation: no previous result bodies or frozen inventory contents.
struct ToolContext {
    scope,                  // world/session, resources/locale, team, provider runtime, request generation
    current_scope,
    client_executor,
    native_inventory_access, // live synchronized readers or an authorized logical-server endpoint
    worker_executor,
    io_executor,
    observed_locators,      // scoped identity/access metadata, not cached Evidence.Record payloads
    continuation_metadata, // query/owner/scope/position or revision; no saved result pages
    document_budget,       // shared by document subrequests and evidence/progress handles in this call
    tool_output_budget,    // reserve capacity during extraction, before advancing a cursor
    replacing_request_id,  // history must exclude the exchange currently being replaced
    adapter_registry,
}

// Observed entries also retain provider ID, record kind and scoped opaque handle.
// A locator remembers HOW to read again. Each owning adapter validates its variant.
enum SourceLocator {
    NativeDefinition(kind, identity),
    NativeInventorySlot(section, slot, owner),
    NativeInventorySummary(player),
    NativeStorageOwner(target_identity, access_path),
    NativeStorageEntry(target_identity, access_path, entry_location),
    NativeEntityEquipment(entity_identity, access_path, equipment_slot),
    NativeEntityStorage(entity_identity, access_path, storage_kind, entry_location),
    NativePlayerEffects(player),
    NativeStackEnchantments(stack_location),
    NativePotionContents(stack_location),
    RecipePlanRoute(identity, quantity, depth, route_identity),
    JeiDisplay(category_identity, live_recipe_locator, runtime_generation),
    FtbObject(object_kind, object_id),
    PatchouliEntry(book_id, entry_id),
    ConfigKey(entry_handle, key),
    ProviderStatus(provider_id),
}

// Target is extensible schema-backed data, not a closed core enum.
// Supported native selectors are player/opened/item/targeted/ridden;
// targeted resolves the current crosshair hit, ridden resolves the vehicle
// occupied by the player independently of the crosshair.
struct InventoryTarget { type_id, fields }
struct InventoryFilter { names?, ids?, tags?, tooltip?, sections? }
struct DocumentFilter { types?: [quest | guide], provider? }
struct RecipeFilter { text?, output_item?, provider? }
struct RegistryFilter { text?, ids?, tags?, provider? }
// Selectors are adapter-contributed strings. The native adapter's shorthand:
// Item="item", Block="block", ItemTag="item_tag",
// Enchantment="enchantment", MobEffect="mob_effect", Potion="potion",
// Dimension="dimension", DimensionType="dimension_type".
struct RegistrySelector { id }

// Page coverage, authority and read time belong to current observations.
// A successful partial result is still handled.
struct EvidenceResult {
    records,
    coverage,
    outcome,
    next_cursor?,
    detail?,
}

trait Toolbox {
    /// Enumerate or filter current contents of one accessible inventory target.
    fn search_inventory(&self, target, filter?, cursor?, limit?, ctx) -> ToolResult {
        Err(NotHandled)
    }
    /// Search current quest/chapter or guide documents by literal query.
    fn search_document(&self, query, filter?, cursor?, limit?, ctx) -> ToolResult {
        Err(NotHandled)
    }
    /// Search current recipe sources, optionally focused on an exact output item.
    fn search_recipe(&self, filter?, cursor?, limit?, ctx) -> ToolResult {
        Err(NotHandled)
    }
    /// Enumerate/filter registered item, block, tag, enchantment, effect or potion definitions.
    fn search_registry(&self, registry, filter?, cursor?, limit?, ctx) -> ToolResult {
        Err(NotHandled)
    }
    /// Resolve observed source handles again and return their current detailed records.
    fn read_evidence(&self, handles, ctx) -> ToolResult { Err(NotHandled) }
    /// Refresh observed quest/chapter progress from the current player/team source.
    fn read_progress(&self, handles, ctx) -> ToolResult { Err(NotHandled) }
    /// Compute bounded recipe routes and quantities from current recipe evidence.
    fn plan_recipe(&self, identity, quantity, depth?, ctx) -> ToolResult { Err(NotHandled) }
    /// Enumerate accessible config value records and their observed entry/key handles.
    fn list_configs(&self, limit?, cursor?, ctx) -> ToolResult { Err(NotHandled) }
    /// Find current accessible config keys/comments by literal text.
    fn search_config(&self, text, limit?, cursor?, ctx) -> ToolResult { Err(NotHandled) }
    /// Read a selected key or bounded text-selected section of an observed config entry.
    fn read_config(&self, handle, key?, section?, limit?, ctx) -> ToolResult { Err(NotHandled) }
    /// Find completed saved exchanges in the current world/server; results are historical.
    fn search_history(&self, text, limit?, cursor?, ctx) -> ToolResult { Err(NotHandled) }
    /// Reopen selected discovered saved exchanges and return their bounded text.
    fn read_history(&self, handles, max_chars?, ctx) -> ToolResult { Err(NotHandled) }
}
```

`SourceLocator` is illustrative internal data, not a second public target registry or a finalized Java hierarchy. Add only the variants needed by emitted records. Handles are issued only after source evidence is observed. Do not construct locators from arbitrary model-supplied IDs or paths.

The public boundary validates registered schemas and bounds. Optional values impose no restriction or take their documented defaults; required-but-nullable JSON properties can encode these semantics for a model backend.

## What each trait method does

These are the public contracts that adapter implementations must preserve. The adapter examples sometimes bundle the same named arguments into a request struct for readability; those structs do not change the tool signatures. `request.reference` denotes the validated singleton locator from `request.handles`; optional filters are normalized to empty filter objects and limits have their defaults applied. Rust-like `output_item` denotes public JSON `outputItem`, and `max_chars` denotes `maxChars`. Record/page construction notation is explanatory; each method returns the shared `EvidenceResult` envelope. `evidence(records)` includes their combined coverage/outcome, not just the record list.

| Method | Arguments and behavior | Returned evidence and limits |
| --- | --- | --- |
| `search_inventory` | Required `target` selects one inventory. Optional `filter` narrows names, exact IDs/tags, tooltip text and sections; OR within one field and AND across populated fields. Omitted filter enumerates. `cursor` continues the same target/query against current data; `limit` bounds the page. | Current owner/access, sections, item variants/counts, resolvable locations, read time and coverage. Empty inventory, no matches, empty selector and inaccessible contents remain distinct. Default/max page size 32. |
| `search_document` | Required `query` is literal case-insensitive text. Optional `filter.types` selects `quest` (including chapters) and/or `guide`; absent/empty means unrestricted. Optional `filter.provider` selects one source; absent combines document sources. | Current FTB quest/chapter and Patchouli guide records with observed handles, coverage and continuation. Default limit16, max64. Config is excluded. |
| `search_recipe` | Optional `filter.text` is literal case-insensitive text; `filter.output_item` is an exact item ID; `filter.provider` selects one recipe source. Absent filter enumerates. No input-item filter is promised. | Current Minecraft and JEI recipe records with handles, coverage and continuation. Default limit16, max64. Minecraft matches decoded output identities; JEI uses its output focus. |
| `search_registry` | Required `registry` selects `item`, `block`, `item_tag`, `enchantment`, `mob_effect`, `potion`, `dimension` or `dimension_type`. Optional `filter.text`, exact `filter.ids`, `filter.tags`, and `filter.provider`; absent filters enumerate. ID/tag matching follows selected source identity. Tag membership filters apply wherever the selected registry exposes holder membership; `item_tag` and `dimension` accept IDs/text, while their `filter.tags` is an explicit `UnsupportedFilter`. Dimension discovery reads current existing worlds; client-only IDs remain partial. | Current registered definitions and observed references. Default limit16, max64. Catalog completeness does not establish complete custom mechanic interpretation. |
| `read_evidence` | Required `handles` contains previously observed source references, possibly from different adapters. Resolve their scoped locators and read each current source again. | Current detailed records for up to16 handles, no pagination. Removed/inaccessible sources or unresolvable derived records yield explicit failure/unavailability; no earlier record replay. |
| `read_progress` | Required observed `handles` identify quest/chapter subjects. Locators retain document kind and object ID; read the live file/player/team overlay. | Current progress/visibility/dependency/task/reward evidence, up to16 handles. Per-handle refresh failures become status records and partial batch coverage rather than old progress. No quest completion or reward claiming action. |
| `plan_recipe` | Required item `identity` and desired `quantity`; optional `depth` defaults to4. Read current native recipe evidence and explore bounded alternatives. | Routes, inputs/outputs, quantity/batch calculations and uncertainty. Quantity1..1,000,000; depth0..8; cap256 nodes/64 branches. This computes evidence; it does not craft or move items. |
| `list_configs` | Optional `limit` and `cursor`; enumerate currently accessible config evidence. Enumerate value/key records and their source-entry handles. | Observed config entry/key handles and saved/loaded/default metadata where available. Default limit16, max64. No arbitrary filesystem browsing. |
| `search_config` | Required literal `text` matches owner, scope, filename, key or comment; optional `limit`/`cursor`. Empty text can enumerate. | Matching current config value records with the same saved-versus-loaded distinctions; default limit16, max64. It does not search arbitrary paths or silently treat disk values as active runtime values. |
| `read_config` | Required observed config `handle`; supply `key` or `section`. Exact `key` wins if both are provided. `section` selects matching key/comment text inside that entry. Optional `limit`. | Current selected config values and metadata, default limit16/max64; no cursor. If more match, report truncation/narrowing guidance. No writes or reloads. |
| `search_history` | Required nonblank literal `text`; search completed saved exchanges for the current world/server, excluding the exchange being replaced. Optional `limit`/`cursor`. | Historical question/answer excerpts and observed history handles; default limit8/max16. Reread current files on every call; results remain historical context, not current game evidence. |
| `read_history` | Required discovered history `handles` (up to8); optional `maxChars` bounds total question+answer text per exchange, default8000/range256..16000. | Selected saved exchange text reread from its current file, with historical/truncated markers. Deleted/replaced references are invalid; no prior body replay. |

For every method, `NotHandled` means that this adapter does not own/support this request, not that a recognized source is empty or temporarily inaccessible. For example, an FTB adapter declines an inventory query by inheriting the default; it returns a handled unavailable result/error when asked about its own quest while the current quest file is missing. The dispatch rules and result coverage rules are separate.

Argument validation belongs at the public boundary; ownership recognition belongs inside the adapter. Source-specific unavailable/partial observations may be successful `EvidenceResult` values; invalid references, invalid cursors and extraction failures are explicit errors. Translate source errors explicitly so an error can never become an accidental `NotHandled`.

## Dispatcher and tool boundary

```rust
impl AdapterRegistry {
    fn dispatch(&self, call, ctx) -> ToolResult {
        for adapter in self.adapters_in_registration_order {
            let result = match call {
                SearchInventory(args) => adapter.search_inventory(args..., ctx).await,
                SearchDocument(args)  => adapter.search_document(args..., ctx).await,
                SearchRecipe(args)    => adapter.search_recipe(args..., ctx).await,
                SearchRegistry(args)  => adapter.search_registry(args..., ctx).await,
                ReadEvidence(args)    => adapter.read_evidence(args..., ctx).await,
                ReadProgress(args)    => adapter.read_progress(args..., ctx).await,
                PlanRecipe(args)      => adapter.plan_recipe(args..., ctx).await,
                ListConfigs(args)     => adapter.list_configs(args..., ctx).await,
                SearchConfig(args)    => adapter.search_config(args..., ctx).await,
                ReadConfig(args)      => adapter.read_config(args..., ctx).await,
                SearchHistory(args)   => adapter.search_history(args..., ctx).await,
                ReadHistory(args)     => adapter.read_history(args..., ctx).await,
            };
            match result {
                Err(NotHandled) => continue,
                result => return result,
            }
        }
        Err(NotHandled)
    }
}

fn execute_model_tool(call, ctx) -> ToolResult {
    validate_known_signature_arguments_and_bounds(call)?;
    apply_argument_defaults_and_normalize_omitted_filters(call);
    require_current_scope(ctx)?;

    // Validate all handles before executing a mixed-owner batch.
    // This checks discovery/scope, not which adapter should handle them.
    validate_observed_handles_if_required(call, ctx)?;

    let result = match call {
        ReadEvidence(handles) => {
            let records = [];
            for handle in handles { // current bound: 16
                let read = ctx.adapter_registry.dispatch(ReadEvidence([handle]), ctx).await?;
                require_same_locator_identity_kind_and_scope(read, handle)?;
                records.extend(read.records);
                combine_coverage_and_details(read);
            }
            Ok(combined_current_evidence(records))
        }
        ReadProgress(handles) => {
            require_all_locators_identify_quest_or_chapter_subjects(handles)?;
            let records = [];
            for handle in handles { // current bound: 16
                match ctx.adapter_registry.dispatch(ReadProgress([handle]), ctx).await {
                    Ok(read) if read_has_expected_quest_identity_and_scope(read, handle) => {
                        records.extend(read.records);
                        combine_coverage_and_details(read);
                    }
                    failed_or_missing => {
                        // A per-handle refresh failure becomes a current status record:
                        // append a current failure status, mark the combined batch partial.
                        // Never reuse the earlier quest payload.
                        records.push(current_progress_status(handle, failed_or_missing));
                    }
                }
            }
            Ok(combined_current_progress(records))
        }
        other => ctx.adapter_registry.dispatch(other, ctx).await,
    };

    require_current_scope(ctx)?; // reject world/reload/team changes during async reads
    validate_result_source_and_navigation_scope(result)?;
    remember_locators_for_newly_observed_records(result); // not result bodies for replay
    bound_and_serialize(result) // 64 ordinary records / ~128 KiB tool envelope
}
```

A switch over the fixed operation names is still needed to invoke typed methods. Adding an adapter changes no branches here. Adapters perform all provider, target, component and handle ownership checks. Unknown operations remain argument errors.

A whole tool call can read several live sources at different times; it does not promise a transactionally consistent global state. Temporary call-local copies used to cross from the game thread to worker code are permissible execution data, not retained snapshots served to later calls.

## Unqualified document and recipe search coordinator

This internal adapter coordinates unqualified `search_document` and `search_recipe` calls. It declines provider-qualified requests. Its subrequests pass back through the same registry as provider-qualified calls, so the coordinator declines them and source adapters match ownership. `search_registry` dispatches directly. Config is excluded from general discovery and keeps its dedicated config tools.

```rust
impl Toolbox for DiscoverySearchAdapter {
    fn search_document(&self, query, filter?, cursor?, limit?, ctx) -> ToolResult {
        if filter.provider.is_some() { return Err(NotHandled); }
        // Registered contributions: FTB Quests (quest, including chapters)
        // and Patchouli (guide). Missing/empty types mean both. Empty source result is handled; unsupported kinds
        // from a provider are skipped only after its qualified call declines.
        return self.fan_out(
            operation = SearchDocument,
            candidates = ctx.adapter_registry.discovery_sources(SearchDocument),
            query = query,
            filter = filter,
            cursor = cursor,
            limit = limit.or(16),
            max_provider_pages = 8,
            ctx = ctx,
        );
    }

    fn search_recipe(&self, filter?, cursor?, limit?, ctx) -> ToolResult {
        if filter.provider.is_some() { return Err(NotHandled); }
        return self.fan_out(
            operation = SearchRecipe,
            candidates = ctx.adapter_registry.discovery_sources(SearchRecipe),
            query = filter.text.unwrap_or(""),
            filter = filter,
            cursor = cursor,
            limit = limit.or(16),
            max_provider_pages = 8,
            ctx = ctx,
        );
    }
}

impl DiscoverySearchAdapter {
    // Shared internal fan-out stores only source index + current adapter
    // cursor + signature/scope/registry generation, never the result records.
    fn fan_out(self, operation, candidates, query, filter, cursor, limit,
               max_provider_pages, ctx) -> ToolResult {
        let continuation = validate_cursor_signature_scope_and_registry_generation(
            cursor, operation, query, filter, ctx
        )?;
        let records = [];
        let pages_read = 0;
        let source_index = continuation.source_index;
        let source_cursor = continuation.source_cursor;
        while source_index < candidates.len() && records.match_count() < limit {
            if pages_read == max_provider_pages
                || ctx.tool_output_budget.exhausted()
                || (operation == SearchDocument && ctx.document_budget.exhausted()) {
                return Ok(partial_page_with_metadata_cursor(records,
                    source_index, source_cursor));
            }
            let source = candidates[source_index];
            let qualified = filter.with_provider(source);
            let result = ctx.adapter_registry.dispatch(
                operation.request(query, qualified, source_cursor,
                    limit - records.match_count()), ctx
            ).await;
            pages_read += 1;
            match result {
                Err(NotHandled) => { source_index += 1; source_cursor = None; }
                Err(error @ (Unavailable(_) | ExtractionFailed(_))) => {
                    // Only source availability/extraction failures contribute
                    // partial status. No later adapter handles this subrequest.
                    records.push(source_failure_status(source, error));
                    source_index += 1; source_cursor = None;
                }
                Err(error) => return Err(error),
                // InvalidArguments, InvalidCursor, InvalidReference, StaleScope
                // and UnsupportedFilter terminate the combined call unchanged.
                // Do not publish accumulated records as a successful result.
                Ok(page) => {
                    require_current_scope_and_provider(page, source, ctx)?;
                    records.extend(page.records);
                    combine_coverage(records, page.coverage);
                    if let Some(next) = page.next_cursor {
                        source_cursor = Some(next);
                    } else {
                        source_index += 1; source_cursor = None;
                    }
                }
            }
        }
        if source_index < candidates.len() {
            // A full output page can leave this source or later sources unread.
            // Preserve the continuation rather than silently losing them.
            return Ok(current_combined_page_with_metadata_cursor(
                records, source_index, source_cursor));
        }
        Ok(current_combined_page(records, no_continuation))
    }
}
```

Candidate source IDs come from each registered adapter's operation-contribution metadata, never a hardcoded provider list. Metadata preserves source order within each operation; adapters still match all requested filters internally. A newly registered document or recipe adapter participates without coordinator edits. The continuation records the complete public query/filter, scope and registry generation plus source position and adapter cursor; it contains no result bodies. Source adapters recalculate each next page from current data. The coordinator reads at most eight source pages per call and returns a metadata-only continuation when more work remains. Config providers are not discovery candidates.

An invalid output item, invalid provider cursor, stale scope or unsupported requested filter fails the whole combined call just as it fails a qualified call. Only `Unavailable` and `ExtractionFailed` are converted into source-status contributions. An adapter may instead return an explicit partial success for supported-but-incomplete extraction; the coordinator preserves that coverage.


## Minecraft adapter

Minecraft resolves vanilla data contracts through live game APIs. The [registry research](2026-10-14-enchantment-potion-registries.md) grounds definition access, and [container access research](2026-10-14-targeted-container-reading.md) grounds synchronized/server reads and read-only constraints.

The Minecraft adapter reads synchronized world registries and loaded recipes, including modded IDs. A namespace test such as `id.starts_with("minecraft:")` would incorrectly exclude supported records. Match the source API/data contract and the selected owner instead. The target game branch is Minecraft 1.21.1.

```rust
impl Toolbox for MinecraftAdapter {
    fn search_inventory(&self, target, filter?, cursor?, limit?, ctx) -> ToolResult {
        if target.type_id not in ["player", "opened", "item", "targeted", "ridden"] {
            return Err(NotHandled);
        }

        // Read client-synchronized sources on the client thread. Closed-container
        // and unsynchronized cargo reads use an authorized live endpoint that
        // executes on the logical server's owning thread, including singleplayer.
        // Label which authority each section actually came from.
        // Check scope before/after execution. Interpret cursors as owner/query/
        // scope/position metadata only; never load prior captured contents.
        match target.type_id {
            "player" => {
                require_live_local_player()?;
                validate_continuation_owner_and_query(cursor, current_player, filter, ctx)?;
                // Enumerate hotbar, main inventory, armor and offhand with
                // section-local indices.
                // Read selected hotbar/offhand locations even when empty.
                // For each current occupied slot:
                //   read item registry ID, localized/custom name, count, components;
                //   read tags/tooltip only as required by the selected filter;
                //   retain missing-field diagnostics;
                //   match OR within each filter field, AND between populated fields;
                //   names/tooltip = literal case-insensitive substrings;
                //   IDs/tags = exact identities; sections = explicit locations.
                // Emit current matching slots and current held-location summary.
                // New locators bind player+section+slot, not a saved stack copy.
                // Return bounded output (page cap 32, reader budget ~96 KiB).
                // Read afresh on continuation; report incomplete fields/sections.
                return Ok(current_player_inventory_page);
            }

            "item" => {
                // Mainhand/offhand selectors resolve the current slot.
                // A slotReference resolves its observed live access path and
                // scoped player section+slot. Reject foreign/expired references.
                let location = resolve_current_selected_or_observed_location(target, ctx)?;
                let stack = current_stack_at(location)?;
                validate_continuation_owner_and_query(cursor, location, filter, ctx)?;
                if stack.is_empty() {
                    return Ok(empty_selector_observation); // distinct from empty container
                }

                if stack.has(DataComponents.CONTAINER) {
                    // Enumerate component slots;
                    // skip empty stacks, retain child slot indices and distinct variants.
                    // Collect only direct contents, bounded at 256 inspected entries.
                } else if stack.has(DataComponents.BUNDLE_CONTENTS) {
                    // Enumerate direct stored stacks
                    // with component-local indices, bounded at 256 entries.
                } else {
                    // This generic reader cannot read this item's storage contract.
                    // Another registered adapter may understand it.
                    return Err(NotHandled);
                }

                // Apply common filters to live child stacks, not the parent item.
                // Preserve section/field limitations. Page at most 32 records.
                // No automatic recursion or fabricated references for unsupported
                // nested locations. Known empty components return complete empty.
                // If recognized component extraction fails, return ExtractionFailed,
                // not NotHandled. Cursor metadata binds the parent access path.
                return Ok(current_direct_container_page);
            }

            "opened" => {
                // Obtain the actually accessible, synchronized menu now.
                let menu = current_live_accessible_menu()
                    .ok_or(Unavailable("opened menu is no longer live-accessible"))?;
                if !menu.exposes_native_inventory_slot_contract() {
                    return Err(NotHandled); // a custom display belongs to its adapter
                }
                validate_continuation_owner_and_query(cursor, menu.identity, filter, ctx)?;
                // Exclude player-owned slots; preserve menu/container identity
                // and menu-slot indices. Skip identified ghost/filter/projection slots
                // from stored quantities; unknown slot semantics keep coverage partial.
                // Cap inspected slots/entries; apply shared filters and output bound.
                // Generic menu slots are a partial view, never full network totals.
                // No menu reopening or saved-capture fallback.
                return Ok(current_visible_menu_page_with_partial_coverage);
            }

            "targeted" => {
                let hit = current_crosshair_hit();
                if hit.is_block() {
                    validate_continuation_owner_and_query(cursor, hit.world_and_position, filter, ctx)?;
                    return self.read_world_inventory(
                        hit.world_and_position, Targeted, filter, cursor, limit, ctx);
                }
                if hit.is_entity() {
                    validate_continuation_owner_and_query(cursor, hit.entity_identity, filter, ctx)?;
                    return self.read_world_inventory(
                        hit.entity_identity, Targeted, filter, cursor, limit, ctx);
                }
                return Err(Unavailable("no inventory target under the crosshair"));
            }

            "ridden" => {
                let vehicle = resolve_current_player_vehicle()
                    .ok_or(Unavailable("player is not riding an entity"))?;
                validate_continuation_owner_and_query(cursor, vehicle.identity, filter, ctx)?;
                // This path does not inspect or depend on crosshair target.
                return self.read_world_inventory(
                    vehicle.identity, Ridden, filter, cursor, limit, ctx);
            }
        }
    }

    fn search_recipe(&self, filter?, cursor?, limit?, ctx) -> ToolResult {
        if filter.provider.is_some_and(|value| value != "native") { return Err(NotHandled); }
        if !ctx.native_version_supported() { return Err(Unavailable("Minecraft source unavailable")); }
        let text = filter.text.unwrap_or("").to_lowercase(Locale::ROOT);
        let output = match filter.output_item {
            None => None,
            Some(id) => Some(parse_resource_location(id)
                .filter(|key| BuiltInRegistries.ITEM.containsKey(key))
                .ok_or(InvalidArguments("output_item must name a registered item"))?),
        };
        // On each call, capture current recipe-manager holders on the client
        // thread and serialize each current recipe holder.
        // Preserve custom ingredient payloads as OPAQUE; ItemValue records
        // item/component/count; TagValue records tag/count; unknown/empty
        // alternatives stay explicit and partial. Output and components are
        // retained. Do not add an input-item search filter.
        let current_recipes = capture_current_native_recipes_on_client_thread(ctx)?;
        let index = KnowledgeIndex::new(current_recipes);
        let candidates = if let Some(item_id) = output {
            // Exact output identities restrict candidates; then text
            // still matches the selected current recipe records below.
            index.recipeOutputs(item_id)
        } else { index.current_recipes() };
        let filtered = candidates.filter(|recipe| text.is_empty()
            || recipe_text_fields(recipe).any(|value| value.to_lowercase(Locale::ROOT).contains(text)));
        let page = page_current_matches(filtered, cursor, limit.unwrap_or(16), 64)?;
        Ok(page_with_scope_and_registry_generation(page, ctx))
    }

    fn search_registry(&self, registry, filter?, cursor?, limit?, ctx) -> ToolResult {
        if filter.provider.is_some_and(|value| value != "native") { return Err(NotHandled); }
        if !matches!(registry,
            Item | Block | ItemTag | Enchantment | MobEffect | Potion | Dimension | DimensionType
        ) { return Err(NotHandled); }
        require_live_world_and_connection()?;
        let text = filter.text.unwrap_or("").to_lowercase(Locale::ROOT);
        let ids = filter.ids.unwrap_or_default();
        let tags = filter.tags.unwrap_or_default();
        if registry == Dimension {
            if !tags.is_empty() { return Err(UnsupportedFilter("dimension has no tag membership")); }
            // Resolve current client/session identity on its thread, then submit
            // asynchronously to the private integrated server. Recheck sole-owner,
            // unpublished-session access and scope there; never block the client.
            // Enumerate current level keys and resolve existing worlds only.
            // Emit ID/type identity, SERVER_READ authority and fresh handles.
            // Otherwise read current connection-known IDs with PARTIAL coverage,
            // including empty results. Do not create/load worlds or scan saves.
            return search_current_runtime_dimensions(ids, text, cursor, limit, ctx);
        }
        let current = ctx.client_thread(|| match registry {
            Item => capture_current_item_registry_and_item_tags(),
            Block => capture_current_block_registry_and_exposed_tags(),
            ItemTag => capture_current_item_tag_registry_and_membership(),
            DimensionType => {
                // Current synchronized registry, including modded types. Project
                // bounded environmental fields; multiple worlds may share a type.
                // Use current holder tags and NativeDefinition(DimensionType, id).
                current_level.registry_access().registry(Registries.DIMENSION_TYPE)?
                    .entries().map(current_dimension_type_record)
            }
            Enchantment => {
                // Use the active world's synchronized datapack
                // registry, not BuiltInRegistries or JAR JSON definitions.
                let entries = current_level.registry_access()
                    .registry(Registries.ENCHANTMENT)
                    .ok_or(Unavailable("current enchantment registry unavailable"))?;
                // Enumerate ID + current description and exposed standard
                // definition: supported/primary items, weight, maximum level,
                // level costs, anvil cost, equipment slots and exclusive set.
                // Retain effect-component payloads and decode supported built-ins.
                // Issue NativeDefinition(Enchantment, id).
                // Modded entries belong here too; custom effect components
                // retain explicit undecoded-mechanic coverage.
                entries.map(current_enchantment_definition_record)
            }
            MobEffect => {
                // Enumerate current BuiltInRegistries.MOB_EFFECT.
                // Record registered ID, exposed display name/category/color;
                // issue NativeDefinition(MobEffect, id). Custom tick/callback
                // behavior is not inferred from those standard fields.
                BuiltInRegistries.MOB_EFFECT.entries()
                    .map(current_mob_effect_definition_record)
            }
            Potion => {
                // Enumerate current BuiltInRegistries.POTION.
                // Record ID and base effect instances with registered effect
                // identities, duration and amplifier. Search the potion's own
                // identity/name where exposed, not arbitrary instance contents.
                // Issue NativeDefinition(Potion, id); custom PotionContents
                // on an actual item remain a separate instance observation.
                BuiltInRegistries.POTION.entries()
                    .map(current_potion_definition_record)
            }
        })?;
        // Exact IDs are namespaced registry IDs. Match tag IDs against holder
        // membership exposed by this registry API. The item_tag registry is
        // itself a tag catalog, so a membership-of-tag filter is undefined.
        if !tags.is_empty() && registry == ItemTag {
            return Err(UnsupportedFilter("filter.tags is undefined for item_tag; use filter.ids or text"));
        }
        let records = [];
        let coverage = current.coverage;
        for record in current.records {
            // Match exact IDs and exposed holder tags, literal case-insensitive
            // name/identity text; OR within a field, AND across populated fields.
            // Evaluate missing selected fields as unknown, not an empty list.
            match evaluate_selected_registry_fields(record, ids, tags, text) {
                Match => records.push(record),
                NoMatch => {},
                Unknown(fields) => {
                    coverage.add_unresolved_filter_fields(record.registry_id, fields);
                    // Keep catalog/match coverage partial so no matches cannot
                    // establish absence when this candidate could not be decided.
                }
            }
        }
        // Cursor stores current scope/registry generation plus positional
        // offset only. Read the registry again on every continuation.
        Ok(page_current_registry_matches(records, coverage, cursor, limit.unwrap_or(16), 64, ctx)?)
    }

    fn read_evidence(&self, handles, ctx) -> ToolResult {
        let locator = ctx.observed_locators.get(handles.single())?;
        if locator.provider_id != "native" { return Err(NotHandled); }

        match locator.source {
            NativeDefinition(Item, id) => {
                // Look up current item registry entry; rebuild its current record.
                // A missing entry is unavailable. Never substitute air/default.
            }
            NativeDefinition(Block, id) => {
                // Look up current block registry entry and rebuild current record.
            }
            NativeDefinition(ItemTag, id) => {
                // Read current synchronized tag membership and serialize it now.
            }
            NativeEntityEquipment(entity_id, access_path, slot) => {
                // Revalidate targeted/ridden/menu access to the SAME entity.
                // Use the native reader below for this exact section/location;
                // equipment empty now is a fresh empty-slot observation.
                return self.read_world_inventory(
                    entity_id, access_path, ExactEquipmentSlot(slot), None, 1, ctx);
            }
            NativeEntityStorage(entity_id, access_path, kind, location) => {
                // The same reader rechecks live access and loot/permission
                // guards. Removed cargo storage or changed mount is unavailable.
                return self.read_world_inventory(
                    entity_id, access_path, ExactStorageSlot(kind, location), None, 1, ctx);
            }
            NativePlayerEffects(player_id) => {
                // On the client thread resolve this scoped local player and read
                // active MobEffectInstances now: effect ID, remaining duration,
                // amplifier and exposed instance flags. Report synchronized
                // player state; definitions alone cannot establish active effects.
                return Ok(current_player_effect_instances);
            }
            NativeStackEnchantments(location) => {
                // Resolve the observed live stack location and access again.
                // Read applied/stored enchantment components and current levels;
                // resolve their definitions through active registry access.
                // Expose standard supported-item/slot/compatibility rules and
                // item-hook results where available, with their context.
                // Custom mechanic/effective-cap hooks without a decoder stay unknown.
                // No enchantments on a readable stack is distinct from lost access.
                return Ok(current_stack_enchantment_evidence);
            }
            NativePotionContents(location) => {
                // Read the live stack's PotionContents component: base potion,
                // additional effects and color, plus effect duration/amplifier.
                // Distinguish absence of the component from unavailable access.
                // Do not substitute the base potion definition for custom contents
                // or infer delivery/duration behavior of a custom item.
                return Ok(current_stack_potion_evidence);
            }
            NativeDefinition(Enchantment, id) => {
                // Resolve the exact ID in the current synchronized
                // enchantment registry and rebuild standard definition fields.
                // Missing entry is unavailable; custom mechanics stay partial.
            }
            NativeDefinition(MobEffect, id) => {
                // Resolve the exact current registered effect and
                // rebuild exposed standard fields, not an active application.
            }
            NativeDefinition(Potion, id) => {
                // Resolve the exact current registered potion and
                // rebuild its base effect instances, not custom item contents.
            }
            NativeDefinition(Recipe, id) => {
                // Read the live recipe holder and rebuild decoded recipe evidence.
                // Missing/unsupported decoding remains unavailable/partial/opaque.
            }
            NativeInventorySlot(section, slot, owner) => {
                // Validate owner/slot in current scope, read current stack at
                // this LOCATION, regenerate current slot record or explicit empty.
                // Do not require ItemStack.matches(old_copy, current_stack).
            }
            NativeInventorySummary(player) => {
                // Regenerate selected hotbar/offhand location summary now.
            }
            NativeStorageOwner(target_identity, access_path) => {
                // Revalidate the observed owner through its actual access path.
                // Opened: matching live menu; targeted: matching current hit/world/
                // position; ridden: same current mount; item: live parent location.
                // Block/entity owners use the access guards in read_world_inventory.
                // Read the current observation; lost or changed owner unavailable.
            }
            NativeStorageEntry(target_identity, access_path, entry_location) => {
                // Resolve the same current owner as above, then the current
                // menu/component entry location. Read it anew; no old entry body.
                // If the owner cannot be established or entry no longer resolves,
                // report unavailable. Do not silently retarget a new menu.
            }
            RecipePlanRoute(identity, quantity, depth, route_identity) => {
                // Rerun plan_recipe with stored query PARAMETERS, not old output.
                // Return the current matching route; if no longer present, unavailable.
                // Recipe-plan handles must distinguish query inputs and route identity.
            }
            ProviderStatus("native") => {
                // Read current native availability; no old error payload replay.
            }
            other => return Err(InvalidReference("native locator cannot be resolved")),
        }
        return Ok(rebuilt_current_record_or_explicit_current_observation);
    }

    fn plan_recipe(&self, identity, quantity, depth?, ctx) -> ToolResult {
        // This adapter owns Minecraft's currently loaded recipe corpus.
        // IDs may name modded items. Invalid IDs/quantity/depth are argument
        // errors, not a reason to try another adapter.
        require_valid_item_identity_and_bounds(identity, quantity, depth.or(4))?;
        require_live_world_and_connection()?;
        // Read fresh recipe facts on the client thread; the worker uses only
        // this call's detached facts to run the bounded route algorithm.
        //
        // For each recipe producing requested item/components:
        //   preserve every candidate alternative;
        //   calculate ceil(required quantity / output count) batches when known;
        //   record route and requested/produced quantity;
        //   mark the route partial when decoding is not VERIFIED or constraints
        //   are unknown; still follow input facts the planner can use;
        //   expand usable consumed item inputs/alternatives with checked arithmetic;
        //   preserve catalysts, unknown quantities and opaque routes as uncertain;
        //   stop cycles, depth bound, 256 nodes or 64 branches as partial.
        // No recipe/unknown semantics do not establish an obtainable complete plan.
        // Preserve unknown catalyst/remainder semantics as PARTIAL; never
        // claim a complete route when required recipe facts are undecoded.
        return Ok(current_bounded_recipe_routes_with_query_locators);
    }
}
```

The internal world-inventory reader is shared by targeted/ridden searches and evidence reads. It performs substantive access and extraction work inside the Minecraft adapter; it is not a dispatch predicate.

```rust
impl MinecraftAdapter {
    fn read_world_inventory(&self, owner, access_path, selection, cursor?, limit?, ctx) -> ToolResult {
        // Resolve the live selector or observed locator on the client thread.
        // Targeted requires the same hit; ridden requires the same direct mount;
        // opened requires the same live menu mapped to this owner and its slots.
        require_live_owner_binding(owner, access_path, ctx)?;
        if !owner.exposes_native_inventory_contract() {
            return if selection.is_evidence_read() {
                Err(Unavailable("observed native inventory no longer resolves"))
            } else { Err(NotHandled) };
        }

        // Client block-entity getters/capabilities and riding alone do not prove
        // contents are synchronized. Select a verified synchronized view, or use
        // ctx.native_inventory_access to request fresh data on the logical server.
        // That endpoint is part of the target design. If absent or access denied,
        // mark those sections unavailable; retain other readable sections as partial.
        // On the server: resolve only loaded owners in the requesting player's
        // world, recheck identity/range/permission and protection rules, and read
        // on the world thread. Never force-load a chunk or trust client assertions.

        match owner {
            Block(position) => {
                // Identify the actual native container contract and storage owner.
                // Chest: account for both halves and verify each half's access.
                // Ender chest: contents belong to the authorized player's inventory.
                // Other native containers: read their exposed storage slots.
                // Check locks/access with side-effect-free checks; do not invoke
                // interaction/canOpen helpers that produce messages or sounds.
                // Check pending loot BEFORE any getter that can unpack it. Pending
                // loot is unavailable, never generated to answer a search.
            }
            Entity(identity) => {
                // LivingEntity: read synchronized equipment slots where exposed.
                // Horses: read equipment through their native inventory mapping.
                // Donkey/mule/llama: include cargo when chest storage is attached.
                // Chest boat/minecart: read native container cargo.
                // Unsynchronized cargo requires the authorized server path above.
                // Check ownership/access and pending loot before slot reads; the
                // presence of Container or an item-handler capability alone does
                // not prove a pure, authorized read of arbitrary modded storage.
                // Preserve equipment IDs and cargo slot indices as separate sections.
            }
        }

        // Enumerate only verified real inventory slots; preserve component variants.
        // Apply the shared filters (or internal exact-location selection), then page
        // within the 32-record/reader-byte bounds. Exact evidence reads retain an
        // empty-slot observation when the slot exists but its contents changed.
        // Emit owner/access-path/location locators. A vanished owner is unavailable;
        // an unsupported contract declines; a supported empty inventory is empty.
        // Recheck scope and selector binding before publishing the fresh result.
        return Ok(current_inventory_page_with_section_authority_and_coverage);
    }
}
```

Inventory locators use **location semantics**: every page and evidence read resolves the current contents of the observed player/entity/menu slot. They do not claim to follow a physical item after it moves. A mainhand selector resolves the selected slot on each new query; continuation metadata rejects a change of owner or location.

Opened-container access is a live synchronized menu observation. It never reopens a menu or falls back to a saved capture. Targeted block and entity paths resolve the current crosshair hit. The `ridden` target resolves the player's current vehicle independently of crosshair state. All paths are read-only: they do not interact, open a screen, generate loot or mutate inventories. Report synchronized-client versus server-read authority explicitly. If a section lacks a live authorized read path, return unavailable/partial coverage.

Every cursor contains only the operation signature, normalized filter/query, scope, registry generation, source position and opaque adapter continuation. Each page recaptures its candidates from live sources; no adapter may serve retained result snapshots. Positional continuation is best-effort when current ordering changes and must reject malformed or out-of-range positions.

The six registry selectors use current registry access: items, blocks, item tags, enchantments, mob effects and potions. Definitions include registered IDs and exposed fields; tags filter entries where the selected registry holder exposes tag membership. For the `item_tag` catalog, IDs/text select tag definitions; `filter.tags` is invalid because tag membership does not apply to tag definitions. Source failures remain unavailable/partial and are never translated into `NotHandled` after Minecraft claims a selector.

### Live evidence locators

Every returned game-evidence record identifies a resolvable source or derived query. Definition locators retain registry kind and ID; slot locators retain owner/access path and location; recipe-plan locators retain query parameters and route identity. Entity equipment and cargo share owner/access checks across targeted, ridden and opened selection while each path uses its actual synchronized or server source. Live gameplay context issues scoped instance references for player effects; inventory observations issue stack-location references for enchantments and potion contents. Saved-history references use `read_history`.

Resolving a handle validates its owner and access, reads current values and builds a new record. The handle never retains the previous result body. Derived outputs have distinct locators so they cannot collide with ordinary recipe definitions. Losing a source produces an explicit unavailable result.

## JEI adapter

API and source evidence: [PackSageJeiPlugin](../../src/main/java/dev/packsage/knowledge/jei/PackSageJeiPlugin.java), [JeiProvider](../../src/main/java/dev/packsage/knowledge/jei/JeiProvider.java), [registration and compatibility gate](../../src/main/java/dev/packsage/client/ClientAssistant.java), [JEI tests](../../src/test/java/dev/packsage/knowledge/jei/JeiProjectionTest.java).

For `search_recipe`, marshal each invocation to the client thread and obtain the current `IJeiRuntime` recipe manager. With `filter.output_item`, resolve the registered item and create a JEI `OUTPUT` focus; malformed or unknown IDs are invalid arguments. Obtain category lookups with hidden categories included and that focus applied, then per-category recipe lookups with hidden recipes included and the same focus. Bound each source page to 256 categories and 2,048 inspected recipes; cap results at 64. Lowercase `filter.text` and match category ID/title, canonical recipe ID and generated handle; absent/empty text matches all. The canonical recipe ID may be absent. Project each result from JEI's live recipe layout: slot name (or `slot-N`), role and alternatives; each typed ingredient's comparison identity, quantity where available, components/display name, visibility and consumption; recipe/catalyst information and tooltip; known/unknown category decoding. Preserve opaque/error records when category or projection data cannot be decoded. Use a locator consisting of logical JEI source, runtime generation, category and live recipe identity; identity can be absent, duplicate, or order-dependent, so validate the locator against the live lookup and never claim a globally stable recipe ID. Cursor stores category index and recipe ordinal (`category * 1_000_000 + recipe`) as position in the newly queried ordering, not a cached lookup. Hitting either source cap returns partial coverage and a next cursor. Search does not match arbitrary ingredient names; `output_item` uses JEI's native focus API. The discovery coordinator applies the shared eight-source-page call bound.

```rust
impl Toolbox for JeiAdapter {
    fn search_recipe(&self, filter?, cursor?, limit?, ctx) -> ToolResult {
        if filter.provider != Some("jei") { return Err(NotHandled); }
        if !ctx.optional_status("jei").supported {
            return Err(Unavailable("JEI version is absent or outside [19.27.0.340,20)"));
        }
        let runtime = self.active_jei_runtime();
        if runtime.is_none() { return Err(Unavailable("JEI runtime is not active")); }
        let text = filter.text.unwrap_or("").to_lowercase(Locale::ROOT);
        let cursor = match cursor {
            None => (0, 0),
            Some(token) => {
                let value = parse_nonnegative_integer(token)
                    .ok_or(InvalidCursor("repeat search without cursor"))?;
                (value / 1_000_000, value % 1_000_000)
            }
        };

        // Entire extraction executes on JEI's owning client thread. Each
        // invocation builds new records from the currently active runtime.
        ctx.client_thread(|| {
            let manager = runtime.get_recipe_manager();
            let focus = match filter.output_item {
                None => empty_focus(),
                Some(id) => {
                    let key = ResourceLocation::try_parse(id)
                        .ok_or(InvalidArguments("invalid outputItem"))?;
                    let item = BuiltInRegistries.ITEM.get_optional(key)
                        .ok_or(InvalidArguments("unknown outputItem"))?;
                    runtime.get_jei_helpers().get_focus_factory()
                        .create_focus(OUTPUT, VanillaTypes.ITEM_STACK, ItemStack(item))
                }
            };
            let mut records = vec![];
            let mut scanned_recipes = 0;
            let mut has_more = false;
            let mut coverage = COMPLETE;
            let mut next = cursor;
            let mut categories = manager.create_recipe_category_lookup()
                .include_hidden().limit_focus(focus).get()?;
            for (category_index, category) in categories.enumerate().skip(cursor.category) {
                if category_index - cursor.category >= 256 {
                    coverage = PARTIAL; has_more = true;
                    next = (category_index, 0); break;
                }
                let category_id = category_id(category);
                let title = category_title(category, category_id);
                let recipe_type = match category.get_recipe_type() {
                    Ok(kind) => kind,
                    Err(error) => {
                        coverage = ERROR;
                        if matches_text(category_id, title, None, text) {
                            records.push(error_record(category_id, title, error));
                        }
                        continue;
                    }
                };
                let local_cursor = if category_index == cursor.category { cursor.recipe } else { 0 };
                let mut recipes = manager.create_recipe_lookup(recipe_type)
                    .include_hidden().limit_focus(focus).get()?;
                for (ordinal, recipe) in recipes.enumerate().skip(local_cursor) {
                    if records.len() == min(limit.unwrap_or(16), 64) || scanned_recipes == 2_048 {
                        coverage = PARTIAL; has_more = true;
                        next = (category_index, ordinal); break;
                    }
                    scanned_recipes += 1;
                    let canonical = canonical_recipe_id(category, recipe).ok();
                    let handle = current_jei_handle(category_id, canonical, recipe, ordinal);
                    if !matches_text(category_id, title, canonical, text)
                        && !text.is_empty() && !handle.to_lowercase(Locale::ROOT).contains(text) {
                        continue;
                    }
                    let layout = manager.create_recipe_layout_drawable(category, recipe, empty_focus_group());
                    let projection = match layout {
                        None => error_projection(category_id, handle, canonical, title,
                            "JEI did not provide a detached recipe layout"),
                        Some(layout) => {
                            let mut slots = vec![];
                            for (slot_index, slot) in layout.slots().enumerate() {
                                let alternatives = slot.all_ingredients().map(|typed| {
                                    let value = typed.value();
                                    Ingredient {
                                        kind: typed.type_id(),
                                        identity: ingredient_helper(runtime, value).unique_id(value),
                                        components: capture_stack_components_or_display_name(value),
                                        quantity: ingredient_amount(value).ok(),
                                        consumption: match slot.role() {
                                            INPUT => CONSUMED, CATALYST => REUSED, _ => UNKNOWN,
                                        },
                                        visible: runtime.ingredient_visibility().is_visible(typed),
                                    }
                                }).collect();
                                slots.push(Slot {
                                    group_id: slot.name().unwrap_or(format!("slot-{slot_index}")),
                                    role: map_role(slot.role()), alternatives,
                                });
                            }
                            let information = recipe_description_and_category_tooltip(recipe, category, layout);
                            // Decoder coverage follows verified category/recipe
                            // semantics, never an ID namespace alone. Unknown
                            // custom behavior stays explicit in the projection.
                            project_with_visibility_and_decoding(category_id, handle, canonical,
                                title, slots, information, decoder_coverage(category, recipe))
                        }
                    };
                    if projection.coverage == ERROR { coverage = ERROR; }
                    else if projection.coverage != COMPLETE && coverage == COMPLETE { coverage = PARTIAL; }
                    records.push(to_recipe_record(projection, current_scope(ctx), current_generation(runtime)));
                    // Store only an opaque locator (runtime generation,
                    // category and recipe object), never this projection.
                    self.remember_live_recipe_locator(handle, category, recipe, current_scope(ctx));
                }
                if has_more { break; }
            }
            Ok(page(records, coverage, has_more.then(|| encode_cursor(next))))
        })
    }

    fn read_evidence(&self, request: &ReadEvidence, ctx: &ToolContext) -> ToolResult {
        // The boundary calls this once per requested handle. A non-JEI handle
        // belongs to a later adapter; a JEI-shaped handle that has gone stale
        // is unavailable and must not fall through.
        if request.reference.provider != "jei" { return Err(NotHandled); }
        if request.reference.source == ProviderStatus("jei") {
            return Ok(current_jei_runtime_status);
        }
        let locator = self.live_recipe_locator(request.reference.source_handle)
            .ok_or(Unavailable("JEI recipe locator expired"))?;
        ctx.client_thread(|| {
            if locator.scope != current_scope(ctx) || locator.runtime_generation != current_generation(self.runtime()) {
                return Err(Unavailable("JEI runtime or scope changed"));
            }
            let manager = self.runtime().get_recipe_manager();
            if manager.get_recipe_category(locator.recipe_type) != locator.category
                || !manager.create_recipe_category_lookup().limit_types([locator.recipe_type])
                    .get()?.any(|value| value == locator.category) {
                return Err(Unavailable("JEI category is no longer registered"));
            }
            if locator.recipe.is_none() {
                // Category-only opaque locator: no recipe-object lookup.
                return Ok(evidence([current_opaque_category_record(locator, ctx)]));
            }
            // Recheck this exact object in JEI's current recipe lookup, then
            // rebuild its projection from its current layout and ingredients.
            if !manager.create_recipe_lookup(locator.recipe_type).get()?
                .any(|value| same_runtime_recipe(value, locator.recipe)) {
                return Err(Unavailable("JEI recipe is no longer registered"));
            }
            let projection = project_current_recipe(manager, locator, ctx)?;
            if !projection.visible || !projection.visibility_known {
                return Err(Unavailable("JEI recipe visibility is unavailable"));
            }
            Ok(evidence([projection]))
        })
    }
}
```

JEI evidence uses record kind `RECIPE` and provider ID `jei`. Retain only an opaque scope/runtime-bound locator to a live category and recipe object; `read_evidence` verifies that the category is still registered and that the exact recipe remains in its live lookup, then constructs a new projection. If the locator expired or the runtime/category/recipe changed, return unavailable/stale. A category locator can represent a recipe whose projection is unknown or cannot be detached. Viewer navigation can use the same revalidated locator as a player-click action, not a model tool.

Every JEI continuation obtains a fresh live lookup. Treat the cursor as a best-effort location in the current ordering, reject invalid positions, and require a new query when the source cannot resume safely. Results across pages are not a frozen set. Keep bounded scanning and partial-result coverage rather than copying the whole JEI recipe set.

## Bounded document extraction

FTB and Patchouli share a per-tool-call document budget: at most 256 document candidates, 2,048 source steps/nested values and 96 KiB of extracted text/JSON. The same budget is passed through every discovery subrequest and every evidence/progress handle in that call; the coordinator's eight-page bound does not reset it. Source steps include collection traversal, cursor seeking, dependencies, tasks, rewards, pages, mappings and JSON nodes. Reserve capacity before reading, copying, localizing or decoding a value, not after building a complete object.

The traversal operations below are explanatory adapter internals. They walk the provider's live collections without flattening the whole source. Resume through current keyed/indexed access where available. Count any required prefix traversal against the budget; if the cursor cannot be reached within a bounded seek, return `InvalidCursor` with restart/narrowing guidance instead of an unbounded scan or the same non-progressing cursor. Verify the stored owner IDs and nested position before continuing; never silently advance past a vanished or changed owner.

A fragment contains a bounded document header, the inspected structured fields/page range, its ordinary document handle, coverage and read time. Match the query against that header and the current fragment; do not remember an earlier match. Each emitted fragment counts toward `limit`, including repeated handles with different ranges. For split literal text, re-read a bounded overlap from the live field so a match crossing the boundary is not lost; cursor offsets advance beyond that overlap. Never merge fragments from different calls into a claim of one consistent document state.

Reserve tool-output space while extracting. At a result/work/byte limit, stop before the next unread position and return partial coverage with the source cursor, even if no match has been found. Cursors contain only query/scope, owner identity and field/page positions, including a text offset when needed. Do not advance past a matching fragment that cannot fit the output. If one field requires an indivisible unbounded getter/decoder, skip that field with an explicit unread-field diagnostic and advance; do not repeatedly retry it or claim complete matching. Unknown/custom callbacks are not executed merely to discover their cost.

Before starting a fragment, reserve its bounded header and at least one next-field step. If the remaining call budget cannot accommodate that minimum, mark it exhausted and stop at the unchanged position; do not enter an inner loop that can neither advance nor return a continuation. The next call gets a fresh budget. Diagnostics and fragment headers count toward the same output bound.

`read_evidence` and `read_progress` use the same bounded extraction for the requested owner. They have no cursor parameter: return bounded details with explicit truncation and a document-search continuation hint, not a new pagination argument or an unlimited full-document read. Direct owner lookup/seek is bounded too.

## FTB Quests adapter

API and source evidence: [FtbProvider](../../src/main/java/dev/packsage/knowledge/ftb/FtbProvider.java), [registration and compatibility gate](../../src/main/java/dev/packsage/client/ClientAssistant.java), [FTB tests](../../src/test/java/dev/packsage/knowledge/ftb/FtbProviderTest.java), [gate tests](../../src/test/java/dev/packsage/client/ClientAssistantTest.java).

Every call resolves `ClientQuestFile.INSTANCE`, player UUID and `file.selfTeamData` on the client thread. `search_document` owns provider `ftb_quests` and document type `quest`, including chapters. Traverse live chapter headers/child summaries and quest definitions/progress lazily; do not build a complete chapter/quest/progress list first. The cursor identifies the file/folder/team/lock scope, chapter/quest ID and next field, child/task/reward/dependency index or text offset.

Match literal case-insensitive text against chapter ID/title/group/group title/child quest IDs and quest ID/raw/display title/chapter/dependency/task/reward IDs, types and item IDs. Preserve dependency alternatives/minimums, task/reward definitions and the current team/player overlay in structured fragments. Chapter progress consists of the inspected current child observations; any completed/total aggregate is explicitly limited to that inspected range. Missing/locked team data and custom or unread fields remain unknown/partial.

```rust
impl Toolbox for FtbQuestsAdapter {
    fn search_document(&self, query, filter?, cursor?, limit?, ctx) -> ToolResult {
        if filter.provider != Some("ftb_quests")
            || (!filter.types.is_empty() && !filter.types.contains(Quest)) {
            return Err(NotHandled);
        }
        if !ctx.optional_status("ftb_quests").supported {
            return Err(Unavailable("ftbquests absent or outside [2101.1.36,2101.2)"));
        }
        ctx.client_thread(|| {
            let file = ClientQuestFile::INSTANCE
                .ok_or(Unavailable("FTB Quests client file unavailable"))?;
            let player = Minecraft::get_instance().player.map(|value| value.uuid());
            let team = file.selfTeamData;
            let mut walk = bounded_live_ftb_walk(
                file, cursor, query, filter, ctx.scope, &mut ctx.document_budget)?;
            // The walk validates owner/position and charges traversal/seek.
            // It holds live references only for this call, never extracted records.
            let mut records = vec![];
            let mut coverage = COMPLETE;
            while !walk.finished() && records.len() < limit.unwrap_or(16) {
                if !ctx.document_budget.reserve_fragment_start(ctx.tool_output_budget) {
                    break;
                }
                let start = walk.position();
                // Charge before looking at the next document or nested value.
                // Read a bounded header for this owner; child lists stay lazy.
                let mut fragment = begin_bounded_ftb_fragment(
                    walk.current_owner(), start, &mut ctx.document_budget)?;

                match walk.current_owner_kind() {
                    Chapter => {
                        // Read ID, display title, group ID/title and visibility.
                        // Walk only the child IDs/progress range that fits the
                        // remaining budget; charge each child before reading it.
                        // Keep its current completion/visibility and unknowns.
                        // No eager all-child progress aggregation.
                        read_chapter_fields_until_budget(
                            &mut walk, &mut fragment, team, player, ctx)?;
                    }
                    Quest => {
                        // Read ID/raw/display title and chapter identity.
                        // Dependency phase: one ID/rule/alternative at a time;
                        // retain OR branches and minimum required counts.
                        // Task phase: charge each task and nested payload value;
                        // read ID/type/item, definition, progress/max/completion.
                        // Reward phase: charge each reward and nested payload;
                        // read ID/type/item, definition and per-player claim state.
                        // Progress phase: current visibility/dependency/completion
                        // fields only through bounded, supported accessors.
                        // Unknown/custom fields stay partial; never invoke a
                        // bulk getter that traverses all tasks/dependencies.
                        read_quest_fields_until_budget(
                            &mut walk, &mut fragment, team, player, ctx)?;
                    }
                }
                // Each read above reserves value/byte/output capacity first and
                // returns the next unread nested position, even inside one quest.
                // An unread indivisible field gets a diagnostic, then is skipped.
                coverage.combine(fragment.coverage);
                walk.advance_to(fragment.next_unread_position);
                require_progress_or_terminal_field_diagnostic(start, walk.position())?;
                if fragment.matches_literal_query(query) {
                    // Emit its FtbObject(kind, id) handle and inspected ranges.
                    // Distinct fragments with the same handle are not discarded.
                    records.push(ftb_fragment_record(fragment, ctx.scope));
                }
            }
            if !walk.finished() { coverage.combine(PARTIAL); }
            Ok(page(records, coverage,
                (!walk.finished()).then(|| walk.metadata_cursor(query, filter, ctx.scope))))
        })
    }

    fn read_progress(&self, request: &ReadProgress, ctx: &ToolContext) -> ToolResult {
        if request.reference.provider != "ftb_quests" { return Err(NotHandled); }
        require_ftb_quest_or_chapter_locator(request.reference)?;
        // Reuse the bounded fresh definition/progress read, including chapter
        // child observations. A handle does not authorize unlimited extraction.
        self.read_evidence(ReadEvidence(request.handles), ctx)
    }

    fn read_evidence(&self, request: &ReadEvidence, ctx: &ToolContext) -> ToolResult {
        if request.reference.provider != "ftb_quests" { return Err(NotHandled); }
        if request.reference.source == ProviderStatus("ftb_quests") {
            return Ok(current_ftb_file_team_and_decoder_status);
        }
        let FtbObject(kind, id) = request.reference.source
            else { return Err(InvalidReference("expected FTB quest or chapter locator")); };
        ctx.client_thread(|| {
            let file = ClientQuestFile::INSTANCE
                .ok_or(Unavailable("FTB Quests client file unavailable"))?;
            // Resolve exactly kind + ID using bounded live lookup. Resolve player
            // and team now; missing owner is unavailable, locked team is unknown.
            // Read this owner's fields with the SAME shared budget and extraction
            // phases above. Stop at budget exhaustion; do not read the whole file.
            // Report omitted fields/child ranges and how to continue through
            // search_document. No cursor is added to read_evidence/read_progress.
            Ok(evidence([read_bounded_current_ftb_object(
                kind, id, file, &mut ctx.document_budget, ctx)?]))
        })
    }
}
```

A continuation starts at the next unread nested position in the current source. It never contains earlier task/reward values, chapter summaries, progress or a remembered query match. Changed data can shift fragments; invalid owner/position/scope returns an error rather than replaying an old fragment. Exhausting the work budget yields partial coverage and a continuation; reaching the end establishes complete matching only if no selected field was unread.

## Patchouli adapter

API and source evidence: [PatchouliProvider](../../src/main/java/dev/packsage/knowledge/patchouli/PatchouliProvider.java), [registration and compatibility gate](../../src/main/java/dev/packsage/client/ClientAssistant.java), [Patchouli tests](../../src/test/java/dev/packsage/knowledge/patchouli/PatchouliProviderTest.java), [gate tests](../../src/test/java/dev/packsage/client/ClientAssistantTest.java).

`search_document` owns provider `patchouli` and type `guide`. Each call resolves `BookRegistry.INSTANCE.books` on the client thread and walks current books/entries/pages under the shared document budget. It does not copy every book or entry before searching. The cursor retains book/entry identity, collection position, page/mapping index and nested field/text offset. Charge hidden/locked entries and any cursor-seek work too; a result limit does not bound those visits by itself.

Search current entry ID, book ID, title and visible localized page text. A metadata match selects the visible pages inspected in that fragment; otherwise select pages whose inspected text matches. Large entries can produce several fragments under the same `PatchouliEntry(book_id, entry_id)` handle. Only inspected visible pages receive navigation targets. Each fragment reports its page/field range and incomplete fields, so it cannot be mistaken for the entire book entry.

Read source JSON, localized text, item links (`item`/`items`), recipe links (`recipe`/`recipes`/`recipe_id`/`recipeId`) and extra recipe mappings incrementally. Reserve work/bytes before copying each JSON node or text segment. Decode the supported `PageText`, `PageSpotlight`, `PageCrafting`, `PageSmelting`, `PageBlasting`, `PageSmoking`, `PageCampfireCooking`, `PageSmithing` and `PageStonecutting` fields; other unlocked page classes remain opaque. A locked page exposes only index/type/visible=false, without copying its body. Failed fields and unread custom/unbounded operations lower coverage.

```rust
impl Toolbox for PatchouliAdapter {
    fn search_document(&self, query, filter?, cursor?, limit?, ctx) -> ToolResult {
        if filter.provider != Some("patchouli")
            || (!filter.types.is_empty() && !filter.types.contains(Guide)) {
            return Err(NotHandled);
        }
        if !ctx.optional_status("patchouli").supported {
            return Err(Unavailable("Patchouli absent or outside [1.21.1-92,1.21.1-94)"));
        }
        ctx.client_thread(|| {
            let mut walk = bounded_live_patchouli_walk(
                BookRegistry.INSTANCE.books, cursor, query, filter,
                ctx.scope, &mut ctx.document_budget)?;
            // Validate book/entry identity and nested position; charge lookup,
            // skipped collection elements and every candidate visit before use.
            let mut records = vec![];
            let mut coverage = COMPLETE;
            while !walk.finished() && records.len() < limit.unwrap_or(16) {
                if !ctx.document_budget.reserve_fragment_start(ctx.tool_output_budget) {
                    break;
                }
                let start = walk.position();
                let entry = walk.current_entry_after_charging_visit()?;
                if entry.shouldHide() || entry.isLocked() {
                    walk.advance_to_next_entry();
                    continue;
                }
                let mut fragment = begin_bounded_guide_fragment(
                    walk.current_book_id(), entry, start, &mut ctx.document_budget)?;
                // Bounded header: book/entry IDs, title, hidden/locked/secret,
                // added-by metadata. No page list or source JSON is copied here.
                let metadata_match = fragment.header_matches_literal(query);

                while walk.in_same_entry() && ctx.document_budget.can_read_next_value() {
                    // Reserve remaining tool-output bytes along with extraction
                    // bytes. Stop BEFORE a value that belongs to the next fragment.
                    if !ctx.tool_output_budget.can_fit_next_fragment_value() { break; }
                    match walk.next_field_kind() {
                        Page => {
                            let page = walk.current_page_after_charging_step()?;
                            if !page.isPageUnlocked() {
                                fragment.add_locked_page_marker(
                                    walk.page_index(), page.type_identity());
                                walk.advance_past_page();
                                continue;
                            }
                            // Read the current page's type, then visit source JSON
                            // nodes individually under the step/byte budget.
                            // Never call sourceObject.deepCopy() for the whole page.
                            // Text: if PageWithText and its text field is primitive,
                            // read/localize only through a bounded supported path.
                            // Continue long readable fields with a text offset and
                            // current-source overlap for literal substring matching.
                            // Item/recipe links: visit each value rather than collect
                            // an entire array before limiting it.
                            // Supported page classes decode their inspected fields;
                            // unknown classes retain bounded raw fields as OPAQUE.
                            // An indivisible unbounded field is explicitly unread.
                            read_page_fields_until_budget(
                                &mut walk, &mut fragment, ctx)?;
                        }
                        ExtraRecipeMapping => {
                            // Version-gated reflection obtains the live map reference
                            // only. Charge and read one mapping at a time, never copy
                            // the entire map. Bridge failures lower coverage.
                            read_next_bounded_mapping(&mut walk, &mut fragment, ctx)?;
                        }
                        EntryField => {
                            // Resume a header/text field that did not fit earlier.
                            read_next_bounded_entry_field(&mut walk, &mut fragment, ctx)?;
                        }
                        EndOfEntry => { walk.advance_to_next_entry(); break; }
                    }
                }
                // Extracted page text participates in matching even when the
                // header does not match. Empty query enumerates inspected fields.
                coverage.combine(fragment.coverage);
                if metadata_match || fragment.visible_text_matches_literal(query) {
                    fragment.select_navigation_pages(metadata_match, query);
                    records.push(guide_fragment_record(fragment, ctx.scope));
                }
                require_progress_or_terminal_field_diagnostic(start, walk.position())?;
            }
            if !walk.finished() { coverage.combine(PARTIAL); }
            Ok(page(records, coverage,
                (!walk.finished()).then(|| walk.metadata_cursor(query, filter, ctx.scope))))
        })
    }

    fn read_evidence(&self, request: &ReadEvidence, ctx: &ToolContext) -> ToolResult {
        if request.reference.provider != "patchouli" { return Err(NotHandled); }
        if request.reference.source == ProviderStatus("patchouli") {
            return Ok(current_patchouli_registry_and_decoder_status);
        }
        let PatchouliEntry(book_id, entry_id) = request.reference.source
            else { return Err(InvalidReference("expected Patchouli entry locator")); };
        ctx.client_thread(|| {
            // Resolve this book/entry with bounded current keyed lookup or seek.
            // Missing owner/contents is unavailable. Recheck hidden/locked state
            // and every inspected page's unlock state on this call.
            // Use the SAME incremental JSON/text/link/mapping extraction above
            // under the shared budget; do not copy the whole entry first.
            // If details are truncated, mark partial and give the source/query
            // identity for continuing with search_document (which has a cursor).
            Ok(evidence([read_bounded_current_guide_entry(
                book_id, entry_id, &mut ctx.document_budget, ctx)?]))
        })
    }
}
```

Budget exhaustion stops extraction itself and returns the next unread page/field position, including when there are no matching results yet. No page bodies or query matches survive in a continuation. A changed owner or invalid nested position fails the continuation; a now-hidden/locked entry is skipped under the live visibility rules. Partial/opaque/failed field coverage is retained even when the cursor reaches the source's end.

## Config adapter

ConfigAdapter resolves runtime catalog entries through the NeoForge ConfigTracker API and reads saved values from instance-scoped config files. Each invocation reads both applicable sources anew.

The loaded ConfigTracker bridge checks NeoForge `[21.1.202,21.2)` and FML `[4.0.41,5)`, plus reflective field access. Saved-file inspection remains available when the bridge is unsupported. Therefore register the config adapter even in that case; do not accidentally remove saved-file coverage with an all-or-nothing registration gate.

```rust
impl Toolbox for ConfigAdapter {
    fn list_configs(&self, limit?, cursor?, ctx) -> ToolResult {
        // This adapter owns config-value discovery and emits entry/key handles.
        return self.search_config("", limit, cursor, ctx);
    }

    fn search_config(&self, text, limit?, cursor?, ctx) -> ToolResult {
        // Read through the two source paths:
        // 1. On client thread read current ConfigTracker configSets/configsByMod.
        //    Gate reflected loaded access by NeoForge+FML versions and field shape.
        //    Copy current values/defaults/comments/restart flags/ranges and scope.
        // 2. On worker inspect known roots for unregistered .toml files:
        //    config/, existing defaultconfigs/, and private singleplayer serverconfig/.
        //    Never add arbitrary model-supplied roots or remote server filesystem.
        // 3. Resolve real paths under allowed roots; read current UTF-8 saved files.
        //    Parse within bounds; undecodable syntax remains partial.
        //    Work bounds: 1 MiB/file, 256 fallback files inspected, walk depth 4,
        //    2,048 keys/entry, nested depth 32.
        // 4. Combine saved and loaded views WITHOUT treating saved as loaded.
        //    Match literal case-insensitive text against owner, scope, file name,
        //    key or comment. Apply that declared matcher before paging.
        // 5. Validate query/scope cursor; page the newly read candidates.
        //    A positional cursor contains no values; changed ordering can shift pages.
        //    Preserve partial bridge/file/parser/bounds diagnostics.
        // 6. Return at most limit.or(16), max64, with a metadata-only cursor.
        //    Issue ConfigKey(entry_handle, key) locators for returned records.
        return Ok(current_config_value_page);
    }

    fn read_config(&self, handle, key?, section?, limit?, ctx) -> ToolResult {
        // Resolve only previously observed config references:
        // exact provider-qualified key handle or unambiguous observed entry handle.
        // An observed entry/key reference is opaque to the caller; resolve
        // its registered access path rather than treating it as a file path.
        let locator = resolve_observed_config_handle(handle, ctx)?;
        if locator.provider_id != "config" { return Err(NotHandled); }
        if key.is_none() && section.is_none() {
            return Err(InvalidArguments("select a config key or section"));
        }

        // Read the current catalog and saved files using the path above.
        // Restrict candidates to locator.entry_handle.
        if key.is_some() {
            // Exact key selection takes precedence if both fields were supplied.
        } else {
            // "section" is a literal text search within the selected entry,
            // not a structural subtree selector.
        }
        // Return a bounded page, no cursor (read_config has no cursor argument).
        // If more match, report truncation and how to narrow the selected key.
        return Ok(current_selected_config_values);
    }

    fn read_evidence(&self, handles, ctx) -> ToolResult {
        let locator = ctx.observed_locators.get(handles.single())?;
        if locator.provider_id != "config" { return Err(NotHandled); }
        match locator.source {
            ConfigKey(entry, key) => {
                // Same fresh catalog/file extraction, exact entry + key.
                // Removed key/file cannot return the old value; report unavailable.
                return Ok(current_config_key_record);
            }
            ProviderStatus("config") => return Ok(current_config_bridge_and_file_status),
            other => return Err(InvalidReference("not a readable config locator")),
        }
    }
}
```

Observed config aliases are resolved within the owning adapter, with scope and ambiguity checks. File reads, loaded values, selected keys and bounded output use the same declared contract.

## History adapter

HistoryAdapter owns the current world/server saved-conversation store. It participates in the registered tool chain and executes file reads on the I/O queue.

```rust
impl Toolbox for HistoryAdapter {
    fn search_history(&self, text, limit?, cursor?, ctx) -> ToolResult {
        require_nonblank_text(text, max_length = 512)?;
        require_limit(limit.or(8), min = 1, max = 16)?;
        require_current_world_and_scope(ctx)?;
        // Validate query, excluded-request and scope continuation metadata.
        // Ask ConversationStore.searchHistory NOW on its I/O queue:
        //   discover current session JSONL files for this world/server;
        //   preserve safe-path/no-symlink and bounded discovery/scan checks;
        //   compare continuation file stamps to the current files;
        //   reject changed files/cursors rather than replay saved result records;
        //   read completed exchanges, excluding ctx.replacing_request_id;
        //   case-insensitive literal match in saved question/answer text.
        // Issue bounded observed handles (max128) holding file position,
        // length, request ID and text fingerprint; keep max64 cursor metadata.
        // Return question excerpt <=256 and answer excerpt <=512 characters,
        // with historical=true and partial/truncated coverage as appropriate.
        return Ok(current_history_search_page);
    }

    fn read_history(&self, handles, max_chars?, ctx) -> ToolResult {
        require_count(handles, min = 1, max = 8)?;
        require_range(max_chars.or(8000), min = 256, max = 16000)?;
        // Check all exact history: references were discovered in this scope,
        // and none identifies the exchange currently being replaced.
        // For each file locator:
        //   reopen the current file;
        //   validate session/world header and bounded byte position/length;
        //   read current bytes and verify the locator fingerprint;
        //   reject deleted/replaced/not-completed exchanges.
        // Split max_chars per exchange between question then answer;
        // report truncation as partial, historical=true always.
        // Scope-check again when asynchronous reads finish.
        return Ok(currently_read_saved_exchanges);
    }
}
```

File stamps and fingerprints are identity/continuation checks, not copied answer bodies. Rejecting a replaced historical exchange preserves the meaning of its identity; this differs from rejecting a player's slot merely because its current contents changed. No general game-evidence `read_evidence` implementation is added for history: history references retain their dedicated `read_history` path.

## Missing or unsupported optional source status

The registry keeps absent, incompatible, linkage-failed and not-ready source diagnostics distinct. An adapter that owns an operation returns handled unavailable/partial status for its source; only a non-owner returns `NotHandled`.

```rust
impl Toolbox for UnavailableSourceAdapter {
    // Constructed with a logical source ID, actual version, expected range,
    // contributed record kinds and the known registration failure reason.
    fn search_document(&self, query, filter?, cursor?, limit?, ctx) -> ToolResult {
        if filter.provider != Some(self.logical_id) || self.document_kinds.is_empty() {
            return Err(NotHandled);
        }
        if filter.types.is_populated() && !filter.types.intersects(self.document_kinds) {
            return Err(NotHandled);
        }
        return Ok(current_source_status_with_unavailable_or_partial_coverage);
    }

    fn search_recipe(&self, filter?, cursor?, limit?, ctx) -> ToolResult {
        if filter.provider != Some(self.logical_id) || !self.contributes_recipes {
            return Err(NotHandled);
        }
        return Ok(current_source_status_with_unavailable_or_partial_coverage);
    }

    fn read_evidence(&self, handles, ctx) -> ToolResult {
        let locator = ctx.observed_locators.get(handles.single())?;
        if locator.provider_id != self.logical_id { return Err(NotHandled); }
        if locator.source.is_provider_status() {
            return Ok(current_source_status);
        }
        return Err(Unavailable(self.reason)); // owned, not a dispatch decline
    }

    fn read_progress(&self, handles, ctx) -> ToolResult {
        let locator = ctx.observed_locators.get(handles.single())?;
        if locator.provider_id != self.logical_id || locator.kind != Quest {
            return Err(NotHandled);
        }
        return Err(Unavailable(self.reason));
    }
}
```

Unavailable-source registrations are diagnostics for the supported adapters in this document, not new mod support. A compatible adapter whose runtime becomes temporarily unavailable returns handled unavailable status; registration remains intact while it loads.

## Conditional registration and versioned adapter implementations

The selected integration ranges are grounded in [ClientAssistant](../../src/main/java/dev/packsage/client/ClientAssistant.java), dependency pins in [gradle.properties](../../gradle.properties), and loaded config bridge checks in [NeoForgeConfigCatalog](../../src/main/java/dev/packsage/knowledge/config/NeoForgeConfigCatalog.java). They define registration scope, not a claim that every admitted version has been live-tested.

```rust
fn create_toolbox(runtime, world, store) -> AdapterRegistry {
    let registry = AdapterRegistry::new();

    // Internal document/recipe coordinator declines qualified calls. Its
    // unqualified fan-out re-enters this registry with provider-qualified
    // requests, which the source adapters match themselves.
    registry.register(DiscoverySearchAdapter);

    // Register adapters only when the mod is loaded and in the selected range.
    // Keep optional classes behind lazy loading so absent mods are safe.
    if runtime.loaded("jei")
        && version_in_range(runtime.version("jei"), "[19.27.0.340,20)")
    {
        registry.register(JeiAdapter {
            // Follow PackSageJeiPlugin's current active runtime on every call.
            runtime_access: lazy_plugin_runtime_supplier,
        });
    } else {
        registry.register(UnavailableSourceAdapter {
            logical_id: "jei", mod_id: "jei",
            document_kinds: [], contributes_recipes: true,
            reason: current_loaded_version_gate_status,
        });
    }

    if runtime.loaded("ftbquests")
        && version_in_range(runtime.version("ftbquests"), "[2101.1.36,2101.2)")
    {
        registry.register(FtbQuestsAdapter {
            // Same logical ID ftb_quests; current file/team resolved each call.
            runtime_access: lazy_current_client_quest_file_supplier,
        });
    } else {
        registry.register(UnavailableSourceAdapter {
            logical_id: "ftb_quests", mod_id: "ftbquests",
            document_kinds: [Quest], contributes_recipes: false,
            reason: current_loaded_version_gate_status,
        });
    }

    if runtime.loaded("patchouli")
        && version_in_range(runtime.version("patchouli"), "[1.21.1-92,1.21.1-94)")
    {
        registry.register(PatchouliAdapter {
            runtime_access: lazy_current_book_registry_supplier,
        });
    } else {
        registry.register(UnavailableSourceAdapter {
            logical_id: "patchouli", mod_id: "patchouli",
            document_kinds: [Guide], contributes_recipes: false,
            reason: current_loaded_version_gate_status,
        });
    }

    // Optional adapter construction/reflection/linkage failures register the
    // corresponding status adapter with the concrete failure reason.
    // The runtime gate strips only the recognized trailing -NEOFORGE suffix
    // and rejects malformed/unknown versions.

    registry.register(ConfigAdapter {
        // This reader rechecks versions and reflective field availability.
        // Loaded access: NeoForge [21.1.202,21.2), FML [4.0.41,5).
        // Unsupported bridge: saved-file path remains available and partial.
        catalog: current_neoforge_config_reader,
        roots: current_instance_config_roots(world),
    });

    // Native source handles recipes, inventories and all six registry selectors.
    // This target branch is Minecraft 1.21.1.
    registry.register(MinecraftAdapter { runtime });

    registry.register(HistoryAdapter { store, world });

    return registry;
}
```

Each adapter supplies descriptor metadata when registered: stable logical source ID, availability/version information, document/recipe/registry contributions, and public target schema contributions. Registration collects metadata and rejects duplicate active source/type identities. It does not register separate matching callbacks or populate a target-to-handler switch. Source adapters match every request themselves. Config is registered for its dedicated tools only and is omitted from general discovery metadata.

The registration shows one implementation per admitted range: JEI `[19.27.0.340,20)`, FTB Quests `[2101.1.36,2101.2)`, and Patchouli `[1.21.1-92,1.21.1-94)`. Each keeps its stable logical source ID (`jei`, `ftb_quests`, `patchouli`). No extra adapter version or unverified range is implied. Future implementations can register under the same logical ID with mutually exclusive gates; dispatcher logic remains unchanged.

## Navigation and lifecycle remain explicit

Player-click navigation is not another model-facing tool. JEI, FTB and Patchouli expose player-click navigation as an internal adapter operation outside the model methods. On a click, resolve the observed navigation locator in the current scope, refresh its current target through the owning adapter, then execute the provider UI action on the client thread. Do not mistake detached recipe/book/quest records for live navigation targets.

Keep world/resource/locale/team/provider-runtime/request-generation invalidation. Dispose old adapter instances and locators when their owning world/runtime changes, rebuild registrations where necessary, and retain the current scope checks before publication. Runtime availability must be checked at execution, not inferred forever from successful startup registration.

## Appendix: current-source mapping and implementation gaps

The main sections specify the final contract. These notes map the source baseline onto that result; they do not constrain the target behavior.

| Current source | Target migration |
| --- | --- |
| `PackSageTools`, `KnowledgeService.execute/search/eligible/PROVIDER_KINDS` | Expose twelve signatures; replace `search_pack` with `search_document`, `search_recipe`, `search_registry`; move matching into adapters and metadata-driven fan-out into `DiscoverySearchAdapter`. |
| `KnowledgeService.observed` | Replace retained evidence payloads with scope-bound locators whose owners reread live sources. Dispatch `read_evidence` and `read_progress` one validated handle at a time. |
| Native inventory/storage readers and menu shortcut | Provide live opened-menu and target/entity/ridden access; add entity equipment/cargo and item/block/enchantment/mob_effect/potion/dimension/dimension_type registry branches; replace captured-page cursors with current positional continuations. |
| Native registry tag matching | Generalize tag membership to registries whose holder APIs expose it; keep `filter.tags` invalid for `item_tag` definitions. |
| `JeiProvider`, `FtbProvider`, `PatchouliProvider` | Move provider matching into `search_recipe`/`search_document`, preserve native JEI focus, live FTB team progress and current Patchouli page visibility/decoding. Reread evidence instead of returning observed record bodies. |
| `ConfigProvider` and `AssistantRuntime.executeTool` | Keep config-specific operations and saved-file bounds out of general discovery; register history in the adapter chain and remove separate history routing. |

Compatibility evidence and links above were checked against repository source and test definitions. This document changes no runtime source and does not claim live verification across every admitted mod version. Focused implementation verification should cover fresh data across pagination, target ownership/access loss, all six registry kinds, handled-vs-NotHandled chaining, and current source version gates. No tests were run for this documentation-only revision.

### Version-gate evidence from the source baseline

The one established implementation is JEI 19.x on Minecraft 1.21.1. The runtime gate in `ClientAssistant` admits `[19.27.0.340,20)`, checks loaded mod ID `jei`, and removes a trailing `-NEOFORGE` before parsing the version. `gradle.properties` compiles against `19.27.0.340`; the automated gate tests exercise versions `19.27.0.340`, `19.57.0.450`, and `19.99.99.999` as accepted boundaries/examples, and reject `19.27.0.339` and `20.0.0.0`. These tests establish range-parser behavior, not live compatibility of every JEI release in the admitted range. `PackSageJeiPlugin` is discovered through JEI's plugin lifecycle. On runtime available it constructs the provider around that actual runtime; on runtime unavailable it invalidates it. The provider is exposed as a logical `jei` source.

Only one FTB Quests implementation exists. The gate admits `[2101.1.36,2101.2)`, checks mod ID `ftbquests`, and `gradle.properties` pins the compile dependency to Curse Maven file `8885017`; FTB Library, FTB Teams and Architectury are compile-only dependencies pinned to file IDs `9008089`, `8724782` and `8492726`. These dependency pins do not create separate runtime adapter versions. Gate tests accept `2101.1.36` and `2101.1.37`, and reject `2101.1.35` and `2101.2`. No V2 class or second version range exists in the repository, so the pseudocode should show one `FtbQuestsAdapter` and label the admitted range rather than invent version branches.

One Patchouli implementation exists. The runtime gate admits `[1.21.1-92,1.21.1-94)`, checks mod ID `patchouli`, and the compile dependency is Curse Maven file `6842247` (the `patchouli_version=1.21.1-92` property is recorded but `build.gradle` uses `patchouli_file_id`). The gate accepts `1.21.1-92` and NeoForge-suffixed `1.21.1-93-NEOFORGE`, and rejects `1.21.1-91`, `1.21.1-94`, `1.21.1-93-FORGE`, malformed strings and unknown versions. Those tests validate the gate only. There is no second Patchouli adapter/version class.

### Historical handle migration

`read_evidence` accepts game-evidence locators, including inventory summaries, storage entries and recipe-plan routes; saved exchanges keep their dedicated `read_history` path.

| Current handle shape/source | Required locator or migration |
| --- | --- |
| `native:item:<id>`, `block:<id>`, `tag:<id>`, raw recipe ID | Keep source identity; reread the actual registry/tag/recipe source. |
| `inventory:<observation-id>:<section>:<slot>` | Store player/section/slot metadata; observation UUID is not source data. |
| `inventory-summary:<observation-id>` | Store player summary locator; regenerate current selected locations. |
| `storage-entry:opened:<slot>` | Add menu/owner identity; current handle alone can collide across opened menus. |
| Targeted block/item component entries | Store scoped target/access path and entry location; validate current owner before reading. |
| Recipe-plan record retaining a recipe's original handle | Give the derived record its own query-and-route locator; raw recipe ID loses requested quantity/depth and can overwrite an ordinary recipe observation. |
| Error/status records | Reread source status where resolvable, otherwise return explicit unavailable; never replay a historical failure as current evidence. |

Derived outputs use distinct locators. Keep handles opaque and bound their retention. Historical exchanges remain historical and do not gain fresh live locators retroactively.


