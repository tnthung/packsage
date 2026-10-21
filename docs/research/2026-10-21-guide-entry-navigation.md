# Guide entry navigation APIs

Date: 2026-10-21.

Scope: targeted source and bytecode inspection for player-click navigation from Modonomicon, GuideME, Immersive Engineering and Actually Additions evidence into the owning in-game viewer. Bytecode was read with `javap` from the installed Packsage profile JARs (Modonomicon 1.120.7, GuideME 21.1.19, Immersive Engineering 1.21.1-12.4.2-194, Actually Additions 1.3.26) and the Modonomicon 1.120.4 compile dependency. This is source/API research, not installed acceptance. It extends the [persistent guide navigation research](2026-10-18-persistent-guide-navigation.md); the shared resolver contract is unchanged.

## Shared seam

`GuideDocumentReader` already declares `navigationTargets`, `refreshNavigation` and `open`; only Ponder overrides them among these readers. `GuideDocumentProvider.refreshNavigation` runs the reader on the client executor and accepts only a same-provider, same-scope result; `open` checks the active scope and maps `RuntimeException`/`LinkageError` to `ERROR`. `GuideDocumentProvider.documentRecord` holds the read fragment when it asks for targets, so a fragment-aware target overload is local to the guide package.

None of the four mods validates destinations before opening. Each fails silently, opens a substitute or throws on a missing or locked destination, so the reader must revalidate before every open.

## Modonomicon

- Reader candidate ID: `bookId|categoryId|entryId`. The reader compiles against the Modonomicon API (`build.gradle`, 1.120.4) and already evaluates category unlocks, entry display state (`HIDDEN`/`LOCKED`/`UNLOCKED`) and per-page unlocks. Version subclasses: legacy below 1.101.0, intermediate 1.101.0 to before 1.102.0, base from 1.102.0.
- Open: `com.klikli_dev.modonomicon.client.gui.BookGuiManager.get().openEntry(ResourceLocation bookId, ResourceLocation categoryId, ResourceLocation entryId, int page)`; signatures are identical in 1.120.4 and 1.120.7. Older ranges could not be inspected locally.
- `BookGuiManager` performs no unlock or display-state checks and opens hidden or locked entries. Opening sends `BookEntryReadMessage`, which marks the entry read and can satisfy read-based unlock conditions, matching an ordinary in-book click.
- A missing book throws `IllegalArgumentException`; unbuilt books can show Modonomicon's error screen; a missing or locked page number logs a warning and keeps the current page. `CategoryLinkBookEntry` opens a category rather than content.
- Category membership can change between pack versions; book and entry IDs are the durable identity.

## GuideME

- Reader candidate ID: `guideId#pageId`, both ResourceLocations. Access is reflective (`guideme.Guides.getById`, private page maps); tests compile fake `guideme.*` classes.
- Open: public `guideme.GuidesCommon.openGuide(Player, ResourceLocation guideId, PageAnchor)`, with public record `guideme.PageAnchor(ResourceLocation pageId, String anchor)` and `PageAnchor.page(ResourceLocation)`. Internal `GuideMEClient`/`GuideNavigation` methods are not used.
- A missing guide only posts a chat message, a missing page shows GuideME's not-found page and a missing anchor logs a warning and stays at the top. `openGuide` returns no status.
- Anchors: public `guideme.compiler.AnchorIndexer(LytDocument).get(String)` matches explicit `<a name>` anchors exactly, or heading text normalized as lowercase (`Locale.ROOT`), trimmed, with each whitespace run replaced by `-`. The requested anchor is not normalized. `PageCollection.getPage(ResourceLocation)` compiles a `guideme.GuidePage(sourcePack, id, document)`; `pageExists` checks the current page set including development overrides.
- Page IDs come from asset paths and survive reloads; guide and page objects are replaced on reload. Heading anchors follow the active language's page text.

## Immersive Engineering

- Reader candidate ID: the manual entry ResourceLocation from `ManualInstance.contentsByName`; visibility uses `getTreeNode()` plus `manual.showNodeInList(node)`. Access is reflective; tests compile fake `blusunrize.*` classes.
- Open: `ManualHelper.getManual().getGui()` returns a `ManualScreen` (null after a failed reload, with a red chat message). In-manual links position the screen through `setCurrentNode(Tree.AbstractNode)`, the public `page` field and `fullInit()`; `Minecraft.setScreen` shows it. The manual item is not required.
- `getEntry` returns null for a missing entry and IE never checks before positioning, so a missing entry throws. Anchors resolve through `getPageForAnchor`, which silently returns a default for an unknown anchor; page counts shift with language.
- Entry keys come from autoload JSON and code registration and survive restarts; `reload()` replaces every `ManualEntry` and tree node.

## Actually Additions

- 1.3.26 ships its booklet as Patchouli book `actuallyadditions:booklet`. The legacy `GuiBooklet`/`BookletUtils` classes are absent and `ActuallyAdditionsAPI` chapter lists are unused at runtime.
- Reader candidate ID: the Patchouli entry ResourceLocation; the reader reaches the live `BookContents` reflectively and filters `shouldHide()`/`isLocked()` entries.
- Open: client `PatchouliAPI.get().openBookEntry(ResourceLocation book, ResourceLocation entry, int page)`. `BookContents.setTopEntry` divides the page index by two to choose the spread (verified in both cached Patchouli JARs), so callers pass a page index. A missing entry throws a `NullPointerException`; a locked entry opens the book without the entry.
- The installed booklet has five entries with no advancement locks; a resource reload rebuilds the contents.

## Recommendations

| Provider | Durable `targetId` | Notes |
|---|---|---|
| Modonomicon | `bookId|entryId` | Resolve the current category from the book; open only `UNLOCKED` content entries in an unlocked category. |
| GuideME | `guideId#pageId` or `guideId#pageId#anchor` | Derive anchors only from plain-text headings; verify with the compiled page's anchor index; never pass a null `PageAnchor`. |
| Immersive Engineering | `entryId` | Entry start only; page indices and anchors are not stable or not verifiable. |
| Actually Additions | `entryId` | Book ID is fixed; pass page 0 through the Patchouli client API. |

Each reader takes a package-private opener seam (client-thread check plus the native open call) so automated tests never construct real screens. The default openers stay reflective where the reader is already reflective.

## Evidence limits

Modonomicon below 1.120.4, GuideME versions other than 21.1.19 and the timing of IE's `fullInit()` relative to screen initialization are unverified. Screen behavior requires installed checks across a full Minecraft restart.
