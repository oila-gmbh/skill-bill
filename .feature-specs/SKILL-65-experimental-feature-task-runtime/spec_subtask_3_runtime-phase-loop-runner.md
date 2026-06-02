---
status: Draft
---

# SKILL-65 Subtask 3 - Runtime Phase-Loop Runner

Parent spec: [.feature-specs/SKILL-65-experimental-feature-task-runtime/spec.md](./spec.md)
Issue key: SKILL-65

## Scope

Implement the runtime phase-loop runner in `runtime-application`, analogous to
`GoalRunner` but iterating the phase definition's ordered `stepIds`. For each
phase the runner: assembles the three-layer handoff briefing, launches one agent
via the existing `GoalRunnerSubtaskLauncher` / `AgentRunService`, waits
synchronously, captures and schema-validates the output, persists the result
plus runtime-owned timestamps and a ledger entry, and only then advances. The
runner reuses the goal-runner machine rather than introducing a second
orchestration loop or a new process adapter.

## Acceptance Criteria

1. A new `FeatureTaskRuntimeRunner` (`runtime-application`) drives the phase loop
   deterministically over the definition's ordered `stepIds`, reusing
   `AgentRunService` / the existing launcher port and `WorkflowEngine`
   (`openRecord`/`updateRecord`/`continueDecision`) for state, briefings, and
   validation. No new process/CLI adapter is created.
2. For each phase, the runner assembles the briefing from the definition's
   static declarations: run-invariants + latest-iteration upstream outputs +
   declared derived context. The launched agent does not select its own inputs.
3. Progression is schema-gated: if a phase's output fails its per-phase schema,
   the phase is not marked complete and the run blocks (or retries per policy)
   loudly and observably. The runner never advances on invalid output.
4. The runtime stamps real `started_at`/`finished_at`, duration, resolved agent
   id, status, and attempt count per phase, emits observability events, and
   appends to the attempt ledger from Subtask 2.
5. Bounded fix loops are implemented (review -> fix -> review, audit -> fix) with
   latest-iteration artifact semantics and a documented iteration cap consistent
   with the existing max-3 convention.
6. Per-phase agent assignment is supported via a static per-phase agent map plus
   override, resolved through the existing order (override -> per-phase ->
   invoked -> `SKILL_BILL_AGENT` -> documented default), defaulting to the
   invoking agent (honoring the SKILL-64 fix; no hardcoded `codex` fallback).
7. Resume restarts from the last incomplete phase using persisted state and
   restores upstream outputs; a missing required upstream output blocks loudly
   rather than launching a phase blind.
8. Run-invariants (spec, acceptance criteria, mandates/overrides) are injected by
   the runner into every phase unconditionally.
9. Architecture boundaries hold: no Clikt/MCP/infra imports in
   `runtime-application`; public types live in `model` packages;
   `RuntimeArchitectureTest` passes.
10. Application unit tests with a fake launcher prove: deterministic phase
    ordering, handoff briefing contents (including unconditional run-invariants),
    schema-gate rejection of invalid output, bounded fix-loop iteration,
    per-phase agent resolution precedence, resume-from-phase with upstream
    restoration, missing-upstream loud-fail, and observability/ledger writes.

## Non-Goals

- No CLI/MCP surface or skill (Subtask 4).
- No dual-agent cross-review merge.
- Do not modify `bill-feature-task`, `GoalRunner` behavior, or the
  `IMPLEMENT`/`VERIFY` families.
- Do not introduce a second orchestration loop divergent from the goal runner.

## Dependency Notes

Depends on: Subtask 1 (definition + handoff contract + output schemas) and
Subtask 2 (persistence + ledger).

Consumed by the entrypoint surface in Subtask 4.

## Validation Strategy

Application unit tests driven by a fake `AgentRunLauncher`/launcher port (no real
agent processes), asserting ordering, handoff assembly, schema gating, fix-loop
bounds, agent resolution, resume, and persistence/ledger side effects;
`RuntimeArchitectureTest` for boundaries.

## Next Path

Run bill-feature-task on spec_subtask_4_cli-mcp-surface-and-experimental-skill.md.

## Spec Path

.feature-specs/SKILL-65-experimental-feature-task-runtime/spec_subtask_3_runtime-phase-loop-runner.md
