# SKILL-57 Durable Progress Contract - Subtask 3: CLI/MCP Surface Wiring and Regression Coverage

Status: Draft
Parent spec: [.feature-specs/SKILL-57-durable-progress-contract/spec.md](./spec.md)
Issue key: SKILL-57
Subtask order: 3 of 3
Depends on: subtasks 1 and 2
Branch model: same-branch, commit per subtask

## Purpose

Finalize external workflow-progress seams and lock behavior with regression tests across domain, application, and adapter boundaries.

## Scope

In scope:

- Wire any required CLI and MCP seam for progress writes/reads while preserving existing map-shape parity and no-progress backward compatibility.
- Include progress summaries/histories in adapter payloads only where useful and contract-safe.
- Add/refresh tests across runtime-domain, runtime-application, and runtime-cli/runtime-mcp seams to cover:
  - malformed progress event rejection
  - bounded history ordering behavior
  - stale/regressive step update rejection
  - intentional loop-back acceptance with persisted reason
  - status/progress reads after terminal `finish`
  - no-progress backward compatibility behavior

Out of scope:

- `bill-feature-implement` prompt changes that emit events.
- Parent goal-runner presentation changes.
- Real-agent interoperability policy.

## Acceptance Criteria

- 7. Tests cover the new contract in `runtime-domain`, `runtime-application`, and any CLI/MCP seam added for progress writes.
- 10. Tests cover stale/regressive updates, intentional loop-back updates, and status/progress reads after terminal `finish`.
- 5. Progress history is bounded; when the limit is exceeded, the newest events are retained and ordering remains stable.
- 6. Existing workflow open/update/get/resume/continue behavior is unchanged when no progress events exist.

## Non-goals

- New orchestration UX decisions beyond this contract.
- Additional runtime features outside durable progress contract hardening.

## Dependencies

- Subtask 1 for durable state primitives.
- Subtask 2 for engine monotonic policy and diagnostics.

## Validation Strategy

- `bill-quality-check`
- Targeted checks during implementation:
  - `(cd runtime-kotlin && ./gradlew :runtime-domain:test :runtime-application:test :runtime-cli:test :runtime-mcp:test)`

## Recommended Next Prompt

Run `bill-feature-implement` on `.feature-specs/SKILL-57-durable-progress-contract/spec_subtask_3_cli-mcp-and-regression-tests.md`.
