# Feature: honest-bridge-status

## Objective
The companion's ongoing foreground notification tells the truth: it says data is flowing only while valid watch data is actually arriving, and shows the fault state otherwise.

## Problem
Field 2026-10-08 → 2026-10-11: with zero watch data for ~3.5 days, the ongoing notification kept saying "SeizureGuard bridge running — Forwarding watch data to OpenSeizureDetector" (`phone/src/main/res/values/strings.xml` `bridge_status_title`/`bridge_status_text`, built once by `BridgeNotifications.buildStatus`, never updated).

## Scope
- `:phone` only: status notification (id 4101, channel `osd_bridge_status`, LOW) re-posted with state-dependent title/text when the bridge fault changes (same place `onHealthTick` already handles fault transitions). Healthy: "Receiving watch data". Fault: "No data from the watch since HH:mm" for `NO_WATCH_DATA`, and the fault-specific text for other faults. Never on a timer; stays silent (DEC-057).
- Tests; docs: DEC-071, CAREGIVER_GUIDE (what the ongoing notification says), SAFETY_FINDINGS section 19 after safety review R18.

Out of scope: OSD's own "Data Sharing: Not Logged In" text; merging the separate silent fault notification; any signed constant.

## Constraints
- No signed constant changes. Silent: LOW channel, `setOnlyAlertOnce`, no sound/vibration.
- Delivery: worktree `../OpenSeizure-honest`, branch `fix/honest-bridge-status` from origin/main b2998d7 -> tests -> safety-reviewer R18 -> user OK before PR -> squash merge after CI.
- TDD: off. Runner: `./gradlew :phone:testDebugUnitTest :phone:lintDebug :wear:testDebugUnitTest :wear:lintDebug`. RDD off. Single PR, forecast ~200 lines.

## Tasks
- [x] T1 State-dependent status notification + tests (route: delegated writer; trigger: 2+ non-trivial files)
- [x] T2 Docs: DEC-071, CAREGIVER_GUIDE (same writer)
- [ ] T3 Checks; safety-reviewer R18; SAFETY_FINDINGS section 19; user OK; PR

## Acceptance criteria
- With no fault, the status notification never claims anything not observed (e.g. "Receiving watch data").
- On any fault transition the status text changes within one health tick (10 s); on recovery it returns to healthy.
- `NO_WATCH_DATA` shows the time of the last valid data (wall clock, HH:mm), or "since the bridge started" if none.
- No re-post when the fault is unchanged; never makes sound or vibration.

## Progress / evidence
- Design approved by user 2026-10-11.
- T1 done (commit 1ea49bb): `BridgeNotifications` (StatusView/statusView, state-dependent buildStatus, re-post only on view change), `BridgeState.hasReceivedValidData()`, `OsdBridgeService` wiring (since-time from `NoDataTracking.snapshot().outageStartMs`), strings.xml, new `BridgeStatusNotificationTest` (9 tests). Initial/neutral state "Waiting for watch data"; "Receiving watch data" only after a valid chunk was observed.
- T2 done: DECISIONS.md DEC-071, CAREGIVER_GUIDE.md section "La notificacion fija del telefono" (docs commit follows).
- Checks (observed): `./gradlew :phone:testDebugUnitTest :phone:lintDebug :wear:testDebugUnitTest :wear:lintDebug` BUILD SUCCESSFUL; phone 253 tests, 0 failures; wear 340 tests (sum of result XMLs, all variants), 0 failures, 1 skipped.

## Next step
T3: safety-reviewer R18, SAFETY_FINDINGS section 19, user OK, PR.
