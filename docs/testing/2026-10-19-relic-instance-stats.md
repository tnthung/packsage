# Current relic stat player checks

The approved [contract](../specs/2026-10-19-relic-instance-stats.md) and [API/fixture evidence](../research/2026-10-19-relic-instance-stats.md) distinguish actual item values from generic ability definitions. These checks use Minecraft; the agent handles automated tests and builds.

1. Exit Minecraft fully, install the verified `dist/packsage.jar` as the instance's `mods/packsage.jar`, then restart the same world. Compare the installed checksum with the build recorded in the validation report.
2. Use a relic with known nonzero level and XP. Hover it and hold Left Shift to open the Relics panel. Record its level, the XP tooltip and a researched ability's displayed effective value. Ability research is player data shared by copies; controlled fixture changes must be recorded separately from item-local progression.
3. Equip that same stack in a functional Curios slot and close the inventory. In a new PackSage chat, ask for the current relic level, XP and rolled stats without supplying the expected numbers.
4. Compare the answer with the panel and inspect the saved tool result's `relicInstance` fields, coverage, actual API version and current slot reference. Readable zero must be numeric zero; unavailable fields must not be invented. Cosmetic-only stacks remain excluded from functional equipment.
5. Change XP on the same fixture, return it to the same functional slot and ask again. Check for a new capture and the changed value. A previous answer and its source details remain historical observations.
6. Open a new answer's source control. Confirm recorded item/location details match that answer and that no temporary `ref:` identifier is displayed. Plain invented citation labels and historical malformed answers remain separate limitations unless explicitly retested.
