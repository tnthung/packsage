# PackSage

PackSage is a Minecraft 1.21.1 NeoForge client mod for asking questions about the active modpack inside the game. V1 combines client-visible game facts, optional JEI and FTB Quests evidence, read-only config inspection, Patchouli books and hosted web search through Sign in with ChatGPT.

## Development status

V1 is under development. The current entrypoint opens a ChatGPT route probe with **P**. The assistant modules and client binding are authored, but real account eligibility and Minecraft behavior still require the [player checks](docs/testing/2026-10-12-player-checks.md). See the [acceptance status](docs/validation/2026-10-12-v1-acceptance.md) for automated evidence and open live gates.

## Try the development probe

Import `dist/packsage-minimal-fixture.zip` into a new CurseForge profile, close Minecraft, and copy `dist/packsage-bootstrap.jar` into that profile's `mods` folder. Keep one PackSage JAR there. Launch the profile, enter a world, press **P**, and follow the linked G1 checklist to sign in through the browser and try text, tools, an image and search. The player workflow requires no Java or Gradle commands.

The pinned loader is NeoForge **21.1.202**. Optional adapter versions are JEI **19.27.0.340**, FTB Quests **2101.1.36** and Patchouli **1.21.1-92**; other versions are explicitly outside the tested adapter contract. The minimal fixture includes their pinned dependencies. Generated JARs and launcher archives live in ignored `dist/` and are not checked into Git.

## V1 boundaries

PackSage reads evidence and offers player-click navigation. It does not execute gameplay commands, change inventory, complete quests or write mod configs. Unsupported recipe categories, tasks, pages and unsynchronized server state remain partial or unknown. External guidance cannot establish a loaded pack fact.

Conversations and screenshot assets are designed to stay under the instance's `packsage/` folder; credentials use protected per-user application storage. Screenshots are removable per message. Recording, API-key/custom model setup, public adapter registration and spoiler covering are outside v1.

The [specification](docs/specs/2026-10-12-v1.md), [implementation plan](docs/plans/2026-10-12-v1-implementation.md) and [changelog](CHANGELOG.md) document the agreed behavior and work. Development builds use `scripts/build.ps1`; fixture preparation uses `scripts/prepare-fixture.ps1`, including separate provider-absent variants. Automated checks are handled during development; live acceptance requires the recorded Minecraft and browser observations.
