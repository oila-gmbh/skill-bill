---
status: Ready
parent_spec: ./spec.md
subtask_id: 3
---

# SKILL-124 · Subtask 3 — Workflow-state persistence migration

## Scope

Migrate the complete workflow-state repository family to named SQLDelight
queries and generated projections.

1. Migrate feature-task workflow insert/upsert/get/list/latest/batch queries for
   both prose and runtime modes.
2. Migrate feature-verify workflow operations and feature-implement/verify
   session-summary projections.
3. Migrate immutable feature-task execution identity persistence and
   repository-scoped continuation candidate lookup introduced by SKILL-120.
4. Replace positional parameter helpers and `ResultSet` mappers with generated
   bindings plus explicit infrastructure mapper functions.
5. Preserve semantic validation for workflow name, mode, contract version,
   execution identity, JSON artifacts, booleans, and missing/corrupt rows.
6. Preserve batch lookup semantics, deterministic ordering, normalized limits,
   ambiguity behavior, and `ON CONFLICT` invariants.
7. Delete the superseded workflow JDBC query/mapping helpers in the same phase.

## Acceptance Criteria (this subtask)

1. `WorkflowStateRepository` and all four capability interfaces are backed by
   generated queries without ordinary `PreparedStatement` or `ResultSet`
   mapping.
2. Feature-task prose/runtime mode pinning, feature-verify behavior, immutable
   identity, continuation lookup, list ordering, limits, and batch results match
   the pre-migration repository contract.
3. Unknown modes, wrong workflow names, invalid contract versions, missing
   identity, malformed JSON, and other durable corruption still loud-fail with
   the expected typed error rather than becoming generated-code exceptions.
4. Writes remain idempotent where currently required and preserve all stored
   text/JSON/timestamp values byte-for-byte unless normalization is an existing
   contract.
5. Existing SKILL-120 continuation tests and CLI/MCP workflow tests pass without
   opening replacement workflows or changing durable artifacts.
6. The duplicated-query/manual-mapping class of error is compile-time checked by
   the authored SQLDelight queries.
7. Superseded workflow JDBC mapping and binding code is removed.

## Non-Goals

- Changing workflow domain models or wire contracts.
- Normalizing artifact JSON into relational tables.
- Migrating work-list, telemetry, review, or learning repositories.
- Transferring schema migration ownership.

## Dependencies

- Subtask 1 must be complete.
- Subtask 2 must be complete.

## Validation Strategy

- Run legacy-versus-generated workflow repository contract tests.
- Run corrupt-row and contract-version rejection tests.
- Run SKILL-120 repository-scoped continuation and ambiguity coverage.
- Run `cd runtime-kotlin && ./gradlew :runtime-infra-sqlite:check :runtime-application:check :runtime-cli:check :runtime-mcp:check`.

## Next Path

Run `bill-feature-task` on
`.feature-specs/SKILL-124-sqldelight-runtime-persistence/spec_subtask_3_workflow-state-migration.md`.
