# Spec Delta: phone-companion-bridge

Change: `watch-osd-message-delivery` (Option F, proposal Engram #1198)
Capability: **phone-companion-bridge** — the new SeizureGuard `:phone` module that bridges
watch Data Layer traffic into the official OpenSeizureDetector (OSD) release APK and relays
OSD alarm state back to the watch.

`openspec/specs/` is currently empty (only `.gitkeep`), so every requirement below is
**ADDED**. No existing requirement is modified or removed.

Scope note: this capability describes WHAT must be true of the companion. The alarm-state
return mechanism and its maximum latency ceiling are **owned by `sdd-design`** (locked
decision #1); this spec states only the observable requirement and defers the number.

---

## ADDED Requirements

### Requirement: Companion module shares the watch app's Data Layer identity

The `:phone` companion module SHALL be built with the same `applicationId` and signed with
the same signing certificate as the `:wear` module, so that Google Play Services routes Wear
Data Layer messages between the two SeizureGuard apps (the AppKey = `packageName` + signing
certificate must match on both paired devices).

The companion SHALL NOT require the watch app to change its `applicationId`
(`com.seizureguard.wear` is retained).

#### Scenario: Data Layer messages are delivered between the two SeizureGuard apps

- **GIVEN** the `:wear` app and the `:phone` companion are installed on a paired watch and phone
- **AND** both were built from the SeizureGuard project with the shared signing key
- **WHEN** the watch sends a `MessageClient` message on an `/osd/*` path
- **THEN** the companion's registered listener receives that message
- **AND** Google Play Services does not drop it with an AppKey / "Failed to deliver" error

#### Scenario: Build configuration enforces one shared key

- **GIVEN** the SeizureGuard Gradle project with `:wear` and `:phone` modules
- **WHEN** the project is configured for release
- **THEN** both modules resolve to the same signing configuration (one key, one certificate)
- **AND** a build that would sign the two modules with different certificates is treated as a
  configuration error, not shipped

### Requirement: Companion minSdk is Android 8.0 (API 26)

The `:phone` companion module SHALL declare `minSdk = 26` (locked decision #3), matching the
OSD phone-app floor and covering the caregiver's existing phone.

#### Scenario: Companion installs on the target phone

- **GIVEN** a phone running Android 8.0 or later
- **WHEN** the caregiver sideloads the companion APK
- **THEN** installation succeeds and the companion's foreground service can start

### Requirement: Companion receives watch accelerometer and settings messages

The companion SHALL register a `MessageClient` listener and accept, at minimum, these
watch→companion paths with the DEC-046 JSON shapes unchanged:

- `/osd/accel_data` — body `{"samples":[<milli-g>, ...]}` with 125 samples per message
- `/osd/settings` — body `{"battery":<0-100>,"sample_freq":<Hz>}`

The companion SHALL parse each message without requiring the watch to alter the existing
DEC-046 payload format.

#### Scenario: A 125-sample accel chunk is received intact

- **GIVEN** the companion foreground service is running and a watch node is connected
- **WHEN** the watch delivers an `/osd/accel_data` message containing 125 milli-g samples
- **THEN** the companion parses all 125 samples in order with no loss or reordering

#### Scenario: A settings message is received

- **GIVEN** the companion is running
- **WHEN** the watch delivers an `/osd/settings` message with battery and sample-frequency fields
- **THEN** the companion parses both fields for forwarding to OSD

### Requirement: Companion forwards watch data to OSD's local web server

For every accepted watch message the companion SHALL make a corresponding HTTP request to
OSD's embedded `SdWebServer` at `http://127.0.0.1:8080`:

- `/osd/accel_data` → `POST /data`
- `/osd/settings` → `POST /settings`

The request body SHALL be in the exact form OSD's `SdWebServer.updateFromJSON(...)` accepts
for the passive (Garmin) data source, such that OSD ingests the samples with no OSD-side code
change. The companion SHALL forward on the same cadence it receives (one inbound message
produces one forward attempt); it SHALL NOT silently coalesce or drop chunks without
recording a delivery failure.

#### Scenario: Live accel data appears in OSD

- **GIVEN** OSD (official release APK) is running with data source set to "Garmin"
- **AND** the companion is receiving `/osd/accel_data` from the watch
- **WHEN** the companion POSTs each chunk to `http://127.0.0.1:8080/data`
- **THEN** OSD displays live accelerometer data originating from the SeizureGuard watch app
- **AND** OSD accumulates samples toward its analysis window and produces alarm states

#### Scenario: Settings reach OSD

- **GIVEN** the companion has received an `/osd/settings` message
- **WHEN** it POSTs to `http://127.0.0.1:8080/settings`
- **THEN** OSD accepts the request (HTTP 2xx) and updates its view of watch battery / sample rate

### Requirement: OSD remains the unmodified official release

The solution SHALL work with the official OSD release APK (V5.0.8+ / beta) selecting the
"Garmin" data source. It SHALL NOT require any change to OSD source, a self-built OSD, or an
upstream Android-broadcast ingress.

#### Scenario: No OSD change is needed to complete the pipeline

- **GIVEN** an unmodified OSD release APK with `SdWebServer` and the Garmin data source
- **WHEN** the companion runs the full receive-and-forward flow
- **THEN** end-to-end delivery succeeds with zero OSD source modification

### Requirement: Companion relays OSD alarm state to the watch

OSD's current alarm state SHALL be reflected on the watch so the watch's haptics (OK /
WARNING / ALARM per `AlarmStateManager`) and the T8 watchdog keep working. The companion is
the component responsible for obtaining OSD's alarm state and delivering it to the watch on
the `/osd/alarm_state` path with the DEC-046 body `{"alarm_state":<int>,"alarm_phrase":<str>}`.

A **stale** alarm state (the companion can no longer obtain a fresh value from OSD) and an
**again-unavailable** alarm state (relay to the watch stops succeeding) SHALL both be
detectable — the watch and the caregiver MUST be able to tell that the alarm state is no
longer trustworthy rather than silently continuing to show the last value as if current.

**Fail-loud relay (amended 2026-09-19, safety finding F1, implemented in Batch 5c):**
- An alarm state of 1 or higher SHALL always be relayed to the watch, on change and as a
  keep-alive while it persists, regardless of any fault and regardless of data freshness.
- An alarm state of 0 (on change and as keep-alive) SHALL be relayed only while the bridge has
  no active fault **and** OSD's data timestamp (`dataTimeStr`) has advanced within the freshness
  window. Otherwise the companion SHALL send nothing (silence), so the watch's staleness
  watchdog detects the condition. The companion SHALL NEVER fabricate a fault or alarm code.
- If the body cannot be read or lacks `alarmState`, nothing is sent.

The exact relay mechanism and the maximum alarm-state→watch latency ceiling are **deferred to
`sdd-design`** (locked decision #1). This spec does not fix a number.

#### Scenario: Alarm state round-trips to the watch

- **GIVEN** the companion is forwarding watch data to OSD and OSD is producing alarm states
- **WHEN** OSD's alarm state changes (e.g. OK → WARNING → ALARM)
- **THEN** the companion delivers the new state to the watch on `/osd/alarm_state`
- **AND** the watch triggers the matching `AlarmStateManager` haptic pattern

#### Scenario: Stale alarm state is detectable, not masked

- **GIVEN** the companion has been relaying alarm state to the watch
- **WHEN** the companion can no longer obtain a fresh alarm state from OSD
- **THEN** this condition is surfaced (companion fault path and/or an explicit stale marker to
  the watch) so downstream alerting does not treat the last-known state as current

### Requirement: Companion detects delivery failure and records it silently

*Amended 2026-09-19 (DEC-057): this requirement previously demanded a caregiver-facing alert.
The owner decided that system faults never interrupt anyone; the only interruption in the
system is a real emergency, which is OSD's job. Superseded decision: DEC-051 #2.*

The companion SHALL run headless with **no status / "receiving" / "degraded" screen**
(locked decision #2). It SHALL **detect** each of these fault conditions and record and show
them passively:

1. It stops receiving watch messages on `/osd/accel_data` for a sustained period (no inbound
   data while monitoring is expected to be active).
2. Its `POST /data` (or `POST /settings`) to `http://127.0.0.1:8080` stops succeeding -
   connection refused, timeout, or non-2xx — for a sustained period (covers "OSD not running"
   and "wrong data source selected").
3. It can no longer obtain OSD's alarm state to relay to the watch.
4. OSD answers but its data timestamp stops advancing (OSD is open but not analysing).

Presentation rules:
- A fault SHALL be shown as an ongoing, low-importance notification with **no sound, no
  vibration, no lights, no heads-up and no full-screen intent**, and SHALL NOT be re-posted on
  a timer. Its text updates when the fault type changes and it is cancelled on recovery.
- Start-failure and restart-needed notices (denied Bluetooth permission, failed resume after
  reboot) SHALL be silent in the same way.
- The companion SHALL keep a bounded log of fault periods and downtime, and SHALL post one
  silent morning summary of the previous night's interruptions.
- Faults SHALL NOT be presented as emergencies, and the companion SHALL NOT alert for them.

Accepted consequence (risk A1, `docs/SAFETY_FINDINGS_WATCH_OSD.md`): a fault is visible only to
someone who looks, or in the morning summary. A stopped or force-closed companion produces no
summary.

The exact time thresholds for "sustained period" are set in `sdd-design` and signed in
`CLINICAL_SIGNOFF.md`.

#### Scenario: OSD is not reachable — recorded silently

- **GIVEN** the companion is receiving data from the watch
- **AND** OSD is closed, or the wrong data source is selected, so POSTs fail
- **WHEN** POSTs to `http://127.0.0.1:8080/data` keep failing past the design threshold
- **THEN** the companion shows a passive notification stating monitoring is not working
- **AND** plays no sound and no vibration, and does not re-post it on a timer
- **AND** the notification persists until POSTs succeed again

#### Scenario: Watch link drops — recorded silently

- **GIVEN** the companion foreground service is running with monitoring expected active
- **WHEN** no `/osd/accel_data` message arrives for longer than the design threshold
- **THEN** the companion records the fault and shows the passive notification
- **AND** does not require anyone to open any companion screen, and makes no sound

#### Scenario: OSD is open but not analysing

- **GIVEN** POSTs to OSD succeed
- **WHEN** OSD's data timestamp does not advance within the freshness window
- **THEN** the companion records an OSD-data-stale fault silently

#### Scenario: Recovery clears the fault

- **GIVEN** the passive fault notification is showing
- **WHEN** inbound watch data and outbound POSTs to OSD are both succeeding again
- **THEN** the companion cancels the notification and closes the fault period in the log

#### Scenario: Morning summary

- **GIVEN** the user intended the bridge to run overnight
- **WHEN** the summary time is reached
- **THEN** the companion posts one silent summary, either "no interruptions" or the number and
  total duration of interruptions by category

### Requirement: Companion survives an 8-hour overnight run

The companion SHALL be able to run continuously for a full night (target 8 hours) without
being killed by the platform or OEM battery optimisation. It SHALL:

- run as a foreground service with an ongoing notification for the whole monitoring session;
- request exemption from battery optimisation / Doze for its process;
- resume monitoring automatically after a system-initiated process kill (e.g. `START_STICKY`
  semantics), restoring the receive-and-forward flow without caregiver action;
- keep battery consumption on the phone within a budget that leaves the phone usable the next
  day.

The concrete service configuration, wake-lock strategy, exemption prompts, and reboot
handling are chosen in `sdd-design`.

#### Scenario: Clean overnight run

- **GIVEN** the companion is started before an 8-hour sleep period with battery optimisation
  exemption granted
- **WHEN** the night elapses
- **THEN** the companion delivered watch data to OSD continuously for the whole period
- **AND** the phone battery remains at a usable level in the morning

#### Scenario: Process killed at night — auto-resume

- **GIVEN** the companion foreground service was running and monitoring was active
- **WHEN** the Android system kills the companion process during the night
- **THEN** the service is recreated and the receive-and-forward flow resumes with no caregiver
  interaction

### Requirement: Watch and companion versions are compatibility-checked

The companion SHALL carry a transport-contract version and SHALL compare it against the
watch app's transport-contract version on every watch settings message. A mismatch, or a watch
that advertises no version (an older app), SHALL be **detected** and **surfaced** without ever
interrupting delivery:

- it is recorded as its own `VERSION_MISMATCH` period in the fault log (not a bridge fault, so the
  companion keeps reporting a healthy link to the watch);
- it is shown as a silent, passive, ongoing notification (no sound, no vibration, no heads-up, per
  DEC-057), with distinct text for "versions differ" and "the watch reports no version";
- it is noted separately in the morning summary, and does not count as an interruption;
- it persists across companion restarts and stops, and closes only when the watch advertises a
  matching version;
- it SHALL NOT stop, pause or filter forwarding to OSD: losing seizure detection is worse than a
  possibly-incompatible contract, and the strict message parsers already reject out-of-contract
  messages (DEC-065, DEC-066).

#### Scenario: Mismatched APKs are flagged

- **GIVEN** a watch app and a companion app built from different, contract-incompatible versions
- **WHEN** they connect over the Data Layer
- **THEN** the companion shows a passive notification indicating the two apps must be updated
  to matching versions, and records a `VERSION_MISMATCH` period in the fault log
- **AND** plays no sound and no vibration
- **AND** the mismatch is not silently ignored

#### Scenario: Mismatch while forwarding continues

- **GIVEN** a version mismatch (or a missing watch version) is open
- **WHEN** the watch keeps sending valid `/osd/accel_data` chunks
- **THEN** the companion keeps forwarding them to OSD and keeps relaying the alarm state to the watch
- **AND** no bridge fault is raised because of the mismatch
- **AND** the notification and the fault-log period remain open across a companion restart until the
  watch advertises a matching version

#### Scenario: Watch advertises no version

- **GIVEN** a watch app older than the version handshake
- **WHEN** it sends a settings message without a usable contract version
- **THEN** it is treated as incompatible, with a milder "looks out of date" passive notice
- **AND** forwarding to OSD continues

#### Scenario: Matched APKs connect cleanly

- **GIVEN** a watch app and companion built from the same project revision
- **WHEN** they connect
- **THEN** no version-mismatch fault is raised and normal forwarding proceeds

---

## Success criteria coverage (from the proposal)

The following proposal-level success criteria are satisfied jointly by this capability and
`watch-companion-transport`:

- OSD (official release APK, data source = "Garmin") displays live accelerometer data
  originating from the SeizureGuard watch app — *Companion forwards watch data to OSD*.
- OSD produces alarm states and the watch receives them and vibrates correctly (OK / WARNING /
  ALARM) — *Companion relays OSD alarm state to the watch* + `watch-companion-transport`.
- Killing the companion, disabling OSD, or stopping the watch service produces a visible
  (silent) DEGRADED / fault indication within the signed ceiling, and is recorded in the fault
  log — *Companion detects delivery failure and records it silently* + watch DEC-048 watchdog.
  No fault produces sound or vibration (DEC-057).
- One full 8-hour overnight run completes with continuous delivery and acceptable battery on
  both devices — *Companion survives an 8-hour overnight run*.
- No OSD source change and no self-built OSD — *OSD remains the unmodified official release*.
- `safety-reviewer` returns PASS before merge — enforced at PR time (not in this spec; the
  `safety-reviewer` skill runs later, per project rule).

## Non-goals (from the proposal — restated as constraints)

- No changes to OSD source and no self-built OSD.
- No on-watch ML inference (stays in OSD).
- No upstream Android-broadcast ingress in OSD (possible future, out of scope here).
- The companion performs **no inference, no threshold evaluation, no SMS** — it is a pure
  transport bridge (the reason the 2026-06-05 `:phone` deletion is only partially reversed).

## Deferred to `sdd-design` (do not resolve in spec)

- The alarm-state return mechanism and its maximum latency ceiling (locked decision #1).
- Concrete time thresholds for "sustained period" before a fault is recorded (signed in
  `CLINICAL_SIGNOFF.md`; see the ceiling discussion in `watch-companion-transport`).
- Foreground-service type, wake-lock strategy, battery-optimisation prompt flow, reboot
  persistence.
- The exact `SdWebServer` `POST /data` body form (`dataObj` param vs posted file) — pinned in
  design against OSD's `updateFromJSON(...)`.

## External gate (from locked decision #5)

The WearSD + OSD-beta validation experiment runs in parallel and MUST pass before `sdd-apply`
begins. If it fails, implementation stops before code is written.
