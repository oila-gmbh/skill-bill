---
name: bill-code-review-backend-persistence
description: Use when reviewing Kotlin backend/server persistence risks including transaction boundaries, query correctness, migration safety, concurrency, and data-consistency behavior.
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

If an `AGENTS.md` file exists in the project root, read it and apply its rules alongside the defaults below. Project rules take precedence when they conflict.

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

## Output Table
| Area | Severity | Confidence | Evidence | Why it matters | Minimal fix |
|------|----------|------------|----------|----------------|-------------|
