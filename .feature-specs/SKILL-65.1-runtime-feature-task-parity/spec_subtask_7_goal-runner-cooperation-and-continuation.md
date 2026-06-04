# SKILL-65.1 · Subtask 7 — Goal-Runner Cooperation and Continuation Entry

Parent: [SKILL-65.1 full parity](./spec.md)
Issue key: SKILL-65.1
Status: Draft

## Scope

Make the runtime cooperate with the `bill-feature-goal` goal runner the way
`bill-feature-task`'s goal-continuation entry does (`SKILL.md:84-89`): a
non-interactive entry that runs exactly one decomposed subtask, continues on the
branch the goal already created, skips its own decomposition, and suppresses the
PR so the goal opens a single PR for the whole goal. This subtask ties together
the hooks from subtasks 1, 3, and 5 and is the closing integration of the parity
feature.

## Acceptance Criteria

1. A non-interactive goal-continuation context can be supplied to a runtime run
   (parent `issue_key` + `subtask_id` + the goal-driven branch + a
   `suppress_pr` signal), analogous to the standard flow's `goal_continuation`
   artifact.
2. Under goal continuation the runtime:
   - **reuses the existing branch** the goal created (via subtask 1's reuse path)
     and never creates its own;
   - **skips decomposition** (via subtask 5's gate) and implements the single
     governed subtask spec;
   - **suppresses the `pr` phase** (via subtask 3's hook) and treats a completed
     `commit_push` as the terminal success signal.
3. The structured subtask outcome (`issue_key`, `subtask_id`, `status`,
   `commit_sha`, `workflow_id`, `blocked_reason`, `last_resumable_step`) is
   persisted in durable runtime state and returned to the goal runner; stdout is
   diagnostic only.
4. Interactive `bill-feature-task-runtime` behavior is unchanged: a direct user
   run still creates its own branch, may decompose, and opens a PR. The
   goal-continuation behavior activates only when the context is present.
5. The runtime never runs installer/uninstall/install-sync flows during
   goal-continuation (matching the standard flow's prohibition), recording any
   skipped install-sync in phase results.
6. An end-to-end integration validation gate demonstrates a goal-driven run:
   pre-created branch reused, no decomposition, PR suppressed, `commit_push`
   terminal — and a direct run: own branch, PR opened.

## Non-Goals

- No change to `bill-feature-goal` or the goal runner itself; this subtask makes
  the runtime *consumable* by it.
- No multi-subtask orchestration inside one runtime run — the runtime still runs
  exactly one subtask per goal-continuation entry.

## Dependency Notes

- Depends on: subtask 1 (branch reuse), subtask 3 (PR suppression + commit
  terminal), subtask 5 (decomposition skip).
- This is the terminal integration subtask; subtask 6's finished telemetry should
  reflect the suppress-PR terminal outcome.

## Validation Strategy

- Tests for goal-continuation activation: branch reused (not created),
  decomposition skipped, PR suppressed, `commit_push` terminal, structured
  outcome persisted/returned.
- Tests confirming interactive runs are unaffected (branch created, PR opened).
- End-to-end gate exercising both a goal-driven and a direct run.
- `(cd runtime-kotlin && ./gradlew check)` and `skill-bill validate` pass.

## Next path

This is the final subtask. Run `skill-bill goal SKILL-65.1` to execute the
decomposition in dependency order.
