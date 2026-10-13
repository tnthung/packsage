# Evolution compatibility implementation

1. Widen the loader metadata range and verify accepted baseline/target versions and rejected earlier/other-line versions.
2. Inspect the target JEI/Patchouli and NeoForge/FML APIs. Enable the additional validated adapter versions without discarding unsupported-version behavior or source provenance.
3. Run the automated suite/build against the fixture baseline and the exact Evolution dependency set. Commit independently reviewable compatibility changes using the staged-index commit-message skill.
4. After CurseForge completes installation, inventory/hash the relevant runtime files, add the tested PackSage JAR and launch a new Evolution test world. Check local recipes, real quests/progress, guidebooks, config distinctions and returned navigation with web lookup disabled, then a combined question and context/history check.
5. Record results and any concrete failure; fix and verify within this compatibility/trial scope. Preserve the user's existing worlds, unrelated implementation-plan edit and reference images.
