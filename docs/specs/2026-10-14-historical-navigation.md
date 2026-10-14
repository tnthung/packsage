# Historical navigation and status clipping

Date: 2026-10-14.

The player reports that only the latest quest references open and navigation failure text is clipped near the composer.

- A recorded local reference in the displayed current-world conversation must remain actionable after a follow-up or session restore. Revalidate its exact provider/source/destination against fresh current provider evidence before opening it. Do not treat the historical observation as current evidence or add it to the model's request-scoped evidence cache.
- Keep exact target/source binding, read-only other-world sessions, current world/resource/player/provider checks and provider-specific lifecycle validation. Missing, replaced or unsupported destinations remain unavailable/stale; arbitrary text is not a navigation target. External URL handling retains its existing validation.
- Include the conversation's initial eight-pixel inset in measured scroll height so the final status line fits inside the existing transcript viewport. Keep status placement and composer geometry.
- Verify navigation across turns/restored sessions, rejection of unobserved or malformed current targets and late resolution after invalidation. Run existing scroll tests for the geometry correction; automated checks do not establish actual rendered glyph visibility.
