# Feature: wear-sensor-decimation

## Objective
Guarantee the watch forwards accelerometer data at exactly 25 Hz (one 125-sample chunk every 5 s), even when the shared hardware sensor runs faster because another client requested a higher rate.

## Problem
OSD reports `Data arriving too quickly: 2.9s (expected 5±1s)`. Field measurement 2026-10-07 (17 min, Galaxy Watch 8 SM-L330):
- Chunks are sample-count driven (`SeizureMonitorService.kt:641`); the requested 40 000 us period is only a hint (`:564`).
- Samsung sysui `SecTiltDetectorImpl` registers the same LSM6DSV accelerometer at 20 000 us (50 Hz) during wrist raises; Android then delivers 50 Hz to every listener.
- 208 chunks: 199 at 5.0 s, 9 under 4 s (min 2.5 s), every fast episode coinciding with a tilt-detector registration.
- When a 50 Hz period exceeds 30 s (seen 2026-10-06, ~32 s twice), OSD enters FAULT and drops packets unanalysed (accepted risk A2, but caused by us).
- Worse: during 50 Hz bursts each packet spans 2.5 s while OSD assumes 25 Hz, so the spectrum OSD analyses is frequency-doubled.

## Why
Alarm path correctness: wrong cadence can mask alarms (FAULT) and wrong sample rate corrupts OSD's 3–8 Hz analysis.

## Scope
- `:wear` only. Timestamp-based decimation (`SensorEvent.timestamp`) to a 40 ms grid before samples enter the buffer.
- Unit tests for the decimator.
- Docs: `DECISIONS.md` DEC-068, `docs/SAFETY_FINDINGS_WATCH_OSD.md` section 16.

Out of scope: companion pacing, OSD settings, any signed constant change (25 Hz, 125 samples, `SAMPLE_STALE_MS` stay as signed).

## Constraints
- No signed constant changes (CLINICAL_SIGNOFF.md).
- Delivery: worktree -> tests -> safety-reviewer on real diff -> user OK before PR -> squash merge.
- TDD: off (source: `openspec/config.yaml` `strict_tdd: false`). Runner: `./gradlew :phone:testDebugUnitTest :phone:lintDebug :wear:testDebugUnitTest`.
- Delivery strategy: ask-on-risk; forecast ~250 authored lines, single PR.

## Tasks
- [x] T1 Decimator class + unit tests (route: delegated writer; trigger: 2+ non-trivial files)
- [x] T2 Wire decimator into `SeizureMonitorService.onSensorChanged` (same writer)
- [x] T3 DEC-068 + SAFETY_FINDINGS section 16 (same writer)
- [ ] T4 Run checks; safety-reviewer on diff; user OK; PR

## Acceptance criteria
- At native 25 Hz (jittered 37–43 ms) no sample is dropped.
- At 50 Hz exactly every other sample is kept; at other rates the long-run kept rate is 25 Hz.
- After a gap (sensor stall/FIFO), the grid resyncs without a burst of accepted samples.
- `SAMPLE_STALE_MS` liveness still updated by every raw event.

## Progress / evidence
- Root cause measured 2026-10-07; engram `hardware/osd-data-too-fast`.

## Next step
Safety-reviewer on the real diff; user OK; PR.

## T1-T3 evidence (writer, 2026-10-07)
- T4 checks: `./gradlew :phone:testDebugUnitTest :phone:lintDebug :wear:testDebugUnitTest`: BUILD SUCCESSFUL (2m56s); `SampleRateDecimatorTest` 7/7 passed in both wear flavors.
- Files: `wear/.../ml/SampleRateDecimator.kt` (+test), `SeizureMonitorService.kt` (liveness per raw event, decimated samples to buffer), `DECISIONS.md` DEC-068, `docs/SAFETY_FINDINGS_WATCH_OSD.md` section 16 (safety verdict pending).
- Remaining in T4: safety-reviewer on diff, user OK, PR.
