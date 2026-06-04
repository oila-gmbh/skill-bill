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
7. **Subtask-timeout resumability** (observed in the first SKILL-65.1 run): a
   goal-driven runtime subtask that hits the goal runner's per-subtask
   wall-clock budget must leave durable, phase-accurate resumable state and, on
   the goal's next attempt, **continue from `last_resumable_step` without redoing
   already-completed phases** — net forward progress per attempt, no busy-loop
   restart from `preplan`. If a single subtask genuinely cannot finish inside one
   budget window, the budget must be either configurable per goal/subtask or
   large enough for a real feature-task subtask, and a timeout must surface a
   clear, actionable `blocked_reason` (it already does) **plus** a resumable
   workflow row the next attempt advances from rather than repeats.
8. **Terminal-result envelope tolerance** (observed in ZERO-89 on 2026-06-04):
   when a goal-driven child agent exits after doing useful work but omits the
   required `RESULT:` prefix around otherwise valid terminal JSON, the parent
   runner must not collapse the subtask into an opaque
   `no_terminal_store_outcome` block. The runtime/launcher must recover or
   classify the child output using a typed fallback path:
   - first trust durable workflow state;
   - if the workflow is terminal, use that as the subtask outcome regardless of
     stdout;
   - if the workflow is non-terminal but resumable, persist a precise
     `blocked_reason` naming the missing `RESULT:` envelope and keep
     `last_resumable_step` accurate so the next attempt resumes the phase;
   - if the final stdout/stderr contains a single top-level JSON object matching
     the declared subagent return contract but missing only the `RESULT:` label,
     parse it as a recovery candidate, record `recovered_missing_result_prefix`
     in the ledger, persist the corresponding phase artifact, and continue
     instead of losing the completed work.
9. The goal runner's attempt ledger distinguishes at least these child-exit
   failure classes: `missing_result_prefix`, `malformed_result_json`,
   `no_terminal_workflow_state`, and `child_process_failed`. Each class includes
   the workflow id, current step, exit status, whether a recoverable JSON object
   was found, and the next safe action (`resume`, `retry_same_step`, or
   `manual_fix`). A generic `no_terminal_store_outcome` may remain as a
   compatibility summary, but it must no longer be the only diagnostic.

## Observed Issue — first run (2026-06-04)

The first `skill-bill goal SKILL-65.1 --agent claude` run created the feature
branch and progressed `preplan -> plan -> implement -> review` (with a couple of
implement/review cycles), then stopped: `subtask 1 stopped (timeout)` at
`current_step=review`, `workflow_id=wfl-20260604-075221-eylh`,
`last_resumable_step=review`. Liveness was `file_activity` throughout, so the
child was making progress, not hung — the per-subtask time budget simply
expired before a terminal workflow-store outcome. AC 7 above exists to make this
case resume-safe (forward progress on the next attempt) rather than a restart.

## Observed Issue — ZERO-89 missing RESULT envelope (2026-06-04)

During `skill-bill goal ZERO-89 --agent codex`, subtask 2 (`runtime-wiring`)
completed implementation, hit review findings, spawned a fix-loop child, and the
child applied the requested fixes. The child final message was structured JSON
with `tasks_completed`, changed files, and validation (`./gradlew
:core:data:jvmTest` and `./gradlew :core:data:spotlessCheck` passed), but it was
not wrapped in the required `RESULT:` block. The parent child activation exited
with status 1, the durable workflow was still at `review`, and reconciliation
recorded `final_reconciled_result=no_terminal_store_outcome`. The goal then
blocked even though the worktree contained the accepted fix and the next safe
action was to resume the same workflow from `review`.

Root cause: the goal runner and launcher treated the final `RESULT:` envelope as
the only machine-readable child terminal signal when the durable workflow had
not yet reached a terminal subtask outcome. That is too brittle for real agent
runtimes: a provider can return valid JSON without the label, truncate wrapper
text, or otherwise violate the envelope while still leaving useful durable state
and file changes. The core fix is to make durable workflow state authoritative
and add a narrow, schema-checked recovery parser for the exact
missing-prefix-only case. Recovery must be explicit and auditable; malformed JSON
or ambiguous multiple JSON objects should still block with a precise resumable
reason rather than guessing.

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
