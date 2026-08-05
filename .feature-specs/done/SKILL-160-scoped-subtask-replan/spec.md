# SKILL-160 — Scoped subtask replan and resume

## Intended Outcome

An operator whose subtask spec changed mid-goal can discard that one subtask's planning
checkpoint and resume the goal from it, without destroying anything else. Today the only way to
invalidate planning is a goal-wide `--hard` reset, which also clears every completed subtask's
terminal state and every out-of-band acceptance. After this feature:

```bash
skill-bill goal replan SKILL-160 --subtask 3
skill-bill goal replan SKILL-160 --subtask 3 --include-shared-preplan
```

discards exactly the named subtask's plan (optionally also the goal-wide shared preplan), leaves
sibling plans, completed subtask rows, commit SHAs and acceptances untouched, and lets the next
`skill-bill goal <key>` regenerate the missing plan from the amended spec and continue.

## Motivation (observed, not hypothetical)

The workaround this replaces is in active use and is lossy.

A goal amended a subtask spec after that subtask's plan had already been checkpointed. Because
resume reuses immutable planning checkpoints and "earlier branch changes do not regenerate
settled planning", the child would have been hydrated with a plan predating the amendment. The
only available remedy was:

1. `skill-bill goal reset <key> --hard --confirm-issue-key <key>` — which reset all three
   subtasks to `pending` and discarded both completed subtasks' `commit_sha` and `workflow_id`;
2. `skill-bill goal accept <key> --subtask 1 --commit <sha> --restore-after-hard-reset …` and the
   same for subtask 2, to rebuild the state the reset had just thrown away.

Three commands, two of them purely compensating for the first, and a commit-to-subtask mapping
the operator had to reconstruct by reading `git log` — recoverable only because the reset happened
to print the SHAs it was discarding. The existence of `accept --restore-after-hard-reset` is
itself evidence that goal-wide hard reset is being used as a scoped tool and losing state that
then has to be restored by hand.

`~/.skill-bill/` carries roughly a dozen hand-copied `review-metrics.before-*-replan-*.db` /
`*-planning-wipe-*.db` / `*-stale-planning-clear-*.db` backup files, each one an operator taking a
manual database snapshot before forcing a replan. That is the real current interface for this
operation.

## Current State (verified in this repository)

The persistence and planning machinery needed for scoped replan already exists. What is missing
is a delete-only primitive and an operator-facing command.

- `goal_subtask_plans` is keyed by `(parent_goal_workflow_id, subtask_id)`.
  `GoalPlanningPreparationStore.replaceSubtaskPlan` already issues
  `DELETE FROM goal_subtask_plans WHERE parent_goal_workflow_id = ? AND subtask_id = ?` before
  re-inserting, so a per-subtask scoped delete is already proven against this schema.
- `NormalizedGoalPlanningPreparationRepository.firstMissingPlan(identity, orderedDescriptors)`
  returns the first ordered subtask with no stored plan row. **Deleting one plan row is therefore
  sufficient to make the existing planning sweep regenerate exactly that plan** — no new planning
  logic is required.
- `GoalPlanningStatusState` already has `PARTIALLY_PLANNED`, and
  `GoalPlanningStatusSnapshot` already carries `sharedPreplanPrepared`, `plannedSubtaskCount` and
  `totalSubtaskCount`, so `planning: state=partially_planned shared_preplan=true planned=2/3` is
  reportable without a status-model change.
- What does not exist: a delete-only scoped operation. The repository port
  (`GoalPlanningPreparationRepository`) offers `replaceSubtaskPlan` (needs a replacement
  checkpoint in hand), `replaceSharedPreplan(checkpoint, expectedPayloadSha256)`
  (compare-and-replace, not delete) and the legacy goal-wide
  `deleteByGoal(parentGoalWorkflowId)`. There is no `deleteSubtaskPlan` and no shared-preplan
  delete.
- `GoalRunnerResetRequest` (`runtime-application/.../model/GoalRunnerRequests.kt`) actively
  forbids the combination this feature needs. Its `init` block requires
  `(subtaskId != null) == deleteChildWorkflow`, `!deleteChildWorkflow || !hard`, and
  `!preservePlanning || hard`. So `--subtask` is reachable only together with
  `--delete-child-workflow`, and never with `--hard`: there is no expressible "invalidate planning
  for subtask N only" request.
- CLI subcommands live in `runtime-cli/.../goal/GoalCliCommands.kt` as
  `DocumentedCliCommand` subclasses — `GoalRunCommand`, `GoalStatusCommand`, `GoalWatchCommand`,
  `GoalPauseCommand`, `GoalResumeCommand`, `GoalResetCommand`, `GoalFindingsCommand`,
  `GoalAcceptCommand`. A new sibling is the established shape.

### Concurrency constraint, from the code

`replaceSubtaskPlan`'s own contract comment states: *"The caller decides regenerability from a
prior read, so this is not atomic against a concurrent writer; only the single-writer goal
planning sweep may call it."* A scoped-replan command is a second writer to the same rows. It must
therefore refuse to run against a live goal rather than racing the sweep — see acceptance
criterion 6.

## Acceptance Criteria

1. `skill-bill goal replan <issue-key> --subtask <id>` discards only that subtask's stored plan
   checkpoint. The shared preplan, every sibling subtask plan, every subtask's `status`,
   `commit_sha`, `workflow_id` and `last_resumable_step`, and every recorded out-of-band
   acceptance are unchanged.
2. `--include-shared-preplan` additionally discards the goal-wide shared preplan. Without the
   flag the shared preplan is never touched.
3. After a scoped replan, `skill-bill goal status <issue-key>` reports the surviving plan count
   and a reason naming the replanned subtask — for a 3-subtask goal replanned at 3,
   `planning: state=partially_planned shared_preplan=true planned=2/3 current=3`.
4. The next `skill-bill goal <issue-key>` regenerates only the missing plan or plans, from the
   current on-disk spec, and continues into the replanned subtask. It does not re-implement or
   re-open any subtask already terminal.
5. The command prints a before/after snapshot in the style `GoalResetCommand` already uses, naming
   what was discarded, so the operation is auditable from the terminal alone.
6. `replan` refuses with a clear, non-mutating failure when the goal is live — an active child
   run or non-idle execution liveness — rather than writing concurrently with the single-writer
   planning sweep.
7. `replan` refuses to target a subtask whose status is already terminal (complete, including
   out-of-band accepted), and the message names `reset` as the operation for that case. Nothing is
   mutated on refusal.
8. `replan` refuses an unknown issue key, a non-positive subtask id, and a subtask id absent from
   the goal's manifest, each with a distinct message and no mutation.
9. `GoalRunnerResetRequest`'s existing invariants are unchanged, and `reset`'s behaviour —
   `--hard`, `--preserve-planning`, `--subtask` + `--delete-child-workflow` — is byte-for-byte
   unchanged. This feature adds a command; it does not relax the reset gate.
10. The governed skill content (`bill-feature-goal.md`, and `bill-feature`'s routing rules where
    they describe planning invalidation) tells an agent to reach for scoped replan when a spec is
    amended mid-goal, instead of hard reset plus compensating accepts.
11. The repository's own check gate passes, with tests covering scoped invalidation, the
    shared-preplan opt-in, each refusal path, and the status projection after replan.

## Non-Goals

- Changing what `reset --hard` does, or relaxing `GoalRunnerResetRequest`'s invariants. Hard reset
  stays the goal-wide sledgehammer; replan is the scoped tool that stops people reaching for it.
- Removing `accept --restore-after-hard-reset`. It stays for the cases hard reset is still right
  for, and for already-reset goals in flight.
- Automatic replan detection. This feature does not hash specs, watch the filesystem, or decide on
  its own that a checkpoint is stale — the operator names the subtask. Spec-drift detection is a
  separate, larger question.
- Editing `decomposition-manifest.yaml` to force progress. The durable store stays authoritative
  and the manifest stays a read-only-safe projection.
- Mid-run replan. `replan` is an out-of-band operator action on an idle goal, not something a
  running child can invoke on itself.
- Replanning a terminal subtask, or reopening completed work. Criterion 7 rejects it.

## Flagged During Preparation

- **Planning regenerates plans for already-complete subtasks.** After a goal-wide hard reset the
  sweep replans every subtask, including ones restored to `complete` by `accept`, because
  `firstMissingPlan` walks all ordered descriptors and does not consult subtask status. It is
  wasted planning time rather than a correctness bug, and scoped replan avoids it by never
  deleting those rows in the first place. Skipping planning for terminal subtasks is a separate
  optimisation and is out of scope here.
- **Command placement.** `replan` is specified as a new sibling subcommand rather than a
  `reset --replan-subtask` flag. Reset's `init` invariants are load-bearing and already encode
  four mutually-exclusive shapes; adding a fifth that inverts
  `require(!deleteChildWorkflow || !hard)` would make that gate materially harder to reason
  about, and `CliGoalResetOptionGateTest` exists to pin it. A distinct verb also reads correctly
  in the skill guidance criterion 10 has to write.
- **`--include-shared-preplan` and already-complete subtasks.** The shared preplan governs every
  subtask, including terminal ones, and `replaceSharedPreplan` enforces "provenance parity with
  the governing shared preplan" for subtask plans. Discarding and regenerating it can therefore
  leave surviving sibling plans provenance-mismatched against the new preplan. Subtask 2 owns
  resolving this explicitly — either by requiring that the opt-in also invalidates every
  non-terminal sibling plan, or by rejecting the combination when a surviving plan would be
  orphaned. It must not be left to be discovered at runtime as a wedged goal.

## Decomposition

`decomposed` — three dependency-ordered subtasks:

1. **Scoped per-subtask replan** — the delete-only primitive, the `replan` command, the refusal
   paths and the status projection. Complete and useful on its own.
2. **Opt-in shared-preplan invalidation** — `--include-shared-preplan`, plus the provenance-parity
   rule flagged above. Depends on 1 for the command and its gates.
3. **Operator guidance in the governed skills** — teach the skills to use it. Depends on 1 and 2,
   since the guidance must describe the flags as shipped.

## Validation Strategy

- The repository's own check gate, per project convention.
- Unit coverage on the new persistence primitive against `goal_subtask_plans`, asserting sibling
  and shared-preplan rows survive a scoped delete.
- `CliGoalResetOptionGateTest` must pass unmodified — it is the regression net for criterion 9.
- End-to-end on a real 3-subtask goal: run to subtask 3, amend subtask 3's spec, `replan
  --subtask 3`, confirm `status` shows `planned=2/3` with subtasks 1–2 still `complete` with their
  SHAs, relaunch, and confirm the regenerated plan reflects the amendment.
- Refusal paths exercised against a live goal, a terminal subtask, and an unknown key, each
  asserted to mutate nothing.

## Next Path

```bash
skill-bill goal SKILL-160
```
