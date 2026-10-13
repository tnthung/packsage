# Greeting request timing validation

Date: 2026-10-14

Contracts: [request timing specification](../specs/2026-10-14-request-timing.md), [implementation plan](../plans/2026-10-14-request-timing.md).

## Build and installation

Instrumentation commit: `d2d48bd`. The full `./gradlew.bat test build --console=plain` run succeeded in 41 seconds: 378 tests, 374 passed, four Windows symlink skips, no failures or errors. Existing controller/backend fixtures exercised completion, tool continuation, cancellation and retries. No new log-capture framework or conversation schema was introduced. Manual diff review and `git diff --check` passed; no formatting tools were used.

The distribution and ATM10 installed JAR hashes both equal `BAA0B171079951C6A1EF1600C63987EB1F25DF6BBAAB57BA381935C017C2C545`. Minecraft was saved and exited normally before replacement. The restarted ATM10 v8.2 client loaded Minecraft 1.21.1, NeoForge 21.1.251 and 535 mods at GUI scale 2.

Only `OoO - PackSage Trial` was opened. The original OoO world was not opened, and no inventory, block or quest actions were performed.

## Method

Four separate fresh sessions submitted the exact two-character question `hi` using `gpt-6-luna` and `ReasoningEffort.NONE`. Search remained off in the composer, which maps to the existing `EXPLICIT_ONLY` policy. Ten local tool definitions and the existing hosted-search definition remained available; model input was not simplified for the benchmark. Each request contained zero selected historical turns and two current input messages: gameplay background, then the exact question.

Samples were run in order: screenshot on, off, off, on. All four returned only a short greeting, made zero tool calls and used one successful HTTP/2 response with status 200. There were no retries, tool continuations or encrypted-reasoning replay items. Saved records contain only `assistantOutput` replay items.

Durations below are differences between `System.nanoTime()` timestamps in the same JVM. The origin is entry to the runtime Send method, before settings preparation. First text means delivery of the first text delta to the controller, not measurement of the first rendered pixel. HTTP header arrival is timestamped before executor handoff; the callback queue delay rounded to zero milliseconds in every sample.

Safe phase logs were copied to `C:\Users\hung1\AppData\Local\Temp\packsage-request-timing-live.log`. The source log is the ATM10 instance's `logs/latest.log`; it will rotate on a later launch. These logs contain timing, counts, sizes and correlation IDs, without message bodies, credentials or item components.

## Measured phases

All durations are milliseconds. Rows partition Send-to-save, apart from the separately identified cumulative rows. Values are rounded to three decimals.

| Phase | On 1 | Off 1 | Off 2 | On 2 |
|---|---:|---:|---:|---:|
| Send to HTTP dispatch: local preparation, capture, auth and serialization | 193.079 | 3.731 | 3.054 | 79.687 |
| HTTP dispatch to response headers: network and upstream waiting combined | 1,788.243 | 1,873.248 | 1,488.744 | 2,359.765 |
| Response headers to first text delta | 579.798 | 508.089 | 384.702 | 699.570 |
| First text delta to model completion delivered to controller | 346.309 | 294.831 | 149.308 | 320.343 |
| Model completion to persistence completion | 96.707 | 3.940 | 3.467 | 9.078 |
| **Send to first text, cumulative** | **2,561.120** | **2,385.069** | **1,876.500** | **3,139.022** |
| **Send to model completion, cumulative** | **2,907.429** | **2,679.900** | **2,025.808** | **3,459.366** |
| **Send to persistence completion, cumulative** | **3,004.136** | **2,683.840** | **2,029.275** | **3,468.444** |

Additional local measurements:

| Measurement | On 1 | Off 1 | Off 2 | On 2 |
|---|---:|---:|---:|---:|
| Controller acceptance to capture-ready callback, ms | 155 | 2 | 2 | 73 |
| Screenshot framebuffer read and PNG encoding, ms | 129 | N/A | N/A | 68 |
| Request builder, ms | 11 | 0 | 0 | 3 |
| Access token availability, ms | 2 | 0 | 0 | 0 |
| JSON serialization and body publisher creation, ms | 1 | 0 | 0 | 1 |
| Request body, bytes | 550,349 | 12,397 | 12,400 | 549,922 |
| Automatic gameplay JSON, bytes | 1,486 | 1,483 | 1,486 | 1,483 |

The first stream bytes arrived approximately one millisecond after response headers in each sample. Receiving initial stream data therefore did not mean answer text was already available. No `openai-processing-ms` header was returned, so there is no provider timing that separates network transfer, queueing and input processing.

Correlation and saved results:

| Sample | Request ID | Sent at, local time | Answer |
|---|---|---|---|
| On 1 | `a643c65e-bace-4bbc-a0e4-d07ac2b6ec4b` | Oct 14, 03:43:41 | Hi! How can I help? |
| Off 1 | `bebe60c3-d8c0-49cb-aba4-ed79514a1ec8` | Oct 14, 03:45:34 | Hi! How can I help? |
| Off 2 | `07fc72be-e546-43ed-ae79-b31bf68fd933` | Oct 14, 03:47:09 | Hi! |
| On 2 | `d924ba61-3225-463e-a531-2e64de5510ba` | Oct 14, 03:49:08 | Hi! How can I help? |

## Interpretation and acceptance

The dominant observed interval was HTTP dispatch to response headers, at 1.489-2.360 seconds. Local preparation was about three milliseconds without screenshots and 80-193 milliseconds with screenshots. First text arrived at 1.877-3.139 seconds; complete saved turns took 2.029-3.468 seconds.

Screenshot capture adds measurable local work and increases the request body from about 12.4 KB to 550 KB. The two samples per setting do not isolate the image's upstream processing or upload cost from ordinary server/network variation, so the observed total difference is not a causal estimate for image latency.

The earlier 11.19-second Send-to-file interval was not reproduced. That request had no phase trace, so its exact breakdown cannot be recovered. This instrumentation changes diagnostics, not prompting, model selection, reasoning effort or request payload policy. It does not establish that the earlier delay is fixed or that future requests will stay below a fixed bound. The installed trace can identify where the next slow request waits.

These measurements do not compare Codex and PackSage under identical payloads or transport conditions. They support identifying PackSage's present bottleneck, rather than attributing all elapsed time to reasoning or model inference.

The timing task is accepted: automated behavior checks pass, four live greetings have correlated stage measurements, and the dominant measured interval and limits are documented. Prior history-rich greeting behavior remains a separate open acceptance item in [the earlier validation](2026-10-13-ui-surface-and-question-focus.md).

Minecraft is left in the paused trial chat, with Luna/none, Search off and Screenshot on restored.
