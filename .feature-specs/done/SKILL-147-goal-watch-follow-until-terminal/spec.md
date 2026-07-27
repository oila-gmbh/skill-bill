# SKILL-147 — `goal watch` follows until terminal; agents never poll in-session

Status: Ready

## Intended Outcome

Two halves of one rule: **watching is the user's terminal, never the agent's
context window.**

1. `skill-bill goal watch <issue_key> --interval-seconds 60` keeps printing a
   refresh every 60 seconds until the goal finishes, then exits on its own.
   Today it prints exactly one refresh and exits, because `--max-refreshes`
   defaults to `1` and `--interval-seconds` only controls the gap *between*
   refreshes. Make the user's live feed actually work.
2. Governed feature skills forbid the agent from polling a running execution,
   launch the run quiet so its progress stream never enters the agent's context,
   and require exactly one completion line when it ends.

### Why polling is the expensive part

The dominant cost is not the bytes a poll returns. It is that **every tool call
re-sends the whole conversation**, and the conversation is larger each time —
each poll's output permanently inflates every request that follows it. Prompt
caching discounts the re-sent prefix but does not eliminate it, and the growth
compounds superlinearly with the poll count. The request count, not any single
poll's output size, is what compounds.

The unit of comparison is one completion signal versus a poll chain. A run whose
result arrives in a single request — a blocking call that returns, or a detached
run whose exit re-invokes the agent once — costs one request however long the run
takes. Sixty polls across the same span cost sixty, each carrying an ever-larger
transcript, for information the one signal was going to deliver anyway.

The current skill text forces exactly that chain: it hands the agent
`goal watch --interval-seconds 5 --max-refreshes 12` — a sixty-second window.
One such call is a single request, but a multi-hour goal makes the agent repeat
it (or fall back to repeated `goal status` calls) for the whole run, and the
repetition is the quadratic pattern.

That ranks the two fixes:

1. **No polling loops** (subtask 2). Removes the quadratic term. This is the
   whole point.
2. **Quiet launch** (subtask 2). Removes a few hundred captured stdout lines from
   one request. Worth doing because it is free once `watch` works, but it is a
   rounding error next to the first.

The same reasoning is why `--monitor` on feature-task stays: its lines ride
inside a call that already happens, adding no request.

What remains in-session is one line: `goal SKILL-146: complete — 3/3 subtasks,
PR <url>`. Composed from the structured result the run already returns, never by
reading back the stream. A finished run that surfaced nothing would read as
broken, and one line is both the cure and the entire budget.

### How the agent learns the run finished

Forbidding polling only works if the completion signal is named, because the
"one blocking call" does not exist for long runs: agent harnesses cap foreground
tool calls (Claude Code kills a foreground command at 10 minutes), which is
exactly why the goal skill already tells the agent to detach when timeout risk
exists. The sanctioned signals, per launch mode:

- **Foreground, within the harness timeout** — the blocking call returns the
  structured result; compose the completion line from it.
- **Detached, on a harness with background-completion notification** — Claude
  Code re-invokes the agent when a background process exits; that single
  re-invocation carries the result. One signal, zero polls.
- **Detached, on a harness without such notification** — the agent prints the
  monitoring block, states that the run continues in the background, and ends
  its turn. The completion line is emitted when the user next addresses the
  session, answered from one `goal status` call. The completion-line obligation
  binds when the run's outcome reaches the session, never through polling.

Subtask 2 writes this into both governed skills so the prohibition and the
completion-line requirement are jointly satisfiable on every supported agent.

Giving the user a watch command that actually works is what makes the quiet
posture acceptable rather than a regression.

The CLI fix is not "unlimited refreshes". It is **follow-until-terminal**: the
watch loop ends when the goal has nothing left to run, so the command still
terminates on its own and still emits a final payload and a meaningful exit code.

## Problem Statement

In `runtime-kotlin/runtime-cli/src/main/kotlin/skillbill/cli/goal/GoalCliCommands.kt`:

- `DEFAULT_GOAL_WATCH_REFRESHES = 1` (line 1163), so the loop body runs once.
- Both the live print and the sleep are guarded by `refreshIndex < maxRefreshes`
  (lines 366-371), so with the default the interval is never applied and the
  only visible output is the terminal summary payload.
- The loop has no notion of goal completion; it is a fixed-count loop, so even a
  large `--max-refreshes` keeps polling a finished goal until the count runs out.
- `refresh_count` in the final payload reports `maxRefreshes`, the requested
  bound, not the number of refreshes actually performed.

## Design

### Terminal detection

The watch loop stops when the status projection says no subtask can still make
progress. Derive this from the existing projection fields — do not add a new
persistence read or a new runner call, and do not let `watch` mutate anything:

- `status == "not_found"` — stop immediately; polling a missing manifest forever
  is a hang, not a wait.
- `pending_count == 0` — every subtask is `complete`, `skipped`, or `blocked`, so
  the goal has no runnable work left. This covers both a finished goal and a
  goal that has stalled on blocked subtasks with nothing else to attempt.

A goal with `blocked_count > 0` **and** `pending_count > 0` is not terminal:
blocked-subtask recovery may still advance it, and the user watching wants to see
that happen.

### Change-only rendering

Following at a short interval repeats one line for as long as a phase runs — six
identical `current_step=review` lines, then five identical `current_step=validate`
lines, of which only the transitions carry information. A refresh prints only when
its rendered text differs from the last printed one.

Comparison is on rendered text with `refresh_index` normalized out, rather than a
hand-picked field list, so it stays correct as the rendering gains lines and
handles the optional diff surfaces without special-casing. The first refresh and
the loop-ending refresh always print. `--show-unchanged` restores the old
behavior.

The resulting quiet gap during a long phase is intentional and gets no synthetic
keepalive line: idle detection below is what distinguishes a running goal from a
stopped one.

### Idle detection

Counts alone are not enough. A goal can have runnable work and still never
advance — a usage-limit pause, a crashed runner, an interrupted foreground run.
The counts never change, so a counts-only loop polls forever.

Subtask 3 adds an **idle** stop sourced from the runtime worker lease
(`FeatureTaskRuntimeWorkerOwnership.heartbeatAt` / `expiresAt`), which already
records execution liveness. An unexpired lease on the current subtask's workflow
means something is running; its absence means nothing is. The read is read-only
and never reclaims or reconciles.

Two conditions keep this from firing wrongly, and both are contract, not polish:

- Liveness resolves to `unknown` — which never stops the loop — for prose-mode
  goals, which have no runtime lease at all, and whenever the read is not
  meaningful or throws.
- A single idle sample does not stop the loop. Three consecutive idle refreshes
  do, so a normal between-subtask handover cannot end a watch on a healthy goal.

### Loop shape

- `--max-refreshes` default becomes unlimited. `0` is the unlimited sentinel and
  stays accepted as an explicit value; any positive value keeps its current
  meaning as a hard upper bound.
- Every refresh prints its `watch_refresh:` line, including the last one. Today
  the final refresh is suppressed, which is why a one-shot watch shows no
  refresh line at all.
- The sleep happens only when another refresh will follow — not after the
  refresh that ends the loop. A finished goal must not cost the user one extra
  interval before the command returns.
- The final payload gains `stop_reason` with one of `goal_terminal`,
  `max_refreshes`, `not_found`, or — once subtask 3 lands — `goal_idle`, and
  `refresh_count` reports the refreshes actually performed.

### Automation compatibility

The final payload and the goal-derived exit code are only emitted after the loop
ends, so a non-interactive caller that passes no bound now waits for the goal to
reach a terminal state instead of returning immediately.

The blast radius is empty. Every caller in this repo is already covered:

- CLI tests (`CliGoalRuntimeTest.kt:423, 460, 521`) pass `--max-refreshes`
  explicitly.
- Docs and governed skills print human-facing copy-paste blocks, updated by
  subtask 1.
- No MCP tool wraps `watch`; it is CLI-only. Nothing in the repo shells out to it.

One-shot behavior stays reachable for anyone outside the repo who wants it:
`--max-refreshes 1` reproduces the old default exactly, and
`skill-bill goal status <issue_key>` is the purpose-built snapshot command that
the governed skills already point agents at. Note the default change in the
release notes; no migration is required.

## Acceptance Criteria

1. `skill-bill goal watch <issue_key> --interval-seconds N` with no
   `--max-refreshes` prints a refresh line, waits N seconds, and prints the next
   refresh line, repeating until the goal is terminal.
2. The loop stops with `stop_reason: goal_terminal` when the projection reports
   `pending_count == 0`, and with `stop_reason: not_found` when the projection
   reports `status == "not_found"`.
2a. The loop stops with `stop_reason: goal_idle` after three consecutive refreshes
    report execution liveness `idle`; a `live` or `unknown` refresh resets that
    count, and a prose-mode goal never stops through this path.
3. A goal with `blocked_count > 0` and `pending_count > 0` does not stop the
   loop.
4. An explicit `--max-refreshes N` (N > 0) still bounds the loop and reports
   `stop_reason: max_refreshes` when the bound is what ended it;
   `--max-refreshes 1` reproduces the pre-change one-shot behavior.
5. No sleep occurs after the refresh that ends the loop.
5a. A refresh prints only when its rendered text, with `refresh_index` normalized
    out, differs from the last printed refresh; the first refresh and the
    loop-ending refresh always print, and `--show-unchanged` restores a line per
    refresh.
6. The final payload reports `refresh_count` as the number of refreshes actually
   performed, and keeps the existing goal-derived exit code.
7. `--interval-seconds` and `--max-refreshes` validation still loud-fails on
   negative values, and `--max-refreshes 0` is accepted as the unlimited
   sentinel.
8. `watch` remains read-only: no child run is launched and no workflow state is
   mutated on any refresh.
9. Governed skill and doc call sites that print the monitoring command no longer
   pass an arbitrary `--max-refreshes` bound, and state that watch follows until
   the goal finishes.
10. `bill-feature-goal` and `bill-feature-task-runtime` forbid, in imperative
    form, the agent running `goal watch` in-session, polling `goal status` on a
    timer or for change, sleeping to re-read progress, tailing logs or diffs to
    infer progress, and spawning any process or subagent to observe a run
    already in flight.
11. `bill-feature-goal` instructs the agent to launch with `--no-live-output`, so
    the run's progress stream never enters the agent's context.
12. Both skills limit the in-session surface to one completion line, errors, and
    one status call on explicit user request, with no transition relay.
12a. Both skills name the completion signal per launch mode — blocking return
     for foreground runs within the harness timeout, background-exit
     notification where the harness provides one, and end-turn with
     report-on-user-return where it does not — so the polling prohibition and
     the completion-line requirement are jointly satisfiable on every supported
     agent.
13. Both skills require exactly one completion line composed only from the
    structured result fields (`status`, counts, `pull_request_url`,
    `blocked_reason`), and forbid composing it by reading or summarizing run
    stdout.
14. Both skills state that agent silence during the run is deliberate and that
    the monitoring block is the user's live feed.

## Non-Goals

- No TTY detection or interactive/non-interactive default switching.
- No streaming of the final payload per refresh; the payload contract is
  unchanged apart from `stop_reason` and the corrected `refresh_count`.
- No wall-clock timeout option; the debounced idle stop replaces the need for one.
- No goal-level lease, and no crash reconciliation or lease reclaim from `watch`.
- No change to `goal status`, to the projection contract, or to the
  `goal_event:` transition stream.
- No change to what the goal runner itself prints.

## Constraints

- `watch` is read-only. It must not launch child runs or mutate workflow state.
- Terminal detection (subtask 1) derives from the existing
  `GoalRunnerStatusProjection` fields already surfaced in the CLI map, and its
  Kotlin changes stay inside `runtime-cli`.
- Idle detection (subtask 3) adds one additive projection field and one lease
  read, touching `runtime-domain` and `runtime-application`. No new contract
  file, no schema change, and no contract-version bump.
- No ports changes. No writes from any `watch` path.

## Sizing

`decomposed` — three subtasks. Subtask 1 is the Kotlin CLI loop change. Subtasks
2 and 3 both depend on it and are independent of each other:

- Subtask 2 is the governed-skill authoring contract; it depends on subtask 1
  because it drops the `--max-refreshes` bound from the polling example, which
  only becomes correct once `watch` follows until terminal.
- Subtask 3 adds the idle stop; it depends on subtask 1 because it extends that
  subtask's loop, `stop_reason` field, and print/sleep ordering.

## Next Path

```bash
skill-bill goal SKILL-147
```
