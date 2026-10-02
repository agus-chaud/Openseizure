# Design: watch-osd-message-delivery — Phone Companion Bridge (Option F)

## Technical Approach

Add a `:phone` module sharing `applicationId` + signing key with `:wear`, so the Wear Data Layer
AppKey matches by construction. `:phone` receives the watch's unchanged DEC-046 messages, translates
them to OSD's **Garmin** HTTP ingress on `127.0.0.1:8080`, polls `GET /data` for the alarm state and
relays it back. No OSD source change, no on-watch ML.

    Watch :wear                          Phone :phone                    OSD (official APK)
    25Hz accel -> CircularBuffer(125)
      --MessageClient /osd/accel_data-->  OsdBridgeService
         {"samples":[125 milli-g]}          OsdPayloadCodec
                                            --POST /data (dataObj)-->  SdWebServer:8080
                                                                       -> SdDataSourceGarmin
                                                                          .updateFromJSON
                                                                          -> doAnalysis()
                                            <--GET /data (alarmState)--  SdData.toString()
      <--MessageClient /osd/alarm_state--  AlarmStateRelay
         {"alarm_state","alarm_phrase"}
    AlarmStateManager haptics
    evaluateHealth(...) -> DEGRADED  <---- (silence = fault)

## Pinned OSD HTTP contract (read from beta source, not assumed)

`SdWebServer.serve()` + `SdDataSource.updateFromJSON()` + `NanoHTTPD.parseBody()`:

| Item | Pinned value | Evidence |
|---|---|---|
| Endpoint | `POST http://127.0.0.1:8080/data` and `/settings` | `SdWebServer:98,135` |
| Content-Type | **`application/x-www-form-urlencoded`**, body `dataObj=<percent-encoded JSON>` | `NanoHTTPD:1021` routes urlencoded bodies to `decodeParms` -> `parms`, which is what `parameters.get("dataObj")` reads |
| Charset | UTF-8, `URLEncoder.encode(json, "UTF-8")` | `decodeParms` -> `decodePercent` |
| `/data` JSON | `{"dataType":"raw","data":[125 doubles, milli-g]}`; optional `HR`, `O2sat`, `Mute`, `data3D[375]` | `SdDataSource:219-262` |
| `/settings` JSON | `{"dataType":"settings","analysisPeriod":5,"sampleFreq":25,"battery":0-100,"watchPartNo":"","watchFwVersion":"","sdVersion":"","sdName":""}` — first three are **mandatory** (`getInt`, uncaught) | `SdDataSource:267-282` |
| Response body | `"OK"` \| `"sendSettings"` \| `"ERROR"` | `SdDataSource:266,282,284` |
| Cadence | `EXPECTED_DATA_PERIOD_SEC=5 ±1` -> POST **every 4–6 s**; outside that for `mFaultTimerPeriod` (30 s) OSD raises a fault | `SdDataSource:91-94,312-350` |
| `GET /data` | `SdData.toString()` JSON incl. `alarmState` (int) and `alarmPhrase`; ~2 KB, no raw arrays | `SdData:460-556` |

**NOT the DEC-046 shape.** OSD's Garmin ingress uses `dataType`/`data`, not `samples`. The
translation is the companion's core job.

Three verified gotchas that drive the choices below:
1. `POST /settings` calls `parameters.get("dataObj").toString()` with **no null guard** — sending a
   raw body there NPEs inside OSD. Form-urlencoded `dataObj` is therefore mandatory, not optional.
2. `NanoHTTPD:1014` stops reading the body at the first `\r\n`. Percent-encoding guarantees no raw
   CR/LF, so the body cannot be truncated.
3. `SdWebServer` binds all interfaces, so OSD's ingress is LAN-reachable and unauthenticated. We use
   loopback only and must document the exposure; we do not change OSD.

## Architecture Decisions

| # | Decision | Alternatives rejected | Rationale |
|---|---|---|---|
| 1 | **`:phone` = same `applicationId` `com.seizureguard.wear`, `namespace = com.seizureguard.phone`, minSdk 26, targetSdk 34; one shared `signingConfigs` in root `signing.gradle.kts` reading gitignored `keystore.properties`; zero new dependencies** (core-ktx, play-services-wearable, coroutines, lifecycle already in `libs.versions.toml`; HTTP via `HttpURLConnection`) | OkHttp/Retrofit; separate keys | AppKey needs identical package+cert. Different namespace avoids `R`/`BuildConfig` collisions. No new deps = smaller review surface and no transitive risk in the alarm path. Today both modules are debug-signed by the same `~/.android/debug.keystore`, so the shared config only matters for release. |
| 2 | **Runtime `MessageClient` listener inside the foreground `OsdBridgeService`** | `WearableListenerService` | Matches OSD's own `SdDataSourceAw` and `:wear`. A `WearableListenerService` is started per message by the system and would race/duplicate the long-lived HTTP + poll loop; the FGS must exist anyway for the loop, wake-lock and watchdog. Works screen-off because GMS holds its own delivery wake-lock. |
| 3 | **Pass-through, no batching**: one `POST /data` per received chunk, `Connection: close`, 4 s connect / 4 s read timeout | Batching N chunks; keep-alive pool | OSD's ±1 s window makes any batching an instant fault. |
| 4 | **Alarm-state return: event-driven `GET /data` 250 ms after each successful POST, plus a 5 s idle safety poll. Relay to watch on change and as a 10 s keep-alive. Hard latency ceiling: 8 s P100, < 2 s typical.** | 2 s fixed poll (`SdDataSourceNetwork` precedent); OSD-side push | `updateFromJSON -> doAnalysis()` runs **inside** the POST handler, so the decision already exists when the POST returns; a 250 ms settle GET catches it. Worst path (GET races the main-thread state write, falls back to the 5 s poll + BT hop) stays under 8 s. 8 s beats OSD's own 20 s AW keep-alive and is 13% of its 60 s fault window. A new decision can only appear once per 5 s analysis window, so a sub-5 s ceiling would be fiction. |
| 5 | **Watch watchdog gains one input `lastAlarmStateAtMs`; timing envelope tightened: `WATCHDOG_INTERVAL_MS` 30s->10s, `DELIVERY_STALE_MS` 60s->40s, new `ALARM_STATE_STALE_MS` 40s (keep-alive 10 s), warm-up 60 s and hysteresis 2 unchanged** | Keep the envelope as-is | **Justified change**: with 30 s ticks + 60 s stale + 2-tick hysteresis, worst-case DEGRADED declaration is **120 s** — it silently violates the proposal's "visible within 60 s" criterion. New worst cases: outbound 40+20=60 s, inbound 10+30+20=60 s (using 30 s stale), sensor 10+20=30 s. Extra cost is a no-op tick every 10 s while the wake-lock is already held and the sensor runs at 25 Hz. Inbound staleness is the only true end-to-end liveness signal, because `sendToAllNodes` returning true is a GMS-transport ack, not proof OSD answered. |
| 6 | **Fault surfacing is asymmetric on purpose**: companion silence -> watch `DEGRADED` (`vibrateDegraded`, distinct 2-pulse) + notification; the companion never fabricates an OSD `alarmState` | Companion sends `alarm_state=4` (FAULT) on failure | `AlarmStateManager` maps 2+ to the strong ALARM waveform, so relaying FAULT would be a false seizure alarm at 3am. Silence + DEGRADED keeps fault and alarm distinguishable. |
| 7 | **Compile-time flag = product flavor `transport` {`companion` (default, first-declared), `osdDirect`}**, `osdDirect` overriding only `applicationId = "uk.org.openseizuredetector"` plus `buildConfigField OSD_DIRECT_MODE`. No flavor source sets. | `BuildConfig` boolean alone; delete the code | The watch's wire format is **identical** in both modes — `/osd/*` paths and DEC-046 JSON already match `SdDataSourceAw`. The only real difference is `applicationId`, which a `BuildConfig` field cannot express; only a flavor can. Because both flavors compile the same sources, **there is no branch to rot**. |
| 8 | **FGS type `connectedDevice`** + `BLUETOOTH_CONNECT` declared, `START_STICKY`, session `PARTIAL_WAKE_LOCK` (10 h timeout renewed each 10 s tick, mirroring `:wear` DEC-048 discipline), `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` from a one-shot `SetupActivity`, `BootReceiver` resuming on `was_bridging` | `dataSync` | `dataSync` carries 6h/24h runtime caps on newer Android — fatal for an 8 h night. `connectedDevice` has no cap and describes the job honestly. |

## Interfaces / Contracts

```kotlin
// phone/.../bridge/OsdPayloadCodec.kt — pure, JVM-testable
object OsdPayloadCodec {
    fun rawDataJson(milliG: DoubleArray): String            // {"dataType":"raw","data":[...]}
    fun settingsJson(battery: Int, sampleFreq: Int = 25,
                     analysisPeriod: Int = 5): String        // dataType=settings, 3 mandatory ints
    fun formBody(json: String): ByteArray                    // "dataObj=" + URLEncoder.encode(json,"UTF-8")
}

// phone/.../bridge/OsdResponseParser.kt — pure
enum class PostOutcome { OK, SEND_SETTINGS, OSD_PARSE_ERROR, WRONG_DATASOURCE, UNREACHABLE }
fun classify(httpCode: Int, body: String): PostOutcome
// WRONG_DATASOURCE <- HTTP 200 whose body still contains "you should not see this message"
//                     (SdWebServer's untouched placeholder when the source is not "Garmin")

// phone/.../bridge/BridgeHealth.kt — pure
enum class BridgeFault { NONE, NO_WATCH_DATA, OSD_UNREACHABLE, OSD_WRONG_DATASOURCE, OSD_REJECTS_DATA }
fun evaluate(nowMs: Long, lastWatchMsgAtMs: Long, lastPostOkAtMs: Long,
             consecutivePostFailures: Int, lastOutcome: PostOutcome): BridgeFault
// NO_WATCH_DATA: no /osd/accel_data for > 30 s (6 missed chunks)
// OSD_UNREACHABLE: >= 3 consecutive failures, or no POST OK for > 20 s
// OSD_WRONG_DATASOURCE / OSD_REJECTS_DATA: latched immediately from the response body

// wear/.../SeizureMonitorService.kt — one added parameter, still pure
fun evaluateHealth(nowMs, lastSampleAtMs, lastDeliveryOkAtMs,
                   lastAlarmStateAtMs, monitoringStartedAtMs): PipelineHealth
```

**Handshake**: on start and whenever a POST returns `sendSettings`, the companion sends
`POST /settings` before the next `/data`. `"ERROR"` -> `OSD_REJECTS_DATA` fault.

**Notification channels** (`:phone`): `osd_bridge_status` (IMPORTANCE_LOW, ongoing, required by the
FGS) and `osd_bridge_fault` (IMPORTANCE_HIGH, ongoing, `CATEGORY_ERROR`, sound + vibration,
**re-posted every 60 s while the fault stands** so it can never be dismissed into silence). Text is
explicit and actionable, e.g. *"SeizureGuard: OSD is not receiving data — check that OSD is running
and its data source is set to Garmin."* No status screen; `SetupActivity` is one-shot setup only
(notification permission, battery-optimisation exemption, start/stop, Garmin instructions).

## File Changes

| File | Action | Description |
|---|---|---|
| `settings.gradle.kts` | Modify | `include(":phone")`; replace the 2026-06-05 removal comment |
| `signing.gradle.kts` (root) + `.gitignore` | Create/Modify | One `signingConfigs` applied to both modules from gitignored `keystore.properties` |
| `phone/build.gradle.kts` | Create | minSdk 26, targetSdk 34, `applicationId com.seizureguard.wear`, `namespace com.seizureguard.phone`, existing deps only |
| `phone/src/main/AndroidManifest.xml` | Create | FGS `connectedDevice`, WAKE_LOCK, POST_NOTIFICATIONS, BLUETOOTH_CONNECT, RECEIVE_BOOT_COMPLETED, INTERNET, REQUEST_IGNORE_BATTERY_OPTIMIZATIONS |
| `phone/.../bridge/OsdBridgeService.kt` | Create | FGS: MessageClient listener, wake-lock, POST loop, poll loop, health tick |
| `phone/.../bridge/OsdHttpForwarder.kt` | Create | `HttpURLConnection` POST/GET to `127.0.0.1:8080`, timeouts, no retry (see below) |
| `phone/.../bridge/{OsdPayloadCodec,OsdResponseParser,BridgeHealth}.kt` | Create | Pure functions above |
| `phone/.../bridge/{AlarmStateRelay,BridgeNotifications}.kt` | Create | Relay to watch; two channels + 60 s re-post |
| `phone/.../{SetupActivity,boot/BootReceiver}.kt` | Create | One-shot setup; boot resume |
| `phone/src/test/.../{OsdPayloadCodec,OsdResponseParser,BridgeHealth}Test.kt` | Create | JVM/Robolectric |
| `wear/build.gradle.kts` | Modify | `transport` flavor dimension; shared signing |
| `wear/.../service/SeizureMonitorService.kt` | Modify | `lastAlarmStateAtMs` field + `evaluateHealth` param + 3 constants |
| `wear/src/test/.../SeizureMonitorServiceTest.kt` | Modify | New `evaluateHealth` cases incl. inbound staleness |
| `DECISIONS.md`, `docs/GUIA_CONECTAR_RELOJ_TELEFONO.md`, `README.md` | Modify | New DEC; two-APK install; OSD data source = Garmin + web server enabled |

**Error handling on POST failure**: no retry, no queue. A late chunk is worse than a missing one —
OSD's ±1 s window would turn a retry into a timing fault, and stale accelerometer data is
clinically meaningless. Failures increment `consecutivePostFailures`, which drives the fault
notification; the next 5 s chunk is the recovery attempt.

## Testing Strategy

| Layer | What | How |
|---|---|---|
| Unit (JVM/Robolectric, no device) | `rawDataJson`/`settingsJson` schema + mandatory fields; `formBody` percent-encoding contains no raw CR/LF and round-trips through a `decodeParms` equivalent; `classify()` for OK / sendSettings / ERROR / placeholder body / IOException; `BridgeHealth.evaluate` boundaries; `evaluateHealth` incl. the new inbound-staleness case and the 60 s worst-case arithmetic; `OSD_DIRECT_MODE` defaults false | Existing junit + robolectric + `org.json` |
| Integration (no OSD) | Loopback `HttpURLConnection` against a stub server asserting the exact wire bytes OSD would parse | Local `ServerSocket` in a JVM test |
| Device / manual | GMS delivery watch->phone; OSD showing live data with source=Garmin; alarm haptics; FGS overnight survival; boot resume; battery-opt exemption; battery draw both devices | User in Android Studio (agents never build) |
| E2E gate | Success criteria in the proposal, incl. kill-the-companion -> DEGRADED within 60 s | One 8 h overnight run before trusting it |

## Threat Matrix

No routing, shell, subprocess, VCS/PR automation, or executable-file classification. One
process-integration boundary applies:

| Boundary | Applicable | Safe behaviour | RED test |
|---|---|---|---|
| Localhost HTTP into another app's unauthenticated server | Yes | Bind target strictly `127.0.0.1` (never `0.0.0.0`/LAN IP); no secrets in the payload; treat every response as untrusted input and parse defensively | `classify()` and the alarm-state parser must return a typed failure, never throw, on malformed/hostile bodies |
| Inbound Data Layer messages from an unknown node | Yes | AppKey already restricts senders to our own signed apps; still validate JSON shape and array length before forwarding | Malformed `/osd/accel_data` is dropped and counted, never forwarded to OSD |
| Shell / subprocess / VCS / executable classification | N/A | — | — |

## Migration / Rollout

1. `:wear` change is additive and flavor-guarded; the `companion` flavor is the default.
2. Rollback = build the `osdDirect` flavor (or revert the `:wear` commit), remove `:phone` from
   `settings.gradle.kts`, uninstall the companion, switch OSD's data source back. No OSD state is
   mutated. Rollback returns the watch to today's non-delivering but self-monitoring behaviour.
3. The WearSD + OSD-beta validation experiment runs in parallel and must pass before `sdd-apply`.

## Open Questions

- [ ] Does `startForeground(connectedDevice)` need `BLUETOOTH_CONNECT` **granted** on the target
      Android version? If denied it throws — implementation must pre-check and fault loudly, never
      crash. Device-verify.
- [ ] Is `connectedDevice` on the BOOT_COMPLETED-allowed FGS-type list for the phone's Android
      version? If not, boot resume must degrade to a high-importance "restart the bridge"
      notification instead of failing silently. Device-verify.
- [ ] Is `mDataFrequencyCheckEnabled` on by default in the user's OSD prefs? If yes the ±1 s window
      is live and jitter tolerance is thinner than assumed.
- [ ] Does OSD write `alarmState` synchronously inside `onSdDataReceived`, or via a main-thread
      post? Decides whether the 250 ms settle delay is sufficient or must grow (ceiling unchanged).
- [ ] Phone battery cost of a 10 s watch-bound keep-alive over 8 h — measure and relax to 15 s
      (with `ALARM_STATE_STALE_MS` 25 s) if it is material.
- [ ] Should the companion send `data3D`? Not required: OSD's ML reads 1D `rawData`
      (`SdAlgMl:207`), and omitting it only zeroes the unused `mAccelMagStdDev`.

## Updates after approval

This design is a historical record of what was decided at the time. Later decisions superseded the
following points; the decisions themselves live in `DECISIONS.md`.

- Update 2026-10-01: superseded by DEC-057 and DEC-058 — the `osd_bridge_fault` channel (HIGH, sound +
  vibration, re-posted every 60 s) was replaced by the silent LOW channel `osd_bridge_fault_silent` with no
  timed re-post. Boot-resume and start failures are silent too.
- Update 2026-10-01: superseded by DEC-059 and DEC-063 — watchdog constants were signed as 10 s / 40 s / 40 s;
  the inbound path uses a 40 s stale threshold (not 30 s), giving a 60 s link ceiling and about 75 s with OSD
  frozen. DEGRADED on the watch is visual only.
- Update 2026-10-01: superseded by DEC-065 and DEC-066 — the contract version travels in the settings
  message; a mismatch or missing version is a `VERSION_MISMATCH` fault-log period (not a `BridgeFault`), shown
  as a silent passive notice and a morning-summary note, kept across restarts until a matching version is seen.
  It never stops forwarding to OSD.
