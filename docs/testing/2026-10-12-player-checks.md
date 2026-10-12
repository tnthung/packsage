# PackSage minimal fixture player checks

These checks use the separate `packsage-minimal` instance prepared by the assistant. They do not require Java, Gradle, a terminal or commands in Minecraft.

## Import and select the instance

1. Open CurseForge and select **Minecraft** in the left sidebar.
2. Open **My Modpacks**, choose **Create Custom Profile**, then choose **Import** in the profile dialog.
3. Select `dist/packsage-minimal-fixture.zip` from the repository. Let CurseForge download the seven listed mod files and create the new `PackSage Minimal Fixture` profile; do not select or modify an existing profile.
4. Open the imported profile's **More** menu and choose **Open Folder** if you need to inspect its files. The downloaded mods are external profile files; the archive carries the authored `overrides` data, including the FTB Quests `lang/en_us.snbt` translations and `resourcepacks/packsage_resourcepack.zip`.
5. Start the imported profile and wait for the title screen. Record whether the title screen appears and any loader/mod error exactly as shown.

The pinned external mods are Minecraft 1.21.1, NeoForge 21.1.202, JEI 19.27.0.340, FTB Quests 2101.1.36, FTB Library 2101.1.37, FTB Teams 2101.1.11, Architectury 13.0.11, Patchouli 1.21.1-92 and Nature's Aura 41.10.

## Create the focused test world

1. Create a new survival or creative singleplayer world named `packsage-fixture`.
2. In the instance folder, copy the prepared `datapacks/packsage_fixture` folder into the new world's `datapacks` folder. If the launcher has an instance file browser, use it to open the world's folder.
3. In Minecraft's Resource Packs screen, enable `PackSage deterministic fixture resource override`; the imported profile already contains `resourcepacks/packsage_resourcepack.zip`.
4. Return to the world after the resource reload. Open the FTB Quests screen and confirm the `PackSage Fixture` chapter is present.

## Focused observations

1. In the recipe viewer, search for torch. Confirm the active torch recipe accepts either coal or charcoal and produces one torch.
2. Search for lantern and confirm the fixture recipe accepts the coal/charcoal alternatives around a torch.
3. In the FTB Quests chapter, confirm the first quest is the prerequisite for both downstream quests. Do not claim completion unless the in-game task state shows it.
4. Obtain or open the Nature's Aura book and navigate to the `Aura, conceptually` entry. Confirm the visible entry contains `PackSage fixture override` after enabling the resource pack.
5. Record the visible chapter, recipe, tag alternatives and book text. A loader error, missing provider, missing chapter or missing override is a failed check and should be copied into the task report with its exact text.

The fixture is intended to establish data presence and navigation only. No startup duration, memory, responsiveness or server compatibility claim is made by these checks.
