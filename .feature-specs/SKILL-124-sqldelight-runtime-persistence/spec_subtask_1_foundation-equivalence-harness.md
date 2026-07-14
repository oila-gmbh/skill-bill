---
status: Ready
parent_spec: ./spec.md
subtask_id: 1
---

# SKILL-124 · Subtask 1 — SQLDelight foundation and equivalence harness

## Scope

Introduce SQLDelight to `runtime-infra-sqlite` without changing production
repository selection or database opening.

1. Select and pin the current stable SQLDelight Gradle plugin/runtime/JDBC
   artifacts compatible with the repository's Kotlin and Gradle versions.
2. Configure generated source and schema output directories using
   configuration-cache-friendly Gradle inputs and outputs. Generated Kotlin and
   temporary schema databases must remain ignored build output.
3. Establish `.sq`/`.sqm` naming, package, query grouping, adapter, and generated
   type conventions for this module.
4. Represent the current latest SQLite schema sufficiently for SQLDelight to
   compile the first typed workflow queries. Do not transfer production
   migration authority yet.
5. Add a small representative query slice covering a nullable projection, an
   enum/wire value, an integer-backed boolean, an upsert, a list query with
   ordering/limit, and a transaction.
6. Build reusable test support for normalized schema comparison, historical
   database fixtures, repository behavior contracts, and generated/domain
   mapping failures.
7. Document the proposed connection and transaction integration seam for
   subtask 2, including how migrated and temporary JDBC repositories will share
   one physical transaction. Validate the approach with an executable test or
   prototype rather than documentation alone.

## Acceptance Criteria (this subtask)

1. Normal Gradle compilation invokes SQLDelight and invalid representative SQL
   fails the build.
2. The checked-in sources contain authored `.sq`/`.sqm` only; generated Kotlin
   and generated database artifacts are not committed.
3. Representative generated queries prove typed parameters, nullability,
   projection mapping, ordering, limit, upsert, boolean, and wire-value support.
4. No production repository or database-open path uses the generated database
   yet, so runtime behavior is unchanged.
5. A reusable normalized-schema assertion and repository contract harness is
   available to all later subtasks.
6. An executable transaction-bridge prototype proves that a generated write and
   a JDBC write can participate in one commit and one rollback on the same
   physical SQLite transaction, or the subtask blocks before any cutover with a
   documented alternative that still satisfies the parent atomicity contract.
7. SQLDelight configuration works under the repository's full `./gradlew check`
   invocation and configuration cache expectations.

## Non-Goals

- Production repository cutover.
- Changing `DatabaseSessionFactory` or `UnitOfWork` caller contracts.
- Retiring `DatabaseSchema`, `DatabaseMigrations`, or
  `DatabaseColumnMigrations`.
- Modeling every statistics query before its owning phase.

## Dependencies

None.

## Validation Strategy

- Run `cd runtime-kotlin && ./gradlew :runtime-infra-sqlite:check`.
- Add build/compile fixtures demonstrating rejection of an invalid column and
  invalid projection where practical.
- Run representative generated-query integration tests against a file-backed
  temporary SQLite database.
- Verify `git status` contains no generated SQLDelight output.

## Next Path

Run `bill-feature-task` on
`.feature-specs/SKILL-124-sqldelight-runtime-persistence/spec_subtask_1_foundation-equivalence-harness.md`.
