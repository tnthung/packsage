# FTB Evolution real-pack trial

Use the existing CurseForge **FTB Evolution 1.45.0** profile and a new world named **PackSage Evolution Trial**. The assistant installs the candidate while Minecraft is closed and handles automated checks. Keep the pack's supplied mods/resources/configuration intact; do not add the synthetic fixture datapack, quest chapter or resource override.

Use GPT-6-Luna with medium effort for the first pass. Keep web Search and Screenshot off for local evidence checks, then enable Screenshot for one context question. If P conflicts, set a free PackSage key in the game's controls. Do not sign out during the trial.

| Check | Question/action | Compare against |
|---|---|---|
| Recipes and navigation | `Using the installed recipes, how do I craft a torch? Show the output count, ingredients and source.` Click the returned recipe reference. | The actual JEI recipe screen, including alternatives and output count. |
| Real progression | `In Getting Started, which quests should I do first? Read my current progress and show their dependencies.` Click a returned quest reference. | The loaded FTB Quests chapter and current team/task state. |
| Guidebook and navigation | `Find an installed Ars Nouveau guide entry explaining the first spellbook. Read it, cite it and give me a reference to open.` | The actual Patchouli entry, supported pages and navigation. Unknown/custom pages must stay partial. |
| Config authority | `Read general.sylphManaCost in ars_nouveau-common.toml. Distinguish its saved and loaded values and cite the source.` Click its config reference. | Read-only source details, the saved file and captured loaded-state provenance. A partial catalog must not be called complete. |
| Combined retrieval | Ask for both current Getting Started progress and the Ars Nouveau config value in one message. | Both sources, fresh progress and correct saved/loaded labels. |
| Context/history | Enable Screenshot and ask what block is targeted, then ask `check again` after changing the target. | Current coordinates, biome, target/image and retained conversation context. |

Record launch-to-menu and world-entry observations, process memory/heap when available, indexing/query responsiveness, coverage limits and exact failures. Successful fixture or dependency-matrix checks alone do not establish these real-pack results. Use a short real-play follow-up after this focused pass to collect useful, incorrect and incomplete answers.
