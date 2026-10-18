# Feature: night-only-monitoring

## Objective
Watch monitoring runs automatically every night from 22:00 to 10:00 local time and is paused during the day, to avoid daytime false alarms and save watch battery. The phone knows a daytime pause is scheduled and does not treat it as a fault.

## Problem
- User decision 2026-10-18: the patient never has seizures during the day; daytime OSD alarms (e.g. 2026-10-11 14:41–16:08, alarmState=2) would become false SMS alerts once the caregiver SMS is configured.
- The watch ran out of battery twice (2026-10-08 and ~2026-10-11/16, `lpm_auto_reboot`); 24 h monitoring drains it.
- Data: wake-up time averages 9:42 on non-delivery days (Candu-health Q3), so the window ends at 10:00 (user choice), not 09:00.

## Scope
- `:wear`: daily schedule (start 22:00, stop 10:00 local) with exact alarms; re-armed on boot, app update, time and timezone changes; boot/update inside the night window starts monitoring; the 10:00 auto-stop sends a "scheduled pause" message (not the deliberate-stop message); manual start during the day still works until 10:00 or a manual stop; a manual stop at night lasts until the next 22:00.
- `:phone`: handle the scheduled-pause message (resume time); status notification "Paused for the day — resumes at 22:00"; no retries/8 h alert during the pause; if no data arrives after the scheduled resume, the outage clock starts at the scheduled resume time.
- Tests on both sides; docs: DEC-072, CLINICAL_SIGNOFF (MONITOR_START 22:00 / MONITOR_STOP 10:00 signed by user 2026-10-18), CAREGIVER_GUIDE, HARDWARE_RUNBOOK DV-12, SAFETY_FINDINGS section 20 after safety review R19.

Out of scope: phone bridge restart after app update (MY_PACKAGE_REPLACED, separate fix); fault-cause ordering (OSD blamed for ~20 s when the watch stops, separate fix); any other signed constant.

## Constraints
- No change to existing signed constants. DEC-057 stays: the pause is silent.
- Fail-safe: if the watch does not resume at 22:00, the phone escalates as for any outage (retry at 01:00, alert at 06:00).
- Exact alarm permission and FGS start from an alarm must be verified against official Android docs before coding, and on hardware (DV-12).
- Delivery: worktree `../OpenSeizure-schedule`, branch `feat/night-only-monitoring` from origin/main 2e36486 -> tests -> safety-reviewer R19 -> user OK before PR -> squash merge after CI.
- TDD: off (engram `sdd/openseizure/testing-capabilities`). Runner: `./gradlew :phone:testDebugUnitTest :phone:lintDebug :wear:testDebugUnitTest :wear:lintDebug`. RDD: off.
- Delivery strategy: single PR (user: one PR per fix); forecast ~600 authored lines.

## Tasks
- [x] T1 Wear schedule + alarms + re-arm receivers + scheduled-pause message + tests (route: delegated writer; trigger: 2+ non-trivial files)
- [x] T2 Phone scheduled-pause handling + status text + escalation baseline + tests (same writer)
- [x] T3 Docs: DEC-072, CLINICAL_SIGNOFF, CAREGIVER_GUIDE, HARDWARE_RUNBOOK DV-12 (same writer)
- [ ] T4 Checks; safety-reviewer R19; SAFETY_FINDINGS section 20; user OK; PR

## Acceptance criteria
- At 22:00 local the watch starts monitoring without user action; at 10:00 it stops and tells the phone it is a scheduled pause.
- Boot, app update, time change or timezone change re-arm both alarms; boot/update inside 22:00–10:00 starts monitoring, outside does not.
- Manual start during the day works; manual stop at night holds until the next 22:00.
- Phone shows a paused status (silent) during the pause and never retries or alerts for it; after 22:00 without data, escalation starts counting from 22:00.
- Nothing sounds or vibrates for the pause.

## Progress / evidence
- Window 22:00–10:00 decided and signed by user 2026-10-18.
- Step 0 (official docs, see DEC-072): USE_EXACT_ALARM (+ SCHEDULE_EXACT_ALARM maxSdk 32) chosen; setExactAndAllowWhileIdle; exact-alarm FGS exemption is documented but wording is "user-requested action" and says nothing about type health -> verified only by DV-12 (open risk).
- T1 commit d971143 (wear): schedule/{MonitoringSchedule,ScheduleLabel,MonitoringScheduler,ScheduleAlarmReceiver,ScheduleRearmReceiver,MonitoringPausedMessage}.kt, BootReceiver, MonitorPrefs, SeizureMonitorService, MainActivity, manifest, strings. Tests: wear 210 passed / 0 failed (companion), osdDirect 210 / 0 failed / 1 skipped.
- T2 commit 3a7ee8f (phone): NoDataEscalation (pausedUntil), BridgeNotifications (paused status), OsdBridgeService (PATH_MONITORING_PAUSED), WatchMessageParser.parsePausedUntil, strings. Tests: phone 278 passed / 0 failed.
- Lint: :phone:lintDebug and :wear:lintDebug (both flavors) passed.
- T3: DEC-072, CLINICAL_SIGNOFF (MONITOR_START/STOP), CAREGIVER_GUIDE, HARDWARE_RUNBOOK DV-12.
- Decision: TRANSPORT_CONTRACT_VERSION stays 1 (a bump to 2 was tried and reverted: ~15 existing tests hard-code 1 as MATCH). Cost documented in DEC-072: old phone + new watch = daily 18:00 false alert.
- Fix (coordinator): phone resume request (DEC-070) now also starts inside the night window with no manual stop pending, and sets was_monitoring=true. +5 tests in ResumeRequestListenerServiceTest.
- Not done: no hardware test; SAFETY_FINDINGS section 20 and R19 left for T4.

## Next step
T4: safety-reviewer R19, SAFETY_FINDINGS section 20, user OK before PR.
