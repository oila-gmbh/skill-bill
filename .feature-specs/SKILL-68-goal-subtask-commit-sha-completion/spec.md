# SKILL-68 - goal-subtask commit-SHA completion handshake

Created: 2026-06-05
Status: Draft
Issue key: SKILL-68
Mode: single_spec
Parent: fixes a goal-runner / feature-task-runtime completion-handshake defect surfaced while running SKILL-66 (`bill-feature-goal`); builds on SKILL-51/56/58/64/67 goal-runner work.

## Sources

Diagnosed live on 2026-06-05 while running `skill-bill goal SKILL-66`. The goal
loop re-selected subtask 1 on every run and never advanced, despite the subtask
work being committed on the feature branch. Durable-store forensics
(`~/.skill-bill/review-metrics.db`) showed the exact handshake break:

- Four `feature_task_runtime_workflows` child rows all carried
  `goal_continuation.subtask_id = 1`. Three reached `commit_push` with
  `workflow_status = completed` and wrote
  `goal_continuation_outcome = {status: "complete"}` **with no `commit_sha`**,
  and `commit_push_result = null`. The fourth blocked at `commit_push` on a
  schema-gate YAML parse error.
- The parent `feature_implement_workflows` decomposition projection kept
  subtask 1 at `status: "blocked"`, `commit_sha: null`, `workflow_id: null`,
  so the runner re-ran subtask 1 indefinitely.

Confirmed code paths:

- `runtime-application/.../FeatureTaskRuntimeRunner.kt`
  `goalContinuationOutcomeFor` records `status = "complete"` with
  `commitSha = commitShaFromPhaseRecords(...)`, which returns `null` when the
  agent's `commit_push` phase payload omits the SHA (or emits prose/markdown).
  There is no git-HEAD fallback at capture time, and `complete`-without-SHA is
  recorded anyway.
- `runtime-application/.../GoalRunnerWorkflowStores.kt`
  `persistMeasuredCompletion` only backfills a measured HEAD SHA when
  `goalContinuationOutcome(...) == null`; an already-written
  `complete`-without-SHA outcome defeats recovery. `terminalOutcomeFor` reads
  the persisted outcome first and short-circuits the measured-SHA branch.
- `runtime-application/.../GoalRunner.kt` `storedOutcome` only calls
  `recoverAndPersistTerminalOutcome` when
  `manifest.subtasks[subtaskId].workflowId` is non-blank; it stayed `null`, so
  recovery never fired.
- `runtime-application/.../DecompositionManifestRuntimeStateSupport.kt`
  `prSuppressedCommitStatus` / `commitShaFrom` source the SHA **only** from
  `commit_push_result.commit_sha`, ignoring `goal_continuation_outcome`; a
  completed-under-suppress_pr subtask with no SHA there is marked `blocked`.
- `runtime-infra-fs/.../FeatureTaskRuntimePhaseOutputSchemaValidator.kt` parses
  phase output as YAML; a markdown bullet such as `- **HEAD = ` triggers a YAML
  alias scan (`unexpected character found *(42)`) and hard-fails the gate.

## Problem

A goal subtask whose `commit_push` completes under `--suppress-pr` can be
recorded as `complete` with **no commit SHA**. Under the
`same_branch_commit_per_subtask` execution model, the manifest cannot advance a
subtask without a SHA, and the existing git-HEAD recovery is structurally
unreachable once a `complete`-without-SHA outcome has been persisted. The result
is a goal that re-runs the same already-finished subtask forever and never
advances — a silent liveness failure that violates the per-subtask commit
invariant and corrupts goal accounting (the subtask never counts as complete).

The SHA can go missing two ways, both observed: the agent's `commit_push`
payload omits the structured `commit_sha`, or the payload is malformed prose
that fails the phase-output schema gate. In every case the commit itself exists
on the branch (HEAD is the subtask's commit), so the SHA is recoverable from
git — the runtime simply fails to do so before declaring completion.

## Goals

1. A goal subtask is **never** recorded as `complete` without a non-blank commit
   SHA under `same_branch_commit_per_subtask`. The SHA is captured from the
   agent's `commit_push` payload when present, and otherwise recovered from a
   runtime-measured git HEAD.
2. When neither the payload nor a measured HEAD yields a SHA, the subtask
   **blocks loudly** with an explicit, actionable reason — it does not silently
   complete and does not silently loop.
3. The git-HEAD recovery already present in the goal store is reachable in the
   real failure case: an existing `complete`-without-SHA outcome must not defeat
   backfill, and recovery must not depend on a manifest field that is null at
   the time it is needed.
4. Manifest advancement recognizes a recovered SHA: subtask completion sourcing
   considers `goal_continuation_outcome.commit_sha`, not only
   `commit_push_result.commit_sha`.
5. Behavior is otherwise unchanged: subtasks that already carry a SHA complete
   exactly as today; non-goal-continuation feature-task-runtime runs are
   unaffected; observability/progress artifacts and CLI output are unchanged for
   runs that do not hit this path.

## Non-Goals

- Do not redesign the `commit_push` phase-output schema or the agent prompt
  contract. Hardening the YAML/markdown parse fragility in
  `FeatureTaskRuntimePhaseOutputSchemaValidator` is explicitly out of scope here
  (track separately); this fix makes the runtime resilient to a missing SHA
  regardless of why it is missing.
- Do not change the `STACKED_BRANCHES` execution model semantics.
- Do not add or alter telemetry event schemas (that is SKILL-66's scope); this
  fix only changes when a subtask is considered complete vs blocked.
- Do not retroactively repair already-corrupted goal state in existing DBs as
  part of the runtime change (a one-off reconciliation of the SKILL-66 store is
  tracked separately and is not gated by this spec).
- Do not relax the per-subtask commit invariant by allowing `complete` without a
  SHA (the rejected alternative).

## Target User Experience

A maintainer runs a decomposed goal as today:

```bash
skill-bill goal SKILL-XX
```

When a subtask's `commit_push` completes but the agent omits the SHA, the
runtime measures HEAD, records the real commit SHA on the subtask, marks it
`complete`, and advances to the next runnable subtask — no loop, no manual
intervention.

When the commit genuinely cannot be resolved (no payload SHA and HEAD
unmeasurable), the run stops with a clear block:

```text
goal SKILL-XX: subtask N stopped (blocked): commit_push completed under
suppress_pr but no commit SHA could be captured from the phase payload or
measured from git HEAD; the per-subtask commit invariant cannot be satisfied.
Resume from last_resumable_step=commit_push.
```

## Acceptance Criteria

1. For a goal-continuation run (`--suppress-pr`) whose `commit_push` step
   completes, the recorded `goal_continuation_outcome` has
   `status = "complete"` **iff** a non-blank `commit_sha` is present; the SHA is
   taken from the phase payload when present, else from a runtime-measured git
   HEAD.
2. When `commit_push` completes but no SHA is available from either source, the
   run records `status = "blocked"` with an explicit `blocked_reason` naming the
   missing-SHA cause and `last_resumable_step = "commit_push"`; it does not
   record `complete`.
3. The goal store no longer strands the recoverable case: a previously persisted
   `complete`-without-SHA outcome is backfilled with a measured HEAD SHA (the
   `needsBackfill` condition fires when the stored outcome lacks a SHA), and the
   recovery path does not silently no-op solely because
   `manifest.subtasks[subtaskId].workflowId` is null.
4. Manifest advancement (`DecompositionManifestRuntimeStateSupport`) sources the
   completing SHA from `commit_push_result.commit_sha` **or**
   `goal_continuation_outcome.commit_sha`; a subtask with a recovered SHA is
   marked `complete` and the runner advances to the next runnable subtask.
5. A subtask that completes with a SHA present behaves exactly as before
   (no regression in the happy path); non-goal-continuation runs and
   `STACKED_BRANCHES` runs are unaffected.
6. Regression tests prove, with a fake launcher and a fake/seeded git HEAD:
   - completed `commit_push` with payload SHA → `complete` (unchanged);
   - completed `commit_push`, no payload SHA, measurable HEAD → `complete` with
     the measured SHA, and the manifest advances;
   - completed `commit_push`, no payload SHA, no measurable HEAD → `blocked`
     with the explicit reason, no `complete` recorded;
   - a pre-existing `complete`-without-SHA store row → backfilled and advanced
     on the next reconciliation.
7. Architecture boundaries hold: `RuntimeArchitectureTest` passes; the git-HEAD
   measurement is reached only through the existing domain-owned
   `WorkflowGitOperations` port; no new application dependency on
   MCP/Clikt/JDBC is introduced.
8. Maintainer validation passes:
   - `skill-bill validate`
   - `(cd runtime-kotlin && ./gradlew check)`
   - `npx --yes agnix --strict .`
   - `scripts/validate_agent_configs`

## Design Notes

- **Block loudly, never complete without a commit.** Confirmed policy decision:
  under `same_branch_commit_per_subtask`, a complete subtask must have a commit.
  If the SHA is unrecoverable, block with a precise reason rather than recording
  a SHA-less completion that strands the goal.
- **Capture at the source, recover at the seam.** The primary fix is in
  `FeatureTaskRuntimeRunner`: prefer the payload SHA, fall back to a measured
  HEAD via `WorkflowGitOperations`, and only then decide complete-vs-blocked.
  The goal-store recovery (`persistMeasuredCompletion`) and advancement
  (`commitShaFrom`) changes are the defense-in-depth that make an
  already-persisted SHA-less outcome recoverable and consumable.
- **Reuse the existing port.** Git HEAD is already measured through
  `WorkflowGitOperations.headCommitSha` on the goal-store recovery path; reuse
  the same port rather than introducing a new git seam.
- **Strictly additive to the contract.** No event branch, table, tool, or CLI
  command changes shape; only the complete-vs-blocked decision and SHA sourcing
  change.
- **YAML/markdown parse fragility is deliberately separate.** The schema-gate
  alias crash (`*(42)`) is a real but distinct issue; this fix makes the SHA
  path robust regardless, and the parser hardening is tracked on its own.

## Validation Strategy

- Application tests in `runtime-application` with a fake `GoalRunnerSubtaskLauncher`
  and a fake `WorkflowGitOperations` covering the four AC6 cases, asserting exact
  recorded `goal_continuation_outcome` status/SHA and manifest subtask
  status/advancement.
- Goal-store tests proving `persistMeasuredCompletion` backfills a measured SHA
  over a `complete`-without-SHA row and that advancement consumes
  `goal_continuation_outcome.commit_sha`.
- `RuntimeArchitectureTest` for boundary integrity.
- The full maintainer command set (AC8) as the closing gate.

## Open Questions

- Should the block in AC2 be retryable in-process (re-measure HEAD after a short
  delay, mirroring `waitForLateTerminalOutcome`) before blocking, or block
  immediately? Leaning: reuse the existing late-outcome recheck window for the
  measured-HEAD path, then block — no new retry policy.
- Should advancement prefer `commit_push_result.commit_sha` over
  `goal_continuation_outcome.commit_sha` when both exist and differ, or treat a
  mismatch as a loud error? Leaning: prefer `commit_push_result` and loud-fail
  on a genuine mismatch, since two disagreeing SHAs indicate a deeper bug.

Run bill-feature-task on .feature-specs/SKILL-68-goal-subtask-commit-sha-completion/spec.md
