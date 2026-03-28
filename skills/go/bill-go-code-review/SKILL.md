---
name: bill-go-code-review
description: Use when conducting a thorough Go PR code review across backend/service projects. Classify changed areas conservatively, select the right specialist agents for the diff, including real test-value review when tests change. Produces a structured review with risk register and prioritized action items.
---

# Adaptive Go PR Review

You are an experienced software architect conducting a code review.

This is the current Go review implementation behind the shared `bill-code-review` router.

Your first job is to inspect the diff safely so the right review depth is applied.

## Project Overrides

If `.agents/skill-overrides.md` exists in the project root and contains a `## bill-go-code-review` section, read that
section and apply it as the highest-priority instruction for this skill. The matching section may refine or replace
parts of the default workflow below.

If an `AGENTS.md` file exists in the project root, apply it as project-wide guidance.

Precedence for this skill: matching `.agents/skill-overrides.md` section > `AGENTS.md` > built-in defaults. Pass
relevant project-wide guidance and matching per-skill overrides to all spawned sub-agents.

## Setup

Determine the review scope:

- Specific files (list paths)
- Git commits (hashes/range)
- Working changes (`git diff`)
- Entire PR

Inspect the changed files and repo markers before applying review heuristics.

Before applying review heuristics, read `orchestration/stack-routing/PLAYBOOK.md` and use it as the source of truth
for Go stack signals and mixed-stack routing expectations. This skill owns the Go review depth that applies after Go
is already in scope.

---

## Review Scope

Inspect the changed files and changed areas.

Use the routing table below to decide which additional specialist skills to run for each meaningful changed area.

### Routing Table

| Signal in the diff                                                                                                                                       | Agent to spawn                              |
|----------------------------------------------------------------------------------------------------------------------------------------------------------|---------------------------------------------|
| Package boundaries, `cmd/`, `internal/`, shared libraries, interface placement, package-level `init()` side effects, dependency direction, adapters/ports, event ownership, orchestration | `bill-go-code-review-architecture`          |
| Goroutines, channel ownership/close behavior, `sync`/`atomic` usage, context propagation, cancellation, loop-variable capture, nil/zero-value handling, wrapped error flow, time logic | `bill-go-code-review-platform-correctness`  |
| `net/http`, chi/gin/echo/fiber handlers, gRPC/protobuf, JSON/YAML/XML struct tags, field presence/`omitempty`, request/response DTOs, validation, status codes, public contracts       | `bill-go-code-review-api-contracts`         |
| `database/sql`, `sqlx`, `sqlc`, GORM, Ent, repositories, transactions, migrations, locking, bulk writes, schema evolution, row/statement lifecycle                                         | `bill-go-code-review-persistence`           |
| HTTP/gRPC clients, queues/workers, retries, deadlines, shutdown logic, queue overflow/backpressure, readiness/health, caches, rate limiting, metrics/logging/tracing, background work    | `bill-go-code-review-reliability`           |
| Auth/authz, middleware/interceptor chains, secrets, TLS/transport security, `os/exec`, file/path handling, SQL construction, template output, SSRF, token/session or cookie handling      | `bill-go-code-review-security`              |
| Test files changed, race-sensitive tests, `t.Run`/`t.Parallel`, fuzz tests, flaky time/concurrency tests, weak assertions, missing regression proof                                          | `bill-go-code-review-testing`               |
| Changed tests look suspiciously weak, tautological, or coverage-padding                                                                                  | `bill-unit-test-value-check`                |
| Hot paths, repeated marshaling, per-request client creation, N+1 or repeated downstream calls, allocation churn, copy-heavy buffer use, unbounded buffers, goroutine storms                 | `bill-go-code-review-performance`           |

## Dynamic Agent Selection

### Step 1: Always include the baseline

Always include:

- `bill-go-code-review-architecture`
- `bill-go-code-review-platform-correctness`

### Step 2: Analyze the diff and select additional agents

Inspect each changed file or tightly related change cluster separately and add the agents from the routing table that
match its signals.

Treat Go-specific runtime semantics as strong routing hints, not tie-breakers. If a change touches goroutine lifetime,
loop-variable capture, wrapped error checks, struct-tag presence semantics, or `database/sql` row/transaction cleanup,
add the matching specialist even when the diff looks small.

### Step 3: Mixed diffs

If different parts of the diff touch different review surfaces:

- inspect those changed areas separately
- keep the baseline specialists for the whole review
- add the specialists needed for the relevant areas
- do not force every file through every specialist

### Step 4: Apply minimum

- Minimum 2 agents (architecture + platform-correctness)
- If tests changed materially, include `bill-go-code-review-testing`
- Maximum 7 agents

### Step 5: Launch in parallel

Spawn all selected sub-agents simultaneously when the agent/runtime supports sub-agents or parallel review passes.

Each sub-agent gets:

- The list of changed files
- Instructions to read its own skill file for the review rubric
- Relevant project-wide guidance and matching per-skill overrides
- The shared contract below

---

## Shared Contract For Every Specialist

- Scope: review only the changes in the current PR/unit of work — do not flag pre-existing issues in unchanged code
- Review only meaningful issues (bug, logic flaw, security risk, regression risk, architectural breakage)
- Flag newly introduced deprecated components, APIs, or patterns when a supported alternative exists, or when deprecated
  usage is broad in scope and not explicitly justified
- Ignore style, formatting, naming bikeshedding, and pure refactor preferences
- Evidence is mandatory: include `file:line` + short description
- Severity: `Blocker | Major | Minor`
- Confidence: `High | Medium | Low`
- Maximum 7 findings per specialist
- Include a minimal, concrete fix for each finding

### Required Finding Schema

```text
[SEVERITY] Area: Issue title
  Location: file:line
  Impact: Why it matters (1 sentence)
  Fix: Concrete fix (1-2 lines)
  Confidence: High/Medium/Low
```

---

## Orchestrator Merge Rules

1. Collect all specialist findings.
2. If a specialist agent fails or returns no output, note it in the summary and continue with available results.
3. Deduplicate by root cause (same evidence or same failing behavior).
4. Keep highest severity/confidence when duplicates conflict.
5. Prioritize: Blocker > Major > Minor, then blast radius.
6. Produce one consolidated report.

---

## Review Output Format

### 1. Agent Summary

```text
Detected review scope: <working tree / commit range / PR diff / files>
Signals: goroutines, context propagation, database/sql, changed tests
Agents spawned: bill-go-code-review-architecture, bill-go-code-review-platform-correctness, bill-go-code-review-persistence, bill-go-code-review-testing
Reason: concurrency-sensitive state handling changed, persistence code changed, and tests changed materially
```

### 2. Risk Register

Format each issue as:

```text
[IMPACT_LEVEL] Area: Issue title
  Location: file:line
  Impact: Description
  Fix: Concrete action
  Confidence: High/Medium/Low
```

Impact levels: BLOCKER | MAJOR | MINOR

### 3. Action Items (Max 10, prioritized)

```text
1. [P0 BLOCKER] Fix issue (Effort: S, Impact: High)
2. [P1 MAJOR] Fix issue (Effort: M, Impact: Medium)
3. [P2 MINOR] Fix issue (Effort: S, Impact: Low)
```

Priority: P0 (blocker) | P1 (critical) | P2 (important) | P3 (nice-to-have)
Effort: S (<1h) | M (1-4h) | L (>4h)

### 4. Verdict

`Ship` | `Ship with fixes [list P0/P1 items]` | `Block until [list blockers]`

---

## Implementation Mode

If invoked standalone, ask: **"Which item would you like me to fix?"**

If invoked from `bill-feature-implement` or another orchestration skill, do not pause for user selection. Return
prioritized findings so the caller can auto-fix `P0` and `P1` items and decide whether to carry `Minor` items forward.

After all `P0` and `P1` items are resolved, run `bill-go-quality-check` as final verification when this review is being
run standalone.

---

## Review Principles

- Changed code only: review what was added or modified in this PR — do not report issues in untouched code, even if it
  violates current rules
- Evidence-based: cite `file:line`
- Project-aware: each agent applies local `AGENTS.md` and required local docs
- Actionable: every issue must have a concrete fix
- Proportional: don't nitpick style if architecture, correctness, or security is broken
- No overoptimization: do not report negligible performance findings with no measurable user-facing or production-facing
  impact
- Honest: if unsure, say what context is missing
