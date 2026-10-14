# Inventory and armor inspection specification

Date: 2026-10-14

Status: historical implementation specification. See the [capability alignment](2026-10-14-capability-reader-alignment.md) for the later delivered search/reference contract and the [behavior-driven tool design](2026-10-14-behavior-driven-tools.md) for its proposed unified replacement. The behavior below remains evidence of the original scope, not the next model-facing API.

## Requested behavior

The player can ask PackSage to inspect the contents of their vanilla player inventory or the armor they are wearing without supplying item IDs first. Retrieval remains on demand and read-only; ordinary message context continues to include only the focused gameplay observation.

## Contract

- Extend `read_inventory`: omitted or null `identities` requests all occupied vanilla player slots. A nonempty list retains selected-ID filtering; reject malformed identities and lists beyond the existing 32-ID limit.
- Return each occupied stack separately, preserving duplicates in different slots. Include its registry ID, display name, count, section, section-local slot index and equipment slot when applicable. Distinguish hotbar, main inventory, armor and offhand; identify the selected hotbar slot.
- Include persistent merged component metadata using registry-aware serialization, so default attributes, durability and custom enchantments remain available. Report encoding failures as unknown rather than inventing metadata.
- Use copied, client-visible inventory state and the existing world/request scope checks. Empty successful inventories are complete observations; unavailable player/world state stays unavailable.
- Keep existing tool-output limits and mark bounded/truncated results partial. Do not include Curios or arbitrary storage networks in this extension.
- Container reading is being investigated separately. Do not infer closed-chest contents from item registry data or an unsynchronized block entity.

## Acceptance

Tests cover enumeration without identities, existing targeted-ID filtering, slot/section identity, duplicate stacks, armor/offhand and selected hotbar labels, merged metadata, empty/unavailable outcomes and scope invalidation. Tool schema and dispatch tests cover null/omitted identities and malformed/bounded input. The full automated suite is run by the assistant.
