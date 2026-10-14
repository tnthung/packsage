# Live dimension transition

Follow-up to [dimension discovery validation](2026-10-14-dimension-discovery.md), using the same installed artifact and `OoO - PackSage Trial`. The player authorized teleporting with existing waypoints. Computer-use opened JourneyMap and used the saved Base waypoint to travel from `compactmachines:compact_world` to `minecraft:overworld`, at (162, 64, 195). No items were moved; the player was left at that waypoint with PackSage closed.

Request `DIMENSION_LIVE_C`, ID `2eec60f0-e68e-4bc9-afdf-6d435148d1e9`, completed on 2026-10-15 at about 06:07 Asia/Taipei. Evidence is in the same replay file identified by the previous report. Five actual tool calls were paired with their results by call ID:

| Call | Check | Result |
|---|---|---|
| `call_ItnZDkSsg4sa78KazLWTMiyx` | Read previous `native:dimension_type:compactmachines:compact_world` before rediscovery | ERROR: `INVALID_REFERENCE: handle was not observed in this scope; repeat discovery`. |
| `call_pCw2QzC1vVv4hkSg9WwKfm0W` | Player inventory, limit 32 | COMPLETE; 18 current records in the new scope. This call does not itself establish the current dimension. |
| `call_HIxA5HjeI2Jt4OPekZjwRSl8` | dimension, native provider, exact IDs `minecraft:overworld` and `compactmachines:compact_world`, limit 16 | Both worlds returned with matching type IDs, COMPLETE / SERVER_READ. Invalidating an old reference did not imply the old world disappeared. |
| `call_dJLQXB9YmAR2otgjcpLt1XRv` | dimension_type, native provider, exact ID `minecraft:overworld`, limit 16 | COMPLETE / LOADED, with current environmental definition. |
| `call_2cjIVH4Yq2LJJ2yWSwhSaI4x` | Read freshly discovered Overworld type reference | COMPLETE / LOADED; observedAt advanced from `2026-10-14T22:07:15.089810500Z` to `2026-10-14T22:07:19.339941700Z`. |

The saved request gameplay context independently reports `dimension=minecraft:overworld`, position (162, 64, 195), and biome `minecraft:plains`. The world-session suffix changed from `503bd042-76a6-47ff-bdb9-a0a55834a178` to `fee05e27-c9ee-4a40-b7d7-28da8d302b36`, and provider runtime changed from `runtime-5` to `runtime-7`.

Acceptance: live dimension transition invalidates prior observed handles, updates request context/scope, and permits fresh searches and reference reads. This adds transition evidence; it does not test dynamic world creation/removal or remote-server fallback. No implementation changes or additional automated test run were needed.
