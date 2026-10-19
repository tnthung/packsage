# Guide and manual adapter completion

Extend the approved [tool contract](../specs/2026-10-14-behavior-driven-tools.md) and [adapter implementation plan](2026-10-14-behavior-driven-toolbox.md) to the six guide/manual roadmap sources: Modonomicon, GuideME, Immersive Engineering, Actually Additions, Relics and Artifacts. Keep `search_document(query, filter?, cursor?, limit?)` and fresh `read_evidence(handles)` unchanged. Each logical provider advertises `guide` documents and owns its matching and version compatibility.

## Implementation constraints

- Read current loaded source objects on the client thread. Respect source visibility/unlock rules and the current locale. Missing or unsupported APIs return explicit unavailable/partial evidence.
- No content snapshots, parallel index or retained document bodies. Bound traversal, cursor seeking, extraction and output; continuation contains only identity/query/scope/position metadata. Fragment long text before copying it. Resolve references against current sources.
- Ability descriptions are documentation, not evidence of the player's current item stats, upgrades or active effects.
- Actually Additions 1.3.26 uses a Patchouli booklet; reuse its verified live reader for that book rather than substituting the legacy booklet API.
- Preserve optional dependency isolation and select the widest version range sharing each reader's API. Untested releases alone do not justify an exact-version pin. Adding providers must not change dispatcher logic.
- Parallel writers own disjoint files. Parent owns registration, build configuration and documentation; stage/commit operations are coordinated. No formatter. Commit each written step before awaiting test results, using the staged-index commit-message skill.

## Work steps

- [x] Verify installed source APIs and record concrete source/version evidence.
- [x] Add shared bounded guide traversal/reference tests, observe meaningful failures, then implement the common provider infrastructure required by the readers.
- [x] Implement Modonomicon, GuideME and Immersive Engineering source readers with source-specific regression coverage.
- [x] Implement Actually Additions booklet, Relics and Artifacts source readers with source-specific regression coverage.
- [x] Register all six logical providers, expose metadata/status and verify provider-qualified and combined discovery through existing dispatch.
- [x] Run focused tests and the full build; independently review freshness, bounds, optional loading and source semantics; fix findings in separate commits.
- [x] Install the tested artifact with Minecraft closed, run computer-use checks in `OoO - PackSage Trial`, and inspect actual call/result pairs for all six sources and fresh evidence reads.
- [x] Update the six roadmap checkboxes only for delivered behavior; record tests, installed observations and remaining source limitations in a dated validation report.

## Compatibility correction, 2026-10-19

The installed Artifacts 13.2.5 and Modonomicon 1.120.7 are rejected by exact pins to 13.2.3 and 1.120.4. Apply the current repository adapter policy to all six guide/manual readers, including the Reliquified Artifacts replacement path. Keep one logical provider per mod and preserve existing unavailable/partial handling when an API is missing.

- [x] Record installed versions and API-family evidence, distinguishing direct signature checks from inferred release-family compatibility.
- [x] Add regression coverage for updated releases, range boundaries and source version metadata; record the replacement path's direct-test limitation.
- [x] Replace exact pins with compatible ranges and report the actual loaded version in provider status and evidence.
- [x] Run automated tests and build the distributable; record the remaining installed acceptance boundary in the [compatibility report](../validation/2026-10-19-guide-adapter-compatibility.md).
