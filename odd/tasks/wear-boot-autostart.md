# Feature: wear-boot-autostart

## Objective
Watch monitoring resumes by itself after the watch reboots (battery died, crash reboot, OS update) or after the app is updated, whenever the user had left monitoring on.

## Problem
Field 2026-10-08 04:22: the Galaxy Watch 8 most likely ran out of battery; boot reason `reboot,lpm_auto_reboot`, booted ~17:14. SeizureGuard never restarted because the watch app has no `BOOT_COMPLETED` receiver. OSD stayed in FAULT for ~3.5 days (engram `hardware/overnight-2026-10-11`).
- `SeizureMonitorService` already persists the user's intent (`seizure_monitor_prefs` / `was_monitoring`), but reads it only in `onRestartAfterKill()` (null-intent sticky restart). Nothing reads it at boot.
- `MainActivity` toggle state is `remember { mutableStateOf(false) }`: after any restart it shows "off" while monitoring may be running.

## Why
Alarm path availability: a reboot silently disables seizure detection until someone notices.

## Scope
- `:wear` only: boot receiver (`BOOT_COMPLETED` + `MY_PACKAGE_REPLACED`), `RECEIVE_BOOT_COMPLETED` permission, prefs helper reusing the existing key, truthful toggle state in `MainActivity`, Robolectric tests.
- Docs: `DECISIONS.md` DEC-069, `docs/SAFETY_FINDINGS_WATCH_OSD.md` section 17 (after safety review R16), `CAREGIVER_GUIDE.md`, `HARDWARE_RUNBOOK.md` (new hardware check: autostart after reboot on the real watch).

Out of scope: phone changes (fixes 2 and 3), `LOCKED_BOOT_COMPLETED` / direct boot, any signed constant.

## Constraints
- No signed constant changes (CLINICAL_SIGNOFF.md).
- Resume only if the user had monitoring on; the stop button keeps it off across reboots.
- Official docs (engram `research/wear-boot-fgs-health`): `BOOT_COMPLETED` is a background-FGS-start exemption; Android 15 boot-blocked FGS types do not include `health`. Unverified: whether the system checks `BODY_SENSORS` (while-in-use) for a boot-started `health` FGS -> must be verified on hardware.
- Delivery: worktree `../OpenSeizure-autostart`, branch `fix/wear-boot-autostart` from origin/main 30bf927 -> tests -> safety-reviewer R16 on real diff -> user OK before PR -> squash merge after CI checks "Unit tests + lint (:wear)" and "(:phone)".
- TDD: off (source: engram `sdd/openseizure/testing-capabilities`, `strict_tdd: false`). Runner: `./gradlew :phone:testDebugUnitTest :phone:lintDebug :wear:testDebugUnitTest :wear:lintDebug`.
- Receipt-driven development: off (global).
- Delivery strategy: ask-on-risk; forecast ~300 authored lines, single PR.

## Tasks
- [x] T1 Boot receiver + prefs helper + manifest + Robolectric tests (route: delegated writer; trigger: 2+ non-trivial files)
- [x] T2 Truthful monitoring state for the `MainActivity` toggle (same writer)
- [x] T3 Docs: DEC-069, CAREGIVER_GUIDE, HARDWARE_RUNBOOK check (same writer)
- [ ] T4 Run checks; safety-reviewer R16 on diff; SAFETY_FINDINGS section 17; user OK; PR

## Acceptance criteria
- On `BOOT_COMPLETED` or `MY_PACKAGE_REPLACED` with `was_monitoring = true`, the receiver starts `SeizureMonitorService` with `ACTION_START` via `startForegroundService`.
- With `was_monitoring` false/unset, or any other action, nothing starts.
- If the start throws, the receiver does not crash; it logs and posts a silent watch notification that opens `MainActivity` (DEC-057: faults are silent).
- The toggle reflects whether monitoring is actually running, also after a reboot or sticky restart.
- Existing `was_monitoring` key is reused (no migration).

## Progress / evidence
- Design approved by user 2026-10-11 ("arrancá por el arreglo 1").
- T1-T3 done by one delegated writer (route: delegated direct; trigger: 2+ non-trivial files). TDD off; tests written alongside.
- Code commit `4cbd5b2` (fix(wear): resume monitoring after watch reboot). Docs commit: the one that adds this file (`docs: record watch boot autostart (DEC-069)`).
- Files (code): `wear/.../boot/BootReceiver.kt` (new), `wear/.../service/MonitorPrefs.kt` (new, same file/key, no migration), `SeizureMonitorService.kt` (uses MonitorPrefs; companion `monitoring` StateFlow set true in onMonitoringStart, false in onMonitoringStop/onDestroy/onCreate; `ensureNotificationChannel()` now silent explicitly; cancels RESTART_NEEDED notification 2 on start), `MainActivity.kt` (collects `monitoring`), `AndroidManifest.xml` (RECEIVE_BOOT_COMPLETED + receiver, LOCKED_BOOT_COMPLETED intentionally omitted), `strings.xml` (restart-needed texts).
- Files (tests): `wear/.../boot/BootReceiverTest.kt` (10 tests, sdk 34), 3 new tests in `SeizureMonitorServiceTest` (60 total).
- Files (docs): `DECISIONS.md` DEC-069, `HARDWARE_RUNBOOK.md` DV-10, `CAREGIVER_GUIDE.md` (reloj reiniciado bullet).
- Verification, `./gradlew :phone:testDebugUnitTest :phone:lintDebug :wear:testDebugUnitTest :wear:lintDebug`: BUILD SUCCESSFUL (3m 1s). BootReceiverTest 10/10 in both flavors (companion, osdDirect); SeizureMonitorServiceTest 60/60 in both; wear companion total 151 tests, phone total 190, 0 failures. Lint clean in both flavors and phone.
- Correction (coordinator): startForeground() throws inside SeizureMonitorService.onCreate(), not in the receiver. Commit `89a6f3a` (fix(wear): surface a refused foreground start instead of crashing): shared `RestartNeededNotifier`; onCreate try/catch -> Log.e, silent notice, `foregroundFailed`, stopSelf(); onStartCommand returns START_NOT_STICKY without monitoring; was_monitoring untouched; seam `foregroundStarter`. 3 new service tests.
- Re-verification after correction, same command: BUILD SUCCESSFUL (2m). SeizureMonitorServiceTest 63/63 and BootReceiverTest 10/10 in both flavors; wear companion total 154, phone total 190, 0 failures; lint clean.
- Pending: SAFETY_FINDINGS section 17 (orchestrator, after R16); hardware check DV-10 (BODY_SENSORS while-in-use on boot-started health FGS is unverified).
- Env note: worktree needed a gitignored `local.properties` (copied from the main checkout) for the Android SDK path.

## Next step
T4: safety-reviewer R16 on the real diff, SAFETY_FINDINGS section 17, user OK, PR.
