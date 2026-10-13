# Live history retrieval checks

Use the installed ATM10 candidate matching [history validation](../validation/2026-10-13-history-adapter.md), in the backed-up working copy `OoO - PackSage Trial`. Open PackSage with F8 and keep the game paused during requests. Turn web Search and Screenshot off.

1. Create a new chat and send a unique test marker plus a code word. Ask for a short acknowledgement and wait until the exchange completes.
2. Create another chat in the same world. Ask the model to search history for the marker, read the matching exchange and report its code word with a history citation. Do not include the code word in this request. Verify the answer, search/read tool results and source session identity. The new chat has no preceding automatic history.
3. Save/quit the trial world and rejoin it. In a fresh chat, repeat the retrieval request. Verify that the persisted exchange remains readable after the world transition.
4. In another fresh chat, search for a different unique marker that was never saved. The model must report no matching history rather than inventing a code word.

Record exact prompts, saved conversation paths, tool calls/results, original timestamps and observed UI outcomes in the validation document. These checks do not modify gameplay progression or open an original world. Automated tests cover other-world exclusion, obsolete handles, replacement/deletion and bounded scans separately.
