# Chat UI player feedback fixes

Date: 2026-10-26. Branch: `t3/fix-chat-ui-feedback`. Starting commit: `40acb8f`.

Implements the four reported UI corrections and the subsequent sidebar paging requirements. The [player feedback specification](../specs/2026-10-26-v2-player-feedback.md) and [Track U validation](2026-10-26-track-u-chat-timing-opening.md) provide the preceding behavior.

## Delivered behavior

- The sidebar requests only `sessionsBeforeCollapse` newest conversations initially. It enumerates file metadata in the current world's directory, sorts by modification time, and reads summaries only for the requested page. Other world directories participate only when the setting is enabled.
- Show more requests the next ten metadata positions. Previously displayed summaries stay in memory. The cursor advances past damaged rows, and one damaged header cannot discard healthy rows in the same page.
- Valid summaries supply row data without opening JSONL files, including rows from other worlds. New summaries include the world label; old summaries without it use the directory label. Missing or stale caches produce header-only `Saved chat` placeholders rather than reading unclicked turns. The selected conversation can load its body normally.
- Completion state is consulted only when a summary identifies a completed answer whose unread state matters. Completing a request reads that conversation's summary instead of scanning all worlds.
- Catalog work uses a separate production executor and never joins the foreground read/write dependency chain. Selected reads remain runnable while catalog work is blocked or fails. Selected opening and sidebar page requests have five-second failure bounds; read/setup/attachment failures leave loading and show an error.
- Sidebar callbacks reject an obsolete world or setting generation, preserve live activity metadata, and retain a refresh requested while another page is loading. Initial selected attachment avoids a redundant page refresh.
- The other-session checkbox is in Settings > Behavior with a tooltip, using the existing persistence workflow. The sidebar checkbox is removed.
- Answer totals and tool durations display seconds with one decimal place; values below 100 ms display `<0.1 s`. Saved values remain milliseconds.
- Tool activities persist an additive `resultDetail` field capped at 4,096 UTF-16 characters. A Result section displays the result detail, up to eight titles and three bounded payload fields per record, bounded nested values, an `and N more` suffix, or the failed call's readable error. Old activities without this field omit Result.
- The separately reserved response-header and tool-row redesigns were not implemented.

## Real-data benchmark

The instance directory was copied once with `Copy-Item` from:

```text
C:/Users/hung1/curseforge/minecraft/Instances/Packsage/packsage
```

All benchmark reads and any benchmark marker/cache writes targeted this disposable copy:

```text
C:/Users/hung1/AppData/Local/Temp/packsage-chat-opening-8545dfb24a404d11b866cdd1d3c88e8a/packsage
```

The copy contains 443 JSONL conversations across six world directories. The reported world contains 372 conversations and 62,336,783 bytes of media. Across all worlds, media totals 155,018,364 bytes. No media files are read during opening. The original instance folder was never modified.

The original `latest` selection is a 26,133-byte conversation with one turn. A second run changes only the copied marker to select the largest conversation: 3,173,873 bytes, two turns, with 192 and 1,809 saved sources respectively. The copied marker was restored afterwards.

`ChatOpeningProfileTest` exercises `AssistantRuntime.enterWorld`/`openScreen`, real store reads, screen widget initialization/layout, history selection and token counts, saved citation-label construction, inline text/message layout for the newest turns, and sidebar row construction. A catalog is deliberately queued before the selected read. Executors are gated to distinguish foreground completion from catalog work.

The render-preparation measurement uses the production text/layout routines with the existing headless test font. It excludes GPU drawing, the Minecraft backdrop/composer drawing, actual font glyph measurement, and real Minecraft provider construction. `ClientAssistant`'s constructor now warms the tokenizer on its worker at client startup; the profile performs that warmup before the open timer and reports its cost separately. These are single fresh-JVM samples with warm filesystem caches, not frame-time percentiles or installed-game acceptance. No game or computer-use session was launched.

### Measurements

Original selected chat, before corrections:

- Widget initialization: 61.1 ms.
- Selected conversation attached: **2,932.4 ms** from opening.
- Selected conversation plus CPU render preparation: **3,120.3 ms**.
- All-world catalog task: **2,836.5 ms**, 443 entries.
- First history/tokenizer selection: 165.9 ms; per-turn token counts: 1.0 ms; text layout: 19.6 ms; sidebar build: 1.3 ms.
- Draining the original `loadSummaries` path and previously queued sidebar work: **20,371.4 ms**. This is the aggregate queued path, not an isolated per-summary service time.

Original selected chat, after corrections at `545e0b4`:

- Widget initialization: 106.0 ms.
- Selected conversation attached: **185.1 ms**.
- Selected conversation plus CPU render preparation: **222.5 ms**.
- Initial two-summary page: 56.6 ms; next ten-summary request: 221.8 ms.
- History preview: 10.2 ms; per-turn token counts: 1.5 ms; text layout: 23.3 ms; sidebar build: 1.3 ms.
- Tokenizer startup work, before the open timer: 241.6 ms.

Largest conversation:

- The paging-only version still took **1,079.7 ms** through CPU render preparation: selected attachment 350.9 ms, cold history/tokenizer work 252.6 ms, and citation/text preparation 470.3 ms.
- The final measured version takes **331.1 ms** to attach and **379.6 ms** through CPU render preparation.
- Its history preview takes 12.6 ms and citation/text layout 32.0 ms. The initial two-summary page takes 45.2 ms, the next ten 201.4 ms, and sidebar construction 2.0 ms.
- Its tokenizer startup work takes 278.3 ms before opening.

The later commits skip unnecessary unread-state reads and fix refresh/corrupt-row handling; they do not add work to the measured selected-chat path. The opt-in profile asserts that selected attachment plus CPU render preparation stays below 500 ms on its copied input.

Raw benchmark XML is retained beside the temporary copy as `before-opening.xml`, `before-large-layout.xml`, `after-latest-opening.xml`, and `after-large-opening.xml`.

### Root causes

1. Catalog reads joined the same `writes` future chain as selected reads. A catalog queued first necessarily held up the selected conversation, regardless of the runtime publishing selection independently of sidebar callbacks.
2. Initial listing read all worlds, and the UI followed it with serial summary refreshes for every conversation. File/header validation, repeated path checks and completion-state reconciliation accumulated across hundreds of entries. Repeated sidebar refreshes amplified that queue.
3. First render initialized the tokenizer on the client thread. That one-time cost was 166–278 ms in these samples; it now runs during client startup.
4. Saved source-label reconstruction parsed tool-result bodies and compared returned records with all saved sources even when no source could supply a special label. The large chat exposed roughly 470 ms of this work. Source-label candidates are now filtered and indexed; source deduplication uses sets instead of repeated linear membership checks.
5. An executor that never ran a selected read could leave loading indefinitely. A timeout now settles the UI with an error. Sidebar failures similarly settle, obsolete callbacks are fenced, and pending refreshes survive concurrent paging.

## Automated verification

At code commit `8ef3d51`:

```powershell
$env:JAVA_HOME='D:/Project/packsage/.tools/jdk/jdk-21.0.12.1'
./gradlew build -q
node D:/Project/packsage/build/tools/count.cjs .
```

Build exit code: 0. **1,269 tests; 1,263 passed; six skipped; zero failures; zero errors.**

Four skipped store tests require Windows symbolic-link support, one grounding test requires the external live-tested candidate file, and the copied-data profile is opt-in. The profile was run separately against both real-data selections and passed its 500 ms bound.

Tests preceded the relevant implementation. Observed RED cases covered the missing page/result APIs, Show more expanding the whole catalog, a permanently stalled selected read, result persistence/display, stale setting callbacks, cross-world completion scans, truncated record-count suffixes, nested payload values, unwanted state/header reads, a refresh lost during paging, and damaged-header page failure. Existing full-suite coverage caught and verified the live activity flag regression. JFR assertions verify exactly N distinct summaries initially, ten additional summaries for the next page, and no unclicked JSONL reads for valid caches.

Luna reviewed the branch and identified the active-row flag and all-world completion scan; both were fixed. The follow-up review identified the pending-refresh and damaged-header cases. The bounded final review approved their fix at `a0ac8cb`. The subsequent nested-payload preview correction passed its regression and the whole suite.

A manual style pass and `git diff --check` were clean. No formatter, push, merge, computer use, game launch or installation was performed. `CHANGELOG.md`, `docs/roadmap`, and `model/GroundingInstructions.java` were unchanged.

## Commits

- `d3cc02d` [Chg] move cross-world chat visibility to behavior settings
- `6bd4cd6` [Chg] display chat durations in seconds
- `bb7f336` [Fix] page chat summaries without blocking selected conversation reads
- `a757570` [Add] readable bounded tool result details
- `9b58a8e` [Fix] load sidebar history in pages and bound stalled chat opening
- `35b72c3` [Add] persist readable tool results and show them in expanded calls
- `54cd0c2` [Chg] align history regressions with paged catalog scheduling
- `4ee825d` [Add] opt-in opening profile for copied player conversation data
- `a3761c8` [Fix] avoid quadratic evidence work when opening large chats
- `f5c52ca` [Fix] initialize the tokenizer before the first chat frame
- `b80d03e` [Fix] discard stale sidebar pages when the world or setting changes
- `9061fcc` [Fix] retain live activity metadata in paged sidebar rows
- `3945bfa` [Fix] read only the completed chat when updating unread metadata
- `24fc2fa` [Fix] retain record truncation counts within bounded tool results
- `545e0b4` [Fix] read cross-world sidebar rows directly from valid summaries
- `6e3d43c` [Fix] skip unread state reads when a summary has no completed answer
- `a0ac8cb` [Fix] preserve pending sidebar refreshes and skip damaged rows
- `8ef3d51` [Fix] show bounded nested payload values in tool results
