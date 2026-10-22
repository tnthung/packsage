# PackSage v2: world model for the Minecraft adapter

Date: 2026-10-22.

Starts the `feat/packsage-v2` rebuild described in the [Minecraft-only baseline](../roadmap/2026-10-22-v2-minecraft-only.md). Mod adapters return later on top of the structure defined here.

## Intent

The long-term goal is an assistant that understands everything that has happened in the player's world and answers with every piece of information it can reach. This first step covers vanilla Minecraft in singleplayer.

The player should be able to:

1. Say "I built an iron farm here" and later ask "where was my iron farm?"
2. Stand in a storage room and ask "where is my stone?", and see the chest highlighted.
3. Ask "why isn't my iron farm working?" and get reasoning from the farm's actual state, including its recent history.
4. Ask "how can I make my armor stronger?" and get the enchantments each worn piece can still take, with mutual exclusions respected.

The assistant answers from what it reads in the world, what the journal recorded and what the player said. It does not guess. When the facts don't explain something, it says so.

## Goals

- Read the live world on the integrated server: blocks, block state, mobs with their hidden state, and every inventory, including inventories nested inside items.
- Record what happens in the world into a per-world journal, automatically and without affecting game performance.
- Keep player-reported achievements and places as notes the model saves on its own.
- Let answers highlight blocks and mobs in the world.
- Define capability interfaces that mod adapters can implement later, with results that merge across adapters.
- Keep a boundary that a server-side component can implement later.

## Non-goals

- Multiplayer and server-side support. Only the boundary is designed now.
- Mod adapters. They are rebuilt later against these interfaces.
- Material-aware planning ("can I afford this?"). The model still combines plans with inventory reads itself.
- Raw SQL access for the model.
- Built-in checks for specific farm or machine designs.

## Anti-goals

The design has failed, even if it works, if any of these happen:

- The game stutters because of recording or reading. The game thread never waits on disk, the database or the model.
- Anything in the world changes because PackSage read it. Opening a container item for reading must not create or modify storage.
- An absent result is presented as proof of absence when coverage was partial.
- A player note is presented as a fact the game confirmed.
- The player is interrupted about memory. Note-taking is invisible unless the player asks.

## Constraints

- NeoForge 1.21.1, Java 21, client mod. In singleplayer the integrated server runs in the same process and is the authoritative source for every live read and every recorded event.
- Results reach the model as JSON, so every model-facing filter is data, never a function.
- Every result reports how complete it is (coverage) and why it stopped, if it stopped early.
- Existing persistent-navigation rules apply to the new link targets ([persistent navigation](2026-10-18-persistent-navigation.md)).

## Architecture

### Capabilities

An adapter is identified by an `AdapterId`, such as `minecraft`. It implements any subset of these capability interfaces. The registry routes each tool call to the adapters that implement the relevant capability.

| Capability | Responsibility | Combination across adapters |
|---|---|---|
| `WorldAccessor` | List blocks and entities around an anchor, summarize blocks, resolve anchors | Lists are combined and deduplicated by identity |
| `BlockInspector` | Facts about one block | Merged: one section per adapter |
| `EntityInspector` | Facts about one entity | Merged: one section per adapter |
| `InventoryReader` | Inventories of a holder, their entries, facts about one item | Inventories combined; item facts merged |
| `Journalist` | Search past events and notes; save, update and delete notes | One journal per world |
| `ProgressReader` | Progress trees: advancement tabs now, quest books later | Trees and entries combined; entry facts merged |
| `StatisticReader` | Counters: mined, crafted, killed, distances, play time | Combined; mod counters later |
| `RecipeInspector` | Recipe search and route planning (existing native readers) | As today; mod adapters later |
| `RulesReader` | Registries, enchantments and eligibility, effects, attributes, dimensions (existing native readers) | As today |

The existing `search_inventory`, history and config tools keep working during the rebuild. The plan decides whether they move into these capabilities or stay as they are.

### The server boundary

All live reads go through these capability interfaces as named queries with plain-data arguments and results. In singleplayer, reads run on the integrated server thread. Results are copied into plain records before returning, and bounds are enforced there. A later server-side component implements the same queries remotely.

**No policy limits.** PackSage reads everything the integrated server holds, including containers out of reach, locked containers, other players' items and every mob, even while the world is open to LAN with others connected. A "no" in an answer means the thing genuinely is not there or cannot be read, never that a rule forbade reading it. Bounds exist only to pace work: a bounded read returns PARTIAL with a cursor that continues it, never a refusal. The existing storage reader's LAN, other-player, reach and lock refusals are removed.

On a multiplayer server, the world, inventory, entity, progress and journal tools report unavailable. Losing today's client-side inventory reads on servers is accepted for v2; the player only plays singleplayer.

*My call:* a large read is split across server ticks with a work budget of about 5 ms per tick, so it never causes a long tick even when the game keeps running behind the chat. Each slice copies its part. The result reports the game-time span it was read over, because the world can change between slices.

### Shared types

```rust
struct BlockPos { dim: String, x: i32, y: i32, z: i32 }

#[derive(Default)]
enum Anchor { #[default] Player, Entity(Uuid), Block(BlockPos), Note(NoteId) }

struct KindFilter { ids: Vec<String>, tags: Vec<String>, text: Option<String> }

struct Page<T> { items: Vec<T>, next_cursor: Option<Cursor>, coverage: Coverage, note: Option<String> }
enum Coverage { Complete, Partial, Unavailable }

struct Section { facts: Facts, coverage: Coverage, note: Option<String> }   // one adapter's contribution
```

- `Anchor::Note` resolves to the note's position, so "check around my iron farm" works.
- A `Page` that stops early because of a bound, unloaded chunks or a failure is `Partial` and says why in `note`.
- Merged results are `BTreeMap<AdapterId, Section>`. If one adapter fails, its section says so, and the other sections still return.

## Reading the world

```rust
struct BlockHandle  { kind: String, pos: BlockPos }
struct EntityHandle { kind: String, uuid: Uuid, pos: (f64, f64, f64), name: Option<String> }

struct Block  { handle: BlockHandle, facts: BTreeMap<AdapterId, Section>, inventories: Vec<InventoryInfo> }
struct Entity { handle: EntityHandle, facts: BTreeMap<AdapterId, Section>, inventories: Vec<InventoryInfo> }

trait WorldAccessor {
    fn summarize_blocks(&self, center: Anchor, radius: f64) -> Result<BlockSummary>;   // counts by kind, notable blocks
    fn list_blocks(&self, center: Anchor, radius: f64, kind: KindFilter, cursor: Option<Cursor>) -> Result<Page<BlockHandle>>;
    fn list_entities(&self, center: Anchor, radius: f64, kind: KindFilter, cursor: Option<Cursor>) -> Result<Page<EntityHandle>>;
}
trait BlockInspector  { fn inspect_block(&self, pos: &BlockPos) -> Result<Option<Facts>>; }   // None: nothing to add
trait EntityInspector { fn inspect_entity(&self, uuid: Uuid) -> Result<Option<Facts>>; }
```

- **Blocks.** Reads cover loaded chunks only. Unloaded chunks inside the radius are counted and make the result partial. `summarize_blocks` gives counts by kind and lists notable blocks with positions: containers, beds, workstations, fluids, light sources and redstone components. Block facts include the block state and light levels.
- **Entities.** Facts include the state the game decides with. For villagers: profession, level, claimed bed and workstation, last slept, last worked, recent golem panic and gossip. For golem spawning, the relevant cooldown. Other mobs carry health, effects, owner or tamed state and age where they apply.
- **Diagnosis.** PackSage supplies facts. The model reasons with its own game knowledge and says when the facts don't explain the problem.
- **Inventories.** Block and entity inspection lists the inventories present (`InventoryInfo`) but never their contents; those are read through `InventoryReader`.

*My call:* radius defaults to 16 and may reach as far as chunks are loaded. A summary page holds up to 4,096 notable blocks and a list page up to 64 items; larger areas continue with a cursor.

## Inventories

### Inventory identity

```rust
struct InventoryId { adapter: AdapterId, section: String }       // written "minecraft/fuel"
struct InventoryInfo { id: InventoryId, label: String, entries: EntryShape, role: SlotRole }
enum EntryShape { Slots(u32), Keyed }                             // Keyed: bundles, later AE2 networks
enum SlotRole { Storage, Input, Fuel, Output, Equipment, Display }
```

Minecraft sections:

| Holder | Sections |
|---|---|
| Player | `minecraft/main` (36), `minecraft/armor` (4), `minecraft/offhand` (1), `minecraft/ender_chest` (27) |
| Chest, barrel, placed shulker box, hopper, dispenser, dropper, crafter | `minecraft/container` |
| Furnace, smoker, blast furnace | `minecraft/input`, `minecraft/fuel`, `minecraft/output` |
| Brewing stand | `minecraft/bottles` (3), `minecraft/ingredient`, `minecraft/fuel` |
| Campfire | `minecraft/cooking` (4) |
| Jukebox, lectern, decorated pot, chiseled bookshelf | `minecraft/container` |
| Donkey, mule, llama | `minecraft/saddle` or `minecraft/carpet`, `minecraft/chest` |
| Horse | `minecraft/saddle`, `minecraft/body_armor` |
| Armor stand, mobs | `minecraft/armor`, `minecraft/hands` |
| Item frame, allay, fox | `minecraft/held` |
| Villager | `minecraft/inventory` |
| Chest or hopper minecart | `minecraft/container` |
| Shulker box item | `minecraft/container` |
| Bundle | `minecraft/bundle` (keyed) |

*My calls:*
- **Double chest:** reading either half returns one 54-slot `minecraft/container` and names the other half's position.
- **Ender chest block:** reading it refers to the player's `minecraft/ender_chest`.

### Item paths

```rust
enum Root { Player, Block(BlockPos), Entity(Uuid) }
struct Step { inventory: InventoryId, entry: EntryKey, kind: String }    // kind: item expected at that entry
struct ItemPath { root: Root, steps: Vec<Step> }                         // the last step is the item itself
enum Holder { Root(Root), Item(ItemPath) }
```

For example, an iron ingot in a bundle, in a shulker box, in a backpack, in a mule's chest:

```
Entity(mule) / minecraft/chest#5 / sophisticatedbackpacks/backpack#12 / minecraft/container#3 / minecraft/bundle#0
```

Paths are capped at 6 steps.

### Two-step reading

```rust
struct ItemEntry {
    item: String, name: String,
    count: u64,               // total for this item id in the listed inventory or search
    variants: u32,            // distinct component sets under this id
    locations: Vec<ItemPath>, // up to 8 concrete locations
    holds_items: bool,        // can be stepped into
}
struct Item { path: ItemPath, facts: BTreeMap<AdapterId, Section>, inventories: Vec<InventoryInfo> }

trait InventoryReader {
    fn inventories(&self, holder: &LiveHolder) -> Vec<InventoryInfo>;
    fn open<'w>(&self, holder: &LiveHolder<'w>, section: &str) -> Option<Box<dyn LiveInventory + 'w>>;
    fn inspect_item(&self, stack: &ItemStack) -> Result<Option<Facts>>;   // merged across adapters
}
trait LiveInventory {
    fn get(&self, entry: &EntryKey) -> Option<&ItemStack>;
    fn entries(&self) -> Vec<(EntryKey, &ItemStack)>;
}
```

- **Listing** groups by item ID, with a total count, a count of variants and up to 8 sample locations. Listing never includes component details.
- **`inspect_item`** reads one path and merges every adapter's facts: enchantments, durability, potion contents, custom name, attribute modifiers and enchantment eligibility.

### Resolving a path across adapters

`LiveHolder` is a server-thread-only view of the player, a block, an entity or an `ItemStack`. It never leaves the read task. A core resolver walks a path inside one server task:

1. Resolve the root.
2. For each step, look up the `InventoryReader` of the step's adapter, open its section on the current holder, take the entry and check that the item's kind matches. The item becomes the next holder.
3. A missing adapter fails as `Unavailable` at that step. An empty entry or a kind mismatch fails as `Stale` at that step. Earlier steps still report what they found.

Each adapter only opens its own sections, so no adapter needs to know about another.

Opening for reading must not create or change anything. Each adapter's tests check this.

### `find_items`

`find_items(center, radius, filter, include_nested)` collects every inventory of every holder in range from all `InventoryReader`s. That includes the player's own, and items nested up to the path cap when `include_nested` is set. It returns grouped `ItemEntry` rows with paths. A later storage-network adapter is included automatically.

*My call:* one search page inspects up to 512 containers, then returns PARTIAL with a cursor that continues the search.

## Progress

Progress is current state (what is done, what is left, what unlocks what). The journal records when something happened.

```rust
struct ProgressTree { id: String, adapter: AdapterId, title: String, done: u32, total: u32 }   // advancement tab, quest chapter
struct ProgressHandle { id: String, adapter: AdapterId, title: String, status: Status }
enum Status { Done, InProgress, Available, Locked, Hidden }
struct Criterion { id: String, label: String, done: bool, done_at: Option<Instant> }
struct Progress {
    handle: ProgressHandle, description: Option<String>, criteria: Vec<Criterion>,
    requires: Vec<ProgressHandle>, unlocks: Vec<ProgressHandle>, rewards: Vec<String>,
    facts: BTreeMap<AdapterId, Section>,
}
trait ProgressReader {
    fn trees(&self) -> Result<Vec<ProgressTree>>;
    fn list_progress(&self, tree: Option<&str>, status: Vec<Status>, text: Option<String>, cursor: Option<Cursor>) -> Result<Page<ProgressHandle>>;
    fn inspect_progress(&self, id: &str) -> Result<Progress>;
}
```

The Minecraft adapter implements this for advancements, including per-criterion state such as the biomes still missing for *Adventuring Time*. *My call:* a hidden advancement is listed as hidden, without its title or criteria, until it is earned.

## Statistics

The game's own counters answer "how many diamonds have I mined?" exactly: blocks mined, items crafted, used, picked up and dropped, mobs killed and killed by, distances, and custom counters such as deaths, jumps and time played.

```rust
struct Statistic { category: String, subject: Option<String>, label: String, value: i64, unit: Unit }
// category: "mined", "crafted", "used", "broken", "picked_up", "dropped", "killed", "killed_by", "custom"
// subject: block, item or entity id; None for custom counters
enum Unit { Count, Distance, Time }

trait StatisticReader {
    fn list_statistics(&self, categories: Vec<String>, subjects: KindFilter, cursor: Option<Cursor>) -> Result<Page<Statistic>>;
}
```

The Minecraft adapter reads the player's server-side statistics. Distances are reported in blocks and times in seconds. Statistics count since the world began; the journal answers questions about when.

## Journal

### What is recorded

Events from the integrated server:

- block placed and broken, by the player and by explosions;
- container transfers: item and count moved into or out of a container by the player;
- deaths (player, named mobs, villagers, tamed animals) with cause;
- kills by the player;
- dimension changes with the portal position;
- advancements earned;
- villager events: profession change, curing, conversion to a zombie villager, trade made;
- raids started and finished;
- first entry into a structure;
- player notes.

Every event has game time, wall-clock time, dimension, position or bounding box, actor and subject (block, item, entity type or advancement).

### Recording without stutter

- **Recorder.** Event handlers only push the event onto a bounded in-memory queue.
- **Writer.** A background thread drains the queue about once a second, merges runs and writes each batch in one SQLite transaction (WAL mode).
- **Run merging.** Consecutive events with the same actor, action, subject and dimension, less than 5 seconds apart and within 16 blocks of the run's bounding box, become one row. That row has a count, a time span, a bounding box and the exact positions packed into a blob. Container transfers and explosions merge the same way.
- **Overflow.** If the queue overflows, events fold into count-only rows instead of blocking.
- **Shutdown.** The writer flushes when the world closes.

### Storage

One SQLite database per world at `saves/<world>/packsage/journal.db`. It holds an event table indexed by dimension and time and by subject, an R-tree over bounding boxes, and a notes table. Everything is kept; there is no pruning yet. Exact-position questions use the R-tree to find candidate runs, then unpack only their positions.

*My call:* bundle the SQLite JDBC driver inside the mod (jar-in-jar).

### Querying

```rust
struct JournalFilter {
    time: Option<TimeRange>, area: Option<(Anchor, f64)>, dimension: Option<String>,
    types: Vec<EventType>, subjects: Vec<String>, text: Option<String>, notes_only: bool,
}
trait Journalist {
    fn search_journal(&self, filter: JournalFilter, cursor: Option<Cursor>) -> Result<Page<JournalEntry>>;
    fn remember(&self, note: NoteInput) -> Result<NoteId>;
    fn update_note(&self, id: NoteId, note: NoteInput) -> Result<()>;
    fn forget(&self, id: NoteId) -> Result<()>;
}
```

Results are bounded pages. Run rows come back as summaries ("broke 2,140 sand around … between … and …").

## Notes

- **When to save.** The model saves a note when the player reports something they did, achieved or made: "I mined 32 diamonds", "I built an iron farm here", "I beat the Wither". Questions and requests are not saved; history search still finds them.
- **What a note holds:** the player's words, time, position and dimension when the note is about a place, a few tags (for example `build`, `achievement`, `resource`) and a link to the originating chat message.
- **Trust.** A note is marked as what the player said. The model may cross-check it against the journal, such as diamonds actually mined or blocks placed at that spot. It never presents a note as confirmed by the game.
- **Corrections.** "I moved my iron farm" or "forget the old base" updates or deletes the existing note instead of adding another.
- **Invisible.** Saving is not announced. The player sees notes only by asking.

## Highlights

New persistent navigation targets: `world-block|<dimension>|x,y,z` and `world-entity|<uuid>`.

- **Opening** a target checks the current world, dimension and existence. A missing block or mob reports an unavailable link instead of highlighting something else.
- **Blocks** get a see-through outline for 30 seconds, drawn in `RenderLevelStageEvent`.
- **Mobs** get the glowing effect, applied client-side only, for 30 seconds.
- **Coordinates** always appear in the answer text as well.

## Model-facing tools

| Tool | Backed by |
|---|---|
| `inspect_area(center?, radius?)` | `summarize_blocks` plus `list_entities` |
| `list_blocks(center?, radius?, filter, cursor?)` | `WorldAccessor` |
| `list_entities(center?, radius?, filter, cursor?)` | `WorldAccessor` |
| `inspect_block(pos)`, `inspect_entity(uuid)` | merged inspectors |
| `list_items(holder, inventory?, filter?, cursor?)` | `InventoryReader` |
| `inspect_item(path)` | merged `InventoryReader::inspect_item` |
| `find_items(filter, center?, radius?, include_nested?)` | all `InventoryReader`s |
| `list_progress(tree?, status?, text?, cursor?)`, `inspect_progress(id)` | `ProgressReader` |
| `list_statistics(categories?, subjects?, cursor?)` | `StatisticReader` |
| `search_journal(filter, cursor?)` | `Journalist` |
| `remember(text, at?, tags?)`, `update_note`, `forget_note` | `Journalist` |
| `search_recipe`, `plan_recipe`, `search_registry`, `read_evidence`, history and config tools | existing, unchanged |

Results keep using the existing evidence wrapper (source, coverage, citable references, navigation targets), so citations, source pills and links keep working. *My call:* the shapes above become the record payloads inside it.

## Prompt

The tool descriptions and system prompt are rewritten for these tools as one policy, under the AGENTS.md prompt rules, including the note-saving rule and invisibility. They are validated in game.

## Tracking

*My call:* v2 work is tracked in the [v2 baseline note](../roadmap/2026-10-22-v2-minecraft-only.md), which grows into the v2 checklist. The existing tool roadmap and adapter feature checklist keep describing `feat/packsage-v1`.

## Left to the builder

- Exact bounds, other than the calls marked above.
- Which NeoForge events and hooks capture each journal event.
- SQLite schema details, indexes, how positions are packed, and how note IDs and journal references are written.
- The highlight's look.
- Which existing native readers move under which capability, and in what order the work lands.

## Acceptance

All in singleplayer, with the default prompt and no developer override:

1. "I built an iron farm here", then after restarting Minecraft, "where was my iron farm?" The reply gives the place, and its link highlights it.
2. In a room of at least 20 chests, with stone in one chest and in a shulker box inside another, "where is my stone?" The reply finds both, the nested one by its path, and highlights each chest.
3. At an iron farm with one villager unlinked from a bed and one recently killed by a zombie, "why isn't my iron farm working?" The reply cites both facts.
4. Wearing partly enchanted armor, "how can I make my armor stronger?" The reply lists the remaining enchantments per piece and respects exclusions.
5. Strip-mine 2,000 sand quickly and set off TNT. Frame time does not visibly change, and the journal shows the runs and the explosion.
