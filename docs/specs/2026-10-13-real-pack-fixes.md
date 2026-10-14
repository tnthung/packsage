# Real-pack trial fixes

Finish the defects observed in Evolution and ATM10, then repeat the affected live checks in the backed-up working world. Preserve the original OoO save and the verified backup. Keep each fix independently reviewable and commit it separately.

- A compatible, partially captured ConfigTracker catalog must retain loaded values actually copied from valid entries. Catalog/key/value bounds remain enforced and incomplete coverage remains partial. Unsupported runtimes and failed bridge access must not expose loaded values as observed.
- A requested JEI recipe reference must resolve to an actual captured JEI navigation target. Native loaded recipe semantics and JEI viewer observations retain their respective authority; do not manufacture targets from native IDs or claim a plain citation is clickable. Scope/runtime invalidation and optional-provider absence remain supported.
- Extend `search_pack` with nullable `provider` and `outputItem`. An output-item query requires `provider: "jei"`; use empty literal text to retrieve focused displays. Validate the registered item and apply an OUTPUT focus to both JEI category and recipe lookups. Keep every existing bound and cursor identity, including the new filters. Return `navigable` beside each reference so the model can offer only real registered navigation; keep native planning unchanged.
- Gameplay context must distinguish a missed ray from a block/entity hit and identify the camera perspective. Grounding instructions must keep visible scene objects separate from the structured target and prohibit guessing a crosshair target from image position when no crosshair is visible.

Verify meaningful regressions before implementation, run the appropriate automated suite/build, then install while Minecraft is closed and repeat config, JEI navigation, first/third-person context and short-history checks. A changed prompt reduces an observed inference error; it does not guarantee every future model answer is correct.
