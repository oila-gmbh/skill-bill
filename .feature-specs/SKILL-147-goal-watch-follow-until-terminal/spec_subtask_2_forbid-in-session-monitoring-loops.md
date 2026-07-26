# SKILL-147 · Subtask 2 — Forbid in-session monitoring loops

Parent spec: `.feature-specs/SKILL-147-goal-watch-follow-until-terminal/spec.md`

## Scope

Turn the existing descriptive progress-visibility guidance into an explicit
prohibition: an agent running a governed feature skill must never poll a running
execution from inside its own session. Watching is the user's terminal, not the
agent's context.

The motivation is token cost. Every poll's output lands in the agent's context
window and is paid for on that request and every request after it. A 60-refresh
watch loop buys the agent nothing it could act on — the foreground call already
returns the outcome — while permanently inflating the transcript.

Files in scope:

- `skills/bill-feature-goal/content.md` — the "Watching Long Runs (orchestrator
  pattern)" section (~line 228) and the live-polling example (~line 306).
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

## Permitted In-Session Surface

The governing line is **push, not pull**: relay a transition the agent already
has; never spend a call to go get one.

The agent's conversation surface during a run is:

1. **Phase transitions that arrive without a call.** When the run is in the
   foreground and the runtime's transition output is already in the agent's
   context, relay it — briefly, one line per transition, no embellishment. This
   costs nothing beyond what was already paid.
2. **Terminal outcome** — what the foreground call returns when it returns:
   complete, blocked, or failed, reported once.
3. **Errors** — a launch failure, a loud-fail, or a non-zero exit, reported when
   it happens.
4. **One status call on explicit user request**, answered from a single
   `skill-bill goal status` invocation.

If a transition is not already in context — a detached or background run, or any
case where obtaining it needs another call — the agent does not go and fetch it.
The user's live feed is the terminal monitoring block the skill is already
required to print. Silence during a detached run is correct behavior, and the
skill says so, so the absence of relayed transitions is not read as a fault.

### Reconciliation note

`skills/bill-feature-goal/content.md:232` currently reads that the agent "does
not relay transitions into the conversation". That is stricter than intended and
must be amended, not preserved: transitions the agent already has are worth
relaying, and the user asked for them. Rewrite the line so it forbids
*poll-driven* relay — going out to fetch transitions and paraphrasing them back
— while permitting zero-cost relay of transitions already in context. The
prohibition is on the loop, not on the telling.

## Acceptance Criteria (this subtask)

1. `skills/bill-feature-goal/content.md` states the five forbidden patterns as a
   prohibition, in imperative form, in the "Watching Long Runs" section.
2. `skills/bill-feature-task-runtime/content.md` states the same prohibition in
   its "Progress Visibility" section, consistent in wording with the goal skill.
3. Both skills state the permitted in-session surface as the four allowed items,
   lead it with the push-not-pull rule, and state the token-cost reason in one
   sentence.
4. Both skills state that transitions already in the agent's context from a
   foreground run are relayed one line each, and that transitions requiring an
   extra call are not fetched.
5. Both skills state that the terminal monitoring block is the user's live feed
   for detached runs, and that agent silence during such a run is correct.
6. The live-polling example at `skills/bill-feature-goal/content.md:306` is
   reframed as a user-terminal command, not something the agent runs, and drops
   its `--max-refreshes` bound per subtask 1.
7. The `content.md:232` "does not relay transitions" line is amended to forbid
   poll-driven relay while permitting zero-cost relay, and no remaining sentence
   in either skill contradicts that.
8. `skill-bill validate`, `npx --yes agnix --strict .`, and
   `scripts/validate_agent_configs` pass.
9. `./install.sh` is run so the local agent install picks up the new staging
   hash.

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
