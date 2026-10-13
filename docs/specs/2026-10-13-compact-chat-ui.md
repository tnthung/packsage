# Compact chat controls

This revision replaces the corresponding toolbar and settings choices in the chat-controls specification.

- Each session row has a deletion control with an explicit confirmation. Deletion removes that session only; deleting the selected session starts an empty chat in the joined world. Active requests block deletion.
- Model and reasoning selectors remain in the composer only. Both open upward, use the button width, and reveal overflowing option labels by scrolling them while hovered. Reasoning uses a brain icon and the selected effort name without an "effort" suffix.
- Use Minecraft GUI coordinates and compact control/font dimensions comparable to native inventory UI. Preserve GUI-scale handling, multiline growth and readable maximum width.
- Composer toolbar buttons share a width and wrap. The context meter is half that width: a solid percentage-filled block without text, retaining its quota/usage tooltip.
- A square icon-only information control precedes the editor, followed by the editor and Send/Stop. Its tooltip shows the current values for coordinates, dimension, biome, held item and looked-at target. Observe these values live without starting a request or capturing a screenshot; capture them again when sending. Screenshot attachment remains a separate toggle.
- Message and history counts estimate tokenized replay text rather than serialized JSON bytes. Use the same counter for history selection and per-role counts, label estimates honestly, and retain whole-exchange quotas and the model/configured limit clamp.
