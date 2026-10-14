# Inventory and storage inspection validation

Date: 2026-10-14.

## Build and automated checks

- Inventory implementation: `cee2f20`.
- Storage implementation: `26a0924`.
- `gradlew.bat test build --console=plain` succeeded after the final review fixes: 400 tests discovered, 396 passed, four existing Windows symlink skips, zero failures or errors.
- Focused inventory tests passed before its separate commit. Storage/tool/knowledge/grounding tests passed together; review regressions first failed for nested storage metadata and consumed continuation cursors, then passed after correction.
- Covered slot identity and duplicate preservation, player-slot exclusion, held shulker/bundle contents and merged non-storage metadata, empty versus unknown storage, original timestamps, scope rebinding/invalidation, five-minute expiry, stable continuation pages, query/scope mismatch, corrected retries, byte-budget paging and tool schema/dispatch.
- Native targeted capture requires an actual block hit and does not read block-entity inventories. Storage metadata omits nested vanilla container/bundle contents before encoding.
- Manual style review and `git diff --check` passed; no formatter was used.

## Installed artifact

ATM10 8.2, Minecraft 1.21.1, NeoForge 21.1.251. Minecraft was saved/exited normally before replacing `mods/packsage.jar`. Build, distribution and installed JAR hashes match:

`A73B101D1C993ED042803D18CFBCB303407674AB75748EA31EB6B46E54A5F342`

Only `OoO - PackSage Trial` is permitted for live checks. The original `OoO` world remains untouched.

## Live acceptance

All four validation exchanges completed using the player's existing GPT-6-Luna / medium selection, with search off and screenshots on. The saved replay in trial session `9a0f2cfe-56b6-4421-955f-08da7eee0fbc` confirms the actual tool calls and results.

| Check | Installed observation | Acceptance |
| --- | --- | --- |
| Inventory and armor | `read_inventory` with null identities returned 31 occupied records: seven hotbar stacks, 20 main inventory stacks and four armor pieces. The answer listed exact IDs and feet/legs/chest/head Unobtainium armor. The inventory screen also showed those four equipped pieces. | Passed |
| Unsupported held item and target | Separate `read_storage` held and targeted calls reported unknown contents for the sword/empty offhand and unavailable contents for the targeted grass block. The answer did not claim empty storage. | Passed |
| Opened-menu shortcut | Opened the existing Tier 3 Pocket Storage unit, then pressed F8. PackSage opened after normal container closure and returned `pocketstorage:psu_container`, its title and original `2026-10-13T22:47:02.046019Z` capture time in a later request. Player inventory slots were excluded. | Capture/lifecycle passed; custom display contents unsupported |
| Supported empty held bundle | Temporarily moved the existing empty bundle from main inventory slot 2 to empty hotbar slot 5 and selected it. `read_storage` held reported mainhand `minecraft:bundle`, supported bundle storage and complete direct contents with zero entries; offhand remained unknown. The answer distinguished observed empty from unknown. | Passed |

Pocket Storage visibly displayed eight custom item entries, but they were not exposed as standard non-player menu slots. The generic reader returned zero entries with partial coverage, and the answer explicitly said this did not prove empty storage. A dedicated Pocket Storage display reader is needed to enumerate those entries; this run does not claim that integration.

The bundle was returned to its original slot, the test hotbar slot was cleared and the original sword selection restored. No bundle contents were changed. The trial player remained at full displayed health and level 57. The final chat is paused; model, effort, search and screenshot preferences were preserved.

Nonempty vanilla opened-container and held shulker/bundle enumeration have automated native fixtures, but were not positively demonstrated with existing contents in this installed trial world. Closed-chest behavior was established from native client source and the implementation's absence of inventory access; the live targeted case was a grass block, not a chest. No AE, backpack or drawer compatibility is claimed.

## Scope limits

Opened menus remain partial visible-slot observations, not whole-network totals or proof that an empty container has synchronized. Closed native containers report contents unavailable. Held native support reads direct synchronized container/bundle components; unsupported mod formats remain unknown. The internal `StorageReader` boundary reserves room for version-specific readers without claiming AE, backpack or drawer integrations.

Player prompts and shortcut instructions: [Inventory and storage inspection](../testing/2026-10-14-inventory-storage-inspection.md).
