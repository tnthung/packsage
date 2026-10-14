# Request timing specification

Date: 2026-10-14

## Purpose

Measure where a greeting request spends time before its first text and completion. The previous Send-to-file-write measurement does not isolate capture, HTTP waiting or generation.

## Requirements

- Use monotonic clocks and correlate diagnostics with the existing request ID. Record local preparation and capture, backend entry, authentication, serialization, HTTP dispatch, response headers, first stream data, first text, response completion, tools/continuations and persistence completion.
- Record separate HTTP rounds for retries and tool continuations. Keep request contents, selected model, reasoning effort, screenshot policy and event behavior unchanged.
- Log only timing and safe metadata: model/effort, counts, sizes, status and request correlation. Never log credentials, message bodies, item components or encrypted reasoning.
- Distinguish Send-to-first-text from Send-to-completion. HTTP dispatch-to-headers includes network and upstream waiting; do not claim it measures model inference alone.
- Compare fresh-session greetings with the same model and NONE effort, with screenshots enabled and disabled. Use the backed-up ATM10 trial world.

## Acceptance

Automated checks exercise the instrumented successful, tool-continuation and cancellation paths without changing their behavior. A live validation record reports measured phase durations and identifies the dominant observed interval, with uncertainty stated for inseparable network/server work.
