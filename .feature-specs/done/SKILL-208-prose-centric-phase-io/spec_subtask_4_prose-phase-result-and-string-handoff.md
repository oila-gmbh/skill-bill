# SKILL-208 subtask 4 — Prose phase result and string handoff

## Scope

The envelope flip. Every feature-task phase launches through
`AgentPhaseInput(input, requestedAction)` and settles with
`AgentPhaseOutput(output)` carrying the agent's returned text verbatim, and each
consumer receives its producers' `output` text instead of a filtered field set.

The producer and consumer sides ship together because they cannot ship apart: a
prose `implement` output with the producer projection gate still live is
quarantined as an invalid `implementation_receipt` and the run wedges.

The durable phase record becomes prose plus a runtime-owned structured sidecar
(R3). The runtime writes the sidecar from its own evidence — the checkpoint, the
minted gate receipt, the captured commit sha. Exactly two sidecar entries are
agent-authored, both for governed artifacts the runtime cannot observe and both
keeping their existing schema and loud-fail:

- The `commit_push` commit subject. The runtime performs the commit, and a wrong
  subject is an irreversible publish to the feature branch, so the subject is
  read from an explicitly delimited line rather than inferred from prose. A blank
  or absent subject blocks the subtask exactly as it does today, and the
  runtime-captured post-amend sha is written into the sidecar so
  `commit_push_result.commit_sha`, the decomposition manifest entry, and the
  goal-continuation outcome remain one value by construction.
- A decompose outcome's decomposition package, read from an explicitly delimited
  block and validated against its existing contract. Goal planning still
  loud-fails on a malformed one. This keeps `plan` single-mode at the phase-output
  level: the phase result is always prose, and the package is a sidecar artifact
  rather than a second shape the phase output can take.

In scope:

- `FeatureTaskRuntimePhaseBriefingAssembler` and
  `FeatureTaskRuntimePhasePromptComposer` compose an `AgentPhaseInput` whose
  `input` carries the upstream phases' `output` text plus the phase's entitled
  context, and whose `requestedAction` states the phase's ask.
- The "Required final output (validated schema gate)" contract, the retry
  skeleton, and the per-phase `produced_outputs` addenda stop being emitted.
- `FeatureTaskRuntimePhaseRecorder` and the run loop persist the agent's returned
  text verbatim as the authoritative phase result alongside the runtime-owned
  sidecar; the durable record and its contract version carry both.
- `PHASE_PROJECTION_MATRIX` delivers producer `output` text under the existing
  handoff budgets, keeping source refs, checkpoint policies, and
  required/optional semantics while its declared-field lists stop filtering the
  handoff.
- Forwarding is bounded so truncation cannot consume the region a derivation
  reads: the segment carrying the verdict, the obligation ids, and the finding
  ids is retained ahead of narrative, and truncation of the remainder records and
  shows itself in the prompt.
- `producerProjectionGateReason`, `requireValidPlanningProjection`, and the
  `RECORD_REJECTED` regeneration edges stop gating the feature-task phase path.
  The gate function itself stays, because `GoalPlanningSweep` and
  `GoalPlanningPreparationCheckpoint` call it and goal planning is out of scope.
- Repair-receipt coverage stops being consulted at all: the `requestedAction`
  names the carried findings and asks for their disposition in prose, coverage is
  decided by the id membership subtask 3 established, and coverage the runtime
  cannot establish emits a record while the next verification pass re-decides
  from the tree.

Out of scope: deleting the now-unreferenced schema files, types, repair passes,
and documentation (subtask 5).

## Acceptance Criteria

1. Every phase id in `FeatureTaskRuntimePhaseWorkflowDefinition.definition.stepIds`
   launches with an `AgentPhaseInput` carrying non-blank `input` and
   `requestedAction`, and no composed phase prompt asks for a final JSON object,
   `produced_outputs`, or `contract_version`.
2. A completed phase persists the agent's returned text verbatim as the
   authoritative result; a phase whose text would have failed the old shape gate
   advances with that text intact; absent or blank output still fails loudly.
3. Each consumer receives its producers' `output` text under the existing handoff
   budgets, and no consumer launch is rejected for a missing declared field.
4. A budget truncation of forwarded prose emits an observability record and is
   visible in the delivered prompt; truncation is never silent and never removes
   the region a derivation reads.
5. No completed producer is quarantined or re-run for the shape of its payload:
   the producer projection gate and the `RECORD_REJECTED` edges no longer decide
   anything on the feature-task phase path, while `GoalPlanningSweep` and
   `GoalPlanningPreparationCheckpoint` keep the gate behaviour they have today.
6. `commit_push` settles with prose plus a runtime-owned sidecar: the commit
   subject is read from an explicitly delimited line, a blank or absent subject
   blocks the subtask rather than publishing a provisional one, the runtime
   performs the staging, amend, sha capture, and push, and the captured sha
   reaches the phase record, the decomposition manifest, and the
   goal-continuation outcome as one value.
7. `write_history` and `pr` settle on prose with no declared-field requirement,
   and neither the history receipt nor the PR result blocks a run for shape.
8. A decompose outcome lands as a schema-valid decomposition package read from an
   explicitly delimited sidecar block, `plan`'s phase output is prose in both
   modes, and goal planning still loud-fails on a malformed package.
9. Carried review findings reach the fixing phase through the `requestedAction`,
   and coverage the runtime cannot establish emits a record instead of blocking
   the phase on receipt shape.
10. Durable record contract versions bumped here are pinned by their parity
    tests, legacy records are rejected loudly, and the hard-reset path for
    in-flight workflows is exercised by a test or documented at the rejection
    seam.

## Non-Goals

- Re-deciding derivation (subtask 3) or fact ownership (subtask 2).
- Deleting the retired schema files, planning projection types, structural
  repair, duplicate-key merge, or schema-failure corrections (subtask 5).
- Extending the envelope to the goal-planning sweep, `bill-feature-verify`, or
  the standalone PR path.
- Deriving a commit subject or a decomposition package from prose. Both are
  governed artifacts under R3.
- Changing the phase DAG, loop caps, ceremony scaling, or quality-gate routing.
- Prose for governed artifacts: workflow state, decomposition manifests,
  platform-pack manifests, and telemetry payloads stay typed.
- In-place migration of in-flight workflows.

## Dependency Notes

- Depends on: subtask 3 — routing, settlement, and obligation closure must
  already work from prose, or the remediation edges become unroutable and the
  completion gate loses its input. Subtask 3 in turn depends on subtask 2.
- Unblocks: subtask 5, which deletes what this subtask stops consulting.

## Validation Strategy

- Targeted tests: prose that the old gate rejected advances with output intact; a
  consumer launches from a producer's prose with no declared-field rejection;
  budget truncation records, shows itself, and retains the derivation region; a
  legacy durable record is rejected loudly.
- A `commit_push` test set: a delimited subject commits and pushes with the
  captured sha reaching the manifest; a blank subject blocks; the manifest entry
  and the goal-continuation outcome never disagree.
- A decompose test: a delimited package lands as a schema-valid manifest and a
  malformed one loud-fails.
- An end-to-end runtime test that reaches `commit_push` on prose phase results.
- Compile the affected runtime modules; the surviving feature-task test sets pass.

## Next Path

Subtask 5 removes the retired schemas, types, and repair passes and aligns
`ARCHITECTURE.md` and the contract inventory with the shipped boundary.
