# Exploration: watch-osd-message-delivery

Fixing the confirmed Wear Data Layer "AppKey" drop: OSD's Google Play Services silently
discards every watch→phone message because SeizureGuard (`com.seizureguard.wear`) and OSD
(`uk.org.openseizuredetector`) share neither package name nor signing certificate.

Status: **ready for proposal** (updated September 2026, after Graham Jones's reply). The
proposed candidate is **Option F** — a phone-side companion app under SeizureGuard's own
identity that bridges the watch to OSD's supported HTTP ingress. Graham confirmed the
package-name half of the AppKey diagnosis and pointed to this companion-app pattern (the
same shape the Garmin seizure detector uses).

Engram mirror: topic key `sdd/watch-osd-message-delivery/explore` (obs #1195).

Investigation only. This document deepens the cause and compares solution paths. It does not
propose or design.

---

## Current State — what the SeizureGuard `:wear` app does today

Single module `:wear`, package/applicationId `com.seizureguard.wear`, `namespace =
com.seizureguard.wear`, minSdk 30, targetSdk 34, compileSdk 34, Kotlin 2.0.21, AGP 8.5.2. No
ML runtime — inference/threshold/alarm/SMS all live in OSD on the phone (engram
`architecture/seizureguard-executorch-api`; model tensor `(1,1,750)`, ExecuTorch `.pte`, in
OSD).

| File | Responsibility | Transport coupling |
|------|----------------|--------------------|
| `service/SeizureMonitorService.kt` | Foreground service (`foregroundServiceType=health`), START_STICKY, PARTIAL WakeLock (10 h timeout, renewed each watchdog tick), 25 Hz `TYPE_ACCELEROMETER` capture, magnitude √(x²+y²+z²) → milli-g, `CircularBuffer` 125-sample transport chunk (~5 s), restart-after-kill resume via SharedPreferences `was_monitoring`. Owns the **T8 pipeline watchdog** (pure `evaluateHealth(now,lastSample,lastDelivery,started)`; HEALTHY/DEGRADED; 60 s warm-up; SAMPLE_STALE 10 s; DELIVERY_STALE 60 s; hysteresis 2 ticks; DEGRADED → notification + `vibrateDegraded`). Owns **validation-mode guard** (DEC-047: `isSequentialMode` default `false` always; opt-in only via Intent extra AND `BuildConfig.DEBUG`). Exposes `alarmState` + `pipelineHealth` StateFlows. | Calls `WearDataLayerManager`; watchdog consumes its Boolean delivery result |
| `data/WearDataLayerManager.kt` | **The broken transport.** `MessageClient` + `Wearable.getNodeClient().connectedNodes`. Paths (DEC-046, JSON UTF-8): `/osd/accel_data` w→p `{"samples":[milli-g,…]}` N=125; `/osd/alarm_state` p→w `{"alarm_state":int,"alarm_phrase":str}`; `/osd/settings` w→p `{"battery":0-100,"sample_freq":Hz}`; `/osd/send_settings` p→w plain `"start"`. `sendToAllNodes` returns Boolean. | **This is the only file implementing the broken path** |
| `alarm/AlarmStateManager.kt` | Haptics by alarmState (0 OK silent, 1 WARNING short pulse, 2+ ALARM strong waveform) + `vibrateDegraded` distinct 2-pulse. | None |
| `ml/CircularBuffer.kt` | Synchronized fixed-capacity `FloatArray` ring buffer. | None |
| `logging/CsvLogger.kt` | Debug-only raw accel CSV to `getExternalFilesDir()`. | None |
| `MainActivity.kt` | Compose UI, start/stop buttons, alarmState colour/text. Uses `com.seizureguard.wear.R`/BuildConfig. | None |

Manifest: `uses-feature android.hardware.type.watch`; `com.google.android.wearable.standalone=true`
(**NOT the cause**); BODY_SENSORS(+BACKGROUND), HIGH_SAMPLING_RATE_SENSORS,
FOREGROUND_SERVICE(+HEALTH), WAKE_LOCK, VIBRATE, POST_NOTIFICATIONS, INTERNET. No
`WearableListenerService`, no `<capability>` xml — listener is runtime, lives with the service
(same style as OSD).

Tests: JVM/Robolectric only (`sequentialMode_isDisabledByDefault`, `evaluateHealth` cases,
WakeLock-level contract). No device farm; user tests manually in Android Studio on a physical
Galaxy Watch 8. "Never build" project rule.

DECISIONS.md: **DEC-046** is the current wear↔OSD contract source of truth (JSON UTF-8, the 4
paths, settings handshake). **There is no DEC yet about the AppKey failure** — this exploration
feeds that decision. `docs/GUIA_CONECTAR_RELOJ_TELEFONO.md` still attributes the June-2026
field-test "Data source fault" to an OSD version being too old (V5.0.5 vs the beta→release merge
in V5.0.8); the AppKey root cause supersedes that explanation.

## OSD side (a local checkout of OpenSeizureDetector `Android_Pebble_SD`, branch `beta`)

`gradle.properties`: `APPLICATION_ID=uk.org.openseizuredetector`, `VERSION_CODE=195`,
`VERSION_NAME=5.1.0b`, `MIN_SDK=26`, `TARGET_SDK=35`, `COMPILE_SDK=36`. `app/build.gradle`:
`namespace 'uk.org.openseizuredetector'`, `org.pytorch:executorch-android:1.0.1`,
`play-services-wearable:+`, BLESSED `com.github.weliem:blessed-android:2.5.0`, RideBeeline
current-time-service `0.1.2`. No signingConfigs in `app/build.gradle` (release uses
default/None here).

`datasource/`: `SdDataSourceAw.java`, `SdDataSourceBLE.java`, `SdDataSourceBLE2.java`,
`SdDataSourceNetwork.java`, `SdDataSourcePebble.java`, `SdDataSourceGarmin.java`,
`SdDataSourcePhone.java`.

**`SdDataSourceAw.java` (confirmed):** runtime `Wearable.getMessageClient(mContext).addListener(this)`
on `start()`; paths `/osd/accel_data,/osd/settings,/osd/hr_data,/osd/request_data,/osd/alarm_state,/osd/send_settings`;
accel parse order = JSON `samples[]` → JSON `x/y/z` → binary int16 LE; `MAX_RAW_DATA=125` →
`doAnalysis()` → `sendAlarmStateToWatch()`; keep-alive posts alarm_state every 20 s;
`getStatus()` faults after 60 s with no data. It can only receive messages addressed to OSD's
own AppKey — there is no API to receive another app's Data Layer messages.

**OSD BLE GATT contract (from `SdDataSourceBLE` / `SdDataSourceBLE2`)** — OSD is the BLE
**central / GATT client**; the watch must be the BLE **peripheral / GATT server + advertiser**:

- Service `SERV_OSD = 000085e9-0000-1000-8000-00805f9b34fb`
- `CHAR_OSD_ACC_DATA 000085e9-0001-…` — NOTIFY — accel samples, encoding per ACC_FMT
- `CHAR_OSD_BATT_DATA 000085e9-0002-…` — READ + NOTIFY — 1 byte battery %
- `CHAR_OSD_WATCH_ID 000085e9-0003-…` — READ — UTF-8 string
- `CHAR_OSD_WATCH_FW 000085e9-0004-…` — READ — UTF-8 string
- `CHAR_OSD_ACC_FMT 000085e9-0005-…` — READ — 1 byte: `0` = 8-bit vector-magnitude scaled
  1g=64 (OSD computes `1000*b/64` → mg); `1` = int16 LE magnitude; `3` = 3D int16 LE `x,y,z`
  triples (OSD computes magnitude + stores 3D)
- `CHAR_OSD_STATUS 000085e9-0006-…` — WRITE (OSD → watch) — 1 byte = alarmState
- CCCD `00002902` must exist and be writable on each notify characteristic
- OSD buffers `MAX_RAW_DATA=125` samples then `doAnalysis()`; assumes 25 Hz always ("BLE device
  always sends data at 25 Hz")
- Optional standard Heart Rate `0000180d`/`00002a37`, standard Battery `0000180f`/`00002a19`
- OSD also starts a Current Time Service **GATT server on the phone** (RideBeeline lib) that the
  peripheral may read
- `SdDataSourceBLE2` (BLESSED) requests MTU 185, `ConnectionPriority.HIGH`, LE Coded PHY S8;
  device chosen by MAC address in `BLEScanActivity` (`scanForPeripheralsWithAddresses`);
  BangleSD / PineTimeSD advertise the OSD service UUID; `SdDataSourceBLE` retries connection if
  the OSD service isn't found.
- `SdDataSourceNetwork.java` is **poll-only**: it is a polling *client* that GETs
  `http://<ip>:8080/data` for alarm JSON (via `downloadUrl`, ~every 2 s) plus `/acceptalarm`.
  It does NOT receive POSTed data and cannot feed the detector. That earlier reading was
  correct — the POST *receiver* is a separate component, `SdWebServer`, described next.

**OSD HTTP ingress — `SdWebServer` + `SdDataSourceGarmin` (verified against the beta checkout):**

- `app/src/main/java/uk/org/openseizuredetector/webserver/SdWebServer.java`: a NanoHTTPD
  server on port **8080**. Handles `POST /data` and `POST /settings`, calling
  `mSdServer.mSdDataSource.updateFromJSON(...)`.
- `app/src/main/java/uk/org/openseizuredetector/datasource/SdDataSourceGarmin.java`: class
  comment — "A Passive data source that expects a device to send it data periodically by
  sending a POST request ... SdWebServer expects POST requests to /data and /settings URLs".
  The "Garmin" datasource is a thin passive shell that relies entirely on `SdWebServer` for
  ingest; it is the officially supported way an external app pushes data into OSD.

## OSD's own watch app — `OpenSeizureDetector/WearSD` (Graham Jones; actively maintained; versionCode 5 / versionName `0.0.5`)

- `applicationId` **and** `namespace` = `uk.org.openseizuredetector` — SAME as the OSD phone
  app. This identity match is the entire reason it works.
- **`minSdk = 36`, targetSdk 36, compileSdk 36** (Android 16 / Wear OS 6). Bleeding edge;
  SeizureGuard is minSdk 30. Whether it installs/runs on the target Galaxy Watch 8 is an OPEN
  QUESTION.
- **No `signingConfigs` block** — built with the developer's debug key in Android Studio. If the
  OSD phone app is also built from source on the same machine, both get `~/.android/debug.keystore`
  → same cert → AppKey matches. A GitHub **release** OSD APK (Graham's release key) would NOT
  match a locally built WearSD.
- Manifest: `com.google.android.wearable.standalone="true"`; NO `WearableListenerService`; NO
  `<capability>` xml; service `SensorDataService` (health); `BootReceiver` (BOOT_COMPLETED +
  LOCKED_BOOT_COMPLETED); permissions include `health.READ_HEART_RATE`, `RECEIVE_BOOT_COMPLETED`,
  `USE_FULL_SCREEN_INTENT`. Same runtime `MessageClient` pattern as SeizureGuard.
- Message contract (`DEVELOPER_GUIDE.md`) — same paths SeizureGuard uses, plus extras:
  - w→p: `/osd/accel_data` `{"samples":[Int,…],"seq":Long,"sent_ms":Long}` ~1/s; `/osd/hr_data`
    `{"hr":Int,"seq","sent_ms"}` ~1/5 s; `/osd/settings`
    `{"version","name","sample_freq","battery","seq","sent_ms"}` ~1/60 s; `/osd/user_action`
    `{"action":"mute"|"unmute"|"accept"}`
  - p→w: `/osd/alarm_state` `{"alarm_state":Int,"alarm_phrase":String}`; `/osd/send_settings`
    (empty)
  - `AlarmStates.kt`: UNKNOWN −1, OK 0, WARNING 1, ALARM 2, FALL 3, FAULT 4, MANUAL 5, MUTE 6,
    NETFAULT 7
  - MessageClient only; node-id cache TTL ~10 s
- README verbatim: "The app drains the battery in approximately 6 hours"; "The data transfer to
  the phone seems to not happen at regular intervals"; "not packaged nicely"; compatible with
  OSD 5.0 and 5.1; discussion `https://github.com/orgs/OpenSeizureDetector/discussions/69`.
- `OpenSeizureDetector/AndroidWear_SD` is an abandoned 2017 stub (`AWSdComms.java` empty) — not
  a precedent.
- OSD issues: #175 (closed, added `SdDataSourceAw` in V4.2), #212 (open), discussion #69
  (battery / irregular transfer). None mention "Failed to deliver" / "AppKey" — because OSD's
  own watch app already shares the package.

## The constraint, deepened

The Wear Data Layer routing key ("AppKey") = **packageName + signing-certificate hash**,
compared across the two paired devices. Google Play Services (`com.google.android.gms.persistent`)
enforces this BEFORE any app listener sees the message. There is no supported cross-package /
third-party Data Layer messaging, and no OSD-side API to receive foreign-AppKey messages.
`DataClient`, `CapabilityClient`, and capability-based discovery are all subject to the same
signature scoping.

Therefore **any Data-Layer-based fix requires SeizureGuard to present the same AppKey as the OSD
build it talks to** — i.e. applicationId `uk.org.openseizuredetector` AND the same signing cert
as the *installed* OSD phone app. Graham's private release keystore is not obtainable, so this
only works if the user runs an OSD phone app they built and signed themselves (or Graham one-off
co-signs a SeizureGuard/WearSD build with the OSD release cert).

## Graham's response (September 2026)

Graham Jones (OSD maintainer) replied to the AppKey diagnosis email. Key points, verbatim
where quoted:

- **Confirms the package-name half of the diagnosis:** "to receive AndroidWear messages the
  watch app and the phone app need to share the same package name. I am not sure about
  signing key, but you might be right." The AppKey requirement is therefore **confirmed for
  the package name** and **plausible but unconfirmed for the signing certificate** (DEC-050 /
  engram #897).
- **He has Android Wear working:** OSD beta (V5.1.x) + WearSD on a Samsung Galaxy Watch 7 he
  bought. "Runs and produces plausible results", not analysed in depth; the main problem is
  battery drain. He suggests trying WearSD + OSD beta first, to gain confidence the pipeline
  is possible. Ref:
  `https://github.com/orgs/OpenSeizureDetector/discussions/69#discussioncomment-18117824`.
- **For a DIFFERENT package (SeizureGuard's case):** "have a lightweight companion app on the
  phone that communicates with your watch, and the companion app sends the data to OSD. That
  is essentially how the Garmin seizure detector works - the Garmin Connect app is the
  interface between the watch and OSD."
  - Simplest: "POST data to the http server that is integrated into OSD" (like Garmin) — he
    calls this "a bit messy".
  - "Best" way: Android Broadcasts, but "OSD does not handle this yet so it would need to be
    added."
- **Against Option C:** "direct BLE comms to the Android Wear watch is probably difficult
  because the Android operating system on the watch is likely to be controlling the bluetooth
  stack."

## Options

### Option A — SeizureGuard adopts `applicationId = uk.org.openseizuredetector` + shared signing key

- **Mechanism**: makes SeizureGuard's AppKey equal OSD's → GMS routes the messages. Existing
  `WearDataLayerManager` paths/JSON transfer UNCHANGED (they already match `SdDataSourceAw`).
- **Feasibility / blockers**: needs the SAME signing cert as the phone's OSD (see constraint
  above). `namespace` can stay `com.seizureguard.wear` (Gradle allows `namespace` ≠
  `applicationId`), so `com.seizureguard.wear.R` / `BuildConfig` references do NOT all have to
  change — only `applicationId`, the `ACTION_START/STOP` string constants, the `adb` component
  names in DEC-047 / HARDWARE_RUNBOOK, and instrumentation-test package expectations need review.
  Watch and phone are different devices → no on-device applicationId collision with WearSD. But
  installing a real OSD (`uk.org.openseizuredetector`) on the SAME phone later would
  collide/replace.
- **Effort**: Low–Medium. ~2–4 h Gradle + constants + doc edits, plus deciding a keystore
  strategy (shared debug keystore, or a dedicated shared release keystore used for both OSD and
  SeizureGuard builds).
- **Risk to alarm path**: Low mechanically (same transport, same code, keeps SeizureGuard's
  watchdog). Medium operationally — two apps both claiming OSD identity → update/launch
  confusion; losing the shared key = no updates.
- **Reversibility**: High (revert `applicationId`).
- **External cooperation**: none if the user runs a self-built OSD; otherwise needs Graham to
  publish a co-signed SeizureGuard build.

### Option B — Use OSD's `WearSD` on the watch instead of SeizureGuard's send path

- **Mechanism**: WearSD already shares OSD's package; build WearSD + OSD phone from source in the
  same Android Studio → shared debug key → AppKey matches → messages arrive. OSD-blessed,
  maintained.
- **Feature gap vs SeizureGuard `:wear` (real, not cosmetic)**:
  - **LOST**: T8 pipeline watchdog + DEGRADED state + `vibrateDegraded` (DEC-048). WearSD has
    `seq`/`sent_ms` metadata and relies on OSD-side 60 s fault; no on-watch "delivery stalled"
    alert. For life-safety this is the biggest regression.
  - **LOST**: validation-mode safety guard (DEC-047) — moot if WearSD has no synthetic mode, but
    the disciplined default-false design goes away.
  - **LOST**: restart-after-kill resume via `was_monitoring`; SeizureGuard's explicit
    WakeLock-renewal contract; the pure-function health tests.
  - **GAINED**: `/osd/hr_data`, `/osd/user_action` (mute/accept from the watch), `seq`/`sent_ms`
    ordering, BootReceiver auto-start, full-screen-intent alarm UI, splash.
  - **Same**: 25 Hz accel, magnitude→milli-g, MessageClient transport, standalone flag,
    foreground health service, no WearableListenerService.
- **Feasibility / blockers**: `minSdk 36` — verify it runs on the Galaxy Watch 8. Still needs OSD
  phone built/signed with a matching cert (same as Option A) unless a co-signed release exists.
  WearSD "not packaged nicely"; ~6 h battery; irregular transfer per its own README.
- **Effort**: Low to first end-to-end alarm (build 2 apps, install, toggle) IF minSdk + signing
  line up. Medium–High if SeizureGuard's watchdog/degraded features must be ported into WearSD
  (upstream Kotlin PR or fork).
- **Risk to alarm path**: Medium — inherits WearSD's known irregular-transfer + battery issues
  and loses SeizureGuard's silent-failure detection.
- **Reversibility**: High (SeizureGuard stays installable in parallel; different watch apps).
- **External cooperation**: light for use as-is; upstream PR if porting features.

### Option C — SeizureGuard speaks BLE to OSD's BLE data source (like BangleSD / PineTimeSD)

- **Mechanism**: OSD runs `SdDataSourceBLE` / `BLE2` as BLE central. SeizureGuard runs a BLE
  **peripheral**: `BluetoothLeAdvertiser` advertising `SERV_OSD 000085e9-…`, plus a
  `BluetoothGattServer` exposing the 6 characteristics above; push 125 accel samples via
  notifications on `CHAR_OSD_ACC_DATA` (8-bit format, 1g=64, is smallest and matches BangleSD),
  expose battery/id/fw/fmt, and handle `CHAR_OSD_STATUS` writes for alarmState. **Completely
  bypasses Google Play Services and the AppKey rule** — no shared package, no shared signing.
- **Feasibility / blockers**: **Wear OS BLE peripheral-role support is not guaranteed.** Must
  verify on the Galaxy Watch 8: `BluetoothAdapter.getBluetoothLeAdvertiser() != null`,
  `isMultipleAdvertisementSupported()`, and that a `BluetoothGattServer` can run while the watch
  is companion-bonded to the phone. Needs `BLUETOOTH_ADVERTISE` + `BLUETOOTH_CONNECT` (API 31+).
  Need to confirm whether OSD's `BLEScanActivity` filters by advertised service UUID and/or a
  device-name pattern. New reconnection/backoff logic on the watch side.
- **Effort**: High. ~3–6 days: new `BleGattServerManager` (advertiser, GATT server, 6 chars +
  CCCDs, MTU/chunking of 125 samples, format byte, connection state machine, reconnect), replace
  `WearDataLayerManager`, rework the watchdog around GATT connection state. Plus a
  hardware-capability spike first.
- **Risk to alarm path**: Medium — brand-new transport with its own failure modes, but BLE is
  the transport OSD's real watches use and OSD ships two BLE datasources. No dependency on Google
  routing.
- **Reversibility**: Medium (large new subsystem; `WearDataLayerManager` could be retained
  behind a flag).
- **External cooperation**: none required to start; a question to Graham about scan-filter / name
  expectations helps.

### Option D — Patch OSD's `SdDataSourceAw` (or add an OSD datasource) to accept a third-party package

- Over the Wear Data Layer: **NOT technically possible.** GMS drops foreign-AppKey messages
  before delivery; `MessageClient` / `DataClient` give OSD no way to receive them. Drop this
  sub-option.
- The only "D" that exists is OSD adding a NEW non-Data-Layer datasource (BLE = Option C, or a
  local bound-service / AIDL / broadcast bridge). That is upstream OSD work on Graham's roadmap +
  release cycle.
- **Effort**: unknown, external; weeks. Not weekend-viable.
- **Reversibility / risk**: n/a (not in our control).

### Option E — other discoveries

- **E1** on-device "shim" APK under `uk.org.openseizuredetector` on the phone re-emitting into
  OSD: dead end — OSD reads its datasource in-process, not via IPC; a shim that isn't OSD cannot
  inject into `SdDataSourceAw`.
- **E2** `DataClient` instead of `MessageClient`: same AppKey scoping. No help.
- **E3** `CapabilityClient` discovery: same signature scoping. No help.
- **E4** `SdDataSourceNetwork`: poll-only alarm reader, cannot ingest accel. Rejected.
- **E5 (viable — the real enabler for A and B)**: build the OSD **phone** app from source and
  sign it with the user's own keystore, then sign SeizureGuard (Option A) or WearSD (Option B)
  with the SAME keystore and applicationId `uk.org.openseizuredetector`. This makes the AppKey
  match without Graham's private key. Cost: the user gives up the convenience of the official
  GitHub-release OSD APK and takes on building/updating OSD themselves.

### Option F — Phone-side companion app under SeizureGuard's own identity (proposed candidate)

- **Mechanism**: the SeizureGuard watch app keeps `applicationId = com.seizureguard.wear`
  unchanged. Add a `:phone` module to the SeizureGuard project with the **same applicationId
  and signing key** as the `:wear` module, so the Wear Data Layer delivers between the two
  SeizureGuard apps (AppKey constraint satisfied — both are the user's, same package + same
  signature). `:phone` receives the watch's `/osd/*` `MessageClient` messages (same paths and
  JSON as DEC-046), then forwards them to OSD via HTTP `POST /data` + `POST /settings` to
  `http://127.0.0.1:8080` (OSD's `SdWebServer`, localhost). In OSD the user selects the
  **"Garmin"** data source. OSD stays the official GitHub-release APK — no self-build, no OSD
  code changes. This bypasses the cross-package Data-Layer limitation entirely by keeping
  Data Layer traffic between two same-identity apps and using OSD's supported HTTP ingress
  for the cross-app hop.
- **Feasibility / blockers**: alarm-state-back-to-watch is an open design question (candidate:
  `:phone` polls `GET /data` on `SdWebServer` and relays `/osd/alarm_state` to the watch over
  the Data Layer). The exact JSON contract `SdWebServer` expects on `POST /data` still needs
  to be pinned down against `updateFromJSON(...)`.
- **Effort**: Medium. New `:phone` module: `MessageClient` receiver + foreground service +
  HTTP client + its own delivery watchdog. Days, not hours, but far less than Option C.
- **Risk to alarm path**: reintroduces a two-part app and adds a new component in the
  life-safety alarm path; "a bit messy" per Graham. Mitigated by leaving the watch app
  unchanged and giving `:phone` its own delivery watchdog.
- **Reversibility**: High — the watch app is untouched.
- **External cooperation**: none.
- **Note on the old `:phone` module**: this partially reverses the 2026-06-05 deletion of the
  previous `:phone` module, but that one was deleted as dead code duplicating OSD's
  inference. This `:phone` does **no inference** — it is a pure transport bridge that OSD
  cannot provide for a third-party package. Different purpose.

## Weekend-viable ranking (fastest working end-to-end seizure alarm, least risk)

**Validation experiment (do first, not the product):** WearSD + OSD beta on the watch, per
Graham's suggestion — prove the pipeline works end-to-end. Validation only; it loses
SeizureGuard's DEC-047 / DEC-048 safety features and drains the battery in ~6 h, so it is
not the product direction.

**Product direction: Option F** — a new `:phone` companion module under SeizureGuard's own
identity, forwarding to OSD's "Garmin" HTTP ingress. Watch app unchanged, OSD stays the
official release APK, no shared signing key.

Relegated / deprioritized:

1. **Option A** (watch adopts `uk.org.openseizuredetector` + shared signing + self-built
   OSD) — relegated: not endorsed by Graham, permanent self-built-OSD maintenance burden,
   identity collision with WearSD.
2. **Option B** (use WearSD) — retained only as the validation experiment above, not the
   product direction: loses DEC-047 / DEC-048, ~6 h battery.
3. **Option C (BLE)** — deprioritized: Graham expects the watch OS to control the Bluetooth
   stack, which makes the peripheral role difficult. Capability spike deferred.
4. **Option D** — unchanged: not possible over the Data Layer, upstream-only otherwise.

## Recommended long-term architecture (if different)

**Option F.** It keeps SeizureGuard an independent app with its own name, package, and safety
watchdog; it needs no shared signing key, no package-identity contortions, and no self-built
OSD; and it rides OSD's officially supported HTTP ingress (the same path the Garmin seizure
detector uses), so it does not depend on OSD release-cycle changes. The cost is a second
component — the `:phone` bridge — in the alarm path, which gets its own delivery watchdog.

If, during proposal / design, the `:phone` bridge proves too fragile overnight, the fallbacks
are: (a) ask Graham to add the Android-Broadcast ingress he called the "best" way, or
(b) revisit Option C if a BLE-peripheral capability spike on the Galaxy Watch 8 unexpectedly
succeeds.

## Open questions (answer / ask upstream before a proposal)

**Resolved or moot under Option F (per Graham's September 2026 reply):**

- ~~Will the user run a self-built OSD **phone** app?~~ Not required for Option F — it talks to
  the official release OSD through its "Garmin" HTTP ingress. Relevant again only if Option A
  or B is revived.
- ~~Does the Galaxy Watch 8 support BLE peripheral role?~~ Deprioritized — Graham expects the
  watch OS to control the Bluetooth stack, so Option C is not a near-term path and the
  capability spike is deferred.
- ~~Package name vs signing certificate in the AppKey?~~ Package name **confirmed** by Graham;
  signing certificate still only "plausible" — but moot for Option F, where both SeizureGuard
  apps share package and key by construction.
- ~~Downstream systems keyed to `com.seizureguard.wear`; permanent sideload-only?~~ Moot for
  Option F: `applicationId` does not change and SeizureGuard keeps its own package, so no
  Play Store namespace conflict is introduced.

**Still open (for the Option F proposal):**

1. Does WearSD (`minSdk 36`, Android 16) install and run on the target Galaxy Watch 8's
   Wear OS version? Needed for the validation experiment, not for Option F itself.
2. What exact JSON contract does OSD's `SdWebServer` expect on `POST /data` (and
   `POST /settings`)? The `:phone` bridge must emit exactly what `updateFromJSON(...)` parses
   for the "Garmin" datasource.
3. How does the `:phone` companion relay alarm state back to the watch? Candidate: poll
   `GET /data` on `SdWebServer` and forward `/osd/alarm_state` over the Data Layer.
4. What is the `:phone` foreground-service battery cost across an 8 h night (on the phone,
   not the watch)?

## Risks

- **Life-safety**: all four options touch the sole data path feeding the seizure alarm. Any
  silent transport stall = missed seizure at 3am. Option B loses SeizureGuard's on-watch
  delivery-stall detection; Option C adds a new, less-proven transport; Options A/B create two
  "OpenSeizureDetector" identities that can be confused on install/update/launch.
- **Signing/keystore**: sharing one signing cert across apps is a lasting key-management burden;
  losing it means neither app can be updated.
- **Bleeding edge**: WearSD `minSdk 36` sharply narrows device support.
- **Upstream dependency**: Options B (as-is + feature port) and D depend on Graham/OSD
  cooperation and release cadence.
- **Docs drift**: `docs/GUIA_CONECTAR_RELOJ_TELEFONO.md` still blames an old OSD version for the
  June-2026 field-test failure; the AppKey root cause supersedes it and must be corrected
  wherever the contract is documented (DEC-046 + a new DEC).
- **WearSD's own README** admits irregular data transfer and ~6 h battery — unresolved even on
  the blessed path.

## Ready for Proposal

**Yes**, with **Option F** as the candidate: a new `:phone` companion module under
SeizureGuard's own identity (`com.seizureguard.wear`, shared signing key) that receives the
watch's `/osd/*` Data Layer messages and forwards them to the official OSD release via its
"Garmin" HTTP ingress (`POST /data` + `POST /settings` on `http://127.0.0.1:8080`). Graham's
September 2026 reply confirmed the package-name AppKey requirement and endorsed this
companion-app pattern (the shape the Garmin seizure detector already uses). The remaining
open items (the `SdWebServer` `POST /data` JSON contract, the alarm-state relay path, and
`:phone` overnight battery) are design questions for the proposal, not blockers. Run the
WearSD + OSD beta validation experiment in parallel to confirm the end-to-end pipeline.

## Updates after approval

- Update 2026-10-01: the approach was chosen in DEC-051 (Option F, companion app on the phone) and the
  implementation is merged on `main`; this exploration is kept as the record of the investigation.
