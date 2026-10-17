# Chat and settings UI

Date: 2026-10-13

This specification records the player's supplied chat/settings sketches and accompanying instructions. It extends the [v1 specification](2026-10-12-v1.md) without changing model, evidence or gameplay permissions.

## Presentation

Use custom drawn controls and a consistent modern neutral theme, with a readable bundled font, modest rounded surfaces and one muted accent. Rounded surfaces use curved corners and a continuous outline, rather than rectangular strips or clipped square corners. Keep the design compact and functional. The red areas in the sketches indicate unused width, not colored UI panels. Center the chat/composer within the available main region and cap its readable width; center settings with the same width rule and styling. Adapt to Minecraft GUI scale and smaller windows.

The sidebar lists sessions grouped by world/server, with the active world first. Each row shows a title from the initial question and a recent message preview. Include a session search field, New chat and group expansion where needed. Bottom-left controls are a gear for Settings and a pause/resume control, hidden outside private singleplayer.

The player's messages align right with the player's skin head. Model messages align left with a square `Ps` placeholder. Navigation/source controls are inline parts of the response at the cited/reference position, rather than a separate button collection. Local and external navigation still use existing validated actions. Historical targets remain visible but may be stale when clicked; the UI must preserve the answer and show the navigation result.

For local citations bound to recorded evidence, preserve descriptive Markdown link text instead of replacing it with a generic provider tag. Hover shows a bounded preview of the recorded facts, including inventory item/count/location or recipe inputs/output when available, and identifies it as a snapshot attached to the answer. Non-navigable recorded sources open read-only details on click; existing navigable citations retain their validated navigation action. Match provider, handle, scope, observation time and generation before displaying a replay payload. Missing or mismatched snapshots remain explicitly unavailable. Do not invent navigation, read current game state on hover, expose raw payload JSON, or imply that recorded evidence proves more than it contains.

## Composer

The bottom composer accepts multiline text and grows upward to a bounded height, then scrolls within the input. Search and Screenshot controls float immediately above it and move as it grows. The icon Send control becomes Stop immediately when a request is accepted, stays Stop during capture, model calls and tool work, and returns to Send after completion, failure or cancellation. Enter sends; Shift+Enter inserts a newline. Preserve the 1,000-character limit and draft across resize and temporary screenshot capture. Keep request activity and failures readable without dumping internal data.

## Sessions

List stored sessions asynchronously through the existing conversation store. Selecting another session cancels the active request and uses generation guards for late loads/results. Same-world sessions can continue with fresh current context. Sessions belonging to another world/server open read-only until that world/server is joined; New chat always starts in the active game world. Viewing history must not replace the operational world, provider scope or knowledge connection.

Persist navigation targets with their answer turns. Old logs without this field remain readable. Keep exact target/source binding and current-scope validation at click time; do not relax provider lifecycle checks for UI convenience.

## Settings

Use the same surfaces, typography and controls as chat. Show the account/provider section with identity supplied by the actual backend; use the connected provider's icon, with a generic fallback for unknown providers. Display Sign in, Cancel sign-in or Sign out according to authentication state.

Use dedicated radio choices for account-visible models and reasoning efforts, not a single cycling button. Keep provider Default effort explicit. Preserve existing automatic-search, open-paused and history limits. Back/Escape save valid changes and return to the same chat. Authentication and setting changes do not alter an active request's immutable model/effort snapshot.

## Verification

The assistant owns compilation, unit/integration tests, assets, builds and installation. Automated checks cover editing/selection/wrapping and layout bounds, session/catalog/world isolation and late results, target persistence and legacy logs, inline source placement, account actions and selection behavior. The player verifies actual Minecraft rendering, input, GUI scales, pause, session selection and navigation. No build or headless layout check is claimed as live visual proof.
