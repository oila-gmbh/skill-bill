---
name: bill-kotlin-code-review-persistence
description: Review Kotlin transaction, session, concurrency, migration, tenant, bulk-write, retry, and durable-side-effect persistence failures.
internal-for: bill-code-review
---

# Persistence Review Specialist

## Focus

- Transaction ownership, thread or session context, concurrent writes, migrations, scoping, and durability

## Ignore

- Query style preferences without a correctness, exposure, latency, or data-loss consequence

## Applicability

Use when Exposed, Spring, Hibernate, JDBC, R2DBC, migrations, repositories, or bulk writes are present.

## Project-Specific Rules

### Persistence Review Rules

- For Exposed, reject suspension or dispatcher hops inside blocking `transaction {}` and use `newSuspendedTransaction` only for coroutine-aware Exposed work; do not transfer this rule to other transaction frameworks.
- When Exposed is detected, require the use case to own one `newSuspendedTransaction` for state that must commit atomically; nested repository boundaries can split commits or reorder writes.
- In Spring proxy mode, verify `@Transactional` calls cross the configured proxy: self-invocation bypasses advice, interface proxies expose only proxied interfaces, and final Kotlin classes or methods need the all-open plugin or an equivalent open boundary; missed interception risks partial commit and data loss.
- Reject Hibernate lazy access after the session closes; reading `entity.children` can throw `LazyInitializationException` or hide query failure.
- Require read-only work to avoid mutating managed `@Entity` objects; automatic dirty flush can corrupt data unexpectedly.
- Keep blocking JDBC execution and its thread-bound Spring transaction context under one owner; do not hop to `Dispatchers.IO` from inside an active thread-bound transaction, because the new thread may execute outside that transaction. Verify R2DBC instead preserves its reactive context without introducing blocking calls.
- Require optimistic versions, locks, atomic predicates, or unique constraints for concurrent updates; load-modify-save can race and lose writes.
- Reject queries and bulk mutations missing trusted tenant and soft-delete predicates; incomplete scope creates cross-account exposure.
- Require Flyway or Liquibase migrations to handle existing rows and mixed application versions; unsafe `NOT NULL` changes can fail rollout.
- Verify indexes with `EXPLAIN` for changed high-volume predicates; an unindexed migration can cause latency and resource exhaustion.
- Require bounded chunking and resume markers for bulk updates; one giant transaction risks lock timeout and unrecoverable partial failure.
- Reject event publication before commit; prefer a transactional outbox when delivery must survive crashes, and treat an `afterCommit` callback as non-durable unless explicit retry or recovery persists failed publication.
- Require the detected framework's transaction boundary to preserve concurrent update order: Exposed `transaction` or `newSuspendedTransaction`, Spring proxy-managed transactions, Hibernate sessions, JDBC thread-bound contexts, and R2DBC reactive contexts are not interchangeable, and mixing them risks data loss or invalid state.
- Verify `@Version` failures trigger bounded conflict handling; ignoring optimistic-lock errors can corrupt data under concurrency.
- Reject `SchemaUtils.create` in production startup because uncontrolled DDL can fail the build and damage persisted state.
- Require `flywayValidate` evidence before release; checksum drift can break migration contracts and cause operational failure.
- For Blocker or Major findings, describe the concrete data-loss, consistency, or durability failure scenario.
