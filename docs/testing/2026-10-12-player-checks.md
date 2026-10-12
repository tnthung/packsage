# PackSage minimal fixture player checks

The current G2 checks use your existing CurseForge **Packsage** profile at `C:\Users\hung1\curseforge\minecraft\Instances\Packsage`. The assistant builds, runs automated tests and installs the candidate as `mods/packsage.jar`. No Java, Gradle, terminal or Minecraft commands are required.

## G2: Open the full assistant

1. Launch **Packsage**. The full assistant has replaced the probe; no JAR copying is needed for this profile.
2. Before entering the world, open **Options → Resource Packs** and enable **PackSage deterministic fixture resource override**.
3. Open the existing **New World**. The assistant installed the fixture datapack in that world's `datapacks/packsage_fixture` folder, plus the fixture quest chapter and book resource ZIP in the profile. Confirm that FTB Quests contains **PackSage Fixture**.
4. Press **P**. Expect a question field and **Send**, **Stop**, **New chat**, **Settings**, screenshot and pause controls. If P conflicts, change **Open PackSage** in **Options → Controls → Key Binds → PackSage**.
5. If the status is signed out, click **Sign in** and complete any browser prompt. Wait for sign-in/model discovery before sending a question. Do not send tokens, callback URLs or credential files.

The seven active fixture mod files have been hash-checked against the pinned manifest. Patchouli 93 was preserved as `mods/Patchouli-1.21.1-93-NEOFORGE.jar.disabled`; the supported 92 JAR is active. The other installed mods were retained.

## G2: Evidence questions

In **Settings**, turn **Automatic search** off and return to chat. Keep the per-send **Search** control off for these local checks. Ask one question at a time and compare the answer against the game:

| Question to send | Expected observation |
|---|---|
| `Use installed recipes: how many torches does the coal-or-charcoal recipe produce? Show its ingredients and source.` | The overridden `minecraft:torch` recipe produces **1** torch, using a stick and **coal OR charcoal**. Compare in JEI and try the returned recipe navigation. |
| `Find the packsage:fixture_lantern recipe. What does one lantern require? Show the tag alternatives and open its recipe.` | Eight surrounding input slots, each accepting coal or charcoal through `packsage:fixture_inputs`, plus one central torch, produce one lantern. The normal lantern recipe may also exist; the answer must distinguish the fixture recipe. |
| `In PackSage Fixture, what depends on Gather the fixture input? Read my current quest progress.` | **Craft the fixture lantern** and **Check the alternate input** depend on **Gather the fixture input**. Compare the actual task/team state and try quest navigation. |
| `Find the installed Nature's Aura entry Aura, conceptually. Quote its fixture marker and open that entry.` | The loaded override contains **PackSage fixture override**. The source book is `naturesaura:book`, entry `intro/aura`; navigation should open that entry. |
| `Read general.fieldCreatorRange from Nature's Aura's config. Is this a saved or loaded value, and what source proves it?` | The current saved `naturesaura-common.toml` contains **24**. Saved and loaded observations must be labelled separately; a saved value alone does not prove the live gameplay effect. |

For fresh progression, obtain coal through ordinary gameplay (or the creative inventory), verify whether the quest actually changes, then ask for current progress again. Inventory possession alone must not be described as completed quest state. Record any missing source, wrong count, unavailable provider, failed navigation or error exactly as shown.

With automatic search still off, send `Search the web for Minecraft Java 1.21.1's release date and cite the official announcement.` Expect hosted search and a source link; click the returned source button. This checks explicit search while automatic search is off. The earlier local questions should use local evidence without automatic lookup.

## G2: Context, lifecycle and history

1. Send a short question with **Screenshot: on**. Confirm attached dimension, position and biome reflect the current world. Inspect whether the model sees the game view rather than the assistant screen; report a blank, stale or obscured image.
2. Turn **Screenshot: off** and send another question. The message should omit the image while retaining gameplay metadata.
3. Toggle **Pause** in private singleplayer and verify that the world actually pauses/resumes. In **Settings**, change **Open paused**, close chat and reopen it to check the opening default. A published/LAN or remote world must remain running.
4. Ask a longer question, close chat with Escape and reopen with P. The request should continue. Try **Stop** during another request and **New chat** during one more; late results must not appear in the new conversation.
5. Save and quit, restart Minecraft and reopen **New World**. Its latest conversation should return. **New chat** must preserve the old log. Change worlds during a request and check that the old answer does not appear in the new world.
6. Reload resources through the game's normal controls and retry local evidence/navigation. Report any obsolete answer published as current or stale link opening the wrong target.

Conversation logs/media are under the profile's `packsage/` folder. The assistant can read ordinary logs and build fixes from this known profile path; the player only needs to report visible results. G2/G3 remain unverified until those observations are recorded.

## Separate fixture imports

The steps below are for additional isolated profiles and optional-provider checks, not setup required for the current **Packsage** profile.

## Import and select the instance

1. Open CurseForge and select **Minecraft** in the left sidebar.
2. Open **My Modpacks**, choose **Create Custom Profile**, then choose **Import** in the profile dialog.
3. Select `dist/packsage-minimal-fixture.zip` from the repository. Let CurseForge download the seven listed mod files and create the new `PackSage Minimal Fixture` profile; do not select or modify an existing profile.
4. Open the imported profile's **More** menu and choose **Open Folder** if you need to inspect its files. The downloaded mods are external profile files; the archive carries the authored `overrides` data, including the FTB Quests `lang/en_us.snbt` translations and `resourcepacks/packsage_resourcepack.zip`.
5. Start the imported profile and wait for the title screen. Record whether the title screen appears and any loader/mod error exactly as shown.

The pinned external mods are Minecraft 1.21.1, NeoForge 21.1.202, JEI 19.27.0.340, FTB Quests 2101.1.36, FTB Library 2101.1.37, FTB Teams 2101.1.11, Architectury 13.0.11, Patchouli 1.21.1-92 and Nature's Aura 41.10.

## G1: Completed route probe and remaining recovery checks

The player has already completed sign-in/catalog, text, the read-only fixture tool, synthetic-image input and hosted search with **GPT-6-Luna**. The [route record](../validation/2026-10-12-chatgpt-route.md#live-gate) distinguishes those observed results from untested recovery and image accuracy. The probe remains available as `dist/packsage-bootstrap.jar` for isolated route diagnosis; the default build opens the full assistant.

1. Close Minecraft. From the imported profile's **More → Open Folder**, open `mods` and copy `D:\Project\packsage\dist\packsage-bootstrap.jar` into it. Keep only one PackSage JAR in that folder.
2. Launch the fixture and enter a world. Press **P** to open PackSage. If another mod uses P, change **Open PackSage** under **Options → Controls → Key Binds → PackSage**.
3. Click **Sign in**, complete the system-browser ChatGPT flow, and return to the game. Record the resulting status. Do not send tokens, callback URLs or credential files.
4. Once the model name appears, click **Text**, then **Read-only tool**, then **Synthetic image**, then **Search**, waiting for each result. Record successful replies and any unsupported or failed status exactly as shown.
   When Search returns a citation, click its **Open citation** button and confirm Minecraft offers the returned source link.
5. Try **Stop** while a request is running, and **Cancel sign-in** during another browser attempt. Record whether the request stops and the screen remains usable.

The assistant handles builds, automated tests and fixes. These game/browser observations are the live gate; the JAR build alone does not prove sign-in, account eligibility, images or hosted search.

## Create the focused test world

1. Create a new survival or creative singleplayer world named `packsage-fixture`.
2. Save and quit to the title screen so the world is closed. In the instance folder, copy the prepared `datapacks/packsage_fixture` folder into the new world's `datapacks` folder. If the launcher has an instance file browser, use it to open the world's folder.
3. In Minecraft's Resource Packs screen, enable `PackSage deterministic fixture resource override`; the imported profile already contains `resourcepacks/packsage_resourcepack.zip`.
4. Reopen the world so its datapack loads, then open the FTB Quests screen and confirm the `PackSage Fixture` chapter is present.

## Focused observations

1. In the recipe viewer, search for torch. Confirm the active torch recipe accepts either coal or charcoal and produces one torch.
2. Search for lantern and confirm the fixture recipe accepts the coal/charcoal alternatives around a torch.
3. In the FTB Quests chapter, confirm the first quest is the prerequisite for both downstream quests. Do not claim completion unless the in-game task state shows it.
4. Obtain or open the Nature's Aura book and navigate to the `Aura, conceptually` entry. Confirm the visible entry contains `PackSage fixture override` after enabling the resource pack.
5. Record the visible chapter, recipe, tag alternatives and book text. A loader error, missing provider, missing chapter or missing override is a failed check and should be copied into the task report with its exact text.

The fixture is intended to establish data presence and navigation only. No startup duration, memory, responsiveness or server compatibility claim is made by these checks.

## Prepared optional-provider variants

G3 can use separate CurseForge imports without editing the main profile:

| Archive in `dist/` | Excluded mods | Pinned mods retained |
|---|---|---|
| `packsage-minimal-fixture-no-jei.zip` | JEI | 6 |
| `packsage-minimal-fixture-no-ftb-quests.zip` | FTB Quests, FTB Library and FTB Teams | 4 |
| `packsage-minimal-fixture-no-patchouli.zip` | Patchouli and Nature's Aura, which requires it | 5 |

The assistant has prepared and checked these archives, portable ZIP paths and mod hashes. They have not been launched in Minecraft. Copy `dist/packsage.jar` into each imported variant's `mods` folder, keeping one PackSage JAR, then use the same local questions above. Missing-provider evidence must be reported as unavailable while unrelated evidence remains usable. Do not replace the full assistant with the bootstrap probe for these checks. Remote/LAN and representative-pack acceptance remain separate open gates.
