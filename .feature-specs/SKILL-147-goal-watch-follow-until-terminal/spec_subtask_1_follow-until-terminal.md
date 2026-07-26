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

## Acceptance Criteria (this subtask)

1. `DEFAULT_GOAL_WATCH_REFRESHES` is `0`, `0` means unlimited, and
   `require(maxRefreshes >= 0)` replaces the current positive-only check while
   still loud-failing on negative input.
2. The loop performs a refresh, prints its `watch_refresh:` line, then stops
   without sleeping when the refresh is terminal or the explicit bound is
   reached; otherwise it sleeps `intervalSeconds` and refreshes again.
3. Terminal detection returns true when the refresh map has
   `status == "not_found"`, or when `pending_count == 0`; it returns false when
   `pending_count > 0`, including when `blocked_count > 0`.
4. The final payload carries `stop_reason` of `goal_terminal`, `not_found`, or
   `max_refreshes`, and `refresh_count` equal to the refreshes performed.
5. The existing goal-derived exit code (`goalStatusExitCode`) is unchanged.
6. A CLI test asserts a multi-refresh follow run stops on the first terminal
   projection without consuming its remaining interval.
7. A CLI test asserts `--max-refreshes 1` still yields exactly one refresh and
   the pre-change payload shape plus `stop_reason: max_refreshes`.
8. A CLI test asserts a blocked-but-pending projection does not stop the loop.
9. A CLI test asserts no child run is launched across a multi-refresh follow run,
   extending the existing read-only assertion.
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
