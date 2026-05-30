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
- Improve stop reports so timeout/idle stops include the last durable progress,
  last file activity, last output timestamp, workflow id, and resumable step.
- Add deterministic tests for:
  - durable progress keeps the child alive with no file changes;
  - file activity keeps the child alive but does not produce terminal outcome;
  - no durable progress/file activity/output triggers idle stop;
  - status reports `implement` when the child workflow is at `implement` even
    if the manifest projection is stale.

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

## Validation

```bash
(cd runtime-kotlin && ./gradlew \
  :runtime-application:test --tests skillbill.application.GoalRunnerTest \
  :runtime-cli:test --tests skillbill.cli.CliGoalRuntimeTest \
  :runtime-infra-fs:test --tests skillbill.launcher.AgentRunLauncherTest)
```
