# Modern chat UI implementation

Date: 2026-10-13

Implement the [approved player reference and UI requirements](../specs/2026-10-13-modern-chat-ui.md) in small functional commits. Preserve the pre-existing edit to `2026-10-12-v1-implementation.md` and the untracked `chat.png`. Never use formatting tools. The coordinator alone runs Gradle, stages exact paths, generates messages with the commit-message skill from the index and commits; no push, merge or PR.

1. Freeze backward-compatible session/account/view/turn contracts. Add immutable session summaries and provider/account presentation metadata; old constructors and log fields retain their behavior. Commit before parallel dependent implementation.
2. Session foundation: add bounded asynchronous session catalog/open operations; implement guarded same-world switching and separate other-world read-only viewing; persist per-turn navigation targets with backward-compatible reads. Preserve current-world/model/evidence guards. Add focused storage/lifecycle tests and commit this independently when verified.
3. Custom chat/settings presentation: share concrete drawing/input controls and bundled font/icon assets. Implement the reference's sidebar, capped message width, avatars, growing composer, floating toggles and inline references. Settings use conditional account actions and model/effort radio choices. Keep screen lifecycle, current request snapshot and screenshot capture intact. UI work and session work own disjoint files.
4. Integrate settings/session actions in `ClientAssistant`; run meaningful UI/state/integration tests, full suite, bootstrap compilation and diff checks. Independently review session generation/world safety and final UI behavior. Commit fixes separately.
5. Build/install committed source with Minecraft closed through `scripts/build.ps1`. Record matching artifact hashes and automated evidence in a dated validation record; provide focused Minecraft-only player checks. Actual visual/navigation acceptance remains open until observed.

The drawing owns layout and interaction. The player confirmed dark neutral styling and read-only access to other-world sessions. No browser/web runtime, model-provider registry, animation system, arbitrary gameplay action or unrelated provider refactor is introduced.
