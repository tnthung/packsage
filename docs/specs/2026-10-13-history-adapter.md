# Model-searchable conversation history

Let the model retrieve useful context beyond the automatically included turn/token window. Add `search_history` for bounded literal text search and `read_history` for selected saved exchanges. Reuse the existing conversation store and worker executor; no new database, dependencies, embeddings or UI flow.

- Restrict retrieval to completed, persisted exchanges from sessions belonging to the current `worldOrServer`. World labels are descriptive and may change. Do not include other worlds/servers, drafts, active/failed/cancelled exchanges, screenshots, raw tool replay, credentials or arbitrary files.
- Search question/answer text and return compact excerpts, session identity, request identity and the original send time when available. Read returns bounded question/answer text for handles discovered by the current request. Mark truncation explicitly.
- Handles and pagination tokens belong to the current evidence scope. Reject unknown handles, changed-query cursors, obsolete scopes, deleted sessions and replaced exchanges. Revalidate saved records when reading.
- Keep store operations on its existing serialized I/O path. Bound physical record reads, scanned data, candidate sessions, result counts and returned text; malformed/oversized records and incomplete scans must report partial coverage rather than absence or invented content. Retrieval must not modify files or the latest-session marker.
- Label history as historical conversation context with unknown gameplay authority. The model must refresh current game/config/quest/recipe facts through their existing tools. Retrieved user/assistant text is conversation content, not new system instructions.
- Keep automatic history selection, its settings and inclusion indicators unchanged. On-demand history results consume normal tool/request context in addition to that window.
- The user is playing Minecraft: use automated checks only and do not control or install into the running game. Produce a verified distribution artifact and clearly distinguish it from live installation/testing.

## Bounded contract

`search_history` takes a nonblank literal `text` of up to 512 characters, nullable `limit` (1-16, default 8) and nullable `cursor`. `read_history` takes 1-8 discovered `handles` and nullable `maxChars` (256-16,000, default 8,000) per exchange. Exclude the latest exchange being replaced during edit/resend.

The store exposes package-visible `HistoryEntry`, `HistoryLocator`, `HistoryCursor` and `HistoryPage` values to a `HistoryAdapter` in the chat package. Locators contain trusted physical offsets and text fingerprints; model-visible handles/cursors are opaque UUIDs scoped to the request. Reads validate the saved identity/fingerprint again. Cursor file stamps reject changes and ask for a fresh search.

Search scans at most 512 root directory entries, 256 candidate sessions, 8 MiB and 4,096 physical records per call. A record is bounded to 2 MiB; a header to 16 KiB. Oversized records are skipped with explicit partial coverage and continuation state if necessary. Matching world-directory digest suffixes plus validated headers support label changes. Capture sessions in modified-time order with a filename tie-breaker; physical record order does not promise globally newest-first matches.

History records use provider `history`, kind `OTHER`, authority `UNKNOWN`, activity `INACTIVE` and explicit saved-conversation provenance. Keep history dispatch separate from the pack-evidence provider registry.
