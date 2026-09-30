# Feature: wear-hardening-7d (Batch 7 follow-ups H7-4..H7-7)

**Objective:** close the safety-review R7 follow-ups so the watch screen always tells the truth about system state.
**Scope (authorized 2026-09-30):** `:wear` only. No change to vibration decision logic (`AlarmStateManager.handleAlarmState`/`classify` semantics) or signed constants.
**Route:** delegated direct (`sdd-apply` writer) in worktree `../OpenSeizure-wt-7d`, branch `sdd/watch-osd-delivery-07d-wear-hardening` from `origin/main` (cb8a7f5). Trigger: writer trigger (2+ non-trivial files).
**TDD:** not configured (source: project has no TDD setting); runner `./gradlew :wear:testDebugUnitTest`.
**Delivery:** single PR, forecast ~250-350 lines; safety-reviewer on real diff before PR; user notified before PR.

## Tasks
- [x] **T1 (H7-5)** Watchdog uses monotonic clock (`SystemClock.elapsedRealtime`) instead of wall clock; tests for backward/forward wall-clock jumps keep the 60 s dead-link ceiling.
- [x] **T2 (H7-4)** Remove tick/listener race: display staleness computed from timestamps at point of use (or atomic), fresh ALARM shown immediately; test reproducing the race.
- [x] **T3 (H7-6)** MUTE (6) shows "SILENCIADO — sin alarmas" (high contrast), no vibration.
- [x] **T4 (H7-7)** Fault label contrast >= 4.5:1; real simulation test for ~75 s frozen-OSD ceiling; out-of-Int-range alarm_state -> silent fault never ALARM; persistent "VERSIÓN DE PRUEBA" banner in osdDirect flavor.
- [x] **T5** Docs on mia: DEC-064, SAFETY_FINDINGS H7-4..H7-7 closed, CAREGIVER_GUIDE MUTE line.

## Checks
`./gradlew :wear:testDebugUnitTest` (both flavors via alias), orchestrator re-run, safety-reviewer R8 on diff.

## Progress
- 2026-09-30: worktree created, writer launched.
- 2026-09-30: T1 9749ccc, T2 aeb0da4 (branch 07d-1, 199+/24-); T3 c92be33, T4 cc25593, R8 fixes cf400a8 (branch 07d-2, 307+/15-). Tests 130/130 both flavors (orchestrator re-run). Safety review R8: 07d-1 PASS, 07d-2 PARTIAL PASS resolved (MUTE label chosen by user: "SILENCIADO, no avisa convulsiones"). Docs committed on mia (DEC-064). Next: open 2 stacked PRs after user go-ahead.
