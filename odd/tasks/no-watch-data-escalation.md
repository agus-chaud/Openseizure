# Feature: no-watch-data-escalation

## Objective
When the phone bridge receives no watch data for a long time, it automatically retries to recover (up to 3 times) and, after 8 hours, warns the user with a clear phone notification that vibrates and says why monitoring is down. A deliberate stop on the watch does not trigger any of this.

## Problem
Field 2026-10-08 → 2026-10-11 (engram `hardware/overnight-2026-10-11`): ~3.5 days with zero watch data. The fault was detected in 30 s (`NO_WATCH_DATA_MS`), but every fault is silent (DEC-057), so nobody noticed until the morning summary. There is no automatic recovery and no way for the phone to wake a stopped watch app.

## Why
A dead system must not look like a quiet night for days. User decision 2026-10-11: retries at 3 h, alert at 8 h with notification + vibration on the phone (partial exception to DEC-057).

## Scope
- `:phone`: escalation logic in the bridge health tick (pure, unit-tested); persisted baseline so process restarts do not reset the clock; retry action (re-register listener + send `/osd/resume_monitoring` to connected nodes + record outcome); 8 h alert on a new vibrating channel with cause text; handling of `/osd/monitoring_stopped` from the watch.
- `:wear`: send `/osd/monitoring_stopped` on user stop; manifest-declared `WearableListenerService` for `/osd/resume_monitoring` that resumes monitoring only if `was_monitoring` is true (reuses DEC-069 start path and `RestartNeededNotifier`).
- Docs: DEC-070, `CLINICAL_SIGNOFF.md` (4 new constants signed by user 2026-10-11), `CAREGIVER_GUIDE.md`, `HARDWARE_RUNBOOK.md` DV-11, `docs/SAFETY_FINDINGS_WATCH_OSD.md` section 18 (after safety review R17).

Out of scope: ongoing notification text (fix 3), sound on the alert, DND bypass, SMS, any change to existing signed constants.

## Signed constants (user, 2026-10-11)
| Constant | Value |
|---|---|
| `NO_DATA_RETRY_AFTER_MS` | 3 h |
| `NO_DATA_RETRY_INTERVAL_MS` | 1 h (retries at 3 h, 4 h, 5 h) |
| `NO_DATA_MAX_RETRIES` | 3 |
| `NO_DATA_ALERT_AFTER_MS` | 8 h |

## Constraints
- No change to existing signed constants. DEC-057 stays for every other fault: only the 8 h alert vibrates; no sound.
- Fail-safe: if the "stopped by user" message is lost, the alert fires (never the other way round).
- Watch-side resume from a background `WearableListenerService` is not a documented FGS background-start exemption: best effort, must be verified on hardware (DV-11).
- Delivery: worktree `../OpenSeizure-nodata`, branch `fix/no-watch-data-escalation` from origin/main 68d7546 -> tests -> safety-reviewer R17 -> user OK before PR -> squash merge after CI.
- TDD: off (engram `sdd/openseizure/testing-capabilities`). Runner: `./gradlew :phone:testDebugUnitTest :phone:lintDebug :wear:testDebugUnitTest :wear:lintDebug`.
- RDD: off (global). Delivery strategy: single PR (user: one PR per fix); forecast ~700 authored lines, above the ~400 heuristic because phone logic, watch listener, tests and docs are one behavior.

## Tasks
- [x] T1 Phone escalation logic + persistence + retry + 8 h alert + tests (route: delegated writer; trigger: 2+ non-trivial files)
- [x] T2 Watch: stop message + resume listener service + tests (same writer)
- [x] T3 Docs: DEC-070, CLINICAL_SIGNOFF, CAREGIVER_GUIDE, HARDWARE_RUNBOOK DV-11 (same writer)
- [ ] T4 Checks; safety-reviewer R17; SAFETY_FINDINGS section 18; user OK; PR

## Acceptance criteria
- No retry and no alert before 3 h without valid watch data.
- Retries at 3 h, 4 h, 5 h; never more than 3 per outage; each retry is recorded with its outcome (watch connected / not connected).
- At 8 h exactly one alert per outage: notification + vibration, no sound, text says since when and the likely cause (watch not connected vs. connected but not sending).
- Valid watch data resets the escalation and cancels the alert.
- A process restart or phone reboot does not reset the outage clock; a user "Start bridge" does.
- After `/osd/monitoring_stopped`, no retries and no alert until valid data arrives again.
- Watch resume listener starts monitoring only if `was_monitoring` is true; failure posts the silent restart-needed notice, never crashes.

## Progress / evidence
- Design and constants approved/signed by user 2026-10-11.
- T1 (commit bb00204): `phone/.../bridge/NoDataEscalation.kt` (signed constants, pure `noDataAction`, persisted `NoDataTracker`, `NoDataEscalator`), `WearWatchLink.kt`, wiring in `OsdBridgeService` (valid accel, `/osd/monitoring_stopped`, `escalationTick()` in the 10 s tick), `BridgeNotifications` (channel `osd_bridge_no_data_alert`, id 4106), `SetupActivity` (Start resets, Stop cancels), `FaultKind` events + `isEvent` filter in `buildSummary`, strings. Tests: `NoDataActionTest` 14, `NoDataTrackerTest` 9, `NoDataEscalatorTest` 17, `NoDataAlertServiceTest` 13, `FaultLogTest` +1.
- T2 (commit 1fb2209): `WearDataLayerManager` paths + `sendMonitoringStopped`, `SeizureMonitorService.onMonitoringStop` notifier seam (process-lifetime scope, 5 s cap), `boot/ResumeMonitoring.kt` shared start path (used by `BootReceiver`), `data/ResumeRequestListenerService.kt` + manifest entry, `RestartNeededNotifier` text parameter. Tests: `ResumeRequestListenerServiceTest` 8 (incl. manifest filter resolution), `MonitoringStoppedMessageTest` 6, `WearDataLayerManagerTest` +2; `BootReceiverTest` 10 unchanged and green.
- T3 (docs commit): DEC-070, CLINICAL_SIGNOFF (4 constants, signed table), CAREGIVER_GUIDE, HARDWARE_RUNBOOK DV-11. SAFETY_FINDINGS section 18 NOT written (after R17).
- Verification (worktree, foreground): `./gradlew :phone:testDebugUnitTest :phone:lintDebug :wear:testDebugUnitTest :wear:lintDebug`: BUILD SUCCESSFUL. phone 244 tests, 0 failures; wear companion 170, osdDirect 170 (1 skipped, pre-existing `assumeFalse`), 0 failures; both lints pass. Not run: any hardware or adb check.
- Deviations: (1) `noDataAction` has an extra optional `lastRetryAtMs` input so a late wake-up cannot burn all 3 retries in 3 ticks; (2) a retry may still run after the alert if it was overdue; (3) after a service restart with an alert on record the alert is re-posted (notification is lost on reboot; it vibrates again); (4) a third cause text for "node check failed"; (5) `RestartNeededNotifier.post` got a text parameter so the phone-request refusal does not say "the watch rebooted"; (6) DV-11 uses the phone wall-clock shortcut instead of a debug trigger: no debug-only code was added; (7) no `TRANSPORT_CONTRACT_VERSION` bump.
- Open risks: background FGS start from `WearableListenerService` unverified (DV-11); stopped-by-user flag has no expiry; wall-clock jumps move the outage clock; old watch builds never send the stop message (false alert after a deliberate stop, fail-safe); alert reaches only the phone running the bridge.
- Coordinator review + safety review R17 on the real diff: **PASS**, `alarm-path`; 4 new constants signed in CLINICAL_SIGNOFF.md. Fixed in review (R17-F3): `setup_instructions` and CAREGIVER_GUIDE still claimed faults "never" vibrate. Findings R17-F1..F10 in `docs/SAFETY_FINDINGS_WATCH_OSD.md` section 18.

## Next step
T4: re-run checks after the R17 text fixes, then user OK before PR.
