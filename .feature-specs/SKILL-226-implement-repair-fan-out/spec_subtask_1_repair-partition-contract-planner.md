# SKILL-226 · Subtask 1 — Repair partition contract and planner

## Scope

Land the governed contract and planner that turn an audit gap / finding
inventory into a file-disjoint implement repair partition plan (or a proven
single-partition fallback).

Deliver:

- New Draft 2020-12 schema under `orchestration/contracts/` for the repair
  partition plan (contract version, partition id, gap/finding refs, exclusive
  writable paths, fallback reason when collapsed to one partition).
- Kotlin `*_CONTRACT_VERSION`, typed parse / `Invalid*SchemaError`, parity test,
  and classpath `Copy` with existence guard per runtime contract rules.
- Planner (pure function or small application service) that:
  - accepts structured gap/finding items with cited paths,
  - greedily or deterministically groups into file-disjoint partitions,
  - collapses to one partition when any required path overlap remains or when
    path sets are unknown / unparseable from prose-only notes,
  - never emits two partitions that share a writable path.
- Unit coverage for overlap → single partition, disjoint → N partitions, empty
  inventory → single empty-or-no-op partition identity as defined by the schema.

This subtask does **not** launch workers yet; it only defines and validates the
plan artifact the next subtask will execute.

## Acceptance Criteria

1. `orchestration/contracts/` contains a versioned repair partition plan schema
   with partition id, assigned refs, exclusive writable path set per partition,
   and an explicit single-partition fallback marker/reason.
2. Kotlin contract version, loud-fail parse, parity test, and classpath schema
   copy match existing runtime-contract patterns.
3. Planner input can be built from structured finding/gap records that carry
   paths; when only prose notes exist without extractable paths, the planner
   emits a single-partition plan rather than guessing concurrency.
4. Any candidate multi-partition plan with overlapping writable paths is either
   rejected by validation or deterministically collapsed to one partition —
   concurrent overlap is impossible to persist as an accepted plan.
5. Focused unit tests cover disjoint, overlapping, empty, and prose-only
   (unknown paths) cases.

## Non-Goals

- Launching implement workers or changing phase settlement.
- Changing audit prompt wording beyond what is required to optionally emit
  structured path-bearing gap items (prefer consuming existing structured
  fields if present).
- Parallel validate gate runners.

## Dependency Notes

- None. First subtask; base branch is `main`.

## Validation Strategy

- Schema parity and parse loud-fail tests.
- Planner unit tests for the acceptance cases above.
- Targeted module tests; no full goal run required for this subtask alone.

## Next Path

Subtask 2 consumes the plan for runtime fan-out.
