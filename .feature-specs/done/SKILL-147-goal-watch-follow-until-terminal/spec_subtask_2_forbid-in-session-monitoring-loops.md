# SKILL-147 · Subtask 2 — Forbid in-session monitoring loops

Parent spec: `.feature-specs/SKILL-147-goal-watch-follow-until-terminal/spec.md`

## Scope

Turn the existing descriptive progress-visibility guidance into an explicit
prohibition: an agent running a governed feature skill must never poll a running
execution from inside its own session. Watching is the user's terminal, not the
agent's context.

The motivation is token cost, and the mechanism is worth stating precisely
because it is not what it looks like.

The expensive part is not the bytes a poll returns. It is that every tool call
re-sends the entire conversation, and the conversation is larger on each
successive call — each poll's output permanently inflates every request that
follows it. Prompt caching discounts the re-sent prefix but does not eliminate
it, so a poll loop's cost still compounds superlinearly: 60 refreshes are 60
full-context requests, each bigger than the one before.

The alternative costs one request: a single completion signal — a blocking call
that returns, or one background-exit re-invocation — delivers the outcome when
the run ends, however long that takes (see Completion Signal below for which
signal applies where). The loop buys nothing the agent could act on — it cannot
intervene mid-run — and pays a compounding price for it.

Files in scope:

- `skills/bill-feature-goal/content.md` — the "Watching Long Runs (orchestrator
  pattern)" section (~line 228), the live-polling example (~line 306), and the
  "Keep live output enabled unless the user asks for quieter output" sentence
  (~line 227), which directly contradicts the quiet-launch posture and must be
  replaced by the `--no-live-output` instruction.
- `skills/bill-feature-task-runtime/content.md` — the "Progress Visibility"
  section (~line 91).

## Forbidden Patterns

State these as a prohibition, not a preference. While a foreground or detached
run is in flight, the agent must not:

1. Run `skill-bill goal watch` in-session, at any interval or refresh count.
2. Call `skill-bill goal status` on a timer, or repeatedly to observe change.
   One status call answering a direct user question is fine; a second call whose
   only purpose is to see whether the first changed is a loop.
3. Sleep, wait, or otherwise idle in order to re-read progress.
4. Tail, poll, or re-read runtime logs, the workflow DB, `git diff`, or changed
   files to infer progress.
5. Re-invoke the runtime, or launch a background process, to observe a run that
   is already executing.

The prohibition binds regardless of how the loop is expressed — a shell loop, a
scheduled wake-up, repeated tool calls, or a subagent spawned to watch.

The test is **request count, not output size**. A "cheap" poll that returns two
lines still costs a full re-send of the conversation, so trimming what a poll
returns does not make polling acceptable. Prefer one long blocking call over any
number of short ones.

## Quiet Launch

The agent launches the goal with `--no-live-output`
(`GoalCliCommands.kt:111`). Everything the run prints to stdout is captured and
lands in the agent's context when the call returns, paid for in full whether or
not anything reads it.

The saving is modest but unbounded-growing: the heartbeat pair fires every 90
seconds (`DEFAULT_STATUS_HEARTBEAT_INTERVAL`,
`runtime-kotlin/runtime-infra-fs/src/main/kotlin/skillbill/launcher/process/AgentRunProcessRunner.kt:117`),
so a three-hour goal accrues a few hundred lines and a longer one proportionally
more. It costs nothing to suppress
once `goal watch` gives the user a real live feed, which is the trade subtask 1
makes possible.

The user's live view is `skill-bill goal watch` in their own terminal — which is
why subtask 1 exists, and why the required monitoring block is the thing that
makes this posture acceptable rather than a regression.

## Completion Signal

The prohibition is only satisfiable if the skills name how the agent learns the
run finished, because the "one blocking call" does not exist for long runs:
agent harnesses cap foreground tool calls (Claude Code kills a foreground
command at 10 minutes), which is why the goal skill already instructs a
detached launch when timeout risk exists. Both skills must state the sanctioned
signal per launch mode:

1. **Foreground, within the harness timeout** — the blocking call returns the
   structured result; compose the completion line from it.
2. **Detached, harness with background-completion notification** — the harness
   re-invokes the agent when the background process exits (Claude Code's
   background tasks do this); that single re-invocation carries the result. One
   signal, zero polls.
3. **Detached, harness without such notification** — the agent prints the
   monitoring block, states that the run continues in the background, and ends
   its turn. The completion line is emitted when the user next addresses the
   session, answered from one `goal status` call.

In every mode the completion-line obligation binds when the run's outcome
reaches the session — never through polling, and mode 3's deferred report is
not a loophole for checking "just once" mid-run.

## Permitted In-Session Surface

The agent's conversation surface for a run is:

1. **A single completion line**, emitted when the call returns.
2. **Errors** — a launch failure, a loud-fail, or a non-zero exit.
3. **One status call on explicit user request**, answered from a single
   `skill-bill goal status` invocation.

There is no in-session transition relay. With `--no-live-output` the agent has no
transitions to relay, and it must not acquire any.

## The Completion Line

A run that finishes with nothing appearing in the session reads as broken. One
line prevents that, and one line is all that is permitted.

Compose it **only** from the structured fields the run already returns
(`goalRunText`, `GoalCliCommands.kt:800-815`): `status`, the completed / pending
/ blocked counts, `pull_request_url`, and `blocked_reason` where present. For
example:

```text
goal SKILL-146: complete — 3/3 subtasks, PR https://github.com/…/pull/241
goal SKILL-146: blocked at subtask 2 — <blocked_reason>
goal SKILL-146: failed — <reason>
```

Explicitly forbidden: reading back, summarizing, or paraphrasing the run's stdout
to produce this line. The structured fields are sufficient and bounded; the
stream is neither. If the user wants detail, they ask, and the agent answers with
one `goal status` call.

### Reconciliation note

`skills/bill-feature-goal/content.md:232` currently reads that the agent "does
not relay transitions into the conversation". That stays correct under this
design and needs no loosening — with a quiet launch there are no in-context
transitions to relay. Extend the surrounding text so it is clear the agent is
nonetheless required to emit the completion line, and that silence during the
run is deliberate rather than a failure.

## Acceptance Criteria (this subtask)

1. `skills/bill-feature-goal/content.md` states the five forbidden patterns as a
   prohibition, in imperative form, in the "Watching Long Runs" section.
2. `skills/bill-feature-task-runtime/content.md` states the same prohibition in
   its "Progress Visibility" section, consistent in wording with the goal skill.
3. `skills/bill-feature-goal/content.md` instructs the agent to launch with
   `--no-live-output`, states the token-cost reason in one sentence, and the
   contradicting "Keep live output enabled" sentence (~line 227) is removed.
4. Both skills state the permitted in-session surface as the three allowed items,
   state that there is no in-session transition relay, and state the cost rule as
   request count rather than output size — one completion signal beats any number
   of short polls, and trimming a poll's output does not make polling acceptable.
4a. Both skills name the completion signal per launch mode as specified in the
    Completion Signal section: blocking return within the harness timeout,
    background-exit re-invocation where the harness provides one, and end-turn
    with report-on-user-return where it does not.
5. Both skills require exactly one completion line, composed only from the
   structured result fields (`status`, counts, `pull_request_url`,
   `blocked_reason`), and give the three worked examples for complete, blocked,
   and failed.
6. Both skills forbid reading back, summarizing, or paraphrasing run stdout to
   compose that line.
7. Both skills state that the terminal monitoring block is the user's live feed
   and that agent silence during the run is deliberate, not a failure.
8. The live-polling example at `skills/bill-feature-goal/content.md:306` is
   reframed as a user-terminal command, not something the agent runs, and drops
   its `--max-refreshes` bound per subtask 1.
9. The `content.md:232` "does not relay transitions" line is extended per the
   reconciliation note, and no remaining sentence in either skill contradicts the
   quiet-launch posture.
9a. The `--monitor` instruction at
    `skills/bill-feature-task-runtime/content.md:88` is retained, and both skills
    state why quiet launch applies to `goal` only: `--monitor` scales with phase
    count, goal live output scales with wall-clock duration.
10. `skill-bill validate`, `npx --yes agnix --strict .`, and
    `scripts/validate_agent_configs` pass.
11. `./install.sh` is run so the local agent install picks up the new staging
    hash.

## `--monitor` on feature-task-runtime stays

`skills/bill-feature-task-runtime/content.md:88` instructs the agent to pass
`--monitor`. Keep that instruction. It is not the same case as goal live output,
and the difference is what the two scale with:

- `--monitor` emits six discrete lifecycle events
  (`FeatureTaskRuntimeRunModels.kt:216-267`: `RunStarted`, `BranchResolved`,
  `BranchSetupBlocked`, `PhaseStarted`, `PhaseFixLoopIteration`,
  `PhaseCompleted`) — roughly two lines per phase, 15-30 lines per run. Scales
  with **phase count**.
- Goal live output adds a heartbeat pair every 90 seconds
  (`AgentRunProcessRunner.kt:117`). Scales with **wall-clock duration**, so a
  long goal accumulates a few hundred lines with no upper bound.

`feature-task` also has no `watch` equivalent, so dropping `--monitor` would
leave the user with no live view in exchange for a saving too small to measure.

The quiet-launch rule therefore applies to `goal` only. State that asymmetry
explicitly in both skills so the inconsistency reads as deliberate rather than an
oversight, and so nobody "fixes" it later by suppressing `--monitor`.

## Non-Goals

- No runtime or CLI enforcement. This is a governed authoring contract, not a
  guard rail the runtime can check; there is no reliable seam to detect an agent
  polling itself.
- No new validator rule. Prose prohibitions are not machine-checkable here, and
  a keyword validator would produce false positives on the legitimate
  user-facing monitoring blocks.
- No change to `bill-feature-task-prose`. Prose mode runs the phase loop
  in-session, so there is no separate execution to poll.
- No change to what the runtime prints, to `--monitor`, or to the required
  monitoring-block ceremony.

## Dependency Notes

Depends on subtask 1: criterion 5 drops the `--max-refreshes` bound from the
polling example, which is only correct once `watch` follows until terminal.
Order subtask 1 first.

## Validation Strategy

```bash
skill-bill validate
npx --yes agnix --strict .
scripts/validate_agent_configs
./install.sh
```

Manual check: launch a goal, confirm the agent prints the monitoring block, then
stays silent until the run returns a terminal outcome or an error.

## Next Path

```bash
skill-bill goal SKILL-147
```
