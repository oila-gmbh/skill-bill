# SKILL-160 · Subtask 1 — Scoped per-subtask replan

Parent spec: `.feature-specs/SKILL-160-scoped-subtask-replan/spec.md`

## QA Statement

An operator who amended one subtask's spec after that subtask was already planned can run
`skill-bill goal replan <key> --subtask <id>`, see a printed before/after confirming only that
subtask's plan was discarded, see `status` report the surviving plans and completed subtasks
untouched, and relaunch the goal so it replans that subtask from the amended spec and continues —
without any hard reset and without re-accepting anything.

## Scope

### 1. Delete-only persistence primitive

Add a scoped plan deletion to `NormalizedGoalPlanningPreparationRepository`
(`runtime-kotlin/runtime-ports/src/main/kotlin/skillbill/ports/persistence/GoalPlanningPreparationRepository.kt`),
following the existing convention there: a default implementation that `error(...)`s with a
"not implemented by this repository" message, so `EmptyGoalPlanningPreparationRepository` in
`runtime-ports/src/testFixtures/` keeps compiling by overriding it to `Unit` like its siblings.

Implement it in `GoalPlanningPreparationStore`
(`runtime-kotlin/runtime-infra-sqlite/src/main/kotlin/skillbill/db/workflow/GoalPlanningPreparationStore.kt`)
as `DELETE FROM goal_subtask_plans WHERE parent_goal_workflow_id = ? AND subtask_id = ?`. The
statement already exists inside `replaceSubtaskPlan`; reuse the same shape, the same
`translateSqlFailure` error handling, and the same identity validation the neighbouring methods
apply. Return the deleted row count so the caller can distinguish "discarded a plan" from "there
was no plan to discard" and report accordingly.

Do not route this through `replaceSubtaskPlan`. That method requires a replacement checkpoint the
operator does not have, and its provenance-parity enforcement is meaningless for a deletion.

### 2. Application-level replan operation

Add a request/response pair alongside the existing ones in
`runtime-kotlin/runtime-application/src/main/kotlin/skillbill/application/model/GoalRunnerRequests.kt`,
mirroring how `GoalRunnerResetRequest`/`GoalRunnerResetSubtaskSnapshot` are shaped — an `init`
block carrying the invariants, and a response exposing a before/after snapshot list so the CLI can
print it.

**Do not touch `GoalRunnerResetRequest`.** Its `init` invariants and
`CliGoalResetOptionGateTest` are the regression net for the parent spec's criterion 9. This is a
new request type, not a widened one.

The operation, on an idle goal, in one durable write:

- resolve the parent goal workflow and its ordered subtask descriptors from the authoritative
  store, not from the on-disk manifest;
- delete the named subtask's plan row;
- leave the shared preplan, every sibling plan row, every subtask's `status`, `commit_sha`,
  `workflow_id`, `blocked_reason` and `last_resumable_step`, and every out-of-band acceptance
  exactly as they were;
- set the goal's current subtask intent to the replanned subtask so the continuation selector
  resumes there, using the same intent-writing path the runtime already uses rather than a new one.

Nothing else changes. In particular the subtask's child workflow row is not deleted — that is
`reset --subtask … --delete-child-workflow`'s job, and conflating them would reintroduce the
state loss this feature exists to remove.

### 3. Refusal paths

Every refusal must be non-mutating and must not leave a partial write. Prefer a single
pre-flight validation before the durable write opens.

- **Live goal.** Refuse when the goal has an active child run or non-idle execution liveness.
  `ExecutionLiveness` (`runtime-domain/.../GoalRunnerModels.kt`) already models `LIVE`/`IDLE`/
  `UNKNOWN`, and `GoalRunnerStatusService` already computes it — reuse that determination rather
  than inventing a second liveness rule. Treat `UNKNOWN` as unsafe and refuse. This exists because
  `replaceSubtaskPlan`'s contract states only the single-writer planning sweep may write these
  rows; a replan racing a live sweep is the failure mode being prevented.
- **Terminal subtask.** Refuse when the target subtask's status is terminal (`complete`, including
  restored out-of-band acceptances). The message names `reset` as the operation for that case.
  Rationale: the continuation selector will not revisit a complete subtask, so discarding its plan
  would silently do nothing useful while making `status` report a phantom missing plan.
- **Unknown issue key**, **non-positive subtask id**, and **subtask id absent from the goal's
  ordered descriptors** each fail with their own distinct message. Do not collapse them into one
  generic error; the operator needs to know which of "wrong repo", "typo", and "wrong goal" they
  hit.

### 4. CLI surface

Add `GoalReplanCommand` to `runtime-kotlin/runtime-cli/src/main/kotlin/skillbill/cli/goal/GoalCliCommands.kt`
as a `DocumentedCliCommand` sibling of `GoalResetCommand`, and register it in the same place the
other goal subcommands are registered:

```
skill-bill goal replan <issuekey> --subtask <int>
```

Options: `--subtask=<int>` (required), plus the `--repo-root=<text>` every sibling accepts.
`--include-shared-preplan` belongs to subtask 2 and is not added here.

Output follows `GoalResetCommand`'s existing before/after shape and
`GoalCliFormatting` helpers — same key ordering conventions, same `goal:`/`status:` header lines —
so the operation is auditable from the terminal alone. Name what was discarded, and say plainly
what was preserved (sibling plans, shared preplan, completed subtask state), because "did this eat
my completed subtasks?" is the exact anxiety that drove operators to snapshot the database by hand.

Register the option gate in a test mirroring `CliGoalResetOptionGateTest` so the new command's
argument validation is pinned the same way.

### 5. Status projection

`skill-bill goal status <key>` after a scoped replan must report the surviving plan count and name
the replanned subtask. No new status model is needed:
`GoalPlanningStatusState.PARTIALLY_PLANNED` already exists, and `GoalPlanningStatusSnapshot`
already carries `sharedPreplanPrepared`, `plannedSubtaskCount`, `totalSubtaskCount`,
`currentPlanningSubtaskId` and `reason`. Confirm the existing derivation produces
`state=partially_planned shared_preplan=true planned=2/3 current=3` for a 3-subtask goal replanned
at 3, and set `reason` to something that names the scoped replan rather than reusing the
"planning has not started" text.

If the existing derivation instead reports `prepared` because it keys off a count that the deletion
did not move, fix the derivation — a status that hides a discarded plan is worse than no status.

## Acceptance Criteria

1. `skill-bill goal replan <issue-key> --subtask <id>` discards exactly that subtask's stored plan
   row from `goal_subtask_plans` and nothing else.
2. After the command, the shared preplan row, every sibling subtask plan row, and every subtask's
   `status`, `commit_sha`, `workflow_id`, `blocked_reason` and `last_resumable_step` are byte-for-byte
   unchanged, and every recorded out-of-band acceptance survives.
3. The goal's current subtask intent points at the replanned subtask, so the continuation selector
   resumes there.
4. `skill-bill goal status <issue-key>` reports the surviving plan count and a reason naming the
   replanned subtask — `planning: state=partially_planned shared_preplan=true planned=2/3 current=3`
   for a 3-subtask goal replanned at 3.
5. The next `skill-bill goal <issue-key>` regenerates only the discarded plan, from the current
   on-disk spec, and continues into that subtask without re-implementing or reopening any terminal
   subtask.
6. The command prints a before/after snapshot naming both what was discarded and what was
   preserved, using `GoalResetCommand`'s existing formatting conventions.
7. Each refusal path — live or unknown-liveness goal, terminal target subtask, unknown issue key,
   non-positive subtask id, subtask id absent from the goal — fails with its own distinct message
   and mutates nothing. The terminal-subtask message names `reset`.
8. `GoalRunnerResetRequest` is unmodified and `CliGoalResetOptionGateTest` passes unmodified.
9. A new option-gate test pins `replan`'s argument validation, mirroring the reset gate test.
10. The repository's check gate passes.

## Non-Goals

- `--include-shared-preplan` — subtask 2.
- Skill-content guidance — subtask 3.
- Deleting the subtask's child workflow row, or any change to
  `reset --subtask … --delete-child-workflow`.
- Any relaxation of `GoalRunnerResetRequest`'s invariants.
- Skipping planning for already-terminal subtasks (flagged in the parent spec as out of scope).
- Automatic staleness detection. The operator names the subtask.

## Dependency Notes

None — this is the first subtask and depends on nothing. It must land complete and useful on its
own: after it, the hard-reset-plus-compensating-accepts workaround is already unnecessary for the
common single-subtask amendment case.

## Validation Strategy

- Persistence test against `goal_subtask_plans` asserting the scoped delete removes one row and
  leaves sibling rows and the shared preplan present.
- Application test per refusal path asserting no mutation, including the `UNKNOWN` liveness case.
- New CLI option-gate test mirroring `CliGoalResetOptionGateTest`; that existing test must pass
  unmodified.
- End-to-end on a real 3-subtask goal: reach subtask 3, amend its spec, `replan --subtask 3`,
  assert `status` shows `planned=2/3` with subtasks 1–2 still `complete` and carrying their SHAs,
  relaunch, and confirm the regenerated plan reflects the amendment.
- The repository's check gate.

## Next Path

Subtask 2 — opt-in shared-preplan invalidation.
