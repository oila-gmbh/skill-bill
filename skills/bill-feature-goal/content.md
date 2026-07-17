---
internal-for: bill-feature
name: bill-feature-goal
description: Use when a decomposed feature goal is ready to run through one confirmation gate. Accepts `mode:runtime` (default), which drives the foreground `skill-bill goal` runtime, or `mode:prose`, which drives an in-session subtask loop, mirroring bill-feature-task's argument convention.
---

# Feature Goal Content

`bill-feature-goal` is the interactive front door for a feature goal that needs multiple decomposed implementation subtasks. It verifies decomposition readiness, asks for exactly one confirmation before starting any automated loop, and runs the confirmed decomposition in the selected mode: the foreground `skill-bill goal` driver by default (`mode:runtime`), or an in-session subtask loop (`mode:prose`).

`bill-feature-goal` is the trigger surface for decomposed-goal orchestration. In
`mode:runtime` (the default) it hands off to the durable `skill-bill goal` runtime
driver; in `mode:prose` it loops the subtasks in the current agent session.

`bill-feature-goal` does not own spec-writing logic. When decomposition artifacts are
missing, it must reuse the shared feature-spec preparation path exposed through
`bill-feature-spec`.

## Modes

`bill-feature-goal` accepts a `mode:` argument, mirroring `bill-feature-task`'s
convention:

- `mode:runtime` (default) drives the foreground `skill-bill goal` runtime
  documented in the sections below. This is the documented default when no
  `mode:` argument is supplied.
- `mode:prose` drives the in-session subtask loop documented in the
  `## Mode: Prose Goal Orchestration (in-session loop)` section near the end of
  this file.

Resolve the mode to `runtime` when no `mode:` argument is supplied.

Accept at most one `code-review:auto`, `code-review:inline`, or
`code-review:delegated` token. Omission resolves to `delegated`; malformed, unknown,
duplicate, or conflicting values fail before confirmation or goal launch. The
selected mode is immutable for the parent and every child: a resume reuses it,
and an attempted explicit change must fail loudly before launching a subtask.

Accept at most one `parallel-review:<agent>` token independently of the review
mode. Omission means one review lane; when present, show the selected agent in
the confirmation gate and carry it unchanged through runtime and prose child
launches. The parent persists this lane selection with the review mode, and a
resume must reuse it rather than silently dropping or changing the second lane.

Receive the already-resolved ordered agent add-on selection from `bill-feature`;
do not parse raw `agent-addon:` tokens here. Show its slugs and descriptions in
caller order in the existing single confirmation, persist the structured
selection with the parent policy, and forward it unchanged to every runtime or
prose child and child continuation artifact. Before parent persistence or child
setup, validate the effective run agent, every explicit phase assignment, and
the resolved parallel-review lane. A resumed omission inherits the durable
selection exactly; any mismatch, missing source, digest drift, or incompatible
new receiving agent fails before selecting or launching a child.

For a prose-goal resume, an omitted `code-review:` or `parallel-review:` token
inherits the durable parent selection. An explicit resumed mode or lane must
match that selection exactly; reject an incompatible value before selecting or
launching a child. Every fresh prose child receives the durable mode and
optional lane alongside its review baseline and pass state, never values
re-derived from the current branch or a sibling subtask. A rejected resume
must not overwrite the durable parent or child review policy.

**opencode and zcode are prose-only.** When the agent currently executing this skill is opencode or zcode, prose is the implicit default and runtime mode is unsupported: opencode's foreground Bash tool is hard-killed at 120s before a phase can finish and per-phase output cannot be harvested back; zcode's foreground runtime exceeds the Bash execution ceiling and a detached zcode child emits no harvestable output before the supervisor kills it as unresponsive. On opencode or zcode: with no mode arg, resolve to `prose` (no need to pass `mode:prose`); with an explicit `mode:runtime`, stop and emit the actionable refusal and do NOT hand off to the `skill-bill goal` runtime:

> Runtime mode is not supported on opencode or zcode in this harness. opencode's foreground Bash tool is hard-killed at 120s before a phase can finish and per-phase output cannot be harvested back; zcode's foreground runtime exceeds the Bash execution ceiling and a detached zcode child emits no harvestable output before the supervisor kills it as unresponsive. Use prose instead — run bill-feature-task-prose for a single feature task, or bill-feature-goal mode:prose for a decomposed goal.

The `skill-bill goal` CLI refuses the same way whenever the resolved runtime agent is opencode or zcode (invoked agent or `--agent-override`), so this skill gate and the CLI agree.

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
- the resolved mode: show `runtime (default)` when the mode was not specified, `runtime` when explicitly set, or `prose` when `mode:prose` was passed
- the parallel review agent when `parallel-review:<agent>` was passed, or `none` otherwise
- the requested code-review selection, showing `delegated (default)` when omitted

Ask one confirmation question: whether to proceed with this decomposition and start the goal loop in the resolved mode.

Do not start the goal loop while the decomposition is unconfirmed. If the user declines, stop and either revise the proposal or leave the goal unstarted, depending on their response.

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

## Goal child review contract

After the child branch is established and before implementation can mutate the
worktree, capture and durably persist that child workflow's `review_base_sha`
and normalized inventory of pre-existing untracked paths. On resume, reuse the
stored baseline exactly; a missing, rewritten, unrelated, or non-ancestor base
blocks loudly. Never substitute `HEAD`, `origin/main`, a merge base, the full
feature branch, or an earlier subtask's commits.

Every child review, including repair and audit-driven re-entry, reviews the
complete base-to-current delta: committed, staged, unstaged, and untracked
paths that were not in the baseline inventory. Pass the same exact delta and
the selected `mode:<auto|inline|delegated>` contract to both full
parallel lanes. Lanes remain non-recursive and the coordinated lanes count as
one review pass.

The complete child-owned delta is the immutable `review_base_sha` through the
current worktree plus `current untracked paths - baseline untracked inventory`.
The baseline subtraction excludes pre-existing untracked files while retaining
new child-owned untracked files after implementation, repair, or resume. Never
replace this scope with a branch-wide diff, merge base, `origin/main`, or
sibling-subtask substitute.

Each child reserves its pass before review starts and permits at most two total
passes across resume, repair, and audit re-entry. If pass two still has
Blocker or Major findings, persist full evidence and the
`review_cap_reached` disposition, emit a compact path-free summary, and
continue to audit without claiming approval or launching a third pass. Audit,
validation, dependency, history, commit/push, and PR gates remain active.

If a crash leaves a reserved pass without its completed durable output, resume
that reserved pass rather than allocating another. Carry completed or capped
state forward on every repair and audit re-entry; `review_cap_reached` is a
non-approval disposition, not a reason to block any independent later gate.

Goal-facing review output and `goal_event` lines contain only subtask id, pass,
verdict/disposition, finding count, severity, class/symbol-or-sanitized-stem
label, and concise text. They must never contain a path, line number, diff
hunk, or raw child-review output; full location-bearing evidence remains in
the child's durable review artifacts and telemetry.

## Confirmed Handoff

After confirmation, ensure the decomposed parent workflow and runtime manifest
now exist from the shared feature-spec preparation path. Then execute the
foreground driver directly in the current agent session, always passing
`--agent` set to the agent currently executing this skill:

```bash
skill-bill goal <issue_key> --agent <currently-executing-agent>
```

Append `--code-review-mode <auto|inline|delegated>` and, when requested,
`--parallel-review-agent <agent>`; require the runtime to pass both selections
to every child. Parallel review remains a second full lane; both lanes receive
this mode and neither may recursively launch parallel review.

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
continuation selector result), `spec_path`, durable `code_review_mode`, optional
`parallel_review_agent`, `review_base_sha`, `baseline_untracked_paths`,
`completed_review_pass_count`, `reserved_review_pass_number`,
`review_cap_disposition`, and the
goal-continuation contract rules (`suppress_pr=true`, `commit_push` is the
terminal signal, no install flows). The Level-1 agent runs the full phase loop
(preplan → plan → implement → review → audit → validate → history → commit_push)
in its own fresh context and returns a bounded RESULT block.

For every fresh child, copy those durable fields without recomputing them. On a
resume, omission inherits the persisted mode and optional lane; reject an
explicit incompatible mode or lane before the child is launched. This preserves
one canonical selection and one exact scope across fresh runs, repair, and
resumption, without changing durable state on a rejected resume.

### Prose goal lifecycle telemetry

Before starting the subtask loop, call `goal_prose_started` with the prose
workflow's `workflow_id` as the stable session key. This is idempotent on resume:
the server performs an UPDATE if the row already exists, so re-calling on resume
is a safe no-op.

```
goal_prose_started:
  issue_key:     <issue key>
  feature_name:  <feature name from manifest>
  workflow_id:   <prose workflow's workflow_id>
  subtask_total: <total subtask count from manifest>
  resumed:       <true if any subtasks already have terminal status>
  started_at:    <current ISO-8601 timestamp>
```

After each Level-1 agent returns with a terminal status, call
`goal_prose_subtask_finished`. This uses ON CONFLICT DO NOTHING at the database
level, so re-calling for an already-recorded subtask is a safe no-op.

```
goal_prose_subtask_finished:
  issue_key:     <issue key>
  workflow_id:   <prose workflow's workflow_id>
  subtask_id:    <subtask id>
  subtask_name:  <subtask name>
  status:        <complete | blocked | skipped>
  started_at:    <subtask started ISO-8601 timestamp>
  finished_at:   <subtask finished ISO-8601 timestamp>
  duration_ms:   <duration in milliseconds>
  attempt_count: <how many times this subtask was attempted>
  blocked_reason: <free-text reason if blocked, null otherwise>
```

At goal completion or termination (clean finish or stop-loudly), call
`goal_prose_finished`:

```
goal_prose_finished:
  issue_key:         <issue key>
  workflow_id:       <prose workflow's workflow_id>
  status:            <completed | blocked>
  started_at:        <goal started ISO-8601 timestamp>
  finished_at:       <goal finished ISO-8601 timestamp>
  duration_ms:       <total duration in milliseconds>
  subtasks_complete: <count of subtasks with status=complete>
  subtasks_blocked:  <count of subtasks with status=blocked>
  subtasks_skipped:  <count of subtasks with status=skipped>
```

These three calls produce a `goal_run_sessions` row with `mode=prose` and the
corresponding `goal_subtask_events` rows, making prose goal runs directly
comparable to runtime goal runs in `goal_stats`.

Selection semantics follow the runtime DecompositionWorkflowContinuation
selector: resume the in-progress subtask; else start the first pending subtask
whose dependencies are complete; else report blocked or all-complete. Resolve the
`workflow_id` for the next runnable subtask via `feature_task_prose_workflow_get`
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
`feature_task_prose_workflow_get` (or `skill-bill goal status <issue_key>`)
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

If `blocked_reason` starts with `audit_gap_respec_suggested`, the stop is a
re-spec opportunity, not a generic manual block. Surface the message and ask the
user whether to decompose the current subtask and start a new execution loop. If
the user agrees, invoke `bill-feature-spec` for the current subtask spec, passing
the blocked workflow id, latest audit output, unmet criteria, and a short
explanation that the audit gaps exceeded the inline remediation budget. The
resulting decomposition must target only the unresolved scope of that subtask;
do not continue the old subtask loop or hand-edit the manifest to skip it.

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
