---
internal-for: bill-feature
name: bill-feature-goal
description: Run a manifest-backed feature goal through one confirmation gate.
---

# Feature Goal Content

`bill-feature-goal` is the interactive front door for every prepared feature goal. It accepts one or more implementation subtasks, verifies manifest readiness, asks for exactly one confirmation before starting any automated loop, and hands off to the durable `skill-bill goal` runtime driver.

`bill-feature-goal` is the trigger surface for manifest-backed goal orchestration. It
hands off to the foreground `skill-bill goal` runtime driver documented in the
sections below.

`bill-feature-goal` does not own spec-writing logic. When decomposition artifacts are
missing, it must reuse the shared feature-spec preparation path exposed through
`bill-feature-spec`.

## Decomposition Proposal

For all prepared goals, first ensure manifest-backed artifacts exist through the
shared preparation path:

- If `.feature-specs/{ISSUE_KEY}-{feature-name}/decomposition-manifest.yaml`
  is missing, invoke `bill-feature-spec` in this session to prepare a
  parent spec and one or more ordered subtask specs.
- If decomposition artifacts already exist, reuse them as-is.

Then present a concise proposal that includes:

- the issue key and feature name
- the parent acceptance criteria
- one or more ordered subtasks with dependency notes; when there is more than one, a line per
  subtask saying why it cannot be folded into its neighbor
- the expected first runnable subtask
- the agent that will be used for child runs, including any explicit override
- the parallel review agent when `parallel-review:<agent>` was passed, or `none` otherwise
- the requested code-review selection, showing `inline (default)` when omitted and marking an explicit `delegated` selection as experimental

Ask one confirmation question: whether to proceed with this decomposition and start the goal loop.

Do not start the goal loop while the decomposition is unconfirmed. If the user declines, stop and either revise the proposal or leave the goal unstarted, depending on their response.

## Confirmed Handoff

After confirmation, ensure the manifest-backed parent workflow and runtime manifest
now exist from the shared feature-spec preparation path. Then execute the
foreground driver directly in the current agent session, always passing
`--agent` set to the agent currently executing this skill:

```bash
skill-bill goal <issue_key> --agent <currently-executing-agent> --no-live-output
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
`claude` from Claude Code or `codex` from Codex), so the
invoking agent — not a hardcoded default — drives child subtask runs. Only use
`--agent-override` when the user explicitly selected a different child agent;
`--agent-override` continues to win over `--agent`.

Do not ask the user to run this command manually. The confirmation gate is the only user interaction required before execution starts.

Launch `skill-bill goal` with `--no-live-output`. Goal live output scales with
wall-clock duration, so suppressing it avoids an unbounded stream being captured
and charged to the agent context; feature-task-runtime `--monitor` is different
because it scales only with phase count.

## Watching Long Runs (orchestrator pattern)

The terminal monitoring block is the user's live feed. The invoking agent does
not attach an observer to the progress stream and does not relay transitions
into the conversation. There is no in-session transition relay; agent silence
during the run is deliberate, not a failure, and ends only when a sanctioned
completion signal or error reaches the session.

After launch, keep the session on the original foreground `skill-bill goal`
blocking call until it returns, or keep the original process alive across yields
and await its exit through the harness process-completion primitive. That single
long wait is the completion signal. It is required, not optional, and it is not
progress observation — the agent makes no separate tool calls while that call runs.

While a foreground or detached run is in flight:

1. Do not run `skill-bill goal watch` in-session, at any interval or refresh count.
2. Do not call `skill-bill goal status` on a timer or repeatedly to observe change.
3. Do not sleep between separate progress checks, schedule wake-ups, or otherwise
   idle between tool calls whose only purpose is to re-read progress.
4. Do not tail, poll, or re-read runtime logs, the workflow DB, `git diff`, or
   changed files to infer progress.
5. Do not re-invoke the runtime or launch an observer process or subagent to
   observe a run that is already executing.

These prohibitions apply to shell loops, scheduled wake-ups, repeated tool
calls, and delegated observers. The cost rule is request count, not wall-clock
time: one completion signal — one blocking launch call or one background-exit
re-invocation — beats any number of short polls, and trimming a poll's output
does not make polling acceptable. A multi-hour blocking wait on the launch
command is the completion signal, not token waste from observing.

The only permitted in-session surface is one bounded terminal notification,
errors such as launch failures, loud-fails, or non-zero exits, and one
`skill-bill goal status` call made in direct response to an explicit user
request.

### Completion Signal

Use the completion signal for the launch mode:

1. For a foreground run within the harness timeout, wait for the blocking call
   to return its structured result.
2. For a detached run where the harness provides background-exit notification,
   let that notification re-invoke the agent once with the result; do not poll.
3. When the harness provides no background-exit notification, do not detach.
   Keep ownership of the original foreground process and use the harness's
   blocking process-completion primitive to await that process's exit. Waiting
   on the original process is a completion signal, not progress polling: do not
   re-read status, logs, workflow state, or process output while it runs. If the
   harness cannot keep or await the original process, loud-fail before launch
   because the session cannot guarantee terminal delivery. Do not substitute a
   `skill-bill goal status` snapshot for the structured terminal result because
   that snapshot omits `pull_request_url` and `blocked_reason`.

Do not read back, summarize, or paraphrase run stdout to compose the
notification or summary. Do not emit progress or transition lines around it.

## Status Checks

```bash
skill-bill goal status <issue_key>
```

```bash
skill-bill goal watch <issue_key> --interval-seconds 5
```

