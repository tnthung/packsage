# Curios equipment player checks

The automated build and acceptance limits are recorded in the [validation report](../validation/2026-10-19-curios-equipment.md). These are Minecraft checks; no Java or Gradle commands are required from the player.

1. Exit Minecraft, replace the older PackSage JAR with `dist/packsage.jar`, then restart the same instance and world. The distribution must match the verified build hash in the validation report.
2. Confirm the adapter list contains one available Curios entry. With functional Curios items equipped, ask in a new chat: "what relics did i put on?" Compare the reported items and slot types with the Curios panel. Two types may both have slot index zero.
3. Change an equipped item and ask again. The new answer must reflect the current equipment; the old answer remains a historical observation.
4. Check that cosmetic-only stacks are excluded from functional equipment. Empty functional equipment must remain distinct from an unavailable read.
5. In a new answer, verify that local citations bind to recorded item/location evidence and do not expose a temporary `ref:` identifier. Open the source control and compare its saved item and slot details. Historical malformed answers are outside this repair.
