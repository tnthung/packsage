# Middle-click Advanced settings

Date: 2026-10-19.

Requirements: [developer prompt settings](../specs/2026-10-15-developer-prompt-settings.md). Implementation: `31f20bf`.

The gear now uses left-click for ordinary settings and middle-click for Advanced settings, whose existing panel is titled Developer settings. Ctrl no longer selects the advanced route. Both normal-world and container-screen creation paths bind the explicit advanced callback. The gear tooltip advertises middle-click; right-click and middle-click outside its hitbox do not select settings.

## Automated checks

Checkpoint `3adddd3` added the callback seam and public `mouseClicked` regression before dispatch. The focused suite ran 48 tests; only the new middle-click assertion failed because the advanced callback count remained zero. After implementation, Java 21 `gradlew.bat test build` passed 641 tests across 54 suites, with zero failures/errors and four existing skips. No formatting tools were used; manual style review and `git diff --check` passed.

Minecraft saved and closed normally before installation through `scripts/build.ps1`. Build and installed minimal-profile JAR hashes matched SHA-256 `080C5D8B2EF3C7A3BBDB16CC1472D25A0FB6EAC9F63F4EA4DDBF1DDF4DAFDC28`.

## Installed checks

Relaunched the minimal `Packsage` profile, Minecraft 1.21.1 / NeoForge 21.1.259 with all 34 mods, and opened PackSage in the original New World at GUI scale 2.

- Middle-clicking the gear opened the developer prompt editor with candidate 1 loaded. Escape returned to the same paused chat, where the new tooltip was readable.
- Right-clicking the gear kept the chat open. Left-clicking opened ordinary settings; Escape returned to chat.
- A second middle-click reopened the editor. Replaced the entire draft with the independently reviewed candidate 2 and clicked Save without restarting Minecraft. Java's Properties reader confirmed the saved override exactly matched the 10,820-character candidate.

The normal-world routing and in-game save iteration path passed. The container-screen callback is wired and compiled but was not separately exercised live. Prompt behavior is tracked in the separate [policy plan](../plans/2026-10-19-coherent-search-policy.md), not established by the gesture checks.
