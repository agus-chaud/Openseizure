# Proposal: watch-osd-message-delivery — Phone Companion Bridge (Option F)

## Intent

Every watch→phone Data Layer message is dropped by Google Play Services before OSD sees it: the
routing AppKey is `packageName + signing cert`, and SeizureGuard (`com.seizureguard.wear`, our key)
shares neither with OSD (`uk.org.openseizuredetector`, Graham's release key). Confirmed root cause,
DEC-050 / Engram #897. Result: no accelerometer data reaches OSD → no inference → **no nocturnal
seizure alarm at all**. This is the single blocking defect in the product.

Graham Jones (OSD maintainer) confirmed the package-name constraint by email and recommended the
sanctioned third-party pattern: a lightweight phone companion that bridges watch data into OSD's
embedded web server — "essentially how the Garmin seizure detector works".

## Approach — Option F

1. **Watch keeps `applicationId = com.seizureguard.wear`.** Sensor pipeline, `CircularBuffer`,
   state machine, DEC-047 validation guard and the T8/DEC-048 watchdog stay as they are.
2. **New `:phone` module** with the SAME `applicationId` and SAME signing key as `:wear`. AppKey
   then matches because both apps are ours; the DEC-046 paths/JSON on the watch side are unchanged.
3. `:phone` receives `/osd/accel_data` and `/osd/settings` over `MessageClient` in its own
   foreground service and forwards them as HTTP `POST /data` / `POST /settings` to
   `http://127.0.0.1:8080`. Verified in OSD `beta`: `webserver/SdWebServer.java` (NanoHTTPD, port
   8080) routes both POSTs to `mSdDataSource.updateFromJSON(...)`.
4. **In OSD the user selects data source "Garmin"** (`SdDataSourceGarmin` is the passive POST
   receiver) — not "Android Wear", not "Network". OSD stays the official release APK; **zero OSD
   source changes, no self-built OSD**.
5. **Alarm-state return path** (the watch needs OSD's state for haptics + watchdog): candidate is
   `:phone` polling `GET /data` on `SdWebServer` and relaying `/osd/alarm_state` to the watch via
   `MessageClient`. Candidate only — decided in `sdd-design`, not here.

## Why Option F over the alternatives

| Option | Verdict |
|---|---|
| A — watch adopts `uk.org.openseizuredetector` + shared key | Not endorsed by Graham; forces the user to build and maintain their own OSD forever; collides with WearSD's identity. **Rejected as direction.** |
| B — replace the watch app with WearSD | Loses the DEC-048 watchdog/DEGRADED alert and the DEC-047 guard; still needs matching signing; ~6 h battery + irregular transfer per its own README. **Kept only as a throwaway validation experiment.** |
| C — watch as BLE peripheral | Graham: Wear OS "is likely controlling the bluetooth stack… probably difficult". **Deprioritized.** |
| D — cross-package Data Layer | Not technically possible; only upstream OSD ingress (e.g. Android broadcasts) would help. **Out of scope.** |

Option F is OSD's sanctioned third-party pattern, requires no cooperation from Graham, and keeps
SeizureGuard's watch app and its safety features intact.

## Scope

### In Scope
- New `:phone` Android handheld module: MessageClient receiver, reliable foreground service,
  HTTP forwarder to `127.0.0.1:8080`, watch→companion delivery watchdog, overnight battery budget.
- Retarget the watch's `WearDataLayerManager` from OSD to the companion (OSD-facing half moves into
  `:phone`); keep DEC-046 JSON shape and paths.
- Alarm-state relay back to the watch so haptics and the T8 watchdog keep working.
- Shared signing configuration across `:wear` and `:phone`.
- New DEC entry; de-drift `docs/GUIA_CONECTAR_RELOJ_TELEFONO.md` and the OSD-setup instructions
  (data source = Garmin, two APKs).
- `safety-reviewer` skill run before any PR (project rule — alarm-path code).

### Out of Scope
- Any change to OSD's source, or a self-built OSD.
- On-watch ML inference (stays in OSD).
- Changes to the watch sensor pipeline, `CircularBuffer`, state machine or T8 watchdog beyond
  redirecting where data is sent.
- Adopting WearSD as the product (validation experiment only).
- Upstream Android-broadcast ingress in OSD (possible future, cleaner than localhost HTTP).

## Reversal of the 2026-06-05 `:phone` deletion — explicit

The 2026-06-05 decision deleted `:phone` as dead code duplicating OSD's inference. **This proposal
partially reverses it, and the reversal is justified**: the new `:phone` does **no inference, no
threshold, no SMS**. It is a pure transport bridge that exists only because OSD cannot receive Data
Layer messages from a third-party package. Different purpose, not the redundancy that was removed.

## Capabilities

### New Capabilities
- `phone-companion-bridge`: the `:phone` module — receive watch messages, forward to OSD's local
  web server, relay alarm state back, detect and surface delivery failure.
- `watch-companion-transport`: the watch's send/receive contract with the companion (retargeted
  DEC-046), including delivery-failure signalling into the existing T8 watchdog.

### Modified Capabilities
- None. `openspec/specs/` is currently empty (only `.gitkeep`), so there are no existing spec files
  to delta.

## Affected Areas

| Area | Impact | Description |
|---|---|---|
| `settings.gradle.kts`, root `build.gradle.kts` | Modified | Re-add `:phone` to the build |
| `phone/` (new module) | New | Bridge service, MessageClient listener, HTTP forwarder, watchdog, minimal UI |
| `wear/src/main/java/com/seizureguard/wear/data/WearDataLayerManager.kt` | Modified | Peer is now the companion, not OSD |
| `wear/src/main/java/com/seizureguard/wear/service/SeizureMonitorService.kt` | Modified | Delivery-health semantics now measure watch→companion |
| Signing config (`:wear`, `:phone`) | New/Modified | Must share one key |
| `DECISIONS.md`, `docs/GUIA_CONECTAR_RELOJ_TELEFONO.md`, `README.md` | Modified | New DEC; two-APK install; OSD data source = Garmin |

## Risks

| Risk | Likelihood | Mitigation |
|---|---|---|
| New component in a life-safety alarm path fails silently overnight | Med | Companion needs its own foreground service + watch→companion delivery watchdog; watch keeps DEC-048 DEGRADED haptics; end-to-end overnight validation before trusting it |
| Companion killed by OEM battery optimisation at 3am | Med | Foreground service, wake-lock discipline, battery-optimisation exemption, START_STICKY resume; explicit overnight battery budget |
| Localhost HTTP is "a bit messy" (Graham's words) and is an untyped contract | Med | Pin the exact POST body form OSD accepts (`dataObj` param vs `postData` file); note Android-broadcast ingress as a cleaner future upstream option |
| OSD web server not running / wrong data source selected by the user | Med | Companion must detect POST failure and surface it as a fault, not fail silently; document the Garmin selection step |
| Two APKs to sideload → user install/upgrade error | Med | Install runbook update; version-match check between watch and companion |
| Reviewer load: new module + transport change may exceed the 400-line budget | High | `sdd-tasks` must forecast and slice into chained PRs |
| Regression in the watch's existing safety features while retargeting | Low | Keep changes to `:wear` minimal; Robolectric tests alongside; `safety-reviewer` PASS before PR |

## Rollback Plan

1. Revert the `:wear` commit that retargets `WearDataLayerManager` — the watch returns to its
   current (non-delivering, but stable and self-monitoring) behaviour.
2. Remove `:phone` from `settings.gradle.kts` and uninstall the companion APK. No OSD state,
   database or config is mutated by this change, so nothing needs undoing on the phone beyond
   switching OSD's data source back.
3. Signing-key sharing is additive and needs no rollback.
4. Because the current state is already "no alarm delivery", rollback cannot make safety worse than
   today — but it also does not restore protection. Do not remove a working companion without a
   replacement path.

## Dependencies

- OSD phone app (official release APK, V5.0.8+ / beta) with `SdWebServer` and the Garmin data
  source available.
- One signing key usable by both modules.
- Physical Galaxy Watch 8 + paired phone for overnight validation (no device farm; user tests in
  Android Studio; agents never run builds).
- The project's `safety-reviewer` skill — mandatory
  before any PR touching this path.

## Success Criteria

- [ ] OSD (official release APK, data source = Garmin) displays live accelerometer data originating
      from the SeizureGuard watch app.
- [ ] OSD produces alarm states from that data, and the watch receives them and vibrates correctly
      (OK / WARNING / ALARM).
- [ ] Killing the companion, disabling OSD, or stopping the watch service produces a **visible
      DEGRADED/fault signal within 60 s** — no silent stall anywhere in the chain.
- [ ] One full 8 h overnight run completes with continuous delivery and acceptable battery on both
      devices.
- [ ] No OSD source change and no self-built OSD required.
- [ ] `safety-reviewer` returns PASS before merge.

## Open decisions (need the user before `sdd-spec`)

1. **Alarm-state return path**: companion polls `GET /data` (simple, adds latency and a poll loop)
   vs another mechanism. Latency ceiling for alarm→haptics?
2. **Caregiver fault UI**: should the companion own a visible "degraded / not receiving" screen and
   notification for the caregiver, or stay headless with the watch as the only alerting surface?
3. **`:phone` minSdk**: which Android version must the companion support (affects foreground-service
   and battery-exemption APIs)?
4. **Keep the watch's direct-to-OSD `MessageClient` code behind a flag** for a future co-signed
   build, or delete it outright?
5. **Validation experiment**: run the WearSD + OSD beta throwaway test first (Graham's suggestion)
   to prove the chain, or go straight to building the companion?

## Proposal question round

Interactive mode, but this executor cannot prompt the user directly. The orchestrator should put
the five open decisions above to the user before `sdd-spec`. The proposal currently assumes:
alarm state returns via companion polling; the companion is minimally headless with a fault
notification; the watch's direct-OSD code is kept behind a flag; and the WearSD experiment runs in
parallel, not as a blocker.

## Updates after approval

This proposal is a historical record of what was decided at the time. Later decisions superseded the
following points; the decisions themselves live in `DECISIONS.md`.

- Update 2026-10-01: superseded by DEC-057 and DEC-058 — "notification for the caregiver" on a
  companion fault became a fully silent presentation (passive notification, fault log, silent morning
  summary). Only a real alarm raised by OSD interrupts.
- Update 2026-10-01: superseded by DEC-059 — the watchdog timing envelope was signed in
  `CLINICAL_SIGNOFF.md` (10 s tick, 40 s delivery stale, 40 s alarm-state stale, hysteresis 2, warm-up 60 s).
  The inbound worst case is about 75 s with OSD frozen, not 60 s (DEC-063).
- Update 2026-10-01: superseded by DEC-065 and DEC-066 — the "version-match check between watch and
  companion" is a recorded, persistent, silent notice; a mismatch never stops forwarding to OSD.
