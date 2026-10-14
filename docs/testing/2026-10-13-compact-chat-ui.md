# Remaining live checks

Close Minecraft, replace the instance's `mods/packsage.jar` with the [verified distribution JAR](../../dist/packsage.jar), and reopen the same world. The instance used during development is `C:\Users\hung1\curseforge\minecraft\Instances\Packsage`.

- The composer row should read information icon, context meter, editor, Send/Stop. Hover the information icon: current coordinates and readable biome/item/target names should appear in aligned rows without Java object names, block states or component dumps. Change position or target and check that the values update. Sending captures those values again.
- Hover Search and Screenshot while enabled and disabled: the enabled hover should remain green and visibly differ from the disabled hover. Check that Sign out remains horizontally centered.
- Send a new message and check its local date/time before the user token count. Reopen the session to check persistence. At a narrow width, metadata and Copy/Edit should stay within the chat area and the actions should remain clickable. Old messages without saved timestamps omit the time.
- Settings and Pause/Resume should have the same compact height as Send, with aligned icons and hover effects. Pause remains hidden in multiplayer.
- Hover an overflowing picker option and wait briefly: its text should scroll within the button-width menu.
- Check the toolbar at the preferred Minecraft GUI scale. Buttons should wrap without covering the editor; the context meter is half their width.
- If the black flash recurs without computer automation, note whether it occurs in normal gameplay or only with PackSage open, and whether the window is focused. This observation remains unresolved.

Automated tests and build commands have already been run by the agent. Deletion persistence, history quotas and preview/request agreement are covered by those tests; this checklist covers visible behavior only.
