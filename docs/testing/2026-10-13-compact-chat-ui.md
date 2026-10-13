# Remaining live checks

Close Minecraft, replace the instance's `mods/packsage.jar` with the [verified distribution JAR](../../dist/packsage.jar), and reopen the same world. The instance used during development is `C:\Users\hung1\curseforge\minecraft\Instances\Packsage`.

- Hover the square information icon immediately before the editor. It should show actual current coordinates, dimension, biome, held item and looked-at target. Change position or target and check that the values update when reopened. Sending captures those values again.
- Settings and Pause/Resume should have the same compact height as Send, with aligned icons and hover effects. Pause remains hidden in multiplayer.
- Hover an overflowing picker option and wait briefly: its text should scroll within the button-width menu.
- Check the toolbar at the preferred Minecraft GUI scale. Buttons should wrap without covering the editor; the context meter is half their width.
- If the black flash recurs without computer automation, note whether it occurs in normal gameplay or only with PackSage open, and whether the window is focused. This observation remains unresolved.

Automated tests and build commands have already been run by the agent. Deletion persistence, history quotas and preview/request agreement are covered by those tests; this checklist covers visible behavior only.
