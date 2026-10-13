# Evolution compatibility and real-pack trial

The user authorized real-pack testing with FTB Evolution and widening PackSage's accepted version range. This supersedes the exact NeoForge metadata pin in the initial v1 validation contract.

- Accept NeoForge `[21.1.202,21.2)` while retaining Minecraft `[1.21.1,1.21.2)` and the existing Java/FML requirements. Keep the fixture compile baseline available.
- The concrete trial target is the user's installing FTB Evolution 1.45.0 profile: Minecraft 1.21.1, NeoForge 21.1.250, JEI 19.57.0.450, FTB Quests/Library/Teams 2101.1.36/2101.1.37/2101.1.11, Patchouli 1.21.1-93, Architectury 13.0.11. Recheck actual installed files before launching.
- Accept JEI `[19.27.0.340,20)`, FTB Quests `[2101.1.36,2101.2)`, and Patchouli `[1.21.1-92,1.21.1-94)` (with its known NeoForge suffix). The config bridge accepts NeoForge `[21.1.202,21.2)` with FML `[4.0.41,5)`, retaining bounded structural extraction and saved-file fallback when capture fails. The endpoint API audit found the calls/fields used by PackSage unchanged. Preserve the actual observed runtime version in source/status provenance, and reject malformed or other-line versions. Range acceptance does not certify every intermediate/future adapter version; record the tested endpoints and live results.
- Leave Evolution's supplied mods, quests, recipes and resources intact; add PackSage and use a new test world. Do not copy the synthetic fixture overrides into Evolution.
- Record actual startup, world entry, retrieval, navigation, context and responsiveness observations. Automated compatibility checks and live pack acceptance remain separate evidence.
