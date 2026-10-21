# Exploration: context-logging

Recording sleep/stress and other watch-measurable variables associated with each detected
seizure, for retrospective correlational analysis.

Status: **partial — not ready for proposal.** Two non-technical decisions block design work
(see Recommendation).

---

## Current State

### This repo (`:wear`) today

Thin, stateless Android Wear data source. Captures accelerometer at 25 Hz, sends 125-sample
JSON chunks to OSD (`/osd/accel_data`), receives `/osd/alarm_state` back, vibrates.
No Room, no persistent DB.

The only on-device storage is a debug-only CSV (`CsvLogger.kt`, DEC-030), explicitly gated to
`BuildConfig.DEBUG` for diagnostics — not meant to persist across sessions or hold clinical
data.

DEC-046 explicitly rejected `DataClient` (which *would* persist the last payload) in favour of
fire-and-forget `MessageClient`, with the stated rationale that the phone does not need to
store accelerometer data because it processes and discards it. **Non-persistence on the watch
is a deliberate, documented design stance, not an oversight.**

### OSD (third party, not controlled by us)

`OpenSeizureDetector/Android_Pebble_SD`, branch `beta`. Already has more infrastructure than
the watch does:

- `SdDataSourceAw.java` sends `/osd/alarm_state` back to the watch reactively (after each
  accel batch) and proactively every 20 s (keep-alive). **Real-time only — nothing persisted
  for the watch to read later.**
- `SdServer.sendPhoneAlarm()` fires an **implicit Android broadcast**
  `uk.org.openseizuredetector.dialler.ALARM` (extra: `NUMBERS`) when a real ALARM state
  triggers phone/SMS alerting. Rate-limited to 1/minute, gated behind the user's
  `mSMSAlarm`/`mPhoneAlarm` preferences. Intended for the companion "OSD Dialler" app, but
  nothing prevents another phone app from registering a receiver for it.
- `LocalEventQuerier.java` plus a local SQLite-backed event/datapoint store hold `dataTime`,
  `desc`, `eventType`, `alarmState` per event, with CSV export. **However: no `<provider>` is
  declared in OSD's `AndroidManifest.xml`** — this store is internal to the OSD process and is
  not reachable by another app via `ContentResolver` without an OSD code change.
- `WebApiConnection_osdapi.java` posts events (`dataTime`, `desc`, `eventType`) to a remote
  OSDB server (`osdapi.org.uk` or self-hosted) via `/api/events/` and `/api/datapoints/`.
  Requires the user to have configured an OSDB account; this is a cloud round-trip, not a
  same-device integration.
- `EditEventActivity.java` shows the existing seizure-event record already has a free-text
  `desc` field and `eventType`/subtype selectors, but no structured sleep/stress fields.

**No Health Connect and no Samsung Health SDK integration exists anywhere in this ecosystem
today. Sleep and stress are not measured at all, by either the watch app or OSD.**

---

## Q1 — Where should this log live?

| Option | What it requires | What it breaks / costs | Reachable? |
|---|---|---|---|
| (a) On the watch (Room / local DB) | Introduces persistence explicitly rejected by DEC-046 and kept debug-only by DEC-030 for battery and flash-wear reasons. Also: Health Connect is **not available on Wear OS at all** — confirmed via Samsung's developer forum (Health Connect does not support Wear OS devices; Health Services is the Wear OS API instead). The two data types the user wants (sleep, stress) are therefore architecturally **unreachable from the watch** regardless of persistence. Wear OS Health Services exposes only real-time fitness metrics (HR bpm, distance, calories), not sleep stages or a stress score. | Reverses a deliberate architectural decision, for data that cannot be read here anyway. | **No — not viable.** |
| (b) On the phone, in a NEW component in this repo | Health Connect (the only non-partner-gated path to sleep data) is phone-only. This repo has **no phone module today** — it was deliberately deleted (README Fase B: the `:phone` module was retired because inference belongs to OSD). Reintroducing one is a real reversal of the stated direction and must be scoped explicitly as *not* re-duplicating OSD's ML/alarm job — only reading Health Connect and listening for OSD's existing signals. | Contradicts the "OSD does everything phone-side" narrative unless very tightly scoped as a correlator, not a second detector. | **Yes** — this is the only place Health Connect and the OSD broadcast/API can be read. |
| (c) A separate standalone app/service | Same phone-side requirement as (b), but shipped as an independent app rather than folded into this repo. Decouples release cadence, avoids polluting the single-module `:wear` story, and can be built without touching OSD or this repo's watch code at all. | Needs its own distribution, its own consent/privacy story, and its own relationship to the caregiver-facing product. | **Yes** — same constraints as (b). |

**Conclusion:** (a) is ruled out by evidence, not merely by architectural preference — the data
is not obtainable from the watch. The real decision is (b) vs (c): both land on the phone; the
difference is whether the work is folded into a reborn `:phone` module in this repo or shipped
separately. **That is a product/repo-scope decision, not a technical one** — see Q5.

---

## Q2 — What sleep/stress data can a Galaxy Watch 8 actually expose?

Verified against current official documentation.

| API | Runs on | Sleep | Stress / HRV | Access model |
|---|---|---|---|---|
| **Google Health Connect** (`developer.android.com/health-and-fitness/health-connect`) | **Phone only** — confirmed unavailable on Wear OS. | `SleepSessionRecord`: start/end time, **sleep stages** (AWAKE / SLEEPING / DEEP / etc.), aggregate `SLEEP_DURATION_TOTAL`. Standard `READ_SLEEP` permission, no partner approval. | No native stress-score type. Only `HeartRateVariabilityRmssdRecord` (raw HRV number), `READ_HEART_RATE_VARIABILITY` permission, no partner approval. | Standard AndroidX permission model, no gatekeeping — **but data only appears if some app (Samsung Health) writes it in.** |
| **Samsung Health Data SDK** (`developer.samsung.com/health/data`) | Phone only, Android 10+. Reads Samsung Health's own aggregated store (sleep, exercise, blood pressure, food, glucose; stress and sleep are among supported categories). | Yes, via Samsung's own algorithm. | Samsung's proprietary **stress score** (computed from the Galaxy Watch BioActive/EDA sensor) lives here, **not** in Health Connect. | **Requires Samsung partner approval** (separate from the old Health SDK's approval — it does not carry over). One search result indicated Samsung is not currently accepting new Partner Apps Program applications; **this claim is time-sensitive and must be re-verified directly on developer.samsung.com before committing to this path.** |
| **Samsung Health Sensor SDK** (`developer.samsung.com/health/sensor`) | **Watch-side**, Galaxy Watch4+ on Wear OS Powered by Samsung. | No — exposes raw/processed **sensor signals** (accelerometer, ECG, PPG, HR including inter-beat intervals), not sleep architecture. | Only insofar as raw PPG/IBI could feed a custom HRV computation — not the Samsung stress score itself. | Different product from the Data SDK; also gated by developer approval. **Not useful for this feature as-is.** |
| **Wear OS Health Services** (`developer.android.com/health-and-fitness/health-services`) | Watch-side, mandatory on Wear OS 3+. | Not designed for this — a real-time/active-session fitness API (HR bpm, distance, calories, pace). Supports "passive goals" that can react to a falling-asleep event, but does not provide sleep stage/duration history. | Not exposed. | N/A for this use case. |

**Bridging fact (verified, official Samsung developer blog):** Samsung Health has synced sleep
and heart-rate data into Health Connect automatically since app v6.22.5 (October 2022), if the
user enables *Sync with Health Connect* in the Samsung Health app.

**Caveat:** community reports (Samsung's own community forum, Sleep-as-Android bug tracker)
indicate **HRV/stress sync into Health Connect is inconsistent and unreliable**. Sleep sync is
solid; HRV/stress sync is not guaranteed.

### Honest answer to the three stated wishes

- **Sleep hours + sleep stages** — obtainable, on the phone, via standard Health Connect
  `READ_SLEEP`. No Samsung partner approval needed, contingent on the user having Samsung
  Health's Health Connect sync toggle enabled. **This is the strongest, lowest-friction path.**
- **Stress level** — the actual Samsung stress score is proprietary, requires Data SDK partner
  approval, and is not confirmed to reliably reach Health Connect. The closest open alternative
  is raw HRV via Health Connect, which is a *proxy*, not the same metric, and itself has
  reported sync gaps.
- **Other viable signals** — Health Connect exposes heart rate, resting HR, respiratory rate,
  SpO2 and body measurements. All standard, no partner gate, contingent on Samsung Health
  writing them in.

---

## Q3 — How does anything on our side learn a seizure occurred?

Detection is 100 % inside OSD (third party). Confirmed integration surfaces, ordered by how
reachable they are **without changing OSD**:

1. **`uk.org.openseizuredetector.dialler.ALARM` implicit broadcast**
   (`SdServer.sendPhoneAlarm()`) — fires on real ALARM escalation, rate-limited to 1/minute,
   carries a `NUMBERS` extra (not a timestamp or event id, but arrival time is approximately
   the event time). **Reachable today with zero OSD changes**, provided the user has
   SMS/phone-alarm enabled in OSD settings.
   *Caveat:* this is an **implicit** broadcast. On Android 8+, apps cannot receive implicit
   broadcasts via a manifest-declared receiver in the background. A companion app would need a
   live, context-registered receiver (i.e. its own running service) at the moment the alarm
   fires — or OSD would need to send it as an explicit intent to a known package, which is an
   OSD-side change. **This is a real reliability caveat, not a formality.**

2. **Local OSD event DB (`LocalEventQuerier`)** — has exactly the fields needed (timestamp,
   alarm state, description), but its backing store has **no exported `<provider>`** in OSD's
   manifest, so it is not reachable by another app as-is. Requires an OSD-side change (export
   the provider, or add a query API). **Upstream ask — not something we can do unilaterally.**

3. **OSD's remote REST API (`/api/events/`, `/api/datapoints/`)** — already timestamped and
   structured (`dataTime`, `desc`, `eventType`), but requires the user to run and maintain an
   OSDB account and server, and requires network access. Heavier dependency than a same-device
   integration, and shifts data off the device entirely (see Q4).

4. **Extending the existing Wear Data Layer contract (DEC-046)** — e.g. having OSD forward a
   richer alarm-confirmed event back through `/osd/alarm_state` or a new path, which the watch
   could then relay onward. This is the only option that touches our repo directly, but it
   still requires an OSD-side change (OSD would need to *originate* that richer message) —
   same upstream dependency as option 2.

### On timing

The correlation goal (sleep the night before, stress that day) is inherently **retrospective**.
**A precise real-time trigger is not required** — a reliable timestamp on the seizure event,
joined after the fact against Health Connect's already-timestamped sleep/HRV records, is
sufficient.

This significantly lowers the bar: option 1 only needs to be good enough to log a timestamp,
not good enough to drive a live UI, which relaxes the Android 8+ reliability caveat somewhat.
A missed broadcast could potentially be reconciled later against OSD's local event history (if
that ever becomes reachable) or against the remote API (if the user has one configured).

---

## Q4 — Privacy and clinical implications

Safety-reviewer lens applied.

- **What changes.** Today this product holds no health data beyond transient accelerometer
  streams that are processed and discarded (DEC-046's explicit rationale). This feature would
  introduce **persistent storage of sleep and stress data tied to specific seizure
  timestamps** — a materially different category of data: longitudinal health history rather
  than transient sensor transport.

- **Where it would live.** On the phone (Q1), likely via Health Connect (Google-governed,
  standard permission/consent UI) plus a new local store for the seizure-timestamp-to-context
  join. Health Connect's own consent, revocation and access model is inherited for free — but
  **the new join data (which seizure correlates with which sleep night) is not covered by
  Health Connect's governance** and would need its own retention, export and deletion story.

- **`CLINICAL_SIGNOFF.md` gap.** Its escalation rule only covers **detection-logic constants**
  (thresholds, window counts, latency ceiling). It has **no category for a new class of stored
  patient data.** This feature would not trip the existing sign-off table as written — which is
  itself a finding: **the current clinical sign-off process has a lane for detection-threshold
  decisions but none for data-governance decisions.** Per the safety-reviewer skill's own
  fail-safe rule (when in doubt whether something is peripheral or detection-logic, treat it as
  detection-logic), the analogous fail-safe here is: **treat new persistent PHI-like storage as
  requiring explicit human sign-off before design work**, even though it does not map cleanly
  onto the existing table's categories.

- **`CAREGIVER_GUIDE.md` gap.** The guide currently promises the caregiver a narrow,
  well-understood system that detects a movement pattern and nothing else. Adding sleep/stress
  history changes what the product *holds* about the patient — a promise the guide does not
  currently make or govern (retention, who can read it, whether patient or caregiver can delete
  it). It must be updated before or alongside any such feature, extending the project's
  existing rule that alarm/behaviour changes require the guide to stay current.

- **Escalation verdict** (this exploration's own judgment, not a formal SafetyReviewReport):
  **this crosses a line that needs a human product/privacy decision before design** — not
  because of alarm-path risk, but because of PHI retention/consent scope creep that today's
  governance documents do not cover.

---

## Q5 — Scope expansion, honestly characterized

This moves the product from **transporting** accelerometer data to a third party for real-time
inference, to **holding** a longitudinal, joinable clinical history (seizure timestamp,
sleep, stress). That is a materially larger product than what exists today.

### What touches OSD (needs their buy-in — not a unilateral decision)

Any of the richer integration points in Q3 (options 2–4): local DB export, richer
`alarm_state` payload, or any change to how and when OSD tells anyone a seizure was confirmed.
We do not control OSD's roadmap. **This is a conversation with the OpenSeizureDetector
maintainers** (GitHub discussion — the same channel already used per README Fase C.2).

### What we could build entirely on our own side

A phone-side companion app that:
1. reads Health Connect for sleep/HRV,
2. listens for the existing `dialler.ALARM` broadcast as a best-effort "something happened
   around this time" signal, and
3. stores the join locally with its own consent and retention UI.

Buildable today, with the stated reliability caveat on (2), and without OSD's cooperation. It
is also the **weakest** version of the feature: it cannot reliably confirm every seizure event,
only those where SMS/phone-alarm was enabled *and* the companion app's receiver happened to be
alive.

### Statistical floor

Nocturnal tonic-clonic seizures are, for most patients on treatment, infrequent — plausibly
several per year to a few per month depending on control. For any correlational claim to be
more than anecdote:

- A **single paired comparison** (e.g. average sleep hours on seizure nights vs non-seizure
  nights) needs roughly **20–30+ seizure-night data points** before a difference is more than
  noise — and even then it supports only "this pattern appeared in this person's data", not a
  general clinical claim.
- A **multi-predictor model** (sleep hours + stress + anything else) commonly wants on the
  order of **10–20 events per predictor** as a floor (a widely used rule of thumb for
  logistic-regression-style analyses). Two or three predictors would therefore want on the
  order of **30–60+ confirmed events** before any single predictor's weight is meaningful.
- At plausible seizure frequencies, reaching even the lower end of that range could take
  **many months to a few years for a single patient**. This is fundamentally an **n = 1
  personal-analytics** exercise, not a population study, and should be framed that way from
  day one.

### Overclaiming risk

With few events, any surfaced pattern (e.g. "you sleep less before seizures") is a correlation
in a small, self-selected, non-randomized sample. It **cannot support a causal claim**, and the
product must not word it as one. Prefer "in your data so far, X and Y happened together N
times" over any causal phrasing.

---

## Approaches

### 1. Do nothing yet — open an upstream conversation with OSD maintainers first

- **Pros:** no unilateral scope creep; the strongest integration points require their
  cooperation anyway; avoids building on the weak broadcast-only signal.
- **Cons:** slow; dependent on a third party's roadmap; delays any user-visible value.
- **Effort:** low (a conversation), but high latency.

### 2. Phone-side companion app/module, self-contained, using only what is reachable today

Reads Health Connect (`READ_SLEEP`, `READ_HEART_RATE_VARIABILITY`) and listens for the
`dialler.ALARM` broadcast; own local store; own consent and retention screens.

- **Pros:** buildable now; no OSD dependency; exercises the honest n = 1 personal-analytics
  framing from day one.
- **Cons:** weakest signal fidelity (may miss events depending on OSD settings and process
  lifecycle); reopens the "no `:phone` module" architectural decision (Fase B) and needs
  explicit scoping so it does not become a second detector; still needs the
  `CLINICAL_SIGNOFF.md` / `CAREGIVER_GUIDE.md` gap addressed first (Q4).
- **Effort:** medium–high.

### 3. Extend the Wear Data Layer contract (DEC-046)

Have a confirmed-seizure event carry richer data through OSD back to the watch, which then
forwards to a phone-side store.

- **Pros:** reuses the transport this repo already owns and tests; keeps the watch's role
  legible (still relays, does not infer or persist).
- **Cons:** requires an OSD-side change to originate the richer payload — the same upstream
  dependency as Approach 1, just narrower; still needs a phone-side store to hold sleep/stress
  and perform the join.
- **Effort:** medium, but gated on upstream.

---

## Recommendation

**Do not start design work yet.** The two blocking unknowns are not technical:

1. **A product decision** — does this become a phone-side companion, and does it live in this
   repo or separately? (Q1 / Q5)
2. **A governance decision** — explicit human sign-off extending `CLINICAL_SIGNOFF.md` and
   `CAREGIVER_GUIDE.md` to cover new persistent health data. (Q4)

Once both are made, **Approach 2** is the only one buildable without waiting on OSD. It should
be scoped explicitly as *best-effort correlation logging*, not a second detector, with UI copy
that avoids implying statistical or causal confidence given the low expected event count (Q5).

---

## Risks

- Samsung's current partner-approval status for the Data SDK (stress score) is a live external
  fact this exploration could not fully verify. **Check developer.samsung.com directly** before
  any commitment to that data source.
- The `dialler.ALARM` broadcast is implicit and subject to Android 8+ background-broadcast
  restrictions. Reliability needs **empirical validation** (a receiver-alive test), not just
  source-reading.
- OSD's local event DB is not exported; any plan relying on it needs upstream cooperation that
  is not guaranteed.
- This feature will produce a small-N dataset. If UI copy overclaims pattern confidence, that
  is a real safety and ethics risk in a life-safety-adjacent product — it implies a false sense
  of predictive power.
- `CLINICAL_SIGNOFF.md`'s escalation rule does not currently cover a new class of stored health
  data. Proceeding to design without extending that governance leaves a real gap.

---

## Ready for Proposal

**No.** Needs an explicit user/product decision on scope (repo-local phone module vs separate
app; how much correlation fidelity is acceptable given the broadcast-only signal), and an
explicit human privacy/clinical sign-off extending the current governance documents, before
`sdd-propose` should run.
