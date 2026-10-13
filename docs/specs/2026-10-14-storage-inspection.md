# Storage inspection specification

Date: 2026-10-14

## Requested scope

The player requests opened-container, targeted-storage and held-storage inspection, with room for future version-specific readers for AE terminals, backpacks, drawers and similar mods. This adds an internal reader boundary, not a public registration framework or all those integrations immediately.

## Read contract

- Add on-demand `read_storage` with target `opened`, `targeted` or `held`, optional literal name/ID text and item-ID filters, bounded page size and an opaque continuation cursor.
- Use an internal `StorageReader` request/result interface based on generic evidence rather than requiring every future reader to expose an `ItemStack` or finite vanilla inventory. Mod readers can later supply their own version-specific captures and paging while retaining a stable logical provider identity.
- For opened storage, pressing the configured PackSage shortcut from a container screen copies visible non-player menu slots before closing the container normally and opening chat. Exclude the player's own inventory menu and player-inventory-backed slots. Preserve normal text input when the shortcut would type into a focused text field.
- Opened results are explicitly partial, last-opened menu observations with the original title/type and capture time. They do not prove complete chest synchronization or whole-network totals. No menu-to-target position association is inferred from the crosshair.
- Targeted vanilla closed containers report client-visible identity/position when available and contents unavailable/unknown. Do not call block-entity inventory getters or generate/unpack loot. A future adapter may expose targeted contents only through a verified data contract.
- Held storage reads supported synchronized vanilla container/bundle components from main hand and offhand. Enumerate direct contents only. Unsupported item storage remains unknown. Stored component length is not reported as inventory capacity.
- Capture and serialize game state on its owning client thread. Retain immutable copied evidence. Do not perform server/world-file reads, open containers automatically, move items or change gameplay state.

## Freshness and bounds

- Keep the last explicit opened observation for at most five minutes. Preserve its capture timestamp on later reads. Match world session, resources/locale, player/team and provider runtime; rebind returned evidence to the current request generation.
- Clear observations on world/player/resource/provider invalidation. Continuations bind to the exact request scope, target and filters and retain one immutable observation while paging.
- Retain at most eight continuation observations. Bound native capture to 256 entries and page size to 32 entries. Return one evidence record containing page metadata and an entries array; keep page payload below 96 KiB so the existing generic tool-output limit cannot discard it after advancing a cursor.
- Mark oversized component metadata unknown/truncated and coverage partial. Distinguish an observed empty supported component from unknown storage. Do not recursively decode arbitrary nested storage.

## Acceptance

Tests cover player-slot exclusion, duplicate slots, held component contents and unsupported/empty distinction, original timestamps, scope rebinding/invalidation, expiry, stable paging, cursor query/scope mismatch and output-budget pagination. Schema/dispatch tests exercise all targets and reject malformed bounds. The assistant runs the full automated suite. Live checks are recorded separately and do not imply unsupported mod integrations are complete.
