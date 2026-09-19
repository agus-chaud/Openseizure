# Tasks: watch-osd-message-delivery (Option F — Phone Companion Bridge)

Source artifacts: `sdd/watch-osd-message-delivery/proposal` (#1198), `.../spec` (#1199),
`.../design` (#1200 + `openspec/changes/watch-osd-message-delivery/design.md`),
`architecture/seizureguard-aw-fix-direction` (#1196, watchdog-constant approval).

Requirement shorthand used for traceability:
- `PCB-1..9` = spec capability `phone-companion-bridge`, requirements 1–9 (shared identity/signing,
  minSdk 26, MessageClient listener, HTTP forward to OSD, works with unmodified OSD release,
  alarm-state retrieval+relay+staleness, failure notification, 8h survival, version handshake).
- `WCT-1..8` = spec capability `watch-companion-transport`, requirements 1–8 (send accel_data, send
  settings, accept alarm_state, staleness handling, DEC-048 delivery-health repoint, direct-to-OSD
  behind flag, exposed contract version, no other :wear changes).
- `PROP-SC` = proposal success criteria (OSD sees live data; alarm round-trip; ≤60s visible fault;
  clean 8h run; no OSD changes; safety-reviewer PASS before merge).

---

## GATE-0 — WearSD + OSD-beta hardware validation experiment (BLOCKING, human-executed)

- [ ] **GATE-0.1** Run `docs/EXPERIMENTO_WEARSD_OSD_BETA.md` end to end on real hardware (watch +
      Garmin-source OSD beta APK). Confirm OSD (unmodified release/beta APK, data source =
      "Garmin") actually accepts and displays data pushed to `SdWebServer:8080` the way the design's
      pinned HTTP contract assumes.
      Satisfies: locked decision #5 (proposal #1198), design "Migration/Rollout" item 3.
      Parallel/non-blocking for planning — but **`sdd-apply` MUST NOT start any code task below
      until this gate has a recorded PASS.** This is a human-on-hardware task; no agent executes it.
      Dependencies: none (may run any time before code starts).

---

## Batch 1 — Shared signing foundation

- [x] **T1.1** Create `signing.gradle.kts` (root) — one `signingConfigs` block reading a gitignored
      `keystore.properties`, applicable from both `:wear` and `:phone`. New file.
      Satisfies: PCB-1 (shared applicationId+cert so Wear Data Layer AppKey matches), design
      Architecture Decision #1.
- [x] **T1.2** Add `keystore.properties.template` (placeholder keys, no real secrets) + update
      `.gitignore` to exclude `keystore.properties`. New/modified.
      Satisfies: PCB-1 (same rationale; prevents committing signing secrets).
- [x] **T1.3** Wire `signing.gradle.kts` into the root `build.gradle.kts` (or via
      `apply(from = ...)` per-module) so both modules can reference the shared config in later
      batches. Modified.
      Dependencies: T1.1.
      Note: this batch does NOT touch `wear/build.gradle.kts` or `phone/build.gradle.kts` yet — the
      per-module `signingConfigs` reference lands with each module's own batch (T2.x, T7.x) to keep
      this PR small and low-risk.

Parallelizable: T1.1/T1.2 in parallel; T1.3 sequential after T1.1.

---

## Batch 2 — `:phone` module scaffold

- [x] **T2.1** `settings.gradle.kts`: `include(":phone")`, replace the 2026-06-05 removal comment.
      Modified.
      Satisfies: PCB-1, PCB-2.
      Dependencies: none (independent of Batch 1, but conventionally lands together).
- [x] **T2.2** `phone/build.gradle.kts` — minSdk 26, targetSdk 34, `applicationId
      com.seizureguard.wear`, `namespace com.seizureguard.phone`, reference shared
      `signingConfigs` from T1.1, zero new dependencies (reuse `libs.versions.toml` entries already
      used by `:wear`: core-ktx, play-services-wearable, coroutines, lifecycle; HTTP via
      `HttpURLConnection`, no OkHttp/Retrofit). New file.
      Satisfies: PCB-1, PCB-2, design Architecture Decision #1.
      Dependencies: T1.1, T1.3.
- [x] **T2.3** `phone/src/main/AndroidManifest.xml` — baseline application manifest (application
      class placeholder, `INTERNET` permission, launcher icon/theme). FGS-specific entries
      (`connectedDevice`, `WAKE_LOCK`, `POST_NOTIFICATIONS`, `BLUETOOTH_CONNECT`,
      `RECEIVE_BOOT_COMPLETED`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`) are added incrementally in
      Batches 5–6 alongside the components that need them, to keep each PR's permission surface
      auditable against the component that declares it. New file.
      Satisfies: PCB-1.
      Dependencies: T2.2.
- [x] **T2.4** Minimal resource scaffold (`strings.xml`, `themes.xml`, launcher icon reuse from
      `:wear` or a placeholder) so the module builds and installs as an empty app. New.
      Dependencies: T2.3.

Parallelizable: none within this batch (strict gradle/manifest dependency chain).

---

## Batch 3a — Pure codec/parser units + tests

- [x] **T3.1** `phone/.../bridge/OsdPayloadCodec.kt` — `rawDataJson`, `settingsJson`, `formBody`
      per the pinned OSD HTTP contract (`dataType`/`data` shape, 3 mandatory settings ints,
      `URLEncoder.encode(json, "UTF-8")` form body). New, pure/JVM-testable.
      Satisfies: PCB-4, design "Pinned OSD HTTP contract" table, gotcha #1 (no null guard on
      `/settings` — form-urlencoded body is mandatory).
      Dependencies: T2.2 (module must exist).
- [x] **T3.2** `phone/src/test/.../OsdPayloadCodecTest.kt` — schema assertions, mandatory-field
      presence, percent-encoding contains no raw CR/LF (gotcha #2). New.
      Dependencies: T3.1.
- [x] **T3.3** `phone/.../bridge/OsdResponseParser.kt` — `PostOutcome` enum + `classify(httpCode,
      body)`, incl. `WRONG_DATASOURCE` detection via OSD's untouched placeholder string. New, pure.
      Satisfies: PCB-4, PCB-5, design Interfaces/Contracts.
      Dependencies: T2.2.
- [x] **T3.4** `phone/src/test/.../OsdResponseParserTest.kt` — OK / SEND_SETTINGS / OSD_PARSE_ERROR
      / WRONG_DATASOURCE / UNREACHABLE cases. New.
      Dependencies: T3.3.

Parallelizable: {T3.1+T3.2} and {T3.3+T3.4} can run in parallel (independent files); both need T2.2.

---

## Batch 3b — BridgeHealth + loopback integration test

- [x] **T3.5** `phone/.../bridge/BridgeHealth.kt` — `BridgeFault` enum + `evaluate(...)` boundary
      logic (`NO_WATCH_DATA` >30s, `OSD_UNREACHABLE` ≥3 consecutive failures or >20s since last
      POST OK, latched `OSD_WRONG_DATASOURCE`/`OSD_REJECTS_DATA`). New, pure.
      Satisfies: PCB-7 (failure surfacing thresholds), design Interfaces/Contracts.
      Dependencies: T3.3 (`PostOutcome` type reused).
- [x] **T3.6** `phone/src/test/.../BridgeHealthTest.kt` — boundary cases for each fault, incl.
      recovery transition back to `NONE`. New.
      Dependencies: T3.5.
- [x] **T3.7** Loopback integration test: local `ServerSocket`-based stub server in a JVM test
      asserting the exact wire bytes OSD's `NanoHTTPD.parseBody()`/`decodeParms` would accept
      (content-type, `dataObj=` prefix, percent-encoding round-trip). New test file.
      Satisfies: design "Testing Strategy" table row "Integration (no OSD)".
      Dependencies: T3.1 (codec produces the body under test).

Parallelizable: T3.5/T3.6 sequential; T3.7 can run in parallel with T3.5/T3.6 (only depends on T3.1).

---

## Batch 4 — `OsdHttpForwarder`

- [x] **T4.1** `phone/.../bridge/OsdHttpForwarder.kt` — `HttpURLConnection` POST/GET to
      `127.0.0.1:8080` only (never `0.0.0.0`/LAN), 4s connect / 4s read timeout, `Connection: close`,
      no retry/no queue/no batching (one POST per received chunk). New.
      Satisfies: PCB-4, PCB-5, design Architecture Decisions #2–#3, Threat Matrix row 1 (loopback
      binding).
      Dependencies: T3.1, T3.3 (uses codec output + classifies response).
- [x] **T4.2** `phone/src/test/.../OsdHttpForwarderTest.kt` — timeout behavior, exact loopback
      target, response classification wiring (can reuse the Batch 3b stub server). Modified/New.
      Dependencies: T4.1, T3.7.

Parallelizable: none (sequential within batch).

---

## Batch 5a — `OsdBridgeService` core

- [x] **T5.1** Extend `phone/src/main/AndroidManifest.xml` — FGS type `connectedDevice`,
      `BLUETOOTH_CONNECT`, `WAKE_LOCK`, `POST_NOTIFICATIONS`. Modified.
      Satisfies: PCB-8, design Architecture Decision #8.
      Dependencies: T2.3.
- [x] **T5.2** `phone/.../bridge/OsdBridgeService.kt` — foreground service: `MessageClient` listener
      for `/osd/accel_data` and `/osd/settings`, `PARTIAL_WAKE_LOCK` (10h timeout, renewed each 10s
      tick), POST loop (pass-through, no batching), event-driven `GET /data` poll (250ms
      post-POST + 5s idle safety poll), health tick, `START_STICKY`. New.
      Satisfies: PCB-3, PCB-4, PCB-6, PCB-8, design Architecture Decisions #2, #4, #8, Threat
      Matrix row 2 (validate JSON shape/array length before forwarding, never forward malformed
      input to OSD).
      Dependencies: T3.1, T3.3, T3.5, T4.1, T5.1.

Parallelizable: none (T5.2 is the integration point for all Batch 3/4 units).

---

## Batch 5b — `AlarmStateRelay` + `BridgeNotifications`

- [x] **T5.3** `phone/.../bridge/AlarmStateRelay.kt` — relays alarm state to the watch via
      `/osd/alarm_state` (`{"alarm_state","alarm_phrase"}`) on change and as a 10s keep-alive; never
      fabricates an `alarmState` on companion-side failure (silence, not a fake FAULT code). New.
      Satisfies: PCB-6, WCT-3, WCT-4, design Architecture Decisions #4, #6.
      Dependencies: T5.2 (consumes the poll loop's `GET /data` result).
- [x] **T5.4** `phone/.../bridge/BridgeNotifications.kt` — two channels: `osd_bridge_status`
      (LOW, ongoing, required by the FGS) and `osd_bridge_fault` (HIGH, ongoing,
      `CATEGORY_ERROR`, sound+vibration, re-posted every 60s while the fault stands). Explicit,
      actionable, non-technical text per fault type. New.
      Satisfies: PCB-7, PROP-SC (visible fault within 60s), design "Notification channels".
      Dependencies: T3.5 (consumes `BridgeFault`), T5.2.
- [x] **T5.5** `phone/src/test/.../OsdBridgeServiceTest.kt` (Robolectric) — listener wiring, health
      tick drives fault/recovery transitions, notification re-post cadence. New.
      Dependencies: T5.2, T5.3, T5.4.

Parallelizable: T5.3 and T5.4 can be developed in parallel (both depend only on T5.2); T5.5 last.

---

## Batch 5c — Relay freshness / fail-loud (safety finding F1)

- [x] **T5c.1** `phone/.../bridge/OsdDataFreshness.kt` + `AlarmStateRelay.kt` — freshness tracker over OSD's
      `dataTimeStr` (last distinct value, injectable clock, `OSD_DATA_FRESH_MS` 15s) and relay asymmetry:
      `alarmState >= 1` always relayed; `0` (change + keep-alive) only if `BridgeFault == NONE` and data fresh,
      else silence. Cites safety-review-pre-batch7 F1. Modified/New.
- [x] **T5c.2** `BridgeFault.OSD_DATA_STALE` (POST OK but OSD data timestamp stale) in `BridgeHealth`/`BridgeState`
      + caregiver notification text in `BridgeNotifications`/`strings.xml`. Cites safety-review-pre-batch7 F1.
      Dependencies: T5c.1.
- [x] **T5c.3** Frozen-state tests: alarm 1/2/3 relayed under fault + stale data; 0 withheld under fault / frozen
      timestamp; 0 resumes on advance; tracker boundaries; `OSD_DATA_STALE` health/state/notification. Cites
      safety-review-pre-batch7 F1. Dependencies: T5c.1, T5c.2.

---

## Batch 6 — `SetupActivity` + `BootReceiver`

- [x] **T6.1** Extend `phone/src/main/AndroidManifest.xml` — `RECEIVE_BOOT_COMPLETED`,
      `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, activity/receiver declarations. Modified.
      Satisfies: PCB-8.
      Dependencies: T5.1.
- [x] **T6.2** `phone/.../SetupActivity.kt` — one-shot setup only (no status/receiving screen):
      request `POST_NOTIFICATIONS` permission, request battery-optimisation exemption, start/stop
      the bridge service, Garmin-data-source instructions text. New.
      Satisfies: PCB-7 (no status UI, headless), PCB-8, design "Notification channels" closing note.
      Dependencies: T5.2 (starts/stops `OsdBridgeService`), T6.1.
- [x] **T6.3** `phone/.../boot/BootReceiver.kt` — resumes the bridge service on boot if
      `was_bridging` flag is set; on FGS-type boot-eligibility failure, degrade to a high-importance
      "restart the bridge" notification instead of failing silently (Open Question, design). New.
      Satisfies: PCB-8.
      Dependencies: T5.2, T5.4 (reuses fault-notification channel for the degrade path), T6.1.

Parallelizable: T6.2 and T6.3 in parallel after T6.1; both need T5.2/T5.4.

---

## Batch 5d — Silent faults, fault log, morning summary (DEC-057)

Policy: system faults never produce sound/vibration/heads-up/repeats; only a real alarm (OSD's job)
interrupts. Fault detection, latch and `AlarmStateRelay` are unchanged. `:phone` only.

- [x] **T5d.1** Silent fault presentation: new LOW channel `osd_bridge_fault_silent` (no sound/vibration/lights),
      legacy `osd_bridge_fault` deleted, no timer re-post (`faultAction` posts only on new/changed fault),
      `postStartFailure`/`postRestartNeeded` silent too. Plus T5d.4 setup text (delivered together in PR 5d-1).
- [ ] **T5d.2** `FaultLog` (bounded, persisted fault periods from health-tick transitions) + service-down
      detection (last-alive timestamp, gap > threshold with `was_bridging` and no clean stop => `SERVICE_DOWN`).
- [ ] **T5d.3** Silent morning summary: 08:00 inexact `setAndAllowWhileIdle` alarm -> manifest receiver ->
      LOW channel `osd_bridge_summary`; pure `buildSummary`; armed by `SetupActivity` Start and `BootReceiver`.
- [x] **T5d.4** Setup instructions: turn OFF OSD "Enable Audible System FaultWarnings"; keep phone charging
      overnight; keep Garmin data source / web server instructions.

---

## Batch 7 — `:wear` retargeting (highest risk — life-safety-adjacent, land after companion is
provably correct in isolation)

- [ ] **T7.1** `wear/build.gradle.kts` — `transport` product flavor dimension: `companion` (default,
      first-declared) and `osdDirect` (overrides only `applicationId =
      "uk.org.openseizuredetector"` + `buildConfigField OSD_DIRECT_MODE`); reference the shared
      `signingConfigs` from T1.1. No new flavor source sets — both flavors compile the same
      sources. Modified.
      Satisfies: WCT-6, design Architecture Decision #7.
      Dependencies: T1.1, T1.3.
- [ ] **T7.2** `wear/.../WearDataLayerManager.kt` (or equivalent peer-facing class) — destination
      peer of DEC-046 messages changes to the companion node; direct-to-OSD path retained behind
      `OSD_DIRECT_MODE`, not deleted. Modified.
      Satisfies: WCT-1, WCT-2, WCT-6, WCT-8 (no other :wear changes beyond this).
      Dependencies: T7.1.
- [ ] **T7.3** `wear/.../service/SeizureMonitorService.kt` — add `lastAlarmStateAtMs` field/param,
      extend `evaluateHealth(...)` signature, update constants: `WATCHDOG_INTERVAL_MS` 30s→**10s**,
      `DELIVERY_STALE_MS` 60s→**40s**, new `ALARM_STATE_STALE_MS` **40s**; warm-up 60s and
      hysteresis (2 ticks) unchanged. **These constants require a human signature in
      `CLINICAL_SIGNOFF.md` before merge** (safety-reviewer BLOCK condition 4). `sendToAllNodes` success continues to
      mean "GMS transport ack", not "OSD answered"; inbound alarm-state staleness is the true
      end-to-end liveness signal. Modified.
      Satisfies: WCT-3, WCT-4, WCT-5, design Architecture Decision #5.
      Dependencies: T7.2 (needs the companion-facing send/receive path to plug staleness into).
- [ ] **T7.4** `wear/src/test/.../SeizureMonitorServiceTest.kt` — new `evaluateHealth` cases: inbound
      alarm-state staleness, the 60s worst-case arithmetic for outbound (40+20), inbound (10+30+20),
      and sensor (10+20) paths; confirm existing `isSequentialMode` default-false, prior
      `evaluateHealth` cases, and `WakeLock` contract tests still pass unchanged. Modified.
      Satisfies: WCT-5, WCT-8 (existing Robolectric tests must still pass unchanged).
      Dependencies: T7.3.

- [ ] **T7.5** OSD alarm-state policy per DEC-057: 2/3/5 => alarm; 4/7/unknown => silent system fault
      (visual only, logged); 6 => no vibration; amend spec WCT-8 (safety finding F2).
- [ ] **T7.6** DEGRADED becomes visual-only (NO vibration) per DEC-057; remove/adjust `vibrateDegraded`
      behaviour and update T7.4 criteria (supersedes persistent-vibration finding F3).
- [ ] **T7.7** Correct the 60s worst-case arithmetic (inbound path uses 40s stale, not 30s) or accept a
      signed ~65-70s ceiling.

Parallelizable: none (strict sequential chain; this is the highest-risk batch and should be
reviewed as its own PR).

---

## Batch 8 — Version/compatibility handshake

- [ ] **T8.1** `:wear` exposes a transport-contract version (piggyback on `/osd/settings` field, or
      a small dedicated field decided at implementation time — design left this open). Modified.
      Satisfies: WCT-7, PCB-9.
      Dependencies: T7.2, T7.3.
- [ ] **T8.2** `:phone` compares the watch's contract version on connect; mismatch routes through
      `BridgeNotifications` (fault channel) as a caregiver-visible notification, not silent
      ignore. Modified.
      Satisfies: PCB-9.
      Dependencies: T8.1, T5.4.
- [ ] **T8.3** Unit tests for the version-compare logic (match/mismatch/missing-field cases). New.
      Dependencies: T8.2.

Parallelizable: none (T8.1 must land before T8.2/T8.3 can be written against a real field).

---

## Batch 9 — Docs touch-ups

- [ ] **T9.1** `docs/GUIA_CONECTAR_RELOJ_TELEFONO.md` — document the two-APK install flow
      (`:wear` + `:phone`), OSD data source = "Garmin", OSD web server must be running. This is new
      content (the two-APK flow did not exist before this change); distinct from the earlier
      doc-drift fix already committed. Modified.
- [ ] **T9.2** `README.md` — same install-flow note, updated architecture summary reflecting the
      companion bridge. Modified.
- [ ] **T9.3** `DECISIONS.md` — touch-up only if implementation details in T7.3/T7.1 diverge from
      what DEC-050/DEC-051 already state (e.g. exact final constant values or flavor name). Do
      **not** re-write the approach decision itself — DEC-050 (root cause) and DEC-051 (Option F)
      are already written and committed; this is a narrow addendum only if needed.
      Dependencies: T7.3 (to confirm final constants match what's documented).

Parallelizable: T9.1/T9.2 in parallel; T9.3 only if a divergence is found during T7.3.

---

## Non-code / device-verify tasks (do not block code merges, but block calling the feature done —
map each to the design's "Open Questions")

- [ ] **DV-1** Confirm `startForeground(connectedDevice)` behavior when `BLUETOOTH_CONNECT` is
      denied — must fault loudly, never crash. Relates to: Batch 5 (T5.1/T5.2).
- [ ] **DV-2** Confirm `connectedDevice` is on the BOOT_COMPLETED-allowed FGS-type list for the
      target Android version; if not, verify T6.3's degrade-to-notification path. Relates to:
      Batch 6 (T6.3).
- [ ] **DV-3** Check whether `mDataFrequencyCheckEnabled` is on by default in the user's OSD prefs
      (tightens the ±1s jitter tolerance if so). Relates to: Batch 4/5 (POST cadence).
- [ ] **DV-4** Determine whether OSD writes `alarmState` synchronously in `onSdDataReceived` or via
      a main-thread post, to confirm the 250ms settle delay is sufficient (ceiling stays 8s
      regardless). Relates to: Batch 5b (T5.3).
- [ ] **DV-5** Measure phone battery cost of the 10s watch-bound keep-alive over 8h; relax to 15s
      (`ALARM_STATE_STALE_MS` 25s) if material. Relates to: Batch 7/8.
- [ ] **DV-6** One full 8h overnight run (E2E gate from design's Testing Strategy) before trusting
      the feature in production use. Relates to: all batches, PROP-SC.

These are explicitly **not** blocking for individual PRs to merge, but PROP-SC ("clean 8h overnight
run") and the design's E2E gate mean the feature is not considered validated until DV-6 passes.

---

## Review Workload Forecast

Per-batch estimated changed-line counts (new + modified, code + tests; docs counted separately):

| Batch | Description | Est. lines | Notes |
|---|---|---|---|
| GATE-0 | Hardware validation experiment | 0 (no diff) | Human-executed, blocking gate |
| 1 | Signing foundation | ~40 | Config only, no runtime code |
| 2 | `:phone` scaffold | ~150 | Manifest + gradle + resource stubs |
| 3a | Codec + parser + tests | ~220 | Pure functions, high test-to-code ratio |
| 3b | BridgeHealth + loopback integration test | ~230 | Includes a stub `ServerSocket` server |
| 4 | `OsdHttpForwarder` + test | ~140 | |
| 5a | `OsdBridgeService` core | ~250 | Highest single-file complexity in `:phone` |
| 5b | `AlarmStateRelay` + `BridgeNotifications` + service test | ~200 | |
| 6 | `SetupActivity` + `BootReceiver` | ~200 | Includes Compose setup UI |
| 7 | `:wear` retargeting | ~230 | **Highest risk**: touches alarm-adjacent watchdog logic |
| 8 | Version/compat handshake | ~100 | |
| 9 | Docs | ~150 (markdown) | Lower review weight than code, but still counted |
| **Total** | | **~1,910** | |

**Exceeds the 400-line review budget**: Yes — total is ~4.8x budget, and even the largest single
batch (5a, ~250) is comfortably under budget only because Batches 3/5 were pre-split into
3a/3b and 5a/5b specifically to stay under it. Without that split, Batch 3 (~450) and Batch 5
(~450) would each individually exceed the 400-line budget.

**Chained PRs recommended**: Yes — 10 PRs in strict dependency order:
`PR1(Batch1) -> PR2(Batch2) -> PR3(Batch3a) -> PR4(Batch3b) -> PR5(Batch4) -> PR6(Batch5a) ->
PR7(Batch5b) -> PR8(Batch6) -> PR9(Batch7) -> PR10(Batch8) -> PR11(Batch9, can land in parallel
with PR9/10 once Batch7's constants are final)`. Batch 7 (`:wear` retargeting) is deliberately
its own PR, isolated from the `:phone` PRs, so the life-safety-adjacent diff is reviewable without
scaffold noise, and lands only after the companion bridge is provably correct in isolation
(Batches 1–6 merged and unit-tested).

**Decision needed before apply: Yes** — two separate confirmations required from the user before
`sdd-apply` starts writing code:
1. Confirm GATE-0 (hardware validation experiment) has a recorded PASS.
2. Confirm/adjust the above 10-PR chained-delivery plan (or accept it as-is) per the `ask-on-risk`
   delivery strategy, given design already flags this as High risk (new module + transport/alarm
   watchdog change) and the ~1,910-line total is well over the 400-line budget.
</invoke>
