# SKILL-65.1 - feature-task-runtime full parity with bill-feature-task

Created: 2026-06-04
Status: Draft
Issue key: SKILL-65.1
Parent: builds on SKILL-65 (commit 1836cca7, experimental-feature-task-runtime);
brings the runtime-driven phase loop to full functional parity with the
`bill-feature-task` prose orchestrator

## Decomposition

This feature is decomposed because closing the parity gap spans the phase-loop
definition, three new terminal phases, an upstream pre-planning phase, a whole
new telemetry family, decomposition handling, and goal-runner integration — far
more than one reliable implementation pass, in strict dependency order:

1. [Runtime Run Setup and Feature Branch](./spec_subtask_1_runtime-run-setup-and-feature-branch.md)
2. [Pre-Planning Phase](./spec_subtask_2_preplan-phase.md)
3. [Post-Validate Phases: History, Commit, PR](./spec_subtask_3_post-validate-history-commit-pr-phases.md)
4. [Size Assessment and Ceremony Scaling](./spec_subtask_4_size-assessment-and-ceremony-scaling.md)
5. [Decomposition Mode and Planning Stop](./spec_subtask_5_decomposition-mode-and-planning-stop.md)
6. [Lifecycle Telemetry and Stats](./spec_subtask_6_lifecycle-telemetry-and-stats.md)
7. [Goal-Runner Cooperation and Continuation Entry](./spec_subtask_7_goal-runner-cooperation-and-continuation.md)

Implement on one branch with a commit per subtask
(`execution_model: same_branch_commit_per_subtask`).

## Problem

The experimental `feature-task-runtime` runs five phases —
`plan -> implement -> review -> audit -> validate` — and stops. The standard
`bill-feature-task` prose orchestrator runs **twelve** stable steps:
`assess -> create_branch -> preplan -> plan -> implement -> review -> audit ->
validate -> write_history -> commit_push -> pr_description -> finish`
(`bill-feature-task/SKILL.md:40`).

A completed runtime run therefore: never created a feature branch (a real
SKILL-66 run edited the working tree directly on `main`), never pre-planned,
never wrote boundary history, never committed/pushed, never opened a PR, and fed
no lifecycle telemetry to the stats/reporting layer. It also cannot decompose an
oversized plan, does not scale ceremony by feature size, and does not cooperate
with the `bill-feature-goal` goal runner (continue on an existing branch, skip
its own decomposition, suppress the PR so the goal opens one).

The branch + PR gaps are pure dropped steps: the prose orchestrator's
SKILL.md/content.md carry `git checkout -b feat/{ISSUE_KEY}-{feature-name}` and
the PR step, but those instructions lived only in agent prose and were never
ported when the loop moved into the runtime (the front door, the CLI `run`
command, the runner, and the phase directives all contain zero git/branch
handling). The other gaps (preplan, history, size scaling, decomposition,
telemetry, goal cooperation) are likewise absent from the runtime path.

## Sources

- `runtime-kotlin/runtime-domain/.../taskruntime/FeatureTaskRuntimePhaseWorkflowDefinition.kt`
  — authoritative phase DAG (`stepIds`, `stepLabels`, `requiredArtifactsByStep`,
  `resumeActions`, `phaseDeclarations`, `completedTerminalSummaryArtifact`).
- `runtime-kotlin/runtime-application/.../FeatureTaskRuntimePhasePromptComposer.kt`
  — per-phase `phaseDirectives` + the phase-output contract framing.
- `runtime-kotlin/runtime-application/.../FeatureTaskRuntimeRunner.kt`,
  `FeatureTaskRuntimeStatusService.kt`, `FeatureTaskRuntimeFixLoopPolicy.kt`,
  `FeatureTaskRuntimePhaseBriefingAssembler.kt`,
  `FeatureTaskRuntimeRunnerTest.kt:46` (pins the completed phase id list).
- `runtime-kotlin/runtime-cli/.../FeatureTaskRuntimeCliCommands.kt` — `run` /
  `status` / `resume`; contains no branch handling.
- `orchestration/contracts/feature-task-runtime-phase-output-schema.yaml` — the
  phase-output schema gate (`produced_outputs` is open: new phases need **no**
  schema or `FEATURE_TASK_RUNTIME_CONTRACT_VERSION` change).
- `bill-feature-task/SKILL.md` (esp. lines 40-42 stable step/artifact ids,
  84-89 goal-continuation + `suppress_pr`, 109-300 per-step bodies, 984-996
  decomposition + error policy) and `native-agents/agents.yaml:311-314` (PR step)
  — the parity reference.
- `bill-boundary-history` (Step 7), `bill-pr-description` (Step 9),
  `bill-feature-spec` shared preparation path (decomposition write seam),
  `orchestration/contracts/decomposition-manifest-schema.yaml`.
- SKILL-66 (`feature-goal-telemetry`) — the reference pattern for adding a
  lifecycle-telemetry family (events + schema + persistence + `*_stats` MCP/CLI +
  remote-stats mapping) to a runtime; subtask 6 mirrors it.
- `runtime-kotlin/docs/architecture/feature-task-runtime-comparison.md` — the
  experimental positioning and gate-integrity rules this feature must preserve.

## Goal / Intended Outcome

A completed `feature-task-runtime` run matches `bill-feature-task` end to end:
it works on a feature branch (never the default branch), pre-plans, plans
(decomposing and stopping when the work is oversized), implements, reviews,
audits, validates, writes boundary history, commits, pushes, opens a PR, scales
ceremony to feature size, emits lifecycle telemetry to the stats layer, and
cooperates with the `bill-feature-goal` goal runner — all with the runtime's
existing per-phase records, append-only ledger, and schema-validity gates intact
and no review/audit/validation gate weakened.

Scope confirmed with the maintainer: full parity including the three intentional
divergences (lifecycle telemetry + stats, size/ceremony scaling, decomposition)
**and** explicit goal-runner cooperation (reuse existing branch, skip own
decomposition, suppress PR when driven by the goal).

## Parity Gap Summary (drives the subtasks)

| bill-feature-task step | runtime today | subtask |
|------------------------|---------------|---------|
| `create_branch` | absent | 1 |
| `assess` (size scaling) | absent (uniform) | 4 |
| `preplan` | absent | 2 |
| `plan` (+ `mode: decompose`) | present; no decompose | 5 |
| `implement`/`review`/`audit`/`validate` | present | — |
| `write_history` | absent | 3 |
| `commit_push` | absent | 3 |
| `pr_description` | absent | 3 |
| `finish` (lifecycle telemetry + stats) | runtime records/ledger only | 6 |
| goal-continuation + `suppress_pr` | absent | 7 |

## Non-Goals

- No change to `bill-feature-task`, `bill-feature-goal`, the goal runner's
  `GhGoalPullRequestPort`, `bill-boundary-history`, or `bill-pr-description`
  themselves — the runtime *uses* these flows; it does not re-author them.
- No promotion/retirement of the experimental runtime; the SKILL-65 promote/kill
  criterion is untouched and the path stays opt-in via `bill-feature-task-runtime`.
- No weakening of any review/audit/validation/schema gate to reach parity.
- No auto-merge or post-PR automation beyond opening the PR and recording its URL.

## Constraints

- `gh` CLI available and authenticated for the PR step; absent/unauthenticated
  blocks loudly with an actionable reason rather than silently completing.
- All git-touching behavior is idempotent across resume (no duplicate branch, no
  second PR, no changes left on the default branch).
- Changes stay confined to the `feature-task-runtime` family; the architecture
  ownership tests (`ImplementationOwnershipArchitectureTest`,
  `RuntimeArchitectureTest`) keep passing.
- The full maintainer gate — `(cd runtime-kotlin && ./gradlew check)` and
  `skill-bill validate` — passes at every subtask commit.

## Next path

```bash
skill-bill goal SKILL-65.1
```
