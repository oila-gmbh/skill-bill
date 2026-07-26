# SKILL-87 — Goal-runner stale-child false-kill fix

Status: Complete

Layer 2 pre-assigns the runtime workflow id (`generateWorkflowId("wftr")`) and persists it onto the
manifest subtask before `subtaskLauncher.launch`, threading it to the child through an
open-with-this-id path (`feature-task run --workflow-id`) rather than resume, so liveness heartbeats
fire from the first supervisor tick. Layer 1 adds a `GoalRunnerReconcileGate(allowInactiveReconciliation,
requireStalenessEvidence)` policy to `reconcileAuthoritativeOutcomes` (the two reconciliation booleans
were bundled into one typed value so the signature stays under the detekt parameter limit, no
suppression): `finalizeGoal` now demands positive evidence a subtask is gone (no
declared liveness within the staleness window, a terminal status, or a terminal outcome) before
stale-blocking, so an empty active set can no longer false-kill a live subtask. `reset()` keeps the
aggressive set-membership semantics (default `requireStalenessEvidence = false`).

## Summary

The goal runner false-kills healthy `mode:runtime` subtasks during a long, quiet first phase
(e.g. a multi-minute preplan that emits no intermediate durable progress). The subtask is blocked
with "Goal status reconciliation closed stale running child … because it was no longer active,"
even though the child is alive and making progress. This spec makes liveness heartbeats fire for a
subtask's first run and makes the reconciler refuse to stale-block a running subtask without
positive evidence it is gone.

## Context

Observed on goal CTX-11 subtask 1 (`mode:runtime`, never resumed as prose), workflow
`wftr-20260620-210408-e40t`:

- preplan ran 21:04:08 → 21:09:14 (305,573 ms) and **completed** with a full digest; plan started
  21:09:14.27.
- 21:09:14.50: reconciliation blocked the subtask as a stale running child — but the child had
  authored `preplan=completed` + `plan=started` 230 ms earlier, so it was provably alive. False
  positive.
- The child workflow recorded **zero** `goal_progress` events (no `operation_started` /
  `operation_heartbeat`) for the entire preplan. PS-24 subtasks 1 & 2 carried the identical
  message — same class of failure.

### Root cause (two layers)

- **Layer 2 (cause): first-run liveness never emits.** `GoalRunnerProgressEventEmitter.emit`
  (`GoalRunnerProgressEventEmitter.kt:46`) no-ops unless `resolveWorkflowId()` is non-blank.
  `resolveWorkflowId` reads `manifest.subtasks[id].workflowId` (`GoalRunner.kt:796` →
  `GoalRunnerTickProgressReader.resolve`, `:1119-1128`). In `runSelectedSubtask`
  (`GoalRunner.kt:197-219`) the driver runs `subtaskLauncher.launch(...)` synchronously and only
  writes the child workflow id into the manifest in `reconcileLaunchOutcome`, **after** `launch()`
  returns. On a first run the child mints its own `wftr` id, so the manifest id is blank for the
  whole run → every heartbeat no-ops → zero liveness recorded.
- **Layer 1 (failed backstop): set-membership-only staleness.**
  `reconcileAuthoritativeOutcomes` (`GoalRunnerWorkflowStores.kt:296-311`) stale-blocks a
  `running` subtask purely on `allowInactiveReconciliation && workflowId !in activeSet`. The port
  default is `allowInactiveReconciliation = true` (`GoalRunnerPorts.kt:74`) and `finalizeGoal`
  passes `activeWorkflowIds = emptySet()` (`GoalRunner.kt:518`), so every still-`running` subtask
  is blocked with no liveness/death check.

### Implementation foot-gun (must avoid)

The existing `childWorkflowId` plumbing (`AgentRunCommandBuilders.kt:161`;
`FeatureTaskRuntimeCliCommands.kt:279`, "Existing runtime workflow id to resume") is **resume**
semantics. Pre-assigning an id and feeding it through the resume path on a first run would make
the child resume a workflow that does not exist yet — the `InvalidWorkflowStateSchemaError` /
mode-collision shape fixed in PR #186. Layer 2 therefore needs an **open-with-assigned-id** path
distinct from resume.

## Scope

- Pre-assign the runtime subtask workflow id in the goal driver before launch, persist it to the
  manifest, and pass it to the child via an open-with-this-id path (not resume).
- Make `reconcileAuthoritativeOutcomes` require positive staleness/death evidence before blocking
  a `running` subtask, and stop `finalizeGoal` from using reset-only blanket-block semantics
  against potentially-live subtasks.
- Add regression coverage for both.

## Acceptance Criteria

1. On a subtask's first `mode:runtime` run, the goal driver pre-assigns the runtime workflow id via `generateWorkflowId("wftr")` and persists it to the manifest subtask **before** `subtaskLauncher.launch(...)` is invoked.
2. The pre-assigned id is passed to the child through an open-with-this-id path (not the resume path), and the runtime child opens its workflow under that exact id rather than minting a new one.
3. With the id present from the start, `resolveWorkflowId` resolves on the first supervisor tick, so `operation_started` and `operation_heartbeat` events are recorded throughout a long, quiet first phase.
4. `reconcileAuthoritativeOutcomes` does not stale-block a `running` subtask without positive evidence it is gone (a staleness window and/or honoring declared liveness); `finalizeGoal` no longer applies `emptySet()` + `allowInactiveReconciliation = true` reset-only semantics against subtasks that may still be live.
5. A regression test proves that a first-run runtime subtask with a long, quiet phase records `operation_started`/`operation_heartbeat` and is not reconciled as stale.
6. `reset()` aggressive-reconciliation semantics are preserved, and neither the resume path nor the PR #186 mode guard regresses (covered by existing tests staying green).
7. `./gradlew check` passes (detekt, spotless, tests) with no new suppressions.

## Non-goals

- No changes to prose-mode orchestration.
- No redesign of the SKILL-64 liveness taxonomy; only make it fire for first runs and make the
  reconciler honor it.
- No change to `reset` teardown behavior.

## Constraints

- Kotlin runtime, hexagonal boundaries; follow `CLAUDE.md` / `AGENTS.md`. No suppressions.
- Land as a branch + PR (CI). Do not push directly to `main`.
- Comment discipline: self-documenting code; no comments that restate the code; KDoc only for
  genuinely non-obvious intent.

## Validation strategy

- Unit/integration tests in `runtime-application` (`GoalRunnerTest` / `FeatureTaskRuntimeRunnerTest`
  families) covering: first-run id pre-assignment + manifest persistence ordering; heartbeats
  recorded during a quiet first phase; reconciler not stale-blocking a live running subtask;
  `reset()` still aggressively reconciles.
- `./gradlew check` for detekt, spotless, and the full affected-module test suites.

## Affected areas

- `runtime-application` goalrunner: `GoalRunner.kt`, `GoalRunnerWorkflowStores.kt`,
  `GoalRunnerProgressEventEmitter.kt`.
- `runtime-ports`: `GoalRunnerPorts.kt`.
- `runtime-cli`: `FeatureTaskRuntimeCliCommands.kt`.
- `runtime-infra-fs`: `AgentRunCommandBuilders.kt`.
- Runtime feature-task open path (`ensureWorkflowOpen` / `openRuntimeWorkflowId`).
