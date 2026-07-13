---
status: Ready
parent_spec: ./spec.md
subtask_id: 4
---

# SKILL-124 · Subtask 4 — Work-list and learning persistence migration

## Scope

Migrate work-list and learning repository persistence to SQLDelight.

1. Migrate the unified work-list query across workflow families, including
   family discrimination, optional issue keys, state metadata, terminal state,
   ordering, sanitization boundaries, and limit behavior.
2. Migrate learning CRUD/query operations, review-linked learning provenance,
   scope/status filters, and created/updated timestamps.
3. Migrate session-learning JSON persistence and decoding.
4. Use generated projections tailored to each port result rather than exposing
   broad table records.
5. Centralize scope, status, boolean, timestamp, and JSON conversion in explicit
   infrastructure mappers with typed rejection of invalid persisted values.
6. Remove replaced result-set mappings and positional bind helpers.

## Acceptance Criteria (this subtask)

1. `WorkListRepository` and `LearningRepository` ordinary operations use
   generated SQLDelight queries and projections.
2. Work-list rows preserve cross-family ordering, stable tie-breaking, limit
   semantics, optional fields, terminal metadata, and terminal-safe rendering
   boundaries.
3. Learning scope/status/provenance filtering, insert/update behavior, and
   session-learning JSON round trips remain behaviorally equivalent.
4. Invalid persisted work-list or learning wire values still fail with bounded,
   typed errors containing the relevant record identity.
5. No N+1 query is introduced when composing the unified work list or learning
   views.
6. Superseded work-list and learning JDBC mappings are deleted.

## Non-Goals

- Changing work-list UX, columns, or sort policy.
- Redesigning learning extraction or review-learning product behavior.
- Migrating review/statistics or telemetry repositories.
- Schema/migration ownership transfer.

## Dependencies

- Subtask 1 must be complete.
- Subtask 2 must be complete.
- Subtask 3 must be complete so work-list workflow projections use the settled
  workflow schema/query conventions.

## Validation Strategy

- Run parity tests with mixed workflow families and deterministic timestamps.
- Test corrupt optional/required values, JSON, enum/status, and limit edges.
- Assert bounded query counts for unified work-list loading.
- Run `cd runtime-kotlin && ./gradlew :runtime-infra-sqlite:check :runtime-cli:check`.

## Next Path

Run `bill-feature-task` on
`.feature-specs/SKILL-124-sqldelight-runtime-persistence/spec_subtask_4_worklist-learning-migration.md`.
