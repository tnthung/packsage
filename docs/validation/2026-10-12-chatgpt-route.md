# Direct ChatGPT route validation

Date: 2026-10-12

## Automated implementation status

The adapter sends the documented public Responses request with `stream: true`, `store: false`, ordered typed input and no `previous_response_id` or preview-forbidden output limit. Messages, base64 image data URLs, encrypted reasoning, namespaced client function calls/results, assistant output text/refusals/URL citations and hosted web-search calls have explicit serializers. Stateless client-tool continuation replays every provider output item and submitted result in the same order used by the continuation, including failed or incomplete web-search calls that omit optional action data. Unknown or malformed provider output fails visibly instead of entering durable history.

The SSE parser accepts LF and CRLF framing across arbitrary byte boundaries. It derives function identity from `response.output_item.done`, cross-checks the official arguments-done event, preserves completed web-search and URL-citation metadata, and emits one terminal failure for provider errors, incomplete responses, invalid events or EOF before `response.completed`. Non-2xx failures retain the status, provider code/message/parameter and request ID.

Authentication implements the documented OSS route with a bounded exact loopback callback, PKCE, state and nonce. ID tokens require a unique RS256 signing JWK, a valid signature, exact issuer/nonce, matching string or array audience, numeric unexpired `exp` and a nonblank subject. Issued client, subject and host identity survive sign-out separately from tokens. Credential and registration records use atomic owner-only storage under per-user application data; refresh rotation is serialized, terminal `invalid_grant` clears unusable tokens, and session epochs prevent refresh or sign-in completions from restoring an old session after cancel/sign-out. Sign-out becomes inactive in memory immediately, retains the old durable refresh token through three asynchronous discovery and revocation attempts with bounded backoff, and deletes it only after the terminal outcome while fencing newer saved credentials. Repeated sign-out requests share the pending outcome so the visible confirmed or unconfirmed result is retained.

Catalog slug/display name remains authoritative while undocumented image/search support begins unknown and context/output limits remain unknown. A completed image request or returned hosted-search call verifies support for that account and model. A capability-specific unsupported response records rejection; later requests omit only the explicitly rejected image/search feature and explain the local fallback.

The visible Minecraft probe provides sign-in, cancel, sign-out, account model selection, text, bounded read-only tool, synthetic image, explicit search and stop controls with streamed response, citation metadata, validated clickable HTTP(S) citation buttons, errors and confirmed/unconfirmed revocation status. It does not itself establish account or route eligibility.

Focused source validation used Java 21-compatible `javac` and a Java 21 runtime against Gson 2.10.1, JUnit 5.11.3 and the resolved NeoForge 21.1.202 artifacts. The current focused auth, request, continuation and SSE suite passes 34 tests. The probe and its citation URL validation test compile against the resolved Minecraft/NeoForge classpath, and that test passes on Java 21. The coordinator owns the repository Gradle and full-suite run.

## Live gate

The player completed the rebuilt probe in the `Packsage` instance after browser-launch fix `8de090e`. Four screenshots show the authenticated catalog selecting `GPT-6-Luna` and completed text, client-tool, image and hosted-search requests:

| Check | Observed result |
|---|---|
| Text | `G1 request completed`, answer `ready`. |
| Read-only tool | `G1 request completed`, answer `{"fixture":"packsage-read-only"}`. This is the probe tool, not installed-pack retrieval. |
| Synthetic image | Completed image request with an image description. This establishes input acceptance for the selected account/model; the tiny synthetic image does not establish visual accuracy. |
| Hosted search | Completed `web_search_call`, release-date answer, returned Minecraft source URL/citation and an `Open citation 1` button. |

The G1 prerequisite for activating the full assistant is satisfied: sign-in/catalog, inference and client-tool exchange worked through the real route. The earlier blurred text and unavailable-browser failures are also superseded by these readable authenticated results.

The screenshots do not establish intermediate streamed-frame behavior, clicking a citation, Stop/cancel, refresh, revocation or recovery. Those observations remain open, as do installed-pack retrieval and full-assistant gameplay checks. No credential contents or callback URLs are recorded.
