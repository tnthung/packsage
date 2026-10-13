# UI assets

Inter is bundled from the [Google Fonts Inter distribution](https://github.com/google/fonts/tree/main/ofl/inter), under the SIL Open Font License in `font/OFL.txt`. `font/ui.ttf` is the pinned variable family file (SHA-256 `29160A80FF49DDCAB2C97711247E08B1FAB27A484A329CE8B813D820DC559031`); `font/ui_bold.ttf` is the pinned SemiBold face from [Inter 4.1](https://github.com/rsms/inter/releases/tag/v4.1) (SHA-256 `78A843FADE9D4612A5567302FB595B56976EB5FCEBF4FEA5A5912D638BAFCDE3`).

The white 64-pixel control icons are rasterized from [Phosphor regular SVGs](https://github.com/phosphor-icons/core/tree/main/assets/regular), under the MIT license in `textures/gui/icons/LICENSE-Phosphor.txt`.

The OpenAI provider mark is rasterized from [Simple Icons 14.15.0](https://github.com/simple-icons/simple-icons/blob/14.15.0/icons/openai.svg), under the CC0 license in `textures/gui/icons/LICENSE-SimpleIcons.md`. It is selected only for a backend reporting the `openai` provider identity.

SVG rasterization used resvg 2.6.2 during asset preparation. No browser or Node runtime is required by the mod.
