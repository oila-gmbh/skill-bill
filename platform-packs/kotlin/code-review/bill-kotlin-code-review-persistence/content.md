---
name: bill-kotlin-code-review-persistence
description: Use when reviewing Kotlin backend/server persistence risks including transaction boundaries, query correctness, migration safety, concurrency, and data-consistency behavior. Use when user mentions database review, transaction boundaries, migration safety, ORM mapping, or query correctness in Kotlin backend.
internal-for: bill-code-review
---

# Backend Persistence Review Specialist

Review persistence failures that can corrupt, expose, or lose data.

## Focus

- Transaction ownership, ORM/session behavior, concurrent writes, tenant scope, and durable side-effect ordering

## Ignore

- Query style preferences without a correctness or production consequence

## Applicability

Use this specialist for Exposed, Spring transactions, Hibernate/JPA, JDBC, R2DBC, repositories, migrations, and mass writes.

## Project-Specific Rules

### Transaction and ORM Boundaries

- Reject suspend calls or dispatcher hops inside Exposed `transaction {}` because its transaction context is thread-bound; use one correctly owned `newSuspendedTransaction` for suspending work, and reject nested or misplaced calls that create a second transaction or lose atomicity.
- Reject Spring `@Transactional` self-invocation and transactional final or non-open methods when proxy interception will not occur.
- Reject Hibernate access that can cause `LazyInitializationException`, and reject unintended dirty flushes caused by mutating managed entities during read-oriented work.
- Do not hold a transaction while performing remote I/O, publishing an event, or dispatching queue work; require after-commit handling or an outbox when ordering matters.

### Consistency and Scope

- Require atomic predicates, version checks, locks, or unique constraints for concurrent writes; reject load-modify-save paths that can lose updates.
- Require tenant, account, ownership, and soft-delete predicates on mass updates and deletes with the same scope enforced by ordinary reads and writes.
- Verify migrations against existing data, nullability transitions, indexes, and mixed-version rollout.
- Require bulk operations and retries to define partial-failure and idempotency behavior.
- For Blocker or Major findings, describe the concrete data-loss, consistency, or durability failure scenario.
