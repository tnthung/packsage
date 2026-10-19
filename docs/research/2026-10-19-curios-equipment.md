# Curios equipment read API

The installed ATM10 artifact is `curios-neoforge-9.5.1+1.21.1.jar`. A read-only API inspection with the workspace Java 21 `javap` confirmed the following public signatures. The same signatures were verified in the official oldest published NeoForge 1.21.1 API artifact, [Curios 9.2.0+1.21.1](https://maven.theillusivec4.top/top/theillusivec4/curios/curios-neoforge/9.2.0%2B1.21.1/curios-neoforge-9.2.0%2B1.21.1-api.jar).

| Public API | Result |
|---|---|
| `CuriosApi.getCuriosInventory(LivingEntity)` | `Optional<ICuriosItemHandler>` |
| `ICuriosItemHandler.getCurios()` | `Map<String, ICurioStacksHandler>` |
| `ICurioStacksHandler.getStacks()` | `IDynamicStackHandler` for functional equipment |
| `ICurioStacksHandler.getCosmeticStacks()` | Separate cosmetic handler, outside the approved scope |
| `IDynamicStackHandler.getSlots()` | Current size of that slot type's functional handler |
| `IDynamicStackHandler.getStackInSlot(int)` | Current stack at the type-local index |

The map key identifies the slot type. Preserve it together with the local index; two slot types may both contain index zero. The functional handler is the equipment source even when an item's rendering is hidden. An absent capability does not establish empty equipment. Use public API interface methods when invoking wrappers reflectively, rather than depending on a concrete implementation class's visibility.

The [official 1.21.1 file listing](https://www.curseforge.com/minecraft/mc-mods/curios/files/all?page=1&pageSize=20&version=1.21.1) starts at 9.2.0 and lists the intervening 9.x releases through the installed 9.5.1. The adapter targets normalized versions `[9.2.0,10.0.0)`, accepting the `+1.21.1` label while preserving the actual loaded version. This is the same read API family; it is not an assertion that every release received live PackSage testing.

The [Accessories Compat Layer](https://github.com/wisp-forest/accessories-compat-layer/tree/1.21.1) wraps Curios through Accessories. Its own minimum Curios version is an ecosystem compatibility requirement, not the minimum version of this read API. PackSage reads only equipment exposed through the Curios interfaces and does not directly integrate another accessory API. Compatibility-layer active-state limitations prevent claiming more than the functional handler actually reports.

The upstream `1.21.x` branch has moved to newer Minecraft types. Installed and versioned artifact signatures govern this implementation; current-branch source alone is insufficient evidence for the 1.21.1 binary contract. API inspection does not prove installed Minecraft acceptance or synchronization behavior in every mod combination.
