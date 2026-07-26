# SKILL-57 Subtask 3 - Goal-Runner Supervision and Diagnostics

Status: Draft
Parent spec: [.feature-specs/SKILL-57-goal-runner-production-hardening/spec.md](./spec.md)
Issue key: SKILL-57
Subtask order: 3 of 4
Depends on: subtasks 1 and 2
Branch model: same-branch, commit per subtask

## Purpose

Make `skill-bill goal` supervision explain what is happening during long child
runs. The parent must distinguish authoritative durable workflow progress from
secondary file activity, output-only activity, and true idle stalls.

## Scope

In scope:

- Update the goal runner progress probe to consume the durable progress channel
  from subtask 1.
- Keep file activity as secondary liveness only:
  - resets idle watchdog;
  - prints a distinct file-activity line;
  - never marks a subtask complete or failed.
- Track and display liveness signal quality:
  - latest durable workflow progress event;
  - latest workflow snapshot change;
  - latest file activity observation;
  - latest child output observation, if available;
  - current child process state.
- Update `skill-bill goal status <issue_key>` to show the active step and the
  latest liveness signal without requiring access to the original terminal.
- Reconcile parent status from authoritative child terminal outcome before using
  the checked-in manifest projection, so status cannot lag behind a completed or
  blocked child workflow.
- Improve stop reports so timeout/idle stops include the last durable progress,
  last file activity, last output timestamp, workflow id, and resumable step.
- Persist a structured supervision event when the parent closes an unresponsive
  child process or continues work inline: phase, reason, last durable progress,
  last file activity, last output observation, workflow id, and continuation
  mode.
- Make finalization atomic: before reporting a goal complete, force the final
  decomposition projection, include any projection delta in the commit/push path
  or stop with a finalization error, and verify the worktree is clean except for
  explicitly ignored paths.
- Reduce default foreground output to parent-owned structured event lines.
  Large diffs, full skill content, and prompt bodies must be hidden behind
  explicit debug output or written to artifact files referenced by path.
- Add deterministic tests for:
  - durable progress keeps the child alive with no file changes;
  - file activity keeps the child alive but does not produce terminal outcome;
  - no durable progress/file activity/output triggers idle stop;
  - status reports `implement` when the child workflow is at `implement` even
    if the manifest projection is stale.
  - terminal child completion is reflected in `goal status` before projection
    catches up;
  - a completed goal exits only after final projection and worktree-clean checks;
  - default output omits full prompts, full skill bodies, and large diffs.

Out of scope:

- New progress-write APIs or feature-implement prompt changes. Those are
  subtasks 1 and 2.
- Agent compatibility policy. That is subtask 4.

## Acceptance Criteria

1. Parent liveness is reset by durable progress events and workflow snapshot
   changes.
2. Parent liveness is also reset by meaningful worktree file activity, but the
   emitted line clearly says file activity is not workflow progress.
3. `goal status` reports the child workflow current step and latest liveness
   signal.
4. Idle stop reports include enough context to decide whether the child was
   stalled in the agent, stalled at workflow persistence, or actively editing
   files without durable progress.
5. Terminal reconciliation still reads only durable workflow outcomes.
6. Existing SKILL-56 CLI behavior and tests remain green.
7. Parent/child state has a single reconciliation path for status, projection,
   terminal outcome, and final report. Terminal child outcome wins over stale
   manifest projection.
8. Unresponsive child handling writes a durable supervision/continuation record
   before the parent kills, replaces, or bypasses the child.
9. A completed goal leaves no uncommitted final manifest projection. If final
   projection changes after the last subtask commit, the runner either commits
   and pushes it through the configured path or stops with an actionable
   finalization error.
10. Default output is quiet and structured. It shows bounded progress events and
    artifact/check summaries, not full prompts, full skill dumps, or huge diffs.

## Validation

```bash
(cd runtime-kotlin && ./gradlew \
  :runtime-application:test --tests skillbill.application.GoalRunnerTest \
  :runtime-cli:test --tests skillbill.cli.CliGoalRuntimeTest \
  :runtime-infra-fs:test --tests skillbill.launcher.AgentRunLauncherTest)
```
