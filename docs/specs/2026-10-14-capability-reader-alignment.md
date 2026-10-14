# Capability reader alignment

Date: 2026-10-14

Status: implemented baseline. The [behavior-driven tool design](2026-10-14-behavior-driven-tools.md) proposes replacing the split model-facing contracts below with `search_inventory(target, filter?, cursor?, limit?)`. Review that document for the intended next design; this specification and its validation describe the existing implementation, not acceptance of the replacement.

## Scope

Align existing inventory and storage readers with the accepted feature-first roadmap. Preserve the shared pack, recipe, quest, config, guide and history tools. This work does not implement the unchecked mod integrations or registry catalogs in the roadmap.

## Inventory

- Introduce an internal `InventoryReader` request/result interface instead of wiring the model to a native-provider inventory callback. Return ordinary scoped evidence.
- Preserve `read_inventory` enumeration and exact item-ID filtering across occupied hotbar, main inventory, armor and offhand slots. Reject malformed namespaced item IDs.
- Include reusable opaque `slotReference` values and explicit section-local coordinates. References identify one immutable observation in the exact request scope, not just a coordinate. Report selected mainhand and offhand locations even when empty.
- Add `search_inventory` with nonblank literal text, selected fields (`name`, `id`, `tag`, `tooltip`), a page limit of 1–32 and an opaque cursor. Default fields are name and ID. Search currently covers vanilla player inventory and equipment; return that source scope explicitly. Additional inventory/storage sources stay behind this common contract when implemented.
- Capture synchronized tag membership and requested tooltip text on the client thread. Missing or truncated selected fields yield partial coverage, including an empty search result. Bound tooltip and component metadata.
- Exclude nested container/bundle contents from general inventory metadata. Inspect these through storage reads. Preserve other merged item components and an omission marker.
- Keep immutable search pages, original capture timestamps, at most eight retained cursor snapshots, and five-minute expiry. Reject changed filters, scope or expired cursors. Fresh reads capture fresh state.

## Storage

- Replace the model-facing `held` target with `item`. `read_storage(target=item, slotReference=...)` inspects the referenced slot; `opened` and `targeted` keep their existing meaning. Require the reference only for item reads.
- Resolve references on the owning client thread. Reject unknown/foreign/expired references and changed scope, slot contents, count or components. Matching the item ID alone is insufficient. Selected/held roles are metadata identifying normal slots.
- Read direct synchronized vanilla container and bundle components at arbitrary player inventory/equipment slots. Coverage pertains to the selected item; an unrelated unsupported offhand must not downgrade it. Unsupported mod item storage remains unknown.
- Keep the existing `StorageReader` boundary and bounded evidence envelopes. Mod-specific item/network readers can implement this operation without another model tool. Do not claim wireless-network access until a supported reader is installed.
- Preserve opened-menu partial coverage, original timestamps, cursor snapshots, target-only unknown results, output budgets and absence of gameplay mutation.

## Validation

Automated regression tests cover schema/dispatch, malformed IDs, source scoping, selected empty slots, duplicate coordinates across sections, search fields and paging, metadata omissions, arbitrary-slot component contents, changed/replaced items and cursor/reference invalidation. Run the full test/build suite.

Install the same verified JAR into the existing minimal Packsage fixture, FTB Evolution and ATM10 profiles. Use the existing fixture/trial worlds; ATM10 uses the backed-up `OoO - PackSage Trial` copy. Verify real tool-call/result logs for inventory, search, item storage and unchanged pack/quest/guide retrieval. Record actual versions and outcomes separately from unit-test evidence. Leave the original OoO world untouched.
