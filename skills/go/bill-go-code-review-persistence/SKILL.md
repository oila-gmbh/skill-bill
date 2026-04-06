---
name: bill-go-code-review-persistence
description: Use when reviewing Go backend/service persistence risks including transaction boundaries, query correctness, migration safety, concurrency, and data-consistency behavior. Use when user mentions database review, SQL, transaction, migration, GORM, sqlx, or query correctness in Go.
---

# Backend Persistence Review Specialist

Review only backend persistence issues that can corrupt data, break consistency, or create high-risk operational regressions.

## Focus
- Transaction boundaries and atomicity
- Query correctness and tenant/filter scoping
- Lost updates, race-prone write patterns, and idempotent persistence behavior
- Migration/schema compatibility risks
- Driver/ORM mapping mismatches that break reads or writes

## Ignore
- Harmless query-style preferences
- Micro-optimizations with no correctness or production impact

## Applicability

Use this specialist for backend/service persistence code only: repositories, `database/sql`, sqlx, sqlc, GORM, Ent,
SQL, migrations, projections, and similar persistence layers.

## Project Overrides

If `.agents/skill-overrides.md` exists in the project root and contains a `## bill-go-code-review-persistence`
section, read that section and apply it as the highest-priority instruction for this skill. The matching section may
refine or replace parts of the default workflow below.

If an `AGENTS.md` file exists in the project root, apply it as project-wide guidance.

Precedence for this skill: matching `.agents/skill-overrides.md` section > `AGENTS.md` > built-in defaults.

## Project-Specific Rules

- Do not split one business write across multiple implicit transactions unless partial completion is explicitly intended
- Avoid load-modify-save patterns that can lose concurrent updates when atomic SQL or version checks are required
- Repository methods must apply required tenant/account/ownership filters consistently
- Upserts, deduplication, and unique-constraint behavior should match the intended idempotency contract
- Migrations must account for existing data, nullability transitions, indexes, and rollout compatibility
- Rows, statements, result sets, and transactions must be closed or finalized reliably using the driver or helper equivalent; place cleanup close to acquisition so early returns, loops, or branching do not leak `Rows` or leave transactions unresolved
- `QueryRow`/`Scan` and equivalent helpers must distinguish `ErrNoRows`, partial reads, and decode failures correctly instead of collapsing them into misleading zero-value success
- Queries and persistence calls should use the correct `context.Context` so deadlines, cancellation, and tracing behave as intended; avoid background-context database calls in request or worker paths
- After `BeginTx`, cleanup must make rollback-on-error explicit so failed or panicking paths do not leave transaction state ambiguous
- Do not hold transactions open across remote I/O, event publishing, or queue dispatch unless the project explicitly requires it
- Bulk operations should preserve correctness, not just speed; verify partial-failure behavior
- Projection or derived-table updates must be concurrency-safe; avoid read-modify-write patterns when atomic SQL/update operations are required
- Migration rollout must consider backfills, dual-read/dual-write windows, and replay or rebuild paths when contracts or projections change
- Prepared statements, repository helpers, and generated query wrappers should not be recreated per item or per request when reuse is required for pool health and predictable latency
- ORM or query-helper convenience helpers (`sqlx`, `sqlc`, GORM, Ent, builders) must not hide missing filters, accidental N+1 write patterns, implicit hooks, or silent partial updates in persistence-critical paths
- GORM hooks/preloads, Ent eager-loading or transaction clients, and scanner/binder helpers must keep transaction scope, lock semantics, and write ordering explicit
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
