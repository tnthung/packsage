# FTB Evolution compatibility preflight

Date: 2026-10-12

The approved representative pack remains FTB Evolution. Its official API version list contains no release targeting NeoForge 21.1.202, the only build accepted by the current PackSage metadata range `[21.1.202,21.1.203)`. The publisher's API and exact release manifests establish these version mismatches; no pack files were downloaded or changed. [Official pack metadata](https://api.feed-the-beast.com/v1/modpacks/public/modpack/125), [FTB API policy](https://www.feed-the-beast.com/blog/p/ftb-api).

| Release / ID | NeoForge | JEI | FTB Quests / Library / Teams | Patchouli | Current contract |
|---|---|---|---|---|---|
| 1.29.1 / 100181 | 21.1.218 | 19.27.0.340 | 2101.1.21 / 2101.1.30 / 2101.1.9 | 1.21.1-92 | JEI and Patchouli match; loader and FTB versions differ. |
| 1.45.0 / 100556 | 21.1.250 | 19.57.0.450 | 2101.1.36 / 2101.1.37 / 2101.1.11 | 1.21.1-93 | FTB versions match; loader, JEI and Patchouli differ. |

The rows come from the [1.29.1 manifest](https://api.feed-the-beast.com/v1/modpacks/public/modpack/125/100181) and [1.45.0 manifest](https://api.feed-the-beast.com/v1/modpacks/public/modpack/125/100556). Neither is a verified compatible instance for the current JAR. NeoForge 21.1.203 also lies outside the exclusive upper bound; sharing Minecraft 1.21.1 alone does not satisfy the present loader contract.

The real-pack acceptance gate remains open. It requires a separately validated loader/adapter compatibility contract before preparing an unchanged release for the player. The current tested ranges remain intact, and the minimal fixture remains the G1/G2 target. This metadata check proves neither startup nor live provider coverage or performance.
