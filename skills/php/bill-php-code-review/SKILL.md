---
name: bill-php-code-review
description: Use when conducting a thorough PHP PR code review across backend/server projects. Classify changed areas conservatively, select the right specialist review passes for the diff, including real test-value review when tests change. Produces a structured review with risk register and prioritized action items. Use when user mentions PHP review, review PHP PR, PHP code review, or asks to review .php files.
---

# Adaptive PHP PR Review

You are an experienced software architect conducting a code review.

This is the current PHP review implementation behind the shared `bill-code-review` router.

Your first job is to inspect the diff safely so the right review depth is applied.

## Project Overrides

If `.agents/skill-overrides.md` exists in the project root and contains a `## bill-php-code-review` section, read that
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

## Dynamic Specialist Selection

### Routing Table

| Signal in the diff                                                                                                                                                                     | Specialist review to run                      |
|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-----------------------------------------------|
| Layering changes, module ownership, ports/adapters, read gateways, outbox, listeners, projectors, boundary-crossing composition                                                        | `bill-php-code-review-architecture`           |
| Conditional logic, state transitions, retry-sensitive logic, time/date logic, nullability, behavior drift in refactors                                                                 | `bill-php-code-review-platform-correctness`   |
| Routes/controllers/actions, requests, resources, serializers, status codes, OpenAPI/schema changes, validation/error payloads, server-rendered payload contracts                       | `bill-php-code-review-api-contracts`          |
| Repositories, ORM models, SQL, query builders, migrations, locking, transactions, projections, bulk writes                                                                             | `bill-php-code-review-persistence`            |
| Jobs, consumers, schedulers, retries, queues, caches, external clients, fallback behavior, logging/metrics/tracing                                                                     | `bill-php-code-review-reliability`            |
| Auth/authz, trust-boundary code, secrets, uploads, signed URLs, template rendering, JS or DOM injection risks, deserialization, sensitive logs, workflow or script credential handling | `bill-php-code-review-security`               |
| Test files changed, contract tests, deterministic retry/idempotency tests, weak/tautological tests, missing regression proof                                                           | `bill-php-code-review-testing`                |
| Changed tests look suspiciously weak, tautological, or coverage-padding                                                                                                                | `bill-unit-test-value-check`                  |
| Hot paths, N+1, repeated downstream calls, serialization waste, feed/backfill loops, rendering waste, unbounded buffers or batch work                                                  | `bill-php-code-review-performance`            |

### Step 1: Always include the baseline

Always include:

- `bill-php-code-review-architecture`
- `bill-php-code-review-platform-correctness`

### Step 2: Analyze the diff and select additional specialist reviews

Inspect each changed file or tightly related change cluster separately and add the agents from the routing table that
match its signals.

### Step 3: Mixed diffs

If different parts of the diff touch different review surfaces:

- inspect those changed areas separately
- keep the baseline specialists for the whole review
- add the specialists needed for the relevant areas
- do not force every file through every specialist

### Step 4: Apply minimum

- Minimum 2 specialist reviews (architecture + platform-correctness)
- If tests changed materially, include `bill-php-code-review-testing`
- Maximum 7 specialist reviews

### Step 5: Choose execution mode

Select `inline` or `delegated` using [review-orchestrator.md](review-orchestrator.md).

- Use `inline` only when the PHP review scope stays small and low-risk under the shared execution-mode contract
- Use `delegated` when the diff is large, the risk profile is high, multiple review surfaces are meaningfully involved, or the safest choice is unclear

### Step 5.5: Scope diff per specialist (delegated mode only)

When execution mode is `delegated`, build a per-specialist file list before launching subagents:

1. Scan each changed file's name and imports for the routing-table signals from the Routing Table
2. Map each file to the specialists whose signals it matches
3. `bill-php-code-review-architecture` always receives all changed files
4. Every other specialist receives only files matching its routing-table signals
5. If a non-architecture specialist's scoped file list is empty, drop it from the selected set
6. After scoping, re-check the minimum-2-specialist requirement; if only architecture remains, add `bill-php-code-review-platform-correctness` with all changed files as the default second

This is a lightweight file-level classification (names + imports), not a full review.

### Step 6: Run selected specialist reviews

If execution mode is `inline`:

- run the selected specialist review passes sequentially in the current thread
- read each specialist skill file as the primary rubric for that pass
- apply the shared specialist contract in [review-orchestrator.md](review-orchestrator.md)
- keep findings attributed to each specialist before merging and deduplicating them for the final report

If execution mode is `delegated`:

- run one delegated subagent per selected specialist review pass
- pass the specialist-scoped file list (from Step 5.5), applicable active learnings, instructions to read the specialist skill file, relevant project-wide guidance and matching per-skill overrides, the parent thread's model when the runtime supports delegated-worker model inheritance, and the shared specialist contract in [specialist-contract.md](specialist-contract.md)
- if delegated review is required for this scope but the current runtime lacks a documented delegation path or cannot start the required subagent(s), stop and report that delegated review is required for this scope but unavailable on the current runtime

---

## Review Output

### 1. Summary

```text
Review session ID: <review-session-id>
Review run ID: <review-run-id>
Detected review scope: <staged changes / unstaged changes / working tree / commit range / PR diff / files>
Detected stack: <stack>
Signals: <markers>
Execution mode: inline | delegated
Applied learnings: none | <learning references>
Specialist reviews: <selected specialists>
Reason: <why these specialists were selected>
```

Every finding in `### 2. Risk Register` must use this exact bullet format (do NOT use markdown tables):

```text
- [F-001] <Severity> | <Confidence> | <file:line> | <description>
```

Severity: `Blocker | Major | Minor`. Confidence: `High | Medium | Low`.

### Auto-Import

After producing the final review output, automatically import it into the local telemetry store so the review run and findings are recorded without manual intervention.

Call the `import_review` MCP tool:
- `review_text`: the complete review output (Section 1 through Section 4)

### Auto-Triage

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

### Implementation Mode Notes

- If invoked from `bill-feature-implement`, `bill-feature-verify`, or another orchestration skill, do not pause for user selection. Return prioritized findings so the caller can auto-fix P0/P1 items and decide whether to carry Minor items forward.
- After all P0 and P1 items are resolved, run `bill-quality-check` as final verification when the project uses a routed quality-check path and this review is being run standalone.
