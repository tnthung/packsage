# Compact chat controls

This revision replaces the corresponding toolbar and settings choices in the chat-controls specification.

- Each session row has a deletion control with an explicit confirmation. Deletion removes that session only; deleting the selected session starts an empty chat in the joined world. Active requests block deletion.
- Model and reasoning selectors remain in the composer only. Both open upward, use the button width, and reveal overflowing option labels by scrolling them while hovered. Reasoning uses a brain icon and the selected effort name without an "effort" suffix.
- Use Minecraft GUI coordinates and compact control/font dimensions comparable to native inventory UI. Preserve GUI-scale handling, multiline growth and readable maximum width.
- Composer toolbar buttons share a width and wrap. Enabled Search/Screenshot toggles retain a distinct enabled appearance while hovered. Model, effort, Search and Screenshot tooltips fit their text with padding, within the existing screen-width cap.
- The composer row contains the square information icon, context meter, editor, then Send/Stop. The meter is half a toolbar button's width, with a solid percentage fill and no text. Both tooltips use concise labels, readable values and an opaque background; raw block states, component dumps and tokenizer implementation details stay out of them.
- The information tooltip shows current coordinates, dimension, biome, held item and looked-at target. Observe these values live without starting a request or capturing a screenshot; capture them again when sending. Screenshot attachment remains a separate toggle.
- Message and history counts estimate tokenized replay text rather than serialized JSON bytes. Use the same counter for history selection and per-role counts, label estimates honestly, and retain whole-exchange quotas and the model/configured limit clamp.
- User message metadata shows the local send date/time before the token count. Capture and persist submission time for sends and edited resends, including failed/cancelled messages; old records without a timestamp omit it rather than inventing one.
- Center the account action label horizontally within its button for every sign-in state.
