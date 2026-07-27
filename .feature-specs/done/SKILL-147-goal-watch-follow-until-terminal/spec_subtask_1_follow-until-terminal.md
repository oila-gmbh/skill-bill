# SKILL-147 · Subtask 1 — Follow-until-terminal watch loop

Parent spec: `.feature-specs/SKILL-147-goal-watch-follow-until-terminal/spec.md`

## Scope

Rework the `goal watch` loop in
`runtime-kotlin/runtime-cli/src/main/kotlin/skillbill/cli/goal/GoalCliCommands.kt`
so it follows the goal until a terminal state, and update the governed call sites
that print the monitoring command.

In scope:

- `GoalWatchCommand.run()` loop: unlimited default, terminal detection, per-refresh
  live print, sleep only before a refresh that will actually happen.
- `DEFAULT_GOAL_WATCH_REFRESHES` becomes the unlimited sentinel `0`.
- A private terminal predicate over the CLI refresh map, reading `status`,
  `pending_count`, and nothing else.
- Change-only rendering: suppress a refresh whose rendered text is identical to
  the last printed one, plus a `--show-unchanged` escape hatch.
- Payload: add `stop_reason`, correct `refresh_count` to the performed count.
- `--max-refreshes` help text: describe the unlimited default and the `0`
  sentinel.
- Call-site updates: `skills/bill-feature-goal/content.md` (the required
  copy-pasteable monitoring block near line 244, the live-polling example near
  line 309, and the `--diff-stat` example near line 324),
  `docs/getting-started.md` line 441, `docs/getting-started-for-teams.md`
  line 85.
- Tests in `runtime-kotlin/runtime-cli/src/test/kotlin/skillbill/cli/CliGoalRuntimeTest.kt`.

Out of scope: everything listed under the parent spec's Non-Goals.

## Change-Only Rendering

A follow-until-terminal watch at a short interval repeats the same line for as
long as a phase runs:

```text
watch_refresh: index=51 status=ok current_subtask=1 current_step=review liveness=workflow_status=running; step=review
watch_refresh: index=52 status=ok current_subtask=1 current_step=review liveness=workflow_status=running; step=review
…
watch_refresh: index=57 status=ok current_subtask=1 current_step=validate liveness=workflow_status=running; step=validate
```

Only index 57 carries information. Print a refresh only when it differs from the
last one printed.

### Comparison rule

Compare the **rendered refresh text** with `refresh_index` normalized out, not a
hand-picked field list. Identical rendering means identical information, so this
stays correct as `goalWatchRefreshText` gains lines, and it needs no upkeep when
new fields are added.

It also handles the diff surfaces for free: with `--diff-stat` or `--diff-hunk`,
a changing diff changes the rendered text and prints; an unchanged diff does not.

### Always print

Suppression never hides an edge:

- the **first** refresh, so the command shows something immediately;
- the refresh that **ends the loop**, whatever the stop reason, so the last
  rendered state is always visible.

### Escape hatch

`--show-unchanged` restores a line per refresh. It exists for debugging the watch
loop itself and for anyone who wants a visible pulse; it is not the default.

### On the quiet gap

With change-only rendering a long phase prints nothing for minutes. That is
correct and must not be softened with a synthetic keepalive line, which would
reintroduce exactly the noise being removed. A goal that has genuinely stopped is
caught by the idle stop in subtask 3, which ends the loop and says so — so
silence means running, and stopped means exited.

## Acceptance Criteria (this subtask)

1. `DEFAULT_GOAL_WATCH_REFRESHES` is `0`, `0` means unlimited, and
   `require(maxRefreshes >= 0)` replaces the current positive-only check while
   still loud-failing on negative input.
2. The loop performs a refresh, prints its `watch_refresh:` line subject to
   criterion 3a, then stops without sleeping when the refresh is terminal or the
   explicit bound is reached; otherwise it sleeps `intervalSeconds` and refreshes
   again.
3. Terminal detection returns true when the refresh map has
   `status == "not_found"`, or when `pending_count == 0`; it returns false when
   `pending_count > 0`, including when `blocked_count > 0`.
3a. A refresh is printed only when its rendered text, with `refresh_index`
    normalized out, differs from the last printed refresh. The first refresh and
    the refresh that ends the loop are always printed.
3b. `--show-unchanged` disables suppression and prints every refresh; it defaults
    to off and is documented as a debugging aid.
3c. No synthetic keepalive or "still running" line is emitted during a suppressed
    run.
4. The final payload carries `stop_reason` of `goal_terminal`, `not_found`, or
   `max_refreshes`, and `refresh_count` equal to the refreshes performed —
   counting refreshes performed, not refreshes printed.
5. The existing goal-derived exit code (`goalStatusExitCode`) is unchanged.
6. A CLI test asserts a multi-refresh follow run stops on the first terminal
   projection without consuming its remaining interval.
7. A CLI test asserts `--max-refreshes 1` still yields exactly one refresh and
   the pre-change payload shape plus `stop_reason: max_refreshes`.
8. A CLI test asserts a blocked-but-pending projection does not stop the loop.
9. A CLI test asserts no child run is launched across a multi-refresh follow run,
   extending the existing read-only assertion.
9a. A CLI test asserts a run of identical projections prints one line, that the
    line prints again when `current_step` changes, and that `--show-unchanged`
    prints every refresh.
9b. A CLI test asserts the loop-ending refresh prints even when identical to the
    previous one.
10. The governed skill and doc call sites print
    `skill-bill goal watch <issue_key> --interval-seconds 5` with no
    `--max-refreshes`, and state that watch follows until the goal finishes.
11. `skill-bill validate`, `(cd runtime-kotlin && ./gradlew check)`,
    `npx --yes agnix --strict .`, and `scripts/validate_agent_configs` pass.

## Non-Goals

Same as the parent spec.

## Dependency Notes

No dependencies. Single subtask; nothing else in the repo blocks or is blocked by
it.

Test fixtures must drive terminal detection through the projection the CLI already
reads, so the existing in-memory goal test doubles in `CliGoalRuntimeTest` are the
seam — no new port or fake is needed.

Because `Thread.sleep` stays in the loop, keep test interval values at `0` so the
suite does not pay wall-clock time; the sleep is already guarded by
`intervalSeconds > 0`.

## Validation Strategy

```bash
(cd runtime-kotlin && ./gradlew :runtime-cli:test --tests '*CliGoalRuntimeTest*')
skill-bill validate
(cd runtime-kotlin && ./gradlew check)
npx --yes agnix --strict .
scripts/validate_agent_configs
```

Manual check against a live goal:

```bash
skill-bill goal watch SKILL-146 --interval-seconds 60
```

Expect a refresh line every 60 seconds and a clean exit once the goal is terminal.

Run `./install.sh` after the `skills/bill-feature-goal/content.md` edit so the
local agent install picks up the new staging hash.

## Next Path

```bash
skill-bill goal SKILL-147
```
