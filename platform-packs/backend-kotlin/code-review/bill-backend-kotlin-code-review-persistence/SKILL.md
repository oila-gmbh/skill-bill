---
name: bill-backend-kotlin-code-review-persistence
description: Use when reviewing Kotlin backend/server persistence risks including transaction boundaries, query correctness, migration safety, concurrency, and data-consistency behavior. Use when user mentions database review, transaction boundaries, migration safety, ORM mapping, or query correctness in Kotlin backend.
---

# Backend Persistence Review Specialist

Review only backend persistence issues that can corrupt data, break consistency, or create high-risk operational regressions.

## Focus
- Transaction boundaries and atomicity
- Query correctness and tenant/filter scoping
- Lost updates, race-prone write patterns, and idempotent persistence behavior
- Migration/schema compatibility risks
- ORM/SQL mapping mismatches that break reads or writes

## Ignore
- Harmless query-style preferences
- Micro-optimizations with no correctness or production impact

## Applicability

Use this specialist for backend/server persistence code only: repositories, DAOs, SQL, migrations, jOOQ, Exposed, JDBC, Hibernate/JPA, R2DBC, or similar layers.

## Project Overrides

If `.agents/skill-overrides.md` exists in the project root and contains a `## bill-backend-kotlin-code-review-persistence` section, read that section and apply it as the highest-priority instruction for this skill. The matching section may refine or replace parts of the default workflow below.

If an `AGENTS.md` file exists in the project root, apply it as project-wide guidance.

Precedence for this skill: matching `.agents/skill-overrides.md` section > `AGENTS.md` > built-in defaults.

## Project-Specific Rules

- Do not split one business write across multiple implicit transactions unless partial completion is explicitly intended
- Avoid load-modify-save patterns that can lose concurrent updates when atomic SQL or version checks are required
- Repository methods must apply required tenant/account/ownership filters consistently
- Upserts, deduplication, and unique-constraint behavior should match the intended idempotency contract
- Migrations must account for existing data, nullability transitions, indexes, and rollout compatibility
- Connection pools, database sessions, cursors, ResultSets, and Statements must be closed reliably; use `use {}` or the framework equivalent consistently
- Avoid holding connections across async boundaries or long-running operations where pool exhaustion could occur
- Do not hold persistence transactions open while waiting on remote I/O
- Bulk operations should preserve correctness, not just speed; verify partial-failure behavior

## Output Rules
- Report at most 7 findings.
- Include data-loss or consistency consequence for each Major/Blocker.
- Include `file:line` evidence for each finding.
- Severity: `Blocker | Major | Minor`
- Confidence: `High | Medium | Low`
- Include a minimal, concrete fix.

## Output Format

Every finding must use this exact bullet format for downstream tooling:

```text
- [F-001] <Severity> | <Confidence> | <file:line> | <description>
```

Do NOT use markdown tables, numbered lists, or any other format for findings.

## Description
This content file is a platform-pack specialist area review module for
`bill-backend-kotlin-code-review-persistence`. The baseline orchestrator delegates a single specialist area here.
The sections above define the specialist playbook; the sections below satisfy
the shell+content contract v1.0.

## Specialist Scope
Scoped to one approved code-review area. Does not cover other areas.

## Inputs
Review scope, changed files, detected stack signals, active learnings,
`review_session_id`, `review_run_id`, and the `orchestrated` flag.

## Outputs Contract
Findings in the shared Risk Register format
`- [F-###] <Severity> | <Confidence> | <file:line> | <description>`, plus
specialist-specific action items consumed by the baseline orchestrator.

## Execution Mode Reporting
Report `Execution mode: inline` or `Execution mode: delegated` per the
shell's output contract.

## Telemetry Ceremony Hooks
Specialist reviews never call `import_review` or `triage_findings` directly;
the baseline orchestrator owns lifecycle telemetry per
`telemetry-contract.md`.
