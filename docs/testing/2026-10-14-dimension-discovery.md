# Dimension discovery live checks

Use the rebuilt full JAR in the existing ATM10 instance and only `OoO - PackSage Trial`. Keep the player's model/search settings. Inspect actual saved call/result pairs in addition to the assistant's answer. The assistant runs automated checks and performs the authorized computer-use validation.

1. Ask `search_registry` for `dimension`, limit 2, then continue its cursor. Expect current world IDs, including native and already existing modded worlds. In private integrated singleplayer, records identify server-read runtime worlds and their dimension type when available. No world should be created or entered by searching.
2. Query `dimension` with exact IDs from step 1 and a case-insensitive text fragment. Expect OR within IDs, AND with text and no unrelated IDs. Reread a returned reference with `read_evidence`; expect a fresh read of the same ID. A tags filter must return `UNSUPPORTED_FILTER`.
3. Query `dimension_type` for `minecraft:overworld` and `minecraft:the_nether`, then an observed modded type. Expect their current environmental definitions, including height and bed/anchor rules, with type identity separate from a dimension instance. Reread one type reference.
4. If a supported dynamic-dimension fixture is already available, record its absence, have the player create it through the normal mod workflow, and repeat discovery in a new call. Expect the new runtime ID without restarting PackSage; after normal removal, a fresh query must not return the old world. Do not create/remove worlds just to manufacture proof without a prepared fixture. Automated mutation tests do not establish installed RFTools compatibility.
5. On a separately authorized remote/published-session fixture, client-known dimension IDs must be explicitly partial. Empty client results cannot establish server-wide absence, and no private integrated-server read may bypass the configured owner policy.

Record the tested artifact hash, request IDs, exact selectors/filter arguments, source authority and remaining fixture gaps in `docs/validation/2026-10-14-dimension-discovery.md`.
