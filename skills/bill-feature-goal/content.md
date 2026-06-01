---
name: bill-feature-goal
description: Use when a decomposed feature goal is ready to run through one confirmation gate and the foreground `skill-bill goal` runtime.
---

# Feature Goal Content

`bill-feature-goal` is the interactive front door for a feature goal that needs multiple decomposed implementation subtasks. It verifies decomposition readiness, asks for exactly one confirmation before starting any automated loop, and hands confirmed decompositions to the local `skill-bill goal` driver.

`bill-feature-goal` is the trigger surface for runtime workflow behavior with
durable state; `skill-bill goal` remains the runtime driver.

`bill-feature-goal` does not own spec-writing logic. When decomposition artifacts are
missing, it must reuse the shared feature-spec preparation path exposed through
`bill-feature-spec`.

## Intake

Clarify the user's feature goal enough to identify:

- the issue key
- the intended outcome
- the acceptance criteria
- known constraints, affected areas, and non-goals

If the issue key is missing, stop and ask for it. Do not invent one.

Classify the goal before decomposing:

- If the goal is small enough for one normal implementation pass, complete it directly in the current agent session. Do not create a decomposition manifest and do not invoke `skill-bill goal`.
- If the goal needs multiple independently resumable implementation subtasks, prepare a decomposition proposal.

## Decomposition Proposal

For decomposed goals, first ensure decomposition artifacts exist through the
shared preparation path:

- If `.feature-specs/{ISSUE_KEY}-{feature-name}/decomposition-manifest.yaml`
  is missing, invoke `bill-feature-spec` in this session to prepare a
  decomposed parent spec and ordered subtask specs.
- If decomposition artifacts already exist, reuse them as-is.

Then present a concise proposal that includes:

- the issue key and feature name
- the parent acceptance criteria
- two or more ordered subtasks with dependency notes
- the expected first runnable subtask
- the agent that will be used for child runs, including any explicit override

Ask one confirmation question: whether to proceed with this decomposition and start the foreground goal loop.

Do not start `skill-bill goal` while the decomposition is unconfirmed. If the user declines, stop and either revise the proposal or leave the goal unstarted, depending on their response.

## Runtime-Owned Worker Model

`skill-bill goal` owns the worker loop after confirmation. The model is flat:
the foreground runtime reads the decomposition manifest, selects the next
runnable subtask, opens or resumes the durable child workflow, and launches one
fresh child process for that subtask. The workflow store is the authority for
subtask state, resumable step, terminal outcome, commit SHA, progress events,
and observability snapshots.

Native or nested subagents can still be useful inside a child agent session for
debugging, context management, or specialist review, but they are not the
reliability contract. Reliability comes from the runtime-owned workflow rows,
the decomposition runtime projection, and explicit child process boundaries.
Do not treat a nested subagent transcript as proof of progress unless the child
workflow has written durable state.

The inherited SKILL-56/SKILL-58 behavior remains part of the contract:

- parent and child workflow rows are authoritative over prose output
- each subtask attempt starts from a fresh child process selected by the runtime
- `goal status` and `goal watch` are read-only and must not launch child runs
- status reconciliation closes stale running child workflows when a terminal
  outcome is authoritative
- raw child stdout/stderr stays hidden by default; use debug output only for
  diagnostics
- completion requires an explicit durable terminal outcome before the goal
  advances or opens the parent PR

## Confirmed Handoff

After confirmation, ensure the decomposed parent workflow and runtime manifest
now exist from the shared feature-spec preparation path. Then execute the
foreground driver directly in the current agent session:

```bash
skill-bill goal <issue_key>
```

Do not ask the user to run this command manually. The confirmation gate is the only user interaction required before execution starts.

Use `--agent` when the invoking agent id is known and `--agent-override` only when the user explicitly selected a different child agent. Keep live output enabled unless the user asks for quieter output.

During the run, treat workflow state as authoritative. Child stdout and stderr are diagnostic. If the driver stops and reports a blocked or failed subtask, do not continue the loop manually; summarize the stopped subtask, reason, workflow id when present, and resumable step.

`skill-bill goal <issue_key>` remains consumer-only. It does not synthesize
decomposition from prose and should loud-fail when the decomposition manifest is
missing.

## Status Checks

Use the read-only status command whenever the user asks where a decomposed goal stands:

```bash
skill-bill goal status <issue_key>
```

Report complete, pending, and blocked counts, the current subtask and step, and the active agent exactly as returned by the command. Do not mutate workflow state during a status-only request.

For live polling without launching child runs:

```bash
skill-bill goal watch <issue_key> --interval-seconds 5 --max-refreshes 12
```

Default goal execution emits compact progress and observability lines while raw
child streams stay hidden:

```text
goal SKILL-901: heartbeat subtask=1 step=implement liveness=durable_progress
goal_observability: issue_key=SKILL-901 subtask_id=1 workflow_phase=implement worker_role=foreground liveness_class=durable_progress sequence_number=1
```

Status and watch can include current git activity on demand:

```bash
skill-bill goal status SKILL-901 --diff-stat
skill-bill goal watch SKILL-901 --diff-stat --interval-seconds 5 --max-refreshes 3
```

Expected text includes one bounded stat snapshot per status/refresh:

```text
diff_stat: files_changed=3 insertions=12 deletions=4
watch_diff_stat: index=2 files_changed=3 insertions=12 deletions=4
```

Bounded hunk output is opt-in and path-scoped:

```bash
skill-bill goal status SKILL-901 \
  --diff-hunk runtime-kotlin/runtime-cli/src/main/kotlin/skillbill/cli/GoalCliCommands.kt \
  --diff-hunk-max-hunks 2 \
  --diff-hunk-max-lines 20 \
  --diff-hunk-max-bytes 4000
```

The runtime prints hunk metadata and bounded lines rather than full raw diff
output:

```text
selected_diff_hunks: count=1 truncated=false
selected_diff_hunk: hunk_index=1 path=runtime-kotlin/runtime-cli/src/main/kotlin/skillbill/cli/GoalCliCommands.kt staged=false header=@@_-10,+10_@@ line_count=4 truncated=false
selected_diff_line: hunk_index=1 line_index=1 path=runtime-kotlin/runtime-cli/src/main/kotlin/skillbill/cli/GoalCliCommands.kt staged=false text=-old
```
