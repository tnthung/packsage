# Coherent assistant instructions

Implemented [the rewrite plan](../plans/2026-10-15-coherent-assistant-instructions.md) in `3b6e197`. The full instruction text now separates goal interpretation, evidence authority, feasibility, investigation and answer presentation. Tool APIs and live readers are unchanged.

The central correction is claim-specific evidence: a complete candidate can suffice within a partial search, incomplete ingredients cannot support affordability, known sufficient materials do not require exhaustive storage discovery, and missing-material claims must account for relevant carried containers. Bounded comparisons are allowed without asserting a global optimum. Exact rereads are attempts to resolve gaps, not promises to overcome extraction limits. Unknown operating conditions qualify execution without invalidating verified material counts.

Removed literal sentence assertions from the prompt tests; those tests retain current-question/history separation, gameplay context, image bytes, model options and tool attachment checks. Matching warning strings was not an oracle for reasoning quality.

`test build` with the repository's portable Java 21 succeeded: 496 tests, 492 passed, four skipped, zero failures or errors. The initial invocation with the shell's Java 24 failed during Gradle configuration; no source change was needed. Gradle emitted daemon socket warnings during the successful run. `git diff --check` passed. No formatter was used.

This is source/build validation, not new model acceptance. The new JAR has not been installed or tested in Minecraft. The existing hopper affordability and carried-material failure remains open in [the workflow report](2026-10-15-natural-player-workflow-fixes.md). A new natural-player conversation must verify complete ingredient accounting, relevant container reads and a feasible plan; repeating the same blocked exact read or merely adding a caveat does not pass that scenario.

Independent Luna review found no fundamental contradiction in the revised coverage, inventory, optimization or vague-question guidance. It identified an omitted connection to the existing `plan_recipe` tool. Commit `4a66a2f` integrates that operation into route discovery while explicitly separating bounded route coverage from fresh inventory and affordability. The prompt request-assembly tests and build passed again after this wording change. This review closes the omission, not the live acceptance failure.
