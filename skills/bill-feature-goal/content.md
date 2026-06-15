---
name: bill-feature-goal
description: Use when a decomposed feature goal is ready to run through one confirmation gate. Accepts `mode:runtime` (default), which drives the foreground `skill-bill goal` runtime, or `mode:prose`, which drives an in-session subtask loop, mirroring bill-feature-task's argument convention.
---

# Feature Goal Content

`bill-feature-goal` is the interactive front door for a feature goal that needs multiple decomposed implementation subtasks. It verifies decomposition readiness, asks for exactly one confirmation before starting any automated loop, and hands confirmed decompositions to the local `skill-bill goal` driver.

`bill-feature-goal` is the trigger surface for runtime workflow behavior with
durable state; `skill-bill goal` remains the runtime driver.

`bill-feature-goal` does not own spec-writing logic. When decomposition artifacts are
missing, it must reuse the shared feature-spec preparation path exposed through
`bill-feature-spec`.

## Modes

`bill-feature-goal` accepts a `mode:` argument, mirroring `bill-feature-task`'s
convention:

- `mode:runtime` (default) drives the foreground `skill-bill goal` runtime
  documented in the existing sections below. This is the documented default when
  no `mode:` argument is supplied.
- `mode:prose` drives the in-session subtask loop documented in the
  `## Mode: Prose Goal Orchestration (in-session loop)` section near the end of
  this file.

Both modes share the same intake, decomposition-readiness checks, and the single
confirmation gate defined in `## Decomposition Proposal`. They differ only in how
confirmed subtasks are executed: `mode:runtime` hands off to the foreground
runtime driver, while `mode:prose` loops the subtasks in the current agent
session.

## Intake

Clarify the user's feature goal enough to identify:

- the issue key
- the intended outcome
- the acceptance criteria
- known constraints, affected areas, and non-goals

If the issue key is missing, stop and ask for it. Do not invent one.

When the caller provides only an issue key, use existing governed artifacts under
`.feature-specs` when there is a single clear match for that key. Ask for a more
specific path only when there is no match or the matches are ambiguous.

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
foreground driver directly in the current agent session, always passing
`--agent` set to the agent currently executing this skill:

```bash
skill-bill goal <issue_key> --agent <currently-executing-agent>
```

### Rehydrate a missing linear-mode spec before launch/resume

The goal's spec source is an artifact stamp read from the
`decomposition-manifest.yaml` `spec_source` field, defaulting to `local`. For
`spec_source: local`, no rehydrate is needed and no Linear MCP call is made.

For `spec_source: linear`, linear-mode goals delete each subtask's spec scratch
incrementally on success (subtask spec after its commit, parent spec + manifest
after the final subtask), so on a resume an earlier-subtask spec being absent is
normal and healthy — do not rehydrate it. Before launching/resuming, only when a
*still-needed* spec (the parent spec or a not-yet-complete subtask's spec) is
missing, rehydrate it first: fetch the parent issue by `issue_key` and each
needed subtask by its `linear_issue_id` via the Linear MCP, rewrite those local
files, then launch. Rehydrate is agent-side MCP only; the `skill-bill goal`
runtime gains no Linear dependency.

Always pass `--agent` set to the agent currently running this skill (for example
`claude` from Claude Code, `codex` from Codex, `opencode` from OpenCode), so the
invoking agent — not a hardcoded default — drives child subtask runs. Only use
`--agent-override` when the user explicitly selected a different child agent;
`--agent-override` continues to win over `--agent`.

Do not ask the user to run this command manually. The confirmation gate is the only user interaction required before execution starts.

Keep live output enabled unless the user asks for quieter output.

## Watching Long Runs (orchestrator pattern)

The user must see the goal progress, not wait in silence for a terminal result.
Surfacing meaningful transitions inline as they arrive is a contract obligation
of the invoking agent, not optional polish.

Long goal runs may exceed a foreground command timeout. When that risk exists,
run the driver detached and consume progress through read-only commands rather
than holding the foreground call open:

- Run `skill-bill goal <issue_key> --agent <agent>` detached (background), then
  poll progress with the read-only `skill-bill goal status <issue_key>` /
  `skill-bill goal watch <issue_key>` commands, or consume the
  `goal_event:` transition stream.
- Do NOT relay raw per-tick `heartbeat` lines verbatim into the parent
  assistant-visible transcript. Heartbeats are high-frequency liveness ticks;
  surface only meaningful transitions to the user.
- Prefer the transition-only `goal_event:` stream for machine consumption: it
  emits one line per meaningful change, so you can track progress without
  deduplicating heartbeats or scraping free-form text.

### `goal_event:` transition schema

The runtime emits a machine-consumable transition line on each meaningful change
only — subtask change, phase/step transition, blocked, failed, completion, and
terminal reconciliation — distinct from the per-tick `heartbeat` and
`goal_observability:` lines. The line uses the stable prefix `goal_event:` and
stable `key=value` keys:

```text
goal_event: issue_key=SKILL-901 subtask_id=1 prev_step=preplan current_step=implement prev_status=in_progress current_status=in_progress event_kind=subtask_resume sequence_number=20001
```

Required keys: `issue_key`, `subtask_id`, `prev_step`, `current_step`,
`prev_status`, `current_status`, `event_kind`, and a monotonic `sequence_number`.
The `goal_event:` sequence space is distinct from the `goal_observability:`
sequence space, so consumers must not assume the two share numbering. A
`goal_event:` line is emitted only on a meaningful change, never once per
heartbeat. Phase/step values are sourced from the authoritative durable workflow
store, never a stale local default.

### Quiet / transition-only monitoring

A quiet or transition-only monitor reports subtask start, phase transition,
blocked/failed, completion, and sparse liveness events without appending every
heartbeat to the foreground assistant-visible transcript. Debug/raw child stdout
and stderr remain explicit opt-in via `--debug-child-output`; default output
keeps raw child streams hidden and surfaces only compact progress, observability,
and transition lines.

During the run, treat workflow state as authoritative. Child stdout and stderr are diagnostic. If the driver stops and reports a blocked or failed subtask, surface it loudly and immediately, do not continue the loop manually, and summarize the stopped subtask, reason, workflow id when present, and resumable step. On a clean finish, report the terminal per-subtask summary — complete, pending, and blocked counts and the final outcome — from the authoritative workflow state.

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

## Mode: Prose Goal Orchestration (in-session loop)

This section documents `mode:prose`. Everything above this section is the
`mode:runtime` default. The prose loop reuses the SAME single confirmation gate
already defined in `## Decomposition Proposal` — it does not introduce a second
confirmation prompt. The decomposition-readiness checks, the proposal contents,
and that single gate are shared verbatim across both modes.

After the user confirms at that single gate, `mode:prose` does NOT launch
`skill-bill goal`. Instead, for each runnable subtask the invoking agent spawns
exactly one Level-1 subtask-agent via the Agent tool with a self-contained
briefing. The agent type is `bill-feature-task-subtask-runner`. The briefing
must carry: `issue_key`, `subtask_id`, `workflow_id` (from the manifest or the
continuation selector result), `spec_path`, and the goal-continuation contract
rules (`suppress_pr=true`, `commit_push` is the terminal signal, no install
flows). The Level-1 agent runs the full phase loop
(preplan → plan → implement → review → audit → validate → history → commit_push)
in its own fresh context and returns a bounded RESULT block.

Selection semantics follow the runtime DecompositionWorkflowContinuation
selector: resume the in-progress subtask; else start the first pending subtask
whose dependencies are complete; else report blocked or all-complete. Resolve the
`workflow_id` for the next runnable subtask via `feature_implement_workflow_get`
or `skill-bill workflow continue` before spawning the Level-1 agent, so the
Level-1 briefing carries the correct `workflow_id`. When a `subtask_id` is
supplied by the caller, treat it as a constraint on the next runnable subtask,
never a way to skip dependencies.

Per-subtask runs keep `suppress_pr=true` (`goal_continuation.suppress_pr=true`)
so each subtask commits but does not open its own PR; the whole goal opens
exactly one parent PR on clean completion.

**Orchestrator thinness constraint.** The invoking agent (Level-0) holds only:
(1) the decomposition manifest, (2) per-subtask terminal outcomes —
`{status, commit_sha, workflow_id}` — one record per completed or stopped
subtask, and (3) the current subtask index. It does NOT accumulate preplan
digests, plans, implementation summaries, code-review reports, or audit reports
from any subtask. The Level-1 return value is the terminal outcome signal;
everything else stays in Level-1 context.

### Terminal-outcome verification and durable authority

After Level-1 returns, verify the terminal outcome via
`feature_implement_workflow_get` (or `skill-bill goal status <issue_key>`)
before advancing to the next subtask. The in-session RESULT block is a signal
only — durable workflow state is authoritative.

The structured outcome fields are `issue_key`, `subtask_id`, `status`,
`commit_sha`, `workflow_id`, `blocked_reason`, and `last_resumable_step`.

Each completed subtask leaves a durable terminal outcome (`status`,
`commit_sha`, `workflow_id`) so `skill-bill goal status` reflects it with NO
hand-repair. The runtime workflow store is the single authority: there is no
second authoritative store and no hand-written DB rows. The DB-to-disk
reconciliation (the `decomposition-manifest.yaml` projection) is read-only-safe
and must never be hand-edited to force progress.

### Blocked or failed subtask: stop loudly

If Level-1 returns a RESULT block with `status` ≠ `completed`, treat it as
blocked/failed. If any subtask returns blocked or failed — anything other than a
terminal success — STOP the loop loudly and immediately. Do NOT continue to the next
subtask manually and do NOT attempt a hand-written continuation. Surface the
subtask id, the reason (`blocked_reason`), the workflow id, and the resumable
step (`last_resumable_step`), leaving resumable durable state in place so a later
resume (by the runtime or a later prose continuation) can pick up exactly where
it stopped. This mirrors the `mode:runtime` stop-loudly contract: blocked state
is sticky and is never silently skipped.

### Clean completion: convergence, parent PR, agent-agnosticism

On clean completion the durable store, the on-disk
`decomposition-manifest.yaml` projection, and git history all agree, and EXACTLY
ONE parent PR is opened for the whole goal (per-subtask runs keep
`suppress_pr=true`). The parent PR is produced via the standard
`bill-pr-description` plus `gh` path; there is no reusable skill-bill parent-PR
command.

This is governed skill content that installs for all supported agents. Only the
entry and launch mechanics differ per agent; no agent-specific orchestration is
hardcoded — the invoking agent drives the loop through the same continuation
contract regardless of which agent runs it.
