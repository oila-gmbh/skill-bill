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

- Reject suspend calls and dispatcher hops inside Exposed `transaction {}`; thread-bound context can be lost and corrupt atomic behavior.
- Require one owned `newSuspendedTransaction` around the use case; nested repository transactions risk partial commit and incorrect ordering.
- Verify Spring `@Transactional` calls cross a proxy; self-invocation or final methods can skip interception and cause data loss.
- Reject Hibernate lazy access after the session closes; reading `entity.children` can throw `LazyInitializationException` or hide query failure.
- Require read-only work to avoid mutating managed `@Entity` objects; automatic dirty flush can corrupt data unexpectedly.
- Verify blocking JDBC stays on `Dispatchers.IO` while R2DBC remains nonblocking; wrong dispatcher ownership risks starvation and timeout.
- Require optimistic versions, locks, atomic predicates, or unique constraints for concurrent updates; load-modify-save can race and lose writes.
- Reject queries and bulk mutations missing trusted tenant and soft-delete predicates; incomplete scope creates cross-account exposure.
- Require Flyway or Liquibase migrations to handle existing rows and mixed application versions; unsafe `NOT NULL` changes can fail rollout.
- Verify indexes with `EXPLAIN` for changed high-volume predicates; an unindexed migration can cause latency and resource exhaustion.
- Require bounded chunking and resume markers for bulk updates; one giant transaction risks lock timeout and unrecoverable partial failure.
- Reject event publication before commit; require an outbox or after-commit hook because rollback would otherwise expose invalid state.
- Require `transaction {}` ownership to preserve concurrent update order; reject a split boundary because it risks data loss and invalid state.
- Verify `@Version` failures trigger bounded conflict handling; ignoring optimistic-lock errors can corrupt data under concurrency.
- Reject `SchemaUtils.create` in production startup because uncontrolled DDL can fail the build and damage persisted state.
- Require `flywayValidate` evidence before release; checksum drift can break migration contracts and cause operational failure.
- For Blocker or Major findings, describe the concrete data-loss, consistency, or durability failure scenario.
