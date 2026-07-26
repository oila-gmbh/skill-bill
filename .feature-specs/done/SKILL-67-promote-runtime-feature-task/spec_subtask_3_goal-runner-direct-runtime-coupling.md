---
status: Complete
---

# SKILL-67 Subtask 3 - Goal Runner Direct Runtime Coupling

Parent spec: [.feature-specs/SKILL-67-promote-runtime-feature-task/spec.md](./spec.md)
Issue key: SKILL-67

## Scope

Make `skill-bill goal` run each decomposed subtask through the canonical runtime
directly, replacing the prose-skill continuation prompt with a direct runtime
invocation. Child subtask state moves from the `feature_implement` workflow family
to the `feature_task_runtime` family.

- Replace the goal-continuation child contract: the hard-coded "Use the installed
  `bill-feature-task` skill in non-interactive goal-continuation mode" prompt at
  `AgentRunCommandBuilders.kt:150` (and the surrounding `launchPrompt` builder)
  becomes a direct `skill-bill feature-task run|resume <issue_key> <spec_path>`
  invocation passing the existing `--goal-parent-issue-key`, `--goal-subtask-id`,
  `--goal-branch`, `--suppress-pr`, and `--agent` flags.
- Update `GoalRunner` / `GoalRunnerLaunchReconciler.subtaskLaunchRequest()` so the
  launch request targets the canonical runtime and the child's durable state is a
  `feature_task_runtime` workflow row; goal resume/continue maps onto runtime
  `resume` semantics (deterministic skip of completed phases) instead of
  `skill-bill workflow continue` against `feature_implement`.
- Preserve goal's flat worker model and the SKILL-56/58 contract: parent/child
  workflow rows authoritative over prose, fresh child process per attempt,
  read-only `goal status` / `goal watch`, stale-running reconciliation on a
  durable terminal outcome, hidden raw child streams by default, and explicit
  durable terminal outcome before advancing or opening the parent PR.
- Preserve goal observability/telemetry (SKILL-61 / SKILL-64 / SKILL-66):
  `goal_event:` transitions, `goal_observability:` lines, compact progress, and
  the goal telemetry family must continue to work with phase/step values now
  sourced from the runtime's per-phase records.

## Acceptance Criteria

1. A goal subtask launch invokes `skill-bill feature-task run|resume` directly
   with the `--goal-*` flags; no child prompt instructs an agent to "use the
   `bill-feature-task` skill".
2. Child subtask state is persisted as a `feature_task_runtime` workflow row;
   `goal status` / `goal watch` report runtime-owned per-phase status, current
   phase, and complete/pending/blocked counts sourced from those rows.
3. Goal resume re-enters an interrupted subtask through runtime `resume`,
   deterministically skipping completed phases and never silently re-running or
   losing completed work; a blocked phase stops the loop with a summarized reason.
4. SKILL-56/58 invariants hold: fresh child per attempt, durable terminal outcome
   gates advancement and parent-PR opening, read-only status never launches a
   child, stale-running reconciliation still closes orphaned child rows.
5. Goal observability and telemetry (`goal_event:`, `goal_observability:`,
   goal telemetry family) continue to emit correctly against the runtime's
   per-phase state.
6. `--suppress-pr` is honored for child subtasks so only the parent goal opens a
   PR.

## Non-Goals

- No CLI/MCP renames (subtask 1) or skill/install changes (subtask 2) beyond
  consuming their outputs.
- Do not migrate historical `feature_implement` goal-child rows into the new
  family; only new child runs use `feature_task_runtime`.
- Do not change the decomposition manifest schema or the goal worker model shape.
- Do not redesign the runtime phase loop.

## Dependency Notes

Depends on: Subtask 1 (canonical `skill-bill feature-task` command and the
`feature_task_runtime` family it resolves to) and Subtask 2 (canonical skill name
referenced in any goal-facing prose/docs). Functionally relies most on Subtask 1.

## Validation Strategy

`(cd runtime-kotlin && ./gradlew check)` focused on `GoalRunner` tests, the
launcher/command-builder tests (`AgentRunCommandBuilders`), and goal
observability/telemetry tests; a goal dry-run on a small decomposed fixture
confirming runtime-owned child state, resume skip behavior, and suppressed child
PRs.

## Next Path

Subtask 4 updates the `bill-feature` dispatcher, docs, and records the maintainer
promote decision.

## Spec Path

.feature-specs/SKILL-67-promote-runtime-feature-task/spec_subtask_3_goal-runner-direct-runtime-coupling.md
