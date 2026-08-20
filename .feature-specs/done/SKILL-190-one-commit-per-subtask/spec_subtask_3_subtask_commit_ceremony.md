# SKILL-190 Subtask 3 — Runtime-owned subtask commit identity and amend ceremony

## Intended Outcome

The forward and remediation checkpoints stop creating branch commits. Instead, the first checkpoint
with staged content creates the single subtask commit, every later checkpoint amends it, and each
checkpoint's pre-amend state is preserved as a ref under
`refs/skill-bill/checkpoints/<issue-key>/<subtask-id>/<sequence>`.

This is the subtask that delivers the owner's requirement. Subtasks 4 and 5 keep the consumers
working.

## Scope

- Replace the `createCommit` calls at `FeatureTaskRuntimeRunLoop.kt:1305` (forward) and `:813`
  (remediation) with a create-or-amend decision.
- Establish subtask commit identity: a `Skill-Bill-Subtask: <issue-key>/<subtask-id>` trailer on
  every runtime-written subtask commit, plus the authoritative record in durable workflow state.
  State decides; the trailer is the recovery fallback when state is unavailable.
- Source the provisional commit subject from the manifest subtask `name` field so the commit is
  legible from creation. Retain the `[phase=… loop=… generation=…]` metadata in the commit body
  rather than the subject, so the audit trail survives without polluting `git log --oneline`.
- Write the checkpoint ref before amending, so the pre-amend state is durable before it leaves branch
  history. Record the identity per the subtask 2 contract in the same transaction as the checkpoint,
  preserving the existing idempotency at `FeatureTaskRuntimePhaseRecorder.kt:1550-1590`.
- Keep the ceremony dispatcher (`nextTransitionTarget`, `:660-700`) and the checkpoint scope
  ownership verdicts (`FeatureTaskRuntimeCheckpointScope.decide`, `:16-39`) as they are. What
  changes is the write, not the decision to write.
- Retire `FeatureTaskRuntimeCheckpointMessage.build`'s current single-line subject form
  (`FeatureTaskRuntimeCheckpointScope.kt:216-222`) in favour of the subtask-commit message builder.

## Acceptance Criteria

1. A subtask driven through one forward checkpoint and any number of remediation loops leaves
   exactly one commit on the feature branch.
2. The first checkpoint with staged content creates the subtask commit; every subsequent checkpoint
   in that subtask amends it and produces no additional branch commit.
3. A checkpoint whose scope verdict is `Skip` or `Block` neither creates nor amends, matching current
   behaviour.
4. Every runtime-written subtask commit carries the `Skill-Bill-Subtask: <issue-key>/<subtask-id>`
   trailer.
5. The create-or-amend decision reads durable workflow state first and falls back to the trailer on
   HEAD only when state is unavailable; the fallback emits an observability record.
6. Before each amend, the pre-amend commit is written to
   `refs/skill-bill/checkpoints/<issue-key>/<subtask-id>/<sequence>` and that ref is confirmed
   resolvable before the amend runs.
7. If the ref write fails, the amend does not run and the checkpoint fails loudly; the runtime never
   discards a state it could not first preserve.
8. Checkpoint metadata (`phase`, `loop`, `generation`) remains recorded in the commit body and in the
   identity ledger; it no longer occupies the commit subject.
9. The provisional subject is derived from the manifest subtask `name`, and a subtask that reaches
   finalisation never remains on the provisional subject.
10. A resume after process death between staging and amend reattaches to the existing subtask commit
    and does not create a second one.
11. `../..` paths are staged by no path in this ceremony, unchanged from today.
12. The existing checkpoint-identity idempotency holds: re-recording the same checkpoint after a
    resume does not append a duplicate ledger entry.

## Non-Goals

- Changing which transitions earn a checkpoint, the phase graph, loop identifiers, or convergence
  limits.
- The `commit_push` finalisation commit and its message. That is subtask 5.
- Reconciliation and rollback consumers. That is subtask 4.
- Ref pruning. That is subtask 6; refs accumulate within a subtask here and are cleaned up later.

## Dependency Notes

Depends on subtask 1 for the amend and ref primitives, and on subtask 2 for the identity fields that
record the checkpoint ref and subtask ID.

Subtask 4 must land before this behaviour is safe in production, because reconciliation still reads
branch ancestry until then. If these are executed as separate commits, treat the pair as the
shippable unit.

## Validation Strategy

Against real temporary repositories:

- One forward checkpoint plus three remediation loops yields one branch commit and four resolvable
  checkpoint refs.
- The trailer is present and parses back to the right subtask.
- Killing the process between stage and amend, then resuming, yields one commit.
- A forced ref-write failure blocks the amend and surfaces the failure.
- `Skip` and `Block` verdicts produce no commit and no ref.
- `git log --oneline` on the branch shows one line for the subtask.

Then the `runtime-application` module checks.

## Next Path

Subtask 4 repoints remediation-base reconciliation and rollback at the checkpoint refs this subtask
now writes.
