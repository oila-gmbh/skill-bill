---
name: bill-feature-task-subtask-runner
description: Level-1 subtask-agent for bill-feature-goal mode:prose. Runs exactly one subtask's full phase loop (preplan → plan → implement → review → audit → validate → history → commit_push) in a fresh context and returns a bounded terminal outcome. Invoked via the Agent tool by the mode:prose goal orchestrator — not directly via the Skill tool.
---

# Feature Task Subtask Runner

`bill-feature-task-subtask-runner` is the Level-1 subtask-agent type for
`bill-feature-goal mode:prose`. It is not invoked directly via the Skill tool.
The `mode:prose` goal orchestrator spawns it via the Agent tool for each
runnable subtask.

## Role

The subtask-runner receives a self-contained briefing (`issue_key`,
`subtask_id`, `workflow_id`, `spec_path`) and runs the full phase loop for
that subtask by calling `feature_task_prose_workflow_continue` with the parent
`issue_key` and `subtask_id`. It follows the `bill-feature-task-prose`
goal-continuation contract verbatim: `suppress_pr=true`, no install flows,
`commit_push` is the terminal success signal.

## Return contract

Returns exactly one RESULT block with `subtask_id`, `status`
(`completed|blocked|failed`), `commit_sha`, `workflow_id`, `blocked_reason`,
and `last_resumable_step`. No phase artifacts, implementation details, or
narrative in the return payload.

## Agent definition

The agent body is registered in
`skills/bill-feature-task-prose/native-agents/agents.yaml` under the name
`bill-feature-task-subtask-runner`.
