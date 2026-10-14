# Targeted closed-container reading

Research date: 2026-10-14

## Conclusion

Normal-player targeted reads of closed vanilla containers need an authorized
logical-server reader. The current client-only reader can report the
client-observed block identity and position, but a dedicated remote client
cannot inspect authoritative block entity contents by reading its own world
cache. There is one vanilla exception: an operator (permission level 2) can
use the existing debug block-entity query packet without a PackSage server
companion. It returns raw saved NBT and is a privileged admin path, not a
least-privilege survival read. A bounded optional companion remains the
appropriate path for ordinary authorized reads.

The work is moderate and bounded if the first release supports only explicitly
authorized vanilla block containers, does not force chunk loads, and refuses
containers with pending loot. Do not turn `IItemHandler` into blanket
permission to inspect arbitrary inventories or storage networks.

Scope checked: Minecraft 1.21.1, exact build NeoForge 21.1.202; installed ATM10
8.2 trial NeoForge 21.1.251. Exact merged source JARs for both builds were
available at [NeoForge 21.1.202 merged sources](../../build/moddev/artifacts/neoforge-21.1.202-sources.jar)
and [NeoForge 21.1.251 merged sources](../../build/moddev/artifacts/neoforge-21.1.251-sources.jar).
The listed vanilla container classes and NeoForge `IItemHandler` were
byte-for-byte equal between these artifacts. The debug-query, server packet
listener and NBT serialization classes were inspected in 21.1.202 only; no
cross-version equality claim is made for them. This is source comparison, not
a live test on either runtime.

## Established facts

### Client-only, integrated, LAN and dedicated server

PackSage's mod entry is declared `@Mod(value = PackSage.MOD_ID, dist = Dist.CLIENT)`
in `src/main/java/dev/packsage/PackSage.java`; the current tree has no payload
registration. `NativeStorageReader.captureTargeted()` reads the local client's
actual block hit, block state and position, then explicitly marks contents
unavailable. That is the right default for a remote client without a
server-side reader; the vanilla operator query described below is separate.

NeoForge's [1.21.1 sides documentation](https://docs.neoforged.net/docs/1.21.1/concepts/sides/)
distinguishes physical distribution from logical side: an integrated
singleplayer server is still a logical server, even though it shares the
client process. A client-only entry point does not itself make its client
world the authoritative server world. In LAN, the host has that integrated
server; another LAN player is still a remote client. For ordinary-player reads,
a dedicated server needs a companion or another verified authorized server
endpoint; the vanilla operator endpoint is a separate privileged option.

Therefore:

- **Private integrated singleplayer:** technically reachable only by
  deliberately dispatching work to the integrated logical server and obeying
  its thread/lifecycle. Existing `ClientLevel` reads are not that path.
- **LAN host:** same integrated-server opportunity, with publication and
  multiple viewers making per-player authorization important.
- **LAN guest or dedicated-server client:** contents require a compatible
  server endpoint and a response; client chunk/block-entity cache is not a
  substitute for server authority.
- **Dedicated server:** current client-only mod does not run there. An optional
  companion should be a separately loadable common/server component or
  companion mod; do not move PackSage's UI classes into a common entry point.

One implementation detail still needs a runtime check: PackSage's
`AssistantScreen.isPauseScreen()` can pause a private singleplayer integrated
server. Minecraft 1.21.1 `IntegratedServer.tickServer()` observes
`Minecraft.isPaused()` and takes `tickPaused()`, which skips normal world
simulation. However, `MinecraftServer.runServer()` continues to call
`waitUntilNextTick()`, which calls `runAllTasks()`, and NeoForge's
`ServerPayloadContext.enqueueWork()` submits work to the listener's main-thread
event loop. This source path supports (but does not prove end-to-end) that
serverbound read work can still drain during pause. Request/response completion
and UI behavior remain an integration test; do not auto-resume the game.

### Vanilla container read behavior

Exact source references below are class/method names in the two merged source
JARs named above.

| Case | Established 1.21.1 behavior | Read implication |
|---|---|---|
| Single chest | `ChestBlock` builds a menu from its `ChestBlockEntity`; `ChestMenu` has container slots followed by player slots. | After authorization and pending-loot checks, read the block-owned container slots directly and copy them; do not create/open a menu. Exclude player slots. |
| Double chest | `ChestBlock.MENU_PROVIDER_COMBINER.acceptDouble()` checks `canOpen()` on both halves, calls `unpackLootTable()` on both, and constructs a 54-slot `CompoundContainer`. | Preserve both-half lock checks; inspect both halves for pending loot before reading either half; represent both positions in provenance. |
| Locked chest | `BaseContainerBlockEntity.canOpen()` delegates to `canUnlock()`, which checks the lock against the player's main hand (and sends a locked message/sound on failure). The double-chest menu checks both halves. `Container.stillValid()` is a distance/block-entity validity check, not a full player access policy. | Never use inventory capability presence or `stillValid()` as permission. Initially deny locked containers, or use a reviewed pure lock predicate; avoid invoking `canOpen()` as a probe because failure sends a message and sound. Protection mods may add other interaction gates and need an established hook before support. |
| Ender chest | `EnderChestBlock.useWithoutItem()` uses the block entity and obstruction check, then creates a menu backed by the acting player's `PlayerEnderChestContainer`; it also awards a stat and angers nearby piglins. `EnderChestBlockEntity` is only the lid/open-count entity, not the item store. | Contents are player-specific. A targeted read may inspect only the requesting player's own ender inventory and must not confuse the block entity with the contents. Do not call normal open/menu flow solely to read, because it has gameplay side effects. |
| Loot-table container | `RandomizableContainerBlockEntity.getItem()` calls `unpackLootTable(null)` before returning a stack. `RandomizableContainer.unpackLootTable()` resolves and fills the table, clears its pending table key, and may fire the generate-loot advancement when given a `ServerPlayer`. `ChestBlock`'s normal open path intentionally unpacks loot. | On server, inspect `getLootTable()` first and return unknown/unavailable when non-null. Do not call `getItem()` on a pending-loot container. Generating contents during an assistant query changes game state and reveals a roll that ordinary play has not yet generated. |

The loot conclusion is specifically about unopened containers with a pending
loot table. A container with no pending table can be read slot-by-slot without
that particular generation path, after server authorization and loaded-state
checks. Other modded container getters may have their own behavior and need
individual contracts.

### Existing vanilla operator query

Minecraft already has a client-to-server block-entity query for the debug
"inspect server block" path. In 1.21.1, `DebugQueryHandler.queryBlockEntityTag`
sends `ServerboundBlockEntityTagQueryPacket(transactionId, pos)`; the matching
`ClientboundTagQueryPacket` carries a nullable raw `CompoundTag` back to the
client callback. `KeyboardHandler.copyRecreateCommand` uses this route for its
server inspection branch. PackSage could call the same public client query
handler from its current remote connection, so an operator can obtain a block
entity NBT snapshot without installing a PackSage server companion.

This is explicitly privileged: `ServerGamePacketListenerImpl.handleBlockEntityTagQuery`
responds only when `player.hasPermissions(2)` (command permission level 2 or
higher). A denied request gets no response, so the `DebugQueryHandler` callback
does not complete. The packet contains a client-chosen position and transaction
ID. The handler does not check range, crosshair hit, line of sight, container
lock, open-menu eligibility or protection-mod policy; it reads in the
requesting player's current server level. `Level.getBlockEntity()` reaches
`getChunkAt()` and the `LevelReader` default requests a full chunk with
`load=true`, so this admin path may load the target chunk. These semantics are
too broad for a normal survival feature and can have chunk-loading and data
exposure costs even for operators.

For vanilla chests, `BlockEntity.saveWithoutMetadata()` itself calls
`saveAdditional()` and serializes components; it does not call inventory
`getItem()`. `ChestBlockEntity.saveAdditional()` calls `trySaveLootTable()`
first and serializes the pending loot table key and seed instead of filling the
table or reading item slots. Thus the vanilla chest snapshot path is
side-effect-free with respect to pending loot generation, and its raw NBT can
distinguish pending loot from an already materialized inventory. This finding
is specific to vanilla chest serialization; arbitrary mod block-entity
`saveAdditional()` implementations may have different costs or side effects.
The raw NBT route also returns implementation metadata and is not a stable,
normalized item-list contract. Double chests require two position queries; the
vanilla debug helper holds one outstanding callback at a time, so a caller
must serialize them. The returned clientbound packet decodes NBT with a
2 MiB `NbtAccounter` budget, another reason not to treat this as an unbounded
generic reader.

Conclusion: this is a useful **optional operator/admin fallback** when the
user is already authorized for level-2 debug inspection. It does not remove
the need for a companion or other explicitly authorized server contract for
ordinary players. If exposed in PackSage, label the source as operator debug
NBT, preserve its raw/provenance limits, serialize double-chest requests, and
handle denied/time-out callbacks. Preserve its operator-only access policy and
distinguish raw block NBT from a normalized inventory snapshot.

### NeoForge capabilities and authorization

NeoForge's [1.21.1 capability documentation](https://docs.neoforged.net/docs/1.21.1/inventories/capabilities/)
defines `Capabilities.ItemHandler.BLOCK` as an **automation-accessible** block
inventory, and `ITEM` as an item-stack handler. It describes capability lookup
by level, position and optional side, plus registered providers that can
return a handler or `null`.

The exact `IItemHandler` source in both artifacts has `getSlots()` and
`getStackInSlot()` alongside `insertItem()` and `extractItem()`. Its Javadoc
requires callers not to mutate the returned stack from `getStackInSlot()`;
that is a read-only contract for that method. The API does not define who the
viewing player is, whether a locked UI may be bypassed, whether data represents
a whole network, or how to authorize a player-specific remote read. The block
capability context is a side direction, not an actor identity. Hence the API
is useful for explicit mod adapters, but capability existence does not grant
PackSage authorization to expose data to its user.

Likewise, arbitrary network inventories are not automatically covered. A
capability may expose a local automation face or a partial/filtered view; an
AE/RS network terminal may expose a separate query model, permissions, range,
channel state or paging semantics. A provider-specific adapter must state what
it observes and validate its access rules. Unsupported sources remain
unknown; do not silently aggregate them as complete inventory.

### Optional companion networking

The official [NeoForge 1.21.1 networking guide](https://docs.neoforged.net/docs/1.21.1/networking/)
and [payload guide](https://docs.neoforged.net/docs/1.21.1/networking/payload/)
document registered `CustomPacketPayload` types, stream codecs and
`RegisterPayloadHandlersEvent`. The versioned guide demonstrates a registrar
network version and client/server handlers; handlers run on the main thread by
default, and payload limits are bounded (serverbound under 32 KiB, clientbound
at most 1 MiB).

A narrow design can retain `read_storage` and branch only `TARGETED` to an
optional server provider. On connection, negotiate a small protocol version
and advertised read features before sending a targeted request. If no
compatible endpoint is advertised, return the current unavailable/unknown
result. Request payloads should contain only the target position and bounded
query/cursor identifiers; the server derives the player and dimension from
the authenticated network context rather than trusting client-supplied actor,
world or authority fields. The server captures an immutable bounded snapshot
on its owning thread and returns a response bound to request ID, scope and
position. Keep each wire response within the established page byte budget
(96 KiB), retaining one immutable bounded server snapshot and serving later
pages from a cursor; do not send a 256-entry capture as one response and rely
on client-side paging. Do not send a request to an unknown payload channel as
a discovery probe; the exact absent-companion disconnect/fallback behavior
still needs an integration test.

For each request, the server should verify the current player, same dimension,
already-loaded chunk, range and server-side target. Define a narrow read
authorization rule; vanilla lock checks alone do not establish protection-mod
permissions, so reject uncertain/protected containers until a compatible hook
is known. Do not load chunks, open a menu, move items, or invoke normal
interaction callbacks. Check pending loot for every constituent inventory
before reading slots. Keep initial support to standard vanilla block
containers with a reviewed policy; treat ender chest separately because it is
personal data behind a world block.

The current architecture provides useful seams: `KnowledgeService` dispatches
`read_storage` to `StorageReader`, `ClientAssistant` injects
`NativeStorageReader`, and existing paging/filter/scope limits are already in
place. But `NativeStorageReader.makePage()` currently stamps
`Authority.VIEWER`, and `Evidence.Authority` has no `SERVER` value. Server
results must retain their origin explicitly: server-side authority, requesting
actor/scope, dimension and position, capture time, provider identity and the
actual read policy. Reusing the schema and paging is reasonable; relabeling a
server snapshot as a viewer observation would not be.

## Difficulty and proposed scope

**Assessment:** moderate, with a meaningful security and lifecycle test cost.
The native vanilla read itself is small. Optional component loading,
payload-version negotiation, per-player authorization, fresh immutable server
evidence, source labeling and paused-integrated-server behavior are the work
that make it more than a getter.

The deployment scope changes the amount of work:

| Scope | Relative difficulty | Main limit |
|---|---|---|
| Remote client, ordinary player, no server reader | Not available from the client cache | Requires a companion or another verified authorized server contract. |
| Integrated singleplayer only | Smaller than the multiplayer feature | Can dispatch directly to the local logical server; still needs thread/lifecycle handling and the same lock, loot and snapshot checks. |
| Operator-only vanilla chest prototype | Smaller than a companion | Existing debug protocol avoids new networking, but needs NBT decoding, timeout/callback ownership and explicit admin scope. |
| Normal-player companion, vanilla containers only | Moderate | New optional protocol, authorization and integration checks; estimate below. |
| Arbitrary modded storage or AE/RS network totals | Separate adapter work | A generic container/capability getter does not establish access rules or complete contents. |

**Effort estimate (not measured):** roughly 3–5 engineering days for a narrow
vanilla-only vertical slice, including implementation, automated tests and
targeted singleplayer/LAN/dedicated integration checks. This excludes arbitrary
modded containers, ender chest, AE/RS network totals, and broad modpack
compatibility. A separate player-facing install/release workflow may add time.

**Safe first scope:** optional server companion; existing `read_storage`
schema; targeted standard chest/container blocks only; loaded chunks only;
same-dimension and range-checked server target; explicit lock authorization;
refuse pending loot; bounded immutable slot snapshot; server-specific evidence
authority/source metadata; graceful unsupported response if negotiation is
absent or incompatible. Defer Ender Chest until its personal-data and exact
interaction policy are independently specified. Keep arbitrary modded and
network stores unknown until their own adapter contracts are reviewed.

## Verification checklist

- [ ] Absent companion and version mismatch do not disconnect the client; no
  unsupported payload is sent before capability negotiation.
- [ ] Private integrated singleplayer completes a targeted read with PackSage's
  default paused state and with the game running; verify user-visible reply
  ordering and that the feature never changes pause state to make progress.
- [ ] Integrated host, LAN guest, and dedicated remote client receive only
  server-derived data for the authenticated requesting player and current
  dimension.
- [ ] Reject out-of-range, wrong-dimension, unloaded-chunk, stale-position,
  occluded/invalid-target and unauthorized/locked requests without loading
  chunks or opening containers.
- [ ] Single chest and double chest return exact contents once; double chest
  checks both locks and both loot-table states, with both positions represented
  in provenance.
- [ ] A chest with a pending loot table remains unavailable and unchanged
  before and after the read; verify both single and double chest cases.
- [ ] If the operator debug path is offered, permission-level-2 users receive
  raw chest NBT without loot generation; non-operators get an explicit local
  timeout/unsupported result rather than a hanging request. Verify the target
  chunk-load side effect is clearly disclosed or avoided.
- [ ] Normal gameplay opening still generates loot through vanilla behavior;
  the assistant reader itself does not generate it.
- [ ] Ender chest, if later included, returns only the requesting player's own
  inventory and does not award the open statistic, anger piglins, alter open
  counts or require a normal menu open.
- [ ] Capture stays on the server thread; serialized result is immutable,
  bounded, paginates consistently, expires/invalidates with scope, and labels
  server authority distinctly from viewer-observed opened menus.
- [ ] Existing client-only opened/held results and their authority labels stay
  unchanged; unsupported mod/network storage remains unknown.
- [ ] Compare build target NeoForge 21.1.202 and installed trial NeoForge
  21.1.251, then record live evidence separately from automated tests.

## Source notes and boundaries

Primary references are the versioned NeoForge 1.21.1 documentation linked
above, and exact merged sources from the two project build artifacts. Relevant
PackSage sources: [`PackSage`](../../src/main/java/dev/packsage/PackSage.java),
[`NativeStorageReader`](../../src/main/java/dev/packsage/knowledge/storage/NativeStorageReader.java),
[`StorageReader`](../../src/main/java/dev/packsage/knowledge/storage/StorageReader.java),
[`KnowledgeService`](../../src/main/java/dev/packsage/knowledge/KnowledgeService.java),
[`ClientAssistant`](../../src/main/java/dev/packsage/client/ClientAssistant.java),
and [`Evidence`](../../src/main/java/dev/packsage/knowledge/Evidence.java).
Relevant merged 21.1.202 source classes/methods: `DebugQueryHandler`,
`ServerboundBlockEntityTagQueryPacket`, `ClientboundTagQueryPacket`,
`ServerGamePacketListenerImpl.handleBlockEntityTagQuery`, `BlockEntity.saveWithoutMetadata`,
`ChestBlockEntity.saveAdditional`, `KeyboardHandler.copyRecreateCommand`,
`Level.getBlockEntity`, `Level.getChunkAt`, `LevelReader.getChunk`, and
`ServerChunkCache.getChunk`; plus these container references:
Minecraft `ChestBlock.MENU_PROVIDER_COMBINER`; `BaseContainerBlockEntity`
(`canOpen`, `canUnlock`); `ChestMenu`; `EnderChestBlock.useWithoutItem`;
`PlayerEnderChestContainer`; `RandomizableContainerBlockEntity.getItem`;
`RandomizableContainer.unpackLootTable`; `MinecraftServer.runServer()` and
`waitUntilNextTick()`; `IntegratedServer.tickServer()` and `tickPaused()`;
NeoForge `ServerPayloadContext.enqueueWork()` and `MainThreadPayloadHandler`;
and NeoForge `IItemHandler`.

The existing [inventory/storage specification](../specs/2026-10-14-storage-inspection.md)
already prohibits client-side block-entity inventory reads and loot generation.
The [validation record](../validation/2026-10-14-inventory-storage-inspection.md)
establishes current client behavior and the unsupported Pocket Storage display;
it does not validate closed-chest contents, server authorization, double-chest
handling, the vanilla operator query or the optional companion protocol. The
source review found no documentation contradiction: capability docs describe
automation access, while neither those docs nor `IItemHandler` define viewer
authorization. The cross-version comparison covered vanilla container classes
and `IItemHandler`, as described above; it does not prove full runtime
compatibility across the two NeoForge builds.
