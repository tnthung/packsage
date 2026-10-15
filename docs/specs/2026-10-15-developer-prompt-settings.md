# Developer prompt settings

Ctrl-clicking the assistant's settings button opens developer settings. Ordinary clicking continues to open regular settings. Developer mode is a per-open gesture, not a persistent global mode.

Developer settings contains a multiline system-prompt editor initialized with the currently effective prompt. Save persists an override in PackSage's settings and returns to the assistant; Cancel, Back or Escape discards the draft. Restore default replaces the draft with the shipped default and requires Save to apply it. Saving the shipped default clears the override, so future releases can supply a newer default. Blank and oversized prompts are rejected with visible feedback rather than silently truncating or applying them.

The prompt is selected once when building each new model request. A saved change affects subsequent questions without restarting or leaving the world. An already-built request and its tool-call loop keep their original instructions. Tool definitions, runtime capability notices, history semantics and live game reads retain their existing behavior. The editor is a deliberate developer entry point, not a security boundary.

Preserve multiline Unicode text across persistence, resize and screen reinitialization. Use the existing editor and visual style. Save failures leave the editor open and do not apply an unsaved override. The setting applies to this game instance, as other PackSage settings do.
