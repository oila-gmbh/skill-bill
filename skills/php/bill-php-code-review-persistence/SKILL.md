---
name: bill-php-code-review-persistence
description: Use when reviewing PHP backend/server persistence risks including transaction boundaries, query correctness, migration safety, concurrency, and data-consistency behavior. Use when user mentions database review, Eloquent, query builder, migrations, transactions, or ORM in PHP.
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

Use this specialist for backend/server persistence code only: repositories, ORM models, query builders, SQL, migrations, projections, and similar persistence layers.

## Project Overrides

If `.agents/skill-overrides.md` exists in the project root and contains a `## bill-php-code-review-persistence` section, read that section and apply it as the highest-priority instruction for this skill. The matching section may refine or replace parts of the default workflow below.

If an `AGENTS.md` file exists in the project root, apply it as project-wide guidance.

Precedence for this skill: matching `.agents/skill-overrides.md` section > `AGENTS.md` > built-in defaults.

## Project-Specific Rules

- Do not split one business write across multiple implicit transactions unless partial completion is explicitly intended
- Avoid load-modify-save patterns that can lose concurrent updates when atomic SQL or version checks are required
- Repository methods must apply required tenant/account/ownership filters consistently
- Upserts, deduplication, and unique-constraint behavior should match the intended idempotency contract
- Migrations must account for existing data, nullability transitions, indexes, and rollout compatibility
- Connections, database sessions, cursors, and statements must be closed reliably using the framework or driver equivalent
- Avoid holding connections across async boundaries or long-running operations where pool exhaustion could occur
- Do not hold persistence transactions open across remote I/O, event publishing, queue dispatch, or other work that should happen after commit unless the project explicitly requires it
- Bulk operations should preserve correctness, not just speed; verify partial-failure behavior
- Projection or derived-table updates must be concurrency-safe; avoid read-modify-write patterns when atomic SQL/update operations are required
- Migration rollout must consider backfills, dual-read/dual-write windows, and replay or rebuild paths when contracts or projections change
- ORM convenience methods must not hide missing filters, accidental N+1 query/write patterns, or silent partial updates in persistence-critical paths
- Check database defaults, casts, enum storage, and timestamp behavior for write/read drift against the intended domain and API contract

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
