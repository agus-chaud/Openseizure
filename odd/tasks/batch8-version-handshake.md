# Feature: batch8-version-handshake (SDD watch-osd-message-delivery, Batch 8)

**Objective:** detect when the watch app and the phone companion are built from contract-incompatible versions, instead of silently misinterpreting messages.
**Spec:** WCT-7 (watch exposes a transport-contract version), PCB-9 (companion compares; mismatch = silent passive notification + fault log per DEC-057, no sound/vibration, not ignored).
**Note:** `tasks.md` T8.2 says "caregiver-visible notification"; superseded by DEC-057 and the amended spec PCB-9 (silent).
**TDD:** not configured; runners `./gradlew :wear:testDebugUnitTest`, `./gradlew :phone:testDebugUnitTest`.
**Delivery:** 3 sub-deliverables, one PR each; safety-reviewer before each PR; user notified before PR.

## Sub-deliverables
- [x] **8a — Watch advertises its contract version (T8.1).** Authorized 2026-09-30. Worktree `../OpenSeizure-wt-8a`, branch `sdd/watch-osd-delivery-08a-wear-contract-version` from `origin/main` 1f9a693. Route: delegated direct (`sdd-apply`). `:wear` adds a transport-contract version to the `/osd/settings` message; `:phone` must keep accepting it and must not forward the extra field to OSD in a way that changes OSD behaviour. No comparison yet.
- [ ] **8b — Companion compares versions (T8.2 core + T8.3).** Pure compare (match / mismatch / missing field), new `VERSION_MISMATCH` fault kind in the fault log. Authorized 2026-10-01. Worktree `../OpenSeizure-wt-8b`, branch `sdd/watch-osd-delivery-08b-phone-version-compare` from `origin/main` ce28139. Defaults chosen by orchestrator (spec-compatible, safety-first): missing version = mismatch (watch older than 8a); forwarding to OSD never stops on mismatch; BridgeFault/alarm-relay behaviour unchanged; no notification yet (8c).
- [ ] **8c — Surface the mismatch silently (T8.2 surfacing).** Silent passive notification, morning summary entry, caregiver guide / runbook. Not authorized yet.

## Progress
- 2026-09-30: split agreed; 8a writer launched.
- 2026-09-30: 8a done — 955d7f9 (wear), 63352e8 (phone), 59+/4-. Tests wear+phone green (orchestrator re-run). Safety review R9: PASS (section 12). Next: user go-ahead to open PR.
- 2026-09-30: 8a merged as PR #35 (CI green); main wear/+phone/ == reviewed tip 63352e8. Worktree and branch removed.
- 2026-10-01: CI now runs :phone tests+lint (PR #36, job `Unit tests + lint (:phone)`; not yet a required check). 8b writer launched.
- 2026-10-01: 8b done and re-sliced: 8b-1 190ee68 (297+/4-), 8b-2 659957c (255+/3-); final tree == writer tip 52bbd21. Each slice tests+lint green alone; phone 162/162. Safety R10: 8b-1 PASS, 8b-2 PASS conditional on R10-F3 in 8c. DEC-065. Next: user go-ahead to open 2 stacked PRs.
