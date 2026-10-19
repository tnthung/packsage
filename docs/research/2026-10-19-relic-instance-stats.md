# Relics current-instance API evidence

The approved [contract](../specs/2026-10-19-relic-instance-stats.md) extends fresh native item records. The existing guide reader uses default instances for ability definitions; generic inventory components omit successful zero defaults and expose raw rolled quality rather than effective values. Live request `51b2ec4d-9427-479c-a8c9-85cae4ae4a6b` therefore reported an empty `leveling` object and could not state the equipped boot's level or XP.

## Public API and read safety

Installed `relics-1.21.1-0.12.8.jar` exposes `IRelicItem.getRelicData(LivingEntity, ItemStack)`. `RelicData.getLevelingData()` supplies successful level, experience, rank and point getters. `getAbilitiesData().getAbilityIDs()` and `getAbilityData(String)` resolve instance abilities; `AbilityData.getStatData(String)` supplies `AbilityStatData.getQuality()` and `getValue()`. Effective values use runtime templates rather than interpreting serialized initial quality. In this API, `AbilityData.getLevel()` reads the ability's point count, so the projection must call that field `points`.

Bytecode inspection found that the data wrapper and leveling getters read the component with `getOrDefault`, but ability/stat getters may create missing defaults through `AbilitiesData.setComponent`, ultimately calling `ItemStack.set`. All projection must therefore begin with a defensive stack copy. Template getters may post `ModifyRelicTemplateEvent` and use the mod's template cache; they do not justify invoking item setters. These are API observations, not installed PackSage stat acceptance.

## Compatible version family

The official [filtered project version listing](https://api.modrinth.com/v2/project/relics-mod/version?game_versions=%5B%221.21.1%22%5D&loaders=%5B%22neoforge%22%5D) enumerates the Minecraft 1.21.1 NeoForge 0.12.x releases. Public classfile method descriptors were compared in the following publisher artifacts against installed 0.12.8:

| Version | Publisher artifact | Bytes | SHA-256 |
| --- | --- | --- | --- |
| 0.12.0 | [JAR](https://cdn.modrinth.com/data/OCJRPujW/versions/cQ706MLl/relics-1.21.1-0.12.0.jar) | 4,165,365 | `BD8928F7A0F3954004F0E904A8CCAD086B340BD57A2EB7CFF55DD6A68732067D` |
| 0.12.7 | [JAR](https://cdn.modrinth.com/data/OCJRPujW/versions/3puaIcSu/relics-1.21.1-0.12.7.jar) | 4,734,820 | `2C831834AF633FA66BC6FB9D6CBA61709B96467BBAE74B1931EDDB8AA188AD4E` |
| 0.12.9 | [JAR](https://cdn.modrinth.com/data/OCJRPujW/versions/CWPAvFMI/relics-1.21.1-0.12.9.jar) | 5,007,686 | `DFBF73A0DE0F3EC99E3765FD1DF523CE600A55473F1CD2AB7E37F6D25F89B6B8` |

The required instance getters match across 0.12.0, 0.12.7, 0.12.8 and 0.12.9. The existing guide reader's additional `AbilityData.getTemplate()` and `AbilityTemplate.getModes()` descriptors also match the sampled releases. This supports the shared family range `[0.12.0,0.13.0)` with graceful API-failure handling. It is representative API evidence, not a live test of every patch or proof of unchanged semantics in future releases.

## Controlled live fixture

The installed admin commands target the held main-hand relic for `/relics relic level SET 2` and `/relics relic experience SET 123`. Command actions are case-sensitive; lowercase `set` is rejected. After these commands on a temporary Springy Boot, the player opened its panel by holding Left Shift. The panel visibly showed level 2, and the XP tooltip showed `123/300.0` with `41.0%` progress.

The initially unresearched ability hid effective values. The verified command syntax is `/relics player research bounce true`; `ability` is an argument name, not a literal command word. After this command and another player-assisted panel opening, the Bounce description visibly showed `73%` force while level and XP remained unchanged. Although the command selects an ability from the held relic, `ResearchData.getComponent/setComponent` reads and writes the player's `RelicsAttachments.PLAYER_RESEARCH` map by research key. This fixture action therefore changes player research shared by copies of that ability; it is distinct from the boot's item-local level/XP change. No other research command was submitted.
