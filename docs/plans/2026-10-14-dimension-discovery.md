# Dimension registry discovery

Extend the approved [tool contract](../specs/2026-10-14-behavior-driven-tools.md) with `search_registry(registry="dimension" | "dimension_type", filter?, cursor?, limit?)`, using the existing native adapter and reference/cursor mechanism. All reads are live; no dimension creation, chunk loading, saved-world scanning or mod-specific dimension-design lookup.

- [x] Add regressions for selector exposure, fresh dimension membership, filtering/pagination, client partial knowledge and dimension-type fields. The schema regression failed before implementation; the initial native fixture failed compilation, so native assertion-red coverage is not claimed. Fresh references were also checked live.
- [x] Implement private integrated-owner runtime dimension enumeration on the server thread with scope/access revalidation; use partial client-known IDs elsewhere. Keep matching in the native adapter.
- [x] Add synchronized dimension-type registry extraction and fresh reference reads. Reject unsupported dimension tags explicitly.
- [x] Update schema/grounding and run focused tests plus the full build. Commit each written work step separately; no formatter.
- [x] Obtain an independent source review and resolve material findings.
- [x] Install the tested artifact while Minecraft is closed and run bounded computer-use checks in `OoO - PackSage Trial`. Record actual dimension/type searches and reference reads separately from automated mutation tests; do not claim RFTools compatibility without an installed fixture.

Results and fixture limits: [validation report](../validation/2026-10-14-dimension-discovery.md).

Use existing bounds (16 default results, 64 maximum, bounded candidate/seek work). Cursors retain query/scope/position only. The server runtime dimension collection supplies current existing worlds, not every saved or uncreated mod-specific world design. The remote/client-only fallback remains partial even for an empty result.
