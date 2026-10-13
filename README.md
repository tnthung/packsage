# PackSage

PackSage is a Minecraft 1.21.1 NeoForge client mod for asking questions about the active modpack inside the game. V1 combines client-visible game facts, optional JEI and FTB Quests evidence, read-only config inspection, Patchouli books and hosted web search through Sign in with ChatGPT.

## Development status

V1 is under development. The default build opens the full assistant with **P**. The player has verified real ChatGPT sign-in, text, a client-tool exchange, image input and hosted search; installed-pack retrieval and gameplay behavior require the [G2/G3 player checks](docs/testing/2026-10-12-player-checks.md). See the [acceptance status](docs/validation/2026-10-12-v1-acceptance.md) for automated evidence and open live gates.

## Try the full assistant

The current candidate is already installed as `C:\Users\hung1\curseforge\minecraft\Instances\Packsage\mods\packsage.jar`. Launch **Packsage**, enable the prepared fixture resource pack, open **New World**, and press **P**. Follow the linked G2 checklist for recipe, quest, book, config and lifecycle questions. The player workflow requires no Java or Gradle commands.

For a separate profile, import `dist/packsage-minimal-fixture.zip` and copy `dist/packsage.jar` into its `mods` folder while Minecraft is closed. Keep one PackSage JAR there. The bootstrap probe is retained separately as `dist/packsage-bootstrap.jar` for route diagnosis.

NeoForge **21.1.202** and the minimal fixture remain the compile/test baseline. Loader and adapters accept the [compatibility ranges](docs/specs/2026-10-13-evolution-compatibility.md), including the newer dependencies in FTB Evolution 1.45.0; incompatible versions or failed extraction remain unavailable or partial. Generated JARs and launcher archives live in ignored `dist/` and are not checked into Git.

## V1 boundaries

PackSage reads evidence and offers player-click navigation. It does not execute gameplay commands, change inventory, complete quests or write mod configs. Unsupported recipe categories, tasks, pages and unsynchronized server state remain partial or unknown. External guidance cannot establish a loaded pack fact.

Conversations and screenshot assets stay under the instance's `packsage/` folder; credentials use protected per-user application storage. Screenshots are removable per message. Recording, API-key/custom model setup, public adapter registration and spoiler covering are outside v1.

The [specification](docs/specs/2026-10-12-v1.md), [implementation plan](docs/plans/2026-10-12-v1-implementation.md) and [changelog](CHANGELOG.md) document the agreed behavior and work. Development builds use `scripts/build.ps1`; fixture preparation uses `scripts/prepare-fixture.ps1`, including separate provider-absent variants. Automated checks are handled during development; live acceptance requires the recorded Minecraft and browser observations.
