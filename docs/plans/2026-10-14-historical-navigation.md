# Historical navigation and status clipping plan

Specification: [Historical navigation and status clipping](../specs/2026-10-14-historical-navigation.md).

1. Correct the one-line transcript scroll measurement, run existing scroll/UI tests and commit the clipping fix independently.
2. Add bounded current-provider revalidation for exact recorded local destinations, with separate navigation authorization that does not broaden model evidence handles. Keep runtime conversation/world/scope guards around asynchronous resolution.
3. Add meaningful service/runtime regressions for older references and invalidation, run focused tests and the full test/build suite, then commit navigation separately using staged-index-only commit messages.
4. Package the verified JAR and record checks and any installed-test limitation. Replace a running game's JAR only after a normal shutdown.
