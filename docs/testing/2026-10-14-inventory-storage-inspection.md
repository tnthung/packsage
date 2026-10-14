# Inventory and storage inspection

Use the rebuilt PackSage JAR in Minecraft 1.21.1. The assistant runs the automated checks; these prompts check the installed game behavior.

## Inventory and armor

Open PackSage and ask: `Read my current inventory and armor. List my hotbar and equipped armor with exact item IDs and slot names.`

The answer should come from `read_inventory`, including occupied vanilla hotbar, main inventory, armor and offhand slots. Duplicate stacks in different slots remain separate. Empty slots are omitted. Curios and other extra equipment slots are not included yet.

## Opened storage

Open an existing container, wait for its contents to appear, then press the configured PackSage shortcut while the container is still open. Ask: `Read the container I just opened. List the visible items and their counts.`

PackSage captures the non-player menu slots before closing the container normally. It retains that observation for five minutes and reports its original capture time. A displayed menu is a partial view: it does not prove an entire storage network or an empty container. Opening PackSage while a printable shortcut is being typed into a focused search field keeps normal text input instead.

## Held and targeted storage

With an existing shulker box or bundle in either hand, ask: `Read storage held in both hands. List the direct contents and counts.` Supported synchronized vanilla item components can be read. Other backpack or storage item formats remain unknown until a reader supports their version.

Aim at a closed chest and ask: `Read the storage I am targeting. Say whether its contents are available.` The native client can report the target identity and position, but closed vanilla contents are unavailable. It does not open containers or read the server/world files.

## Extension boundary

`StorageReader` accepts opened, targeted and held requests with filters and bounded cursors. Future readers can supply mod-specific menu, item or virtual-network observations without requiring a vanilla `ItemStack` inventory. AE, backpack and drawer integrations are not claimed by the native reader.
