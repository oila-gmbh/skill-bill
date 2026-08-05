# SKILL-160 · Subtask 2 — Opt-in shared-preplan invalidation

Parent spec: `.feature-specs/SKILL-160-scoped-subtask-replan/spec.md`

## QA Statement

An operator whose amendment changed goal-wide context rather than one subtask's steps can run
`skill-bill goal replan <key> --subtask <id> --include-shared-preplan`, see the shared preplan
discarded alongside that subtask's plan, see `status` report `shared_preplan=false`, and relaunch so
the goal regenerates the preplan and every plan that depends on it — with no surviving plan left
provenance-mismatched against the new preplan, and with completed subtasks' terminal state intact.

## Scope

### 1. The flag

Add `--include-shared-preplan` to `GoalReplanCommand` (built in subtask 1) and the corresponding
field to the replan request in `GoalRunnerRequests.kt`, defaulting to `false`. Without the flag the
shared preplan is never touched — that is subtask 1's behaviour and it must not regress.

### 2. Shared-preplan deletion primitive

Add a delete-only shared-preplan operation to `NormalizedGoalPlanningPreparationRepository`,
following the same convention subtask 1 used for the subtask-plan delete: an `error(...)` default in
the port, a real implementation in `GoalPlanningPreparationStore`, and an override in
`EmptyGoalPlanningPreparationRepository`.

Do not reuse `replaceSharedPreplan(checkpoint, expectedPayloadSha256)`. Its compare-and-replace
gate exists so "a concurrent writer changing the stored payload after the caller's gate decision
must fail the compare-and-replace instead of being deleted" — that contract is about replacement,
and it needs a checkpoint the operator does not have. A deletion wants its own primitive.

However, **keep the concurrency intent**: read the stored preplan's payload digest during the same
pre-flight that subtask 1's refusal paths run, and make the delete conditional on that digest still
matching. If it moved, refuse rather than deleting something the operator never inspected.

### 3. The provenance-parity problem — resolve it explicitly

This is the substance of this subtask, not an edge case.

`replaceSubtaskPlan`'s contract states that "provenance parity with the governing shared preplan is
still enforced". Subtask plans are therefore bound to the preplan that governed them. Discarding and
regenerating the shared preplan while sibling subtask plans survive can leave those survivors
bound to a preplan that no longer exists, which is exactly how a goal wedges: the sweep neither
accepts the stale plan nor regenerates it.

Determine from the code how parity is actually enforced — which field carries the provenance link,
whether the check runs at checkpoint time, at read time, or both — and then pick one of these two
resolutions, implement it, and state which was chosen and why:

- **Cascade.** `--include-shared-preplan` also discards every *non-terminal* subtask's plan, so no
  survivor can be orphaned. Terminal subtasks' plans are left alone because they are never replanned
  or rehydrated; if that assumption is false in the code, this option collapses into the second.
- **Reject.** Refuse the combination whenever a surviving plan would be left provenance-mismatched,
  naming the subtasks that block it and the command that would clear them.

Cascade is the better default if and only if terminal subtasks' plans are genuinely never read
again. Verify that in the code rather than assuming it. Do not ship a third option that leaves the
mismatch to be discovered at runtime.

Whichever is chosen, record it in the module's `agent/decisions.md` alongside the evidence that
decided it — this is precisely the kind of non-obvious invariant a later change will otherwise
rediscover the hard way.

### 4. Status and output

- `status` reports `shared_preplan=false` after the opt-in, and the planning state reflects that a
  preplan regeneration is pending.
- The command's before/after output names the shared preplan explicitly as discarded, and — if
  cascade was chosen — lists every additional subtask plan the cascade removed. An operator must
  never learn from a later run that this command discarded more than the one subtask they named.

## Acceptance Criteria

1. `--include-shared-preplan` discards the goal-wide shared preplan in addition to the named
   subtask's plan; omitting the flag leaves the shared preplan untouched, and subtask 1's behaviour
   is unchanged.
2. The shared-preplan deletion is conditional on the stored payload digest still matching the one
   read during pre-flight, and refuses without mutating if it moved.
3. No surviving subtask plan is left provenance-mismatched against a discarded preplan: either the
   cascade removed every non-terminal sibling plan, or the command refused and named the blocking
   subtasks. The chosen resolution is stated in the implementation and recorded in the module's
   `agent/decisions.md` with the code evidence that decided it.
4. Every subtask's `status`, `commit_sha`, `workflow_id` and out-of-band acceptance survives the
   opt-in unchanged — the flag widens *planning* invalidation only, never runtime state.
5. `skill-bill goal status <issue-key>` reports `shared_preplan=false` after the opt-in, with a
   planning state and reason reflecting that a preplan regeneration is pending.
6. The command's before/after output names the shared preplan as discarded and lists every
   additional subtask plan removed by a cascade.
7. The next `skill-bill goal <issue-key>` regenerates the shared preplan and every discarded plan
   from the current on-disk specs, then continues into the replanned subtask without reopening any
   terminal subtask.
8. `GoalRunnerResetRequest` remains unmodified and `CliGoalResetOptionGateTest` passes unmodified.
9. The repository's check gate passes.

## Non-Goals

- Changing `replaceSharedPreplan`'s compare-and-replace contract or its callers.
- Replanning or reopening terminal subtasks.
- Automatic detection of *which* amendments warrant preplan invalidation. The operator decides by
  passing the flag.
- Skill-content guidance — subtask 3.

## Dependency Notes

Depends on subtask 1, which owns the `replan` command, the request type, the pre-flight refusal
paths and the status projection this subtask extends. The flag has nowhere to live until that
command exists, and the digest check in section 2 is specified to run inside subtask 1's
pre-flight.

## Validation Strategy

- Persistence test asserting the shared-preplan delete is digest-conditional: it succeeds on a
  match and refuses without mutating on a mismatch.
- A test that pins the chosen provenance resolution directly — for cascade, that no non-terminal
  sibling plan survives; for reject, that the refusal names the blocking subtasks and mutates
  nothing.
- A test asserting runtime state (`status`, `commit_sha`, `workflow_id`, acceptances) is untouched
  by the opt-in.
- End-to-end: on a 3-subtask goal at subtask 3 with subtasks 1–2 complete, run the opt-in, assert
  `status` shows `shared_preplan=false` with 1–2 still complete and carrying their SHAs, relaunch,
  and confirm the preplan and the required plans regenerate and the goal continues.
- The repository's check gate.

## Next Path

Subtask 3 — operator guidance in the governed skills.
