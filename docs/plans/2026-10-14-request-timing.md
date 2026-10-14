# Request timing implementation plan

**Spec:** [Request timing specification](../specs/2026-10-14-request-timing.md).

1. Add minimal correlated timing logs at existing controller, capture and backend boundaries. Preserve request and cancellation semantics; avoid a new telemetry framework or conversation schema.
2. Exercise the changed asynchronous paths with the existing test fixtures and focused meaningful checks. Run the full test/build suite and inspect the diff manually without formatting tools.
3. Commit instrumentation separately, package the verified artifact and replace the ATM10 JAR after a normal game shutdown.
4. Run fresh-session `hi` requests using GPT-6-Luna and NONE, web search disabled, with screenshots on and off. Read the timing logs and saved exchanges.
5. Record phase measurements and limitations in a dated validation document and commit the evidence separately.
