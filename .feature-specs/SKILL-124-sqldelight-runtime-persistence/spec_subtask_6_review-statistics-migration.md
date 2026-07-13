---
status: Ready
parent_spec: ./spec.md
subtask_id: 6
---

# SKILL-124 · Subtask 6 — Review persistence and statistics migration

## Scope

Migrate review ingestion, feedback, triage, and analytical queries to
SQLDelight, including the module's most complex joins and aggregations.

1. Migrate review-run, finding, feedback-event, and review-finished persistence,
   preserving composite keys, foreign keys, cascades, and idempotency.
2. Migrate triage reads/writes and learning payload integration owned by review
   completion.
3. Migrate health, average, size, child-step, workflow, goal, finding, platform,
   percentile, and utility statistics queries.
4. Prefer named static SQL and generated result projections. Where caller-driven
   grouping/filtering requires dynamic composition, constrain it through typed
   enums or closed allowlists and keep value data bound as parameters.
5. Preserve aggregation nullability, empty-set behavior, percentile definition,
   ordering, grouping labels, and JSON payload decoding.
6. Verify relevant indexes and representative `EXPLAIN QUERY PLAN` output or
   bounded performance characteristics.
7. Delete the replaced JDBC review/statistics helpers and duplicated SQL
   constants.

## Acceptance Criteria (this subtask)

1. `ReviewRepository` and all production review/statistics paths use generated
   queries except narrowly documented dynamic fragments that cannot be modeled
   statically.
2. Review import, findings, feedback, finish state, triage, and learning linkage
   preserve transactional and foreign-key behavior.
3. Every existing statistics endpoint/result retains its grouping, filters,
   counts, averages, percentiles, null/empty behavior, ordering, and bounded
   payload shape.
4. Dynamic grouping/filtering cannot interpolate user-controlled identifiers or
   SQL; unsupported values fail before execution.
5. Representative query plans retain expected indexes and do not introduce N+1
   loading.
6. Corrupt review/telemetry rows still produce the expected typed errors rather
   than silent coercion.
7. Superseded review JDBC mappers, binders, and SQL constants are removed.

## Non-Goals

- Changing code-review scoring, health formulas, severity taxonomy, or feedback
  semantics.
- Adding new analytics dimensions.
- Replacing JSON telemetry payload contracts.
- Moving schema/migration ownership.

## Dependencies

- Subtasks 1 through 5 must be complete.

## Validation Strategy

- Run parity fixtures containing empty, single-run, multi-run, goal, standalone,
  accepted, rejected, duplicate, and malformed review data.
- Compare all statistics output maps/DTOs between legacy fixtures and generated
  queries.
- Add rejection tests for every dynamic grouping/filter input.
- Inspect representative SQLite query plans in deterministic integration tests.
- Run `cd runtime-kotlin && ./gradlew :runtime-infra-sqlite:check :runtime-application:check :runtime-cli:check :runtime-mcp:check`.

## Next Path

Run `bill-feature-task` on
`.feature-specs/SKILL-124-sqldelight-runtime-persistence/spec_subtask_6_review-statistics-migration.md`.
