# Spec Delta: watch-companion-transport

Change: `watch-osd-message-delivery` (Option F, proposal Engram #1198)
Capability: **watch-companion-transport** — the `:wear` app's send/receive contract with the
phone companion: the DEC-046 paths and JSON retargeted from OSD to the companion, and how a
broken companion link feeds the existing T8 / DEC-048 watchdog.

> **Amended 2026-09-19 (DEC-057, safety review `safety-review-pre-batch7` F2/F3).** Three
> requirements below were changed to match the owner's decision that system faults are silent:
> (1) *Watch receives alarm state* now defines what each OSD alarm state does on the watch;
> (2) *Watch→companion delivery failure* now presents DEGRADED visually only, with no haptic; and
> (3) *No change to the watch pipeline* now lists those changes as permitted. The watchdog
> constants named here are **proposed** and are not final until signed in `CLINICAL_SIGNOFF.md`.

`openspec/specs/` is currently empty, so every requirement below is **ADDED**. The watch's
sensor pipeline, `CircularBuffer`, seizure state machine and T8 watchdog algorithm are **not**
modified by this capability beyond changing where data is sent and what "delivery" measures.

---

## ADDED Requirements

### Requirement: Watch sends accelerometer data to the companion, not to OSD

The watch SHALL send each ~125-sample transport chunk to the phone companion via
`MessageClient` on path `/osd/accel_data` with the DEC-046 body `{"samples":[<milli-g>, ...]}`
(N = 125). The payload format, sample count, cadence and the `CircularBuffer` upstream of it
SHALL be unchanged from today; only the destination peer changes (the companion instead of
OSD).

#### Scenario: Chunk is addressed to the companion node

- **GIVEN** the watch monitoring service is capturing accelerometer data at 25 Hz
- **AND** the phone companion app is the connected Data Layer peer
- **WHEN** the `CircularBuffer` fills a 125-sample chunk
- **THEN** the watch sends it on `/osd/accel_data` to the companion
- **AND** the JSON body matches the existing DEC-046 `{"samples":[...]}` shape byte-for-byte in structure

#### Scenario: No direct traffic to OSD in the default build

- **GIVEN** a default (non-flagged) release build of the watch app
- **WHEN** the watch sends accel data
- **THEN** it targets only the SeizureGuard companion, and does not attempt to reach
  `uk.org.openseizuredetector` over the Data Layer

### Requirement: Watch sends settings to the companion

The watch SHALL send `/osd/settings` with body `{"battery":<0-100>,"sample_freq":<Hz>}` to the
companion on the same cadence and format as the current DEC-046 contract.

#### Scenario: Settings delivered to companion

- **GIVEN** the watch service is running
- **WHEN** it emits a periodic settings update
- **THEN** the message is sent on `/osd/settings` to the companion with battery and
  sample-frequency fields

### Requirement: Watch receives alarm state from the companion

The watch SHALL accept `/osd/alarm_state` messages from the companion with body
`{"alarm_state":<int>,"alarm_phrase":<str>}` and SHALL drive `AlarmStateManager` from them
according to the table below. The integer is OSD's `AlarmState`. The source of that state is
the companion (which obtained it from OSD) rather than OSD directly.

| `alarm_state` | OSD meaning | Watch behaviour |
|---|---|---|
| 0 | OK | No haptic |
| 1 | WARNING | Short pulse (unchanged) |
| 2, 3, 5 | ALARM, FALL, MANUAL | ALARM strong waveform |
| 4, 7 | FAULT, NETFAULT | **Silent system fault**: no haptic, no sound; visible indication only; recorded as a fault |
| 6 | MUTE | No haptic |
| any other value (negative, or 8 and above) | unknown | Treated as a silent system fault, exactly like 4 and 7; never as ALARM |

A silent system fault SHALL NOT vibrate as ALARM. Rationale (DEC-057): the only interruption
in the whole system is a real emergency. Note that OSD itself forces FAULT above ALARM while a
fault stands (`SdServer.java:1274-1281`), so an OSD fault means a seizure alarm may not fire;
that accepted risk is recorded in `docs/SAFETY_FINDINGS_WATCH_OSD.md` (A2), not mitigated here.

#### Scenario: WARNING state vibrates the watch

- **GIVEN** the watch is connected to the companion
- **WHEN** the companion sends `/osd/alarm_state` with `alarm_state = 1`
- **THEN** the watch plays the WARNING haptic pattern via `AlarmStateManager`

#### Scenario: ALARM, FALL and MANUAL states vibrate the watch

- **GIVEN** the watch is connected to the companion
- **WHEN** the companion sends `/osd/alarm_state` with `alarm_state` equal to 2, 3 or 5
- **THEN** the watch plays the ALARM strong-waveform haptic pattern

#### Scenario: FAULT state does not vibrate

- **GIVEN** the watch is connected to the companion
- **WHEN** the companion sends `/osd/alarm_state` with `alarm_state = 4` (or 7)
- **THEN** the watch plays no haptic and no sound
- **AND** the watch shows a visible system-fault indication and records the fault

#### Scenario: MUTE state does not vibrate

- **GIVEN** the watch is connected to the companion
- **WHEN** the companion sends `/osd/alarm_state` with `alarm_state = 6`
- **THEN** the watch plays no haptic

#### Scenario: Unknown state is a silent fault, never an alarm

- **GIVEN** the watch is connected to the companion
- **WHEN** the companion sends `/osd/alarm_state` with a value outside 0 to 7
- **THEN** the watch plays no haptic and treats it as a silent system fault

### Requirement: Alarm state must be recognisable as stale on the watch

The watch SHALL be able to tell that the alarm state it holds is no longer current — for
example when `/osd/alarm_state` updates stop arriving from the companion, or the companion
signals that it can no longer obtain state from OSD. A stale alarm state SHALL NOT be
presented or acted on as if it were a fresh confirmed value; it feeds the watchdog's
degraded-path handling instead.

The concrete staleness window is set by `sdd-design` together with the alarm-state return
mechanism and latency ceiling (locked decision #1). This spec fixes no number.

#### Scenario: Alarm-state updates stop — watch treats state as stale

- **GIVEN** the watch has been receiving periodic `/osd/alarm_state` updates from the companion
- **WHEN** no update arrives for longer than the design-defined staleness window
- **THEN** the watch no longer treats the last-known alarm state as a current confirmed value
- **AND** the condition contributes to the watch's DEGRADED handling (see next requirement)

### Requirement: Watch→companion delivery failure drives the DEC-048 watchdog

The watch's T8 / DEC-048 delivery-health semantics (`DELIVERY_STALE`, DEGRADED) SHALL now
measure **watch→companion** delivery. The send call to the companion SHALL return a success
/ failure signal that the existing `evaluateHealth` watchdog consumes in place of the current
watch→OSD result. A broken companion link (companion not installed, not running, not
acknowledging, or Data Layer node lost) SHALL produce the existing DEGRADED outcome. DEGRADED
SHALL be presented as a **visual indication only** (the DEGRADED notification / screen state on
the watch). The watch SHALL NOT play any haptic or sound for DEGRADED, neither on entering it
nor while it persists (DEC-057). The existing `vibrateDegraded` haptic is therefore removed.

The inbound alarm-state staleness (`ALARM_STATE_STALE`) feeds the same DEGRADED outcome.

Timing constants are **proposed** as: `WATCHDOG_INTERVAL` 10 s, `DELIVERY_STALE` 40 s,
`ALARM_STATE_STALE` 40 s, warm-up 60 s, `SAMPLE_STALE` 10 s, hysteresis 2 ticks. They are final
only once signed in `CLINICAL_SIGNOFF.md`. With these values the worst-case time from the last
good event to DEGRADED is about 60 s for a lost companion link and about 75 s when OSD stops
analysing while the companion keeps polling (computed, not measured on hardware).

#### Scenario: Companion is not running — watch degrades

- **GIVEN** the watch monitoring service has completed its warm-up period
- **AND** the phone companion app is not running (or its process was killed)
- **WHEN** watch→companion sends fail for longer than `DELIVERY_STALE` (with hysteresis)
- **THEN** the watch enters the DEGRADED state and shows the DEGRADED indication
- **AND** plays no haptic and no sound

#### Scenario: Companion link recovers — watch returns to healthy

- **GIVEN** the watch is in DEGRADED state due to failed watch→companion delivery
- **WHEN** sends to the companion succeed again for the hysteresis window
- **THEN** the watch returns to the HEALTHY state and clears the DEGRADED indication

#### Scenario: Watchdog constants match the signed values

- **GIVEN** the retargeted transport
- **WHEN** the watchdog evaluates delivery health
- **THEN** the interval, stale windows, warm-up and hysteresis equal the values signed in
  `CLINICAL_SIGNOFF.md`
- **AND** Batch 7 SHALL NOT be merged while any of them is unsigned

### Requirement: Direct-to-OSD MessageClient path is retained behind a build flag

The watch's existing direct-to-OSD `MessageClient` code path SHALL be kept behind a
compile-time flag rather than deleted (locked decision #4), so a future co-signed build can
use it without re-implementation. The default build SHALL use the companion path; the flagged
build SHALL restore the previous direct-to-OSD behaviour.

#### Scenario: Default build uses the companion path

- **GIVEN** the watch app built with default flags
- **WHEN** it starts monitoring
- **THEN** it sends to the companion and the direct-to-OSD path is inactive

#### Scenario: Flagged build uses the direct-to-OSD path

- **GIVEN** the watch app built with the direct-to-OSD flag enabled
- **WHEN** it starts monitoring
- **THEN** it sends on the DEC-046 paths directly to `uk.org.openseizuredetector` as it did
  before this change

### Requirement: Watch exposes a transport-contract version for compatibility checking

The watch app SHALL expose a transport-contract version that the companion can read (for
example in the `/osd/settings` payload or an explicit handshake message) so the companion can
detect a watch/companion version mismatch. The watch SHALL continue to function for its own
local duties (sensor capture, watchdog, haptics from any received alarm state) regardless of
the check result; surfacing the mismatch to the caregiver is the companion's responsibility.

#### Scenario: Watch advertises its contract version

- **GIVEN** the watch app is connected to the companion
- **WHEN** it sends its settings / handshake
- **THEN** the message includes a transport-contract version the companion can compare against
  its own

### Requirement: No change to the watch pipeline beyond the send target

Retargeting the transport SHALL NOT change the accelerometer sampling (25 Hz), the
magnitude→milli-g conversion, the `CircularBuffer`, the seizure state machine, the DEC-047
validation-mode guard, or the T8 watchdog algorithm. The only permitted `:wear` changes are:
the destination peer of the DEC-046 messages, what the delivery-health signal measures, the
staleness handling for received alarm state, the build flag for the retained direct path, the
exposed contract version, **the interpretation of received alarm states (table in *Watch
receives alarm state*), DEGRADED becoming visual-only (removal of its haptic), and the watchdog
constants as signed in `CLINICAL_SIGNOFF.md`** (DEC-057).

#### Scenario: Safety guards are intact after retargeting

- **GIVEN** the retargeted watch app
- **WHEN** its Robolectric test suite runs
- **THEN** the DEC-047 `isSequentialMode` default-false behaviour and the WakeLock contract
  tests still pass unchanged
- **AND** the `evaluateHealth` watchdog cases still pass, except those that assert a constant
  value or the DEGRADED haptic, which are updated deliberately and listed in the PR

---

## Success criteria coverage (from the proposal)

Jointly with `phone-companion-bridge`, this capability covers:

- The watch receives alarm states and vibrates correctly for OK / WARNING / ALARM (2, 3, 5),
  and stays silent for faults and MUTE — *Watch receives alarm state from the companion*.
- Killing the watch service, the companion, or OSD yields a visible (silent) DEGRADED
  indication within the signed ceiling — *Watch→companion delivery failure drives the DEC-048
  watchdog* (+ silent companion fault record). No fault produces sound or vibration.
- No change to the watch sensor pipeline / `CircularBuffer` / state machine / T8 beyond
  redirecting the send target — *No change to the watch pipeline beyond the send target*.

## Non-goals (from the proposal — restated as constraints)

- No on-watch ML inference.
- No changes to the watch sensor pipeline, `CircularBuffer`, seizure state machine, or T8
  watchdog algorithm/constants beyond redirecting the send target and what delivery measures.
- WearSD is not adopted as the product watch app.
- No cross-package Data Layer messaging to OSD in the default build.

## Deferred to `sdd-design` (do not resolve in spec)

- The alarm-state return mechanism and its maximum latency ceiling (locked decision #1).
- The staleness window for received alarm state on the watch.
- The exact form of the watch↔companion version handshake (settings field vs dedicated
  message).
- The name and build wiring of the direct-to-OSD compile-time flag.

## External gate (from locked decision #5)

The WearSD + OSD-beta validation experiment runs in parallel and MUST pass before `sdd-apply`
begins.
