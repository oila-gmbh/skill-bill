---
name: bill-php-code-review
description: Use when conducting a thorough PHP PR code review across backend/server projects. Classify changed areas conservatively, select the right specialist agents for the diff, including real test-value review when tests change. Produces a structured review with risk register and prioritized action items.
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
relevant project-wide guidance and matching per-skill overrides to all spawned sub-agents.

## Setup

Determine the review scope:

- Specific files (list paths)
- Git commits (hashes/range)
- Working changes (`git diff`)
- Entire PR

Inspect the changed files and repo markers before applying review heuristics.

Before applying review heuristics, read `orchestration/stack-routing/PLAYBOOK.md` and use it as the source of truth
for PHP stack signals and mixed-stack routing expectations. This skill owns the PHP review depth that applies after PHP
is already in scope.

Before spawning specialists or formatting the final report, read `orchestration/review-orchestrator/PLAYBOOK.md`. Use
it as the source of truth for the shared specialist contract, merge rules, common output sections, shared standalone
behavior, and review principles used by stack-specific review orchestrators.

---

## Review Scope

Inspect the changed files and changed areas.

Use the routing table below to decide which additional specialist skills to run for each meaningful changed area.

### Routing Table

| Signal in the diff                                                                                                                                                                     | Agent to spawn                                |
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

## Dynamic Agent Selection

### Step 1: Always include the baseline

Always include:

- `bill-php-code-review-architecture`
- `bill-php-code-review-platform-correctness`

### Step 2: Analyze the diff and select additional agents

Inspect each changed file or tightly related change cluster separately and add the agents from the routing table that
match its signals.

### Step 3: Mixed diffs

If different parts of the diff touch different review surfaces:

- inspect those changed areas separately
- keep the baseline specialists for the whole review
- add the specialists needed for the relevant areas
- do not force every file through every specialist

### Step 4: Apply minimum

- Minimum 2 agents (architecture + platform-correctness)
- If tests changed materially, include `bill-php-code-review-testing`
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
Signals: transactions, projections, changed tests
Agents spawned: bill-php-code-review-architecture, bill-php-code-review-platform-correctness, bill-php-code-review-persistence, bill-php-code-review-testing
Reason: transaction and projection paths changed, plus tests changed materially
```

For the shared risk register, action items, verdict format, merge rules, and review principles, follow
`orchestration/review-orchestrator/PLAYBOOK.md`.

## Implementation Mode Notes

- If invoked from `bill-feature-implement` or another orchestration skill, do not pause for user selection. Return
  prioritized findings so the caller can auto-fix `P0` and `P1` items and decide whether to carry `Minor` items
  forward.
- After all `P0` and `P1` items are resolved, run `bill-php-quality-check` as final verification when this review is
  being run standalone.
