# Guide entry navigation

Date: 2026-10-21.

Add persistent player-click navigation from Modonomicon, GuideME, Immersive Engineering and Actually Additions evidence into each mod's own viewer. This extends the [persistent source navigation contract](2026-10-18-persistent-navigation.md) without changing it: `NavigationTarget(scope, providerId, sourceHandle, targetId)` stays serialized as-is, `targetId` is the durable destination, and the existing runtime, service and provider guards stay mandatory. API evidence is in the [guide entry navigation research](../research/2026-10-21-guide-entry-navigation.md).

## Contract

- Every record that a reader returns for a currently visible document includes at least one navigation target. That target's `sourceHandle` is the record's candidate ID and its `targetId` is the provider's durable destination below. Readers emit no target when no reliable destination exists.
- `refreshNavigation` runs on the client executor. It parses `targetId` strictly: each ResourceLocation part must round-trip through `ResourceLocation.tryParse(...).toString()`, and extra or missing separators are rejected. It resolves the destination against current content and returns a fresh target in the requested scope whose `sourceHandle` is the current candidate ID. Otherwise it returns `null` for a wrong provider, malformed, removed, hidden or locked destination. It never substitutes another entry, page or anchor.
- `open` rejects a target outside the active scope as `STALE` and returns `UNSUPPORTED` off the client thread. It revalidates the destination against current content and requires `sourceHandle` to still name that destination; a changed destination is `STALE`. A destination that is missing, hidden, locked or whose viewer is unavailable returns `UNAVAILABLE` and makes no native open call. A successful native call returns `OPENED`. A native `RuntimeException` or `LinkageError` returns `ERROR`.
- Opening never creates `read_evidence` handles and never refreshes the historical answer's facts.
- Each reader's native open call and client-thread check go through a package-private opener seam, so automated tests never construct real screens.

## Destinations

- **Modonomicon:** `bookId|entryId`. Resolve the entry's current category from the loaded book. The destination is available only for a `BookContentEntry` whose category is unlocked and whose display state is `UNLOCKED`. Opening calls `BookGuiManager.get().openEntry(bookId, currentCategoryId, entryId, 0)`. That call marks the entry read, as an ordinary in-book click does. The destination is also unavailable if books are not loaded and built. All three Modonomicon version ranges share this destination format.
- **GuideME:** `guideId#pageId` opens the page and `guideId#pageId#anchor` opens a heading within it. Records always offer the page target. They also offer an anchor target when the cited fragment lies at or after a heading whose children are all plain text. The anchor is that heading's text, normalized as lowercase (`Locale.ROOT`), trimmed, with each whitespace run replaced by a single `-`. An anchor target is valid only when the current compiled page's `AnchorIndexer` finds it. A missing anchor makes that target unavailable, while the page target continues to work independently. Opening calls `GuidesCommon.openGuide(player, guideId, new PageAnchor(pageId, anchorOrNull))` and never passes a null `PageAnchor`.
- **Immersive Engineering:** `entryId`, at the start of the entry. IE fills its manual entries lazily, when the manual is first opened. Only when the player clicks a link (`refreshNavigation` or `open`) does PackSage load an uninitialized manual through IE's own `getGui()` path, as using the manual item would. Model reads never load it. The entry must then exist in the current manual and be shown in the list. Opening obtains the current `ManualScreen`; a null screen is `UNAVAILABLE`. It shows the screen and positions it at the entry's first page.
- **Actually Additions:** `entryId` within the fixed book `actuallyadditions:booklet`. The entry must exist in the current live contents and must not be hidden or locked. Opening calls `PatchouliAPI.get().openBookEntry(actuallyadditions:booklet, entryId, 0)`.

## Verification

Each reader gets automated tests for:
- targets on current records;
- a saved target resolving after content was recreated, with a different source handle;
- `null` for wrong provider, malformed, removed, hidden and locked destinations;
- open on the client thread, with exactly one native call carrying the exact arguments;
- `STALE`, `UNSUPPORTED` and `UNAVAILABLE` outcomes, with zero native calls;
- `ERROR` mapping.

GuideME tests also cover anchor derivation, the normalization rule, formatted headings producing no anchor, and an invalid anchor that leaves the page target intact. Modonomicon tests also exercise the legacy and intermediate subclasses through the shared path. Run the focused suites, the full Java 21 `gradlew.bat test build` and `git diff --check`.

Installed checks cover:
- a generated link for each provider opening the intended entry, page or anchor;
- the same saved links reopening after a full Minecraft restart;
- a locked Modonomicon entry refusing to open.

Record installed checks separately from automated results.
