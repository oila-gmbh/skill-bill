# SKILL-164 · Subtask 2: Phase-neutral evidence assembly hoist

## Scope

Hoist SKILL-158's review-scoped commit evidence assembly into the phase-neutral deriver from
subtask 1, so the evidence is produced once for any consumer rather than as a review-private
step. Review becomes the first consumer of the shared store; its observable behaviour does not
change.

In scope:

- Relocating or generalizing the assembly path that produces `ReviewCommitUnit`,
  `ReviewChangedHunk`, and `ReviewEvidenceTarget` so it is invoked by the shared deriver and is
  not review-phase-private.
- Making the review context path read units, hunks, and evidence targets from the stored
  artifact instead of re-deriving them.
- Making every specialist lane read from the shared stored evidence; `ReviewLaneBundleAssembly`
  sources its inputs from the store rather than from a per-lane derivation.
- Preserving both review scopes: `BRANCH_DIFF` for MEDIUM/LARGE and `CURRENT_UNIT_OF_WORK` for
  SMALL, per `ceremonyScaling`.
- Preserving synthetic unit handling (`SYNTHETIC_WORKING_TREE`, `SYNTHETIC_SUPPLIED_DIFF`,
  `SYNTHETIC_AGGREGATE_PR_DIFF`) and the `synthetic:` placeholder identity rule.

Out of scope: audit consumption, projection contract changes, briefing instruction changes.

## Acceptance Criteria

1. The commit evidence assembly producing review units, hunks, and evidence targets is invoked
   by the shared deriver and is no longer private to the review phase.
2. The review context path reads units, hunks, and evidence targets from the stored artifact
   and performs no independent repository traversal when a matching artifact exists.
3. Every specialist lane sources its assigned paths and hunks from the shared stored evidence;
   N lanes over one checkpoint produce exactly one derivation.
4. `ReviewCommitUnit.commitUnitId`, packet digests, and lane bundle identities are byte-for-byte
   unchanged for unchanged inputs.
5. `BRANCH_DIFF` review under MEDIUM and LARGE sizing resolves the same evidence set it
   resolved before the hoist.
6. `CURRENT_UNIT_OF_WORK` review under SMALL sizing resolves the same evidence set it resolved
   before the hoist.
7. Synthetic review units retain their `synthetic:` placeholder identities and sole-unit
   ordering invariant through the shared store.
8. Commit-focused sparse lane routing decisions, including `SKIPPED` dispositions, are
   unchanged by the hoist.

## Non-Goals

- Delivering evidence to the audit phase.
- Altering lane routing policy, expansion records, or budget policy.
- Changing review findings, severities, or verdict semantics.

## Dependency Notes

Depends on subtask 1 for the store and deriver seam.

## Validation Strategy

Characterization tests asserting identity stability across the hoist: `commitUnitId`, packet
digest, and lane bundle identity computed pre- and post-hoist for fixed inputs. A lane
fan-out test asserting exactly one derivation for N lanes at one checkpoint. Scope tests for
`BRANCH_DIFF` and `CURRENT_UNIT_OF_WORK`. Synthetic-source tests across all three synthetic
kinds. The full existing review suite, including SKILL-158's routing tests, must stay green
unmodified.

## Next Path

Proceed to subtask 3.
