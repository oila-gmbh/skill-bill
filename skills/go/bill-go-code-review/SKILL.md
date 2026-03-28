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

Before spawning specialists or formatting the final report, read `orchestration/review-orchestrator/PLAYBOOK.md`. Use
it as the source of truth for the shared specialist contract, merge rules, common output sections, shared standalone
behavior, and review principles used by stack-specific review orchestrators.

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
- The shared specialist contract in `orchestration/review-orchestrator/PLAYBOOK.md`

---

## Review Output Format

### 1. Agent Summary

```text
Detected review scope: <working tree / commit range / PR diff / files>
Signals: goroutines, context propagation, database/sql, changed tests
Agents spawned: bill-go-code-review-architecture, bill-go-code-review-platform-correctness, bill-go-code-review-persistence, bill-go-code-review-testing
Reason: concurrency-sensitive state handling changed, persistence code changed, and tests changed materially
```

For the shared risk register, action items, verdict format, merge rules, and review principles, follow
`orchestration/review-orchestrator/PLAYBOOK.md`.

## Implementation Mode Notes

- If invoked from `bill-feature-implement` or another orchestration skill, do not pause for user selection. Return
  prioritized findings so the caller can auto-fix `P0` and `P1` items and decide whether to carry `Minor` items
  forward.
- After all `P0` and `P1` items are resolved, run `bill-go-quality-check` as final verification when this review is
  being run standalone.
