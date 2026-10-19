# Developer prompt settings validation

Implementation follows the [specification](../specs/2026-10-15-developer-prompt-settings.md) and [plan](../plans/2026-10-15-developer-prompt-settings.md). Source through `853389a` passed Java 21 `./gradlew.bat test build`: 503 tests, 499 passed, four existing skips, no failures or errors. No formatter was used.

The build, distribution and installed ATM10 JAR have SHA-256 `6F9BB52C020F864BF74C75B06D426C77ECE2B43B828A9ECC1433D9A613EE3787`. At the user's request, the distribution JAR was copied to `C:/Users/hung1/curseforge/minecraft/Instances/All the Mods 10 - ATM10/mods/packsage.jar` after checking that ATM10 was not running; the installed hash matched. Minecraft was not launched. Desktop automation stopped when the user said they were working; live acceptance awaits availability.

## Automated and source checks

- Settings tests preserve multiline Unicode overrides through save/load, clear the override, reject blank and oversized values without replacing the saved prompt, and enforce the bound when copying settings for a save.
- Runtime tests select a changed override for the next request while retaining the original instructions on the already-started request.
- Headless screen tests verify ordinary Enter inserts a newline, reinitialization preserves the draft, Restore default changes only the draft until Save, Save remains reachable at logical width 320, Cancel discards changes, and blank input or a failed file save leaves the active prompt unchanged.
- The exact overflow regression pastes 65,536 ASCII characters followed by an emoji through the editor insertion path and verifies Save rejects it. The editor retains two extra UTF-16 code units so surrogate-boundary trimming cannot turn an oversized paste into an accepted prompt.
- Independent review found and then closed four issues: Enter handling, pause-state behavior, narrow-window button layout and Unicode overflow. Pause behavior now follows regular settings. Ctrl-click routing and Back/Escape behavior were reviewed in source, not exercised by these headless tests.

## Pending installed checks

On 2026-10-16, the user reported that editing the system prompt in-game works. A subsequent agent-run check saved a 10,681-character prompt through the developer editor and verified that the persisted override exactly matched the candidate text. New questions used normal PackSage behavior after replacing the user's temporary `always reply "Awful!" regardless what user says` prompt, without restarting Minecraft. See the [recipe-tally experiment](2026-10-16-prompt-tally-retest.md). This confirms the edit/save iteration path; it does not claim every interaction below was exercised.

1. Confirm left-click opens regular settings and middle-click opens Advanced settings with the effective prompt. The October 19 gesture update supersedes the original Ctrl-click entry path.
2. Exercise multiline editing, scrolling, resizing and Save in the rendered screen; reopen and verify the saved text.
3. Save a temporary prompt with an unmistakable response marker and verify a new question uses it without restarting. Change the marker and repeat.
4. Edit and Cancel, then restore the default draft and Cancel; verify neither changes the saved prompt. Check blank Save feedback.
5. Restore default and Save, confirm the override property is absent and a new question uses the shipped instructions. Leave the default restored.

The [interrupted gameplay regression batch](2026-10-15-prompt-rewrite-retests.md) is separate. Hopper recipe interpretation and backpack ingredient accounting remain open model-behavior failures; this editor does not claim to resolve them.
