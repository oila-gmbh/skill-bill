# SKILL-208 subtask 2 — Runtime-held facts and runtime-minted evidence

## Scope

Apply R1 (mint over read) to every fact a phase currently echoes that the runtime
can observe for itself, and decide mutating-phase idempotency from repository
evidence instead of a producer claim. Today a correct phase fails when it reports
the wrong review run id, omits `commit_focused_accounting`, echoes a stale
`repository_checkpoint`, or forgets the `reconciled_state` object — none of which
the runtime needed to be told.

The pattern already exists in this runtime and this subtask generalizes it rather
than inventing it. `gateRepairNoOutputSchemaDirective` already runs validate and
build repair turns in prose with no phase-output schema;
`FeatureTaskRuntimeValidationGateCoordinator` and
`FeatureTaskRuntimeBuildGateCoordinator` mint the receipt from runtime-measured
evidence; and `stampRuntimeOwnedImplementationCheckpoint` already overwrites
`gate_run_count` and `gate_runs` on the fallback path. Extending that seam is
cheaper and safer than reading the same facts out of prose later.

In scope:

- Review run id and the review finding set: both come from the runtime's own
  review import rather than `produced_outputs.review_run_id` and
  `produced_outputs.findings`. This is the load-bearing half of the subtask,
  because it is what lets subtask 3 derive a review verdict and lets the
  measurement layer keep its input. Every consumer that reads the finding set —
  `GoalSubtaskReviewSummaryReducer.structuredFindings` and everything downstream
  of it (`unaddressed_findings`, `review_finding_outcomes`, cross-pass
  `findingKey` matching, `blocker_dispositions`) — reads it from the import.
- Commit-focused accounting: recorded from runtime-owned review execution state
  for a delegated pass over a real commit sequence, or recorded absent with a
  record; never requested from the agent.
- Repository checkpoints, `changed_paths`, and gate run counts: taken from the
  checkpoint resolver, `repositoryOwnedPaths`, and the validation and build gate
  coordinators. An agent echo is ignored rather than validated.
- Mutating-phase idempotency: `implement` and `implement_fix` are judged by
  comparing the repository against the checkpoint the runtime resolved, and the
  `reconciled_state` requirement leaves the schema and the prompt.
- Every fact the runtime cannot establish for itself emits a record and blocks or
  degrades explicitly.

Out of scope: verdict and settlement derivation (subtask 3), the envelope and
verbatim prose output (subtask 4), and the payload shape rules subtask 1 demoted.
Plan-obligation closure is explicitly not moved here: it is not observable from
the repository, so it is R2 work and belongs to subtask 3.

## Acceptance Criteria

1. The review run id and the review finding set used by the phase path and by
   every measurement consumer come from the runtime's own review import, and no
   phase prompt asks the agent to echo either.
2. The records the measurement layer writes — `unaddressed_findings`,
   `review_finding_outcomes`, and the cross-pass finding identity they match on —
   are populated from that import and carry the same coverage they do today.
3. Commit-focused accounting is recorded from runtime-owned review execution
   state, and a pass with no real commit sequence records its absence with a
   record instead of prompting for the key.
4. Repository checkpoints, `changed_paths`, `gate_run_count`, and `gate_runs` are
   taken from runtime state; an agent-supplied value for any of them is ignored
   and never gates the phase.
5. Mutating-phase idempotency is decided by comparing the repository against the
   runtime-resolved checkpoint; a mutating phase that reconciled the tree
   completes without reporting `reconciled_state`, and one that did not reconcile
   still fails loudly.
6. A phase that would previously fail only because an echoed fact was missing,
   stale, or wrong now completes, and the recorded fact matches the runtime's own
   state rather than the agent's claim.
7. Any fact the runtime cannot establish emits an observability record and either
   blocks or degrades explicitly, with no silent default.
8. Prompt directives no longer mention the removed keys, and bumped contract
   versions are pinned by their parity tests.

## Non-Goals

- Deriving verdicts or settlement (subtask 3), including the review verdict this
  subtask supplies the input for.
- Moving plan-obligation closure off the agent's output; the repository cannot
  answer which plan task was completed, so that stays R2 work in subtask 3.
- Replacing the output envelope or the launch prompt structure (subtask 4).
- Retiring the repair receipt, its coverage diagnostic, or the planning
  projection contracts (subtasks 4 and 5).
- Changing checkpoint policy semantics, the evidence broker, review importing
  itself, or quality-gate routing.

## Dependency Notes

- Depends on: none.
- Independent of: subtask 1, which can land in either order relative to this one.
- Unblocks: subtask 3, which cannot derive a review verdict from prose until the
  finding set has a runtime-held source, and subtask 4, which drops the
  mutating-phase and echo constraints this subtask makes unnecessary.

## Validation Strategy

- Targeted tests: a mutating phase that reconciled the tree completes without
  `reconciled_state`; one that did not reconcile still fails; a phase omitting the
  review run id still joins to the imported run; a review pass whose output
  carries no `findings` array still produces the same `unaddressed_findings` and
  `review_finding_outcomes` rows; an unestablishable fact produces a record plus
  an explicit block or degradation.
- Contract-version parity tests for any bumped record.
- Compile the affected runtime modules.

## Next Path

Subtask 3 derives settlement and the routing verdicts now that the facts they
combine with are runtime-held.
