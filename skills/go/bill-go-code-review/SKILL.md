---
name: bill-go-code-review
description: Use when conducting a thorough Go PR code review across backend/service projects. Classify changed areas conservatively, select the right specialist review passes for the diff, including real test-value review when tests change. Produces a structured review with risk register and prioritized action items.
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
relevant project-wide guidance and matching per-skill overrides to every delegated or inline specialist review pass.

## Setup

Determine the review scope:

- Specific files (list paths)
- Git commits (hashes/range)
- Staged changes (`git diff --cached`; index only)
- Unstaged changes (`git diff`; working tree only)
- Combined working tree (`git diff --cached` + `git diff`) only when the caller explicitly asks for all local changes
- Entire PR

Inspect the changed files and repo markers before applying review heuristics.

Resolve the scope before reviewing. If the caller asks for staged changes, inspect only the staged diff and keep unstaged edits out of findings except for repo markers needed for classification.

## Additional Resources

- For shared stack-routing signals and tie-breakers, see [stack-routing.md](stack-routing.md).
- For shared review-orchestration rules, see [review-orchestrator.md](review-orchestrator.md).
- For agent-specific delegated review execution, see [review-delegation.md](review-delegation.md).

When the caller already passed the detected stack, skip reading [stack-routing.md](stack-routing.md). For standalone invocation, read it before classifying.

Before selecting specialist review passes or formatting the final report, read [review-orchestrator.md](review-orchestrator.md) unless the caller already passed the shared review contract.

Before delegating specialist review passes, read only your current runtime's section in [review-delegation.md](review-delegation.md).

---

## Review Scope

Inspect the changed files and changed areas.

Use the routing table below to decide which additional specialist skills to run for each meaningful changed area.

### Routing Table

| Signal in the diff                                                                                                                                       | Specialist review to run                    |
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

## Dynamic Specialist Selection

### Step 1: Always include the baseline

Always include:

- `bill-go-code-review-architecture`
- `bill-go-code-review-platform-correctness`

### Step 2: Analyze the diff and select additional specialist reviews

Inspect each changed file or tightly related change cluster separately and add the specialist reviews from the routing
table that
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

- Minimum 2 specialist reviews (architecture + platform-correctness)
- If tests changed materially, include `bill-go-code-review-testing`
- Maximum 7 specialist reviews

### Step 5: Choose execution mode

Select `inline` or `delegated` using [review-orchestrator.md](review-orchestrator.md).

- Use `inline` only when the Go review scope stays small and low-risk under the shared execution-mode contract
- Use `delegated` when the diff is large, the risk profile is high, multiple review surfaces are meaningfully involved, or the safest choice is unclear

### Step 6: Run selected specialist reviews

If execution mode is `inline`:

- run the selected specialist review passes sequentially in the current thread
- read each specialist skill file as the primary rubric for that pass
- apply the shared specialist contract in [review-orchestrator.md](review-orchestrator.md)
- keep findings attributed to each specialist before merging and deduplicating them for the final report

If execution mode is `delegated`:

- run one delegated subagent per selected specialist review pass
- pass the list of changed files, applicable active learnings, instructions to read the specialist skill file, relevant project-wide guidance and matching per-skill overrides, the parent thread's model when the runtime supports delegated-worker model inheritance, and the shared specialist contract in [review-orchestrator.md](review-orchestrator.md)
- if delegated review is required for this scope but the current runtime lacks a documented delegation path or cannot start the required subagent(s), stop and report that delegated review is required for this scope but unavailable on the current runtime

---

## Review Output Format

### 1. Specialist Summary

```text
Review session ID: <review-session-id>
Review run ID: <review-run-id>
Detected review scope: <staged changes / unstaged changes / working tree / commit range / PR diff / files>
Signals: goroutines, context propagation, database/sql, changed tests
Execution mode: inline | delegated
Applied learnings: none | <learning references>
Specialist reviews: bill-go-code-review-architecture, bill-go-code-review-platform-correctness, bill-go-code-review-persistence, bill-go-code-review-testing
Reason: concurrency-sensitive state handling changed, persistence code changed, and tests changed materially
```

Every finding in `### 2. Risk Register` must use this exact bullet format (do NOT use markdown tables):

```text
- [F-001] <Severity> | <Confidence> | <file:line> | <description>
```

Severity: `Blocker | Major | Minor`. Confidence: `High | Medium | Low`.

## Auto-Import

After producing the final review output, automatically import it into the local telemetry store so the review run and findings are recorded without manual intervention.

Call the `import_review` MCP tool:
- `review_text`: the complete review output (Section 1 through Section 4)

## Auto-Triage

After the user responds to the review findings and the agent has acted on each decision (applied fixes, skipped findings, etc.), record the triage decisions so the telemetry event fires.

Each finding gets one decision using its position number from the risk register:
- `fix` — the finding was accepted and the fix was applied
- `accept` — the finding was accepted but no code change was needed
- `skip` — the finding was intentionally skipped (append a reason after ` - `)
- `false_positive` — the finding was incorrect

Call the `triage_findings` MCP tool:
- `review_run_id`: the review run ID from the review output
- `decisions`: prefer a single structured selection string that fully resolves the review, e.g. `["fix=[1,3] reject=[2]"]`
- fallback: explicit numbered decisions still work, e.g. `["1 fix", "2 skip - intentional", "3 accept"]`

Skip auto-triage when the review produced no findings.

For action items, verdict format, merge rules, and review principles, follow [review-orchestrator.md](review-orchestrator.md).

## Implementation Mode Notes

- If invoked from `bill-feature-implement` or another orchestration skill, do not pause for user selection. Return
  prioritized findings so the caller can auto-fix `P0` and `P1` items and decide whether to carry `Minor` items
  forward.
- After all `P0` and `P1` items are resolved, run `bill-go-quality-check` as final verification when this review is
  being run standalone.
