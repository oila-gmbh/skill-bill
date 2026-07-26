# SKILL-143 - Bounded recovery for a blocked subtask's stale child workflow

status: prepared
issue_key: SKILL-143
preparation_mode: single_spec
spec_source: local

## Intended Outcome

A blocked subtask can be returned to a runnable state with an explicit operation
scoped to that subtask. The operation deletes its incompatible stale child
workflow while preserving immutable planning checkpoints and leaving every other
subtask's durable state untouched.

## Problem

A soft goal reset clears a blocked subtask's reason and marks it in progress, but
deliberately preserves child workflows. If the child already has a durable terminal
outcome, the next goal run immediately reconciles that outcome and blocks the
subtask again. The reset reports an unqualified success even though the subtask is
not runnable.

The current child-deletion path requires a goal-wide hard reset with
`--preserve-planning`. That resets unrelated completed subtasks and discards their
out-of-band acceptance records. Operators must then reconstruct commit SHAs and
acceptance reasons by hand. The attempt ledger compounds the problem by advising
resume even when resume deterministically re-blocks.

## Acceptance Criteria

1. A subtask-scoped reset exists that deletes the incompatible child workflow for one named subtask and leaves all other subtasks' runtime fields unchanged.
2. The subtask-scoped operation preserves immutable planning checkpoints, matching current `--preserve-planning` semantics.
3. A completed subtask's status and out-of-band acceptance survive any reset that does not explicitly name that subtask.
4. A soft reset that leaves a subtask unrunnable reports the blocking child workflow and the exact command that clears it instead of reporting an unqualified `status: ok`.
5. The attempt ledger's `next_safe_action` distinguishes a resumable block from one requiring child-workflow deletion and never advises an action that immediately re-blocks.
6. Before a hard reset that would discard completed-subtask acceptance records acts, it identifies the acceptances that will be lost and prints the commands required to restore them.
7. Recovering from a stale terminal child workflow requires no manual re-`accept` of already complete subtasks unrelated to the block.

## Constraints

- The workflow store remains authoritative.
- `decomposition-manifest.yaml` remains a read-only projection and is never edited to force progress.
- Any operation that deletes durable records remains explicit; there is no new destructive default.
- Existing blocked-state stickiness remains unchanged.

## Non-Goals

- Automatically retrying blocked subtasks.
- Making blocked states non-sticky.
- Changing review-specialist routing or install repair hints observed during the same SKILL-142 session.

## Evidence

- Parent goal `wfl-20260725-000845-rpdi` for SKILL-142, subtask 2.
- Stale child workflow `wftr-20260725-125500-xgip`.
- A soft reset changed `blocked` to `in_progress`, followed by immediate reconciliation back to blocked.
- The required goal-wide hard reset changed an unrelated completed subtask to pending and removed its accepted commit `8e6ae197...`.

## Validation Strategy

- Add command-level acceptance and rejection tests for the subtask selector and explicit destructive behavior.
- Add persistence tests proving the selected child is removed while planning checkpoints and unrelated subtask runtime fields and acceptances remain byte-for-byte equivalent.
- Add output tests for soft-reset diagnostics and hard-reset acceptance-loss warnings.
- Add attempt-ledger tests for resumable versus stale-terminal-child recovery guidance.
- Run the routed repository quality checks after implementation.

