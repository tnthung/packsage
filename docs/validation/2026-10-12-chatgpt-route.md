# Direct ChatGPT route validation

Date: 2026-10-12

## Automated implementation status

The direct backend now builds Responses requests with `stream: true`, `store: false`, history in `input`, and no `previous_response_id` or preview-forbidden `max_output_tokens`. It parses the account model catalog using only entries whose visibility is `list`, preserves streamed text, client tool calls and URL citations, and stops dispatch after cancellation.

Authentication implements the documented OSS route: it binds an HTTP callback to `127.0.0.1` on a dynamically selected port at `/auth/callback`, opens the exact redirect URI in the system browser, validates state, preserves an issued callback client ID before exchange, posts the authorization-code grant, fetches OpenID configuration/JWKS, validates RS256 issuer/audience/expiry/nonce/subject, requires `chatgpt.tokens.use.direct`, and persists credentials outside conversation/media files with atomic owner-only storage where supported. Refresh rotation is serialized per auth instance and sign-out uses the discovered revocation endpoint. HTTP and browser flow remain injectable for deterministic tests.

The focused tests cover request serialization, model selection, route error mapping and byte-fragmented Unicode/SSE events. The coordinator owns the first post-RED test run and build; this document does not claim a local test pass.

## Live gate

G1 is pending. No real ChatGPT account, dynamic client registration, authenticated `/v1/models` response, streamed `/v1/responses` completion, refresh rotation, tool continuation, image input or hosted search result has been observed by this implementation. The production loopback/token/JWKS path is implemented, but release readiness remains blocked until those checks are completed with the mod's own browser authorization flow.
