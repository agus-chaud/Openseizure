---
name: sdd-flow
description: >
  Complete SDD orchestration reference: explore → propose → spec → design → tasks → apply → verify → archive.
  The ORCHESTRATOR reads this to understand the full flow, phase contracts, and human-in-the-loop checkpoints.
  Trigger: "/sdd-new", "/sdd-ff", "/sdd-continue", "/sdd-apply", "/sdd-verify", "iniciar sdd", "spec-driven".
license: MIT
metadata:
  author: gentleman-programming
  version: "1.0"
---

# SDD Flow — Full Orchestration Reference

## What Is SDD

Spec-Driven Development is a structured planning layer for substantial changes.
Instead of jumping to code, you build a chain of artifacts (proposal → spec → design → tasks) that
give you full clarity BEFORE implementation starts.

**When to use SDD:**
- The change touches multiple files or modules
- You're not 100% sure of the approach yet
- A team will review or implement the change
- The change has non-obvious risks

**When NOT to use SDD:**
- Single-file trivial fixes
- Copy/paste content changes
- Pure config updates

---

## Dependency Graph

```
sdd-init
    ↓
sdd-explore  (optional, but recommended for unfamiliar codebases)
    ↓
sdd-propose  ← HUMAN CHECKPOINT #1: approve proposal before continuing
    ↓
sdd-spec 
    ↓
sdd-design  
    ↓
sdd-tasks    ← HUMAN CHECKPOINT #2: approve tasks before implementing
    ↓
sdd-apply    (run in batches, one phase at a time)
    ↓
sdd-verify   ← HUMAN CHECKPOINT #3: approve verification report
    ↓
sdd-archive
```

---

## Human-in-the-Loop Protocol

**This is non-negotiable.** The orchestrator MUST stop and wait at these points:

### Checkpoint 1 — After sdd-propose

```
STOP. Show the user:
  - Problem being solved
  - Proposed approach (1-3 sentences)
  - Files/modules affected
  - Risks identified

Ask: "¿Aprobamos esta propuesta para seguir con spec + design?"
Wait for explicit approval before launching sdd-spec and sdd-design.
```

### Checkpoint 2 — After sdd-tasks

```
STOP. Show the user:
  - Full task list with phases
  - Estimated complexity per phase
  - Any blockers or unknowns

Ask: "¿Aprobamos este breakdown para comenzar la implementación?"
Wait for explicit approval before launching sdd-apply.
```

### Checkpoint 3 — After sdd-verify

```
STOP. Show the user:
  - What passed
  - What failed or has gaps
  - Recommendation (ship / fix first)

Ask: "¿Cerramos el cambio o hay algo para corregir primero?"
Wait for explicit decision before sdd-archive.
```

---

## Orchestrator Commands

### `/sdd-init`
Bootstraps the `openspec/` structure. Run once per project.
Delegates to: `sdd-init` sub-agent.

### `/sdd-explore <topic>`
Analyzes the codebase around a topic before proposing changes.
Delegates to: `sdd-explore` sub-agent.
Returns: codebase analysis, relevant files, current patterns.

### `/sdd-new <change-name>`
Meta-command. Runs explore → propose in sequence.
1. Launch `sdd-explore` sub-agent
2. Show summary to user (no checkpoint here — exploration is informational)
3. Launch `sdd-propose` sub-agent with exploration output
4. **CHECKPOINT #1** — wait for approval

### `/sdd-ff <change-name>`
Fast-forward: propose → spec + design (parallel) → tasks.
1. Launch `sdd-propose`
2. Show proposal → **CHECKPOINT #1**
3. Launch `sdd-spec` AND `sdd-design` in parallel (both delegate async)
4. Combine outputs, show task preview → **CHECKPOINT #2**
5. Launch `sdd-tasks`

### `/sdd-continue [change-name]`
Creates the next missing artifact in the dependency chain.
1. Check what exists: proposal? spec? design? tasks?
2. Identify what's missing next
3. Launch the appropriate sub-agent
4. Apply the relevant checkpoint if applicable

### `/sdd-apply [change-name]`
Implements tasks in batches.
Ask user: "¿Cuántas tareas por batch? (recomendado: 1 fase a la vez)"
Delegates to: `sdd-apply` sub-agent with specific task range.
Loop until all tasks done.

### `/sdd-verify [change-name]`
Verifies implementation against spec.
Delegates to: `sdd-verify` sub-agent.
**CHECKPOINT #3** after result.

### `/sdd-archive [change-name]`
Closes the change and archives artifacts.
Delegates to: `sdd-archive` sub-agent.

---

## Sub-Agent Skills Map

| Phase | Skill file |
|-------|-----------|
| Init | `~/.claude/skills/sdd-init.md` |
| Explore | `~/.claude/skills/sdd-explore.md` |
| Propose | `~/.claude/skills/sdd-propose.md` |
| Spec | `~/.claude/skills/sdd-spec.md` |
| Design | `~/.claude/skills/sdd-design.md` |
| Tasks | `~/.claude/skills/sdd-tasks.md` |
| Apply | `~/.claude/skills/sdd-apply.md` |
| Verify | `~/.claude/skills/sdd-verify.md` |
| Archive | `~/.claude/skills/sdd-archive.md` |

---

## Artifact Store Modes

| Mode | Behavior |
|------|----------|
| `engram` | Default. Persistent memory across sessions. Artifacts stored in Engram. |
| `openspec` | File-based. Creates `openspec/` directory in the project. |
| `hybrid` | Both. Cross-session recovery + local files. Higher token cost. |
| `none` | Returns results inline only. No persistence. |

Default: `engram` when available, otherwise `openspec`.

---

## Engram Topic Keys

Use these as `topic_key` when saving/reading artifacts:

| Artifact | Key |
|----------|-----|
| Project context | `sdd-init/{project}` |
| Exploration | `sdd/{change}/explore` |
| Proposal | `sdd/{change}/proposal` |
| Spec | `sdd/{change}/spec` |
| Design | `sdd/{change}/design` |
| Tasks | `sdd/{change}/tasks` |
| Apply progress | `sdd/{change}/apply-progress` |
| Verify report | `sdd/{change}/verify-report` |
| Archive report | `sdd/{change}/archive-report` |
| DAG state | `sdd/{change}/state` |

Reading from Engram (always two steps):
1. `mem_search(query: "sdd/{change}/proposal", project: "{project}")` → get observation ID
2. `mem_get_observation(id: {id})` → full content (search results are TRUNCATED, always do step 2)

---

## Result Contract

Every sub-agent returns this envelope:

```
status:            success | partial | failed | blocked
executive_summary: One paragraph. What happened, what was produced.
artifacts:         List of files/engram keys created or updated.
next_recommended:  What the orchestrator should do next.
risks:             Anything that could go wrong in the next step.
```

---

## Orchestrator Rules

1. **NEVER execute code inline** — all analysis, writing, and implementation goes to sub-agents.
2. **ALWAYS pre-resolve skill paths** before launching sub-agents. Pass the exact path, don't make the sub-agent search.
3. **ALWAYS include engram write instructions** in sub-agent prompts: "Save significant discoveries to engram via mem_save with project: '{project}'."
4. Sub-agents have NO memory — the orchestrator is responsible for passing all required context.
5. For SDD phases that require artifacts from previous phases, pass the **topic key or file path**, NOT the content itself — the sub-agent reads it directly.
6. Use `delegate` (async) by default. Only use `task` (blocking) when you need the result before your next step.

---

## Recovery

If session is interrupted mid-change:

**Engram mode:**
```
mem_search(query: "sdd/{change}/state", project: "{project}")
mem_get_observation(id: {id})
→ Resume from last completed phase
```

**Openspec mode:**
```
Read openspec/changes/{change}/state.yaml
→ Resume from last completed phase
```

**None mode:**
State not persisted. Explain to user and restart from the last known checkpoint.
