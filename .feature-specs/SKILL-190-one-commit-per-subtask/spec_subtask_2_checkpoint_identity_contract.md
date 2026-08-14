# SKILL-190 Subtask 2 — Amend-aware checkpoint-identity contract

## Intended Outcome

The checkpoint-identity ledger describes a world where each checkpoint is its own permanent commit.
Under amend semantics that model is wrong in a way that fails closed: its `commit_shas_are_unique`
coherence check treats a re-recorded sha as evidence of a resume bug. This subtask versions the
contract so it can describe an amended subtask commit plus a stable per-checkpoint ref.

## Scope

- Bump the contract version in
  `orchestration/contracts/feature-task-runtime-checkpoint-identity-schema.yaml` and its paired
  Kotlin `*_CONTRACT_VERSION` constant in `runtime-contracts/.../FeatureTaskRuntimeSchemaPaths.kt:203-215`.
- Replace `commit_shas_are_unique` (`:87-90`) with invariants that hold under amend: the checkpoint
  **ref** is unique per sequence, and the recorded commit sha is the sha that ref pointed at when the
  checkpoint was taken.
- Keep `sequence_numbers_are_unique` (`:85-86`); the ledger stays append-only. Amending a commit does
  not amend the ledger.
- Add the fields the later subtasks need: the checkpoint ref name, and the subtask ID the checkpoint
  belongs to.
- Extend `FeatureTaskRuntimeCheckpointIdentity` in
  `runtime-domain/.../FeatureTaskRuntimeCheckpointIdentityModels.kt:35-73` with the new fields and
  their validation, and update `fromArtifactMap` (`:112-141`) and the artifact codecs (`:160-190`).
- Add a typed rejection error alongside the existing
  `InvalidFeatureTaskRuntimeCheckpointIdentitySchemaError` family in
  `runtime-contracts/.../ShellContentContractErrors.kt:438` for legacy records that predate the bump.
- Update the validator in
  `runtime-infra-fs/.../FeatureTaskRuntimeCheckpointIdentitySchemaValidator.kt`.
- Legacy records loud-fail and are quarantined and regenerated in band, matching the existing
  schema-bump behaviour described in `CLAUDE.md`. Do not write a silent compatibility reader.

## Acceptance Criteria

1. The schema version is bumped in the YAML contract and in the paired Kotlin constant, and the two
   agree.
2. `commit_shas_are_unique` is removed and replaced by a checkpoint-ref uniqueness invariant that
   holds when the underlying subtask commit is amended.
3. `sequence_numbers_are_unique` is retained unchanged; the ledger remains append-only.
4. The identity record carries the checkpoint ref name and the owning subtask ID, both validated.
5. A record written under the previous version is rejected with a typed error carrying the expected
   and actual contract versions, and is quarantined rather than accepted or silently upgraded.
6. Every parse seam for this contract fails loudly on drift; no path converts a rejection into an
   empty or default identity list.
7. Reading a ledger that mixes valid current records with one legacy record fails the whole read
   rather than returning the valid subset.
8. The contract-version parity check is resolved per the tension recorded in the parent spec: either
   a parity test exists on the `PlatformPackSchemaContractVersionTest` pattern, or an explicit
   recorded operator decision waives it. The subtask does not proceed on an unrecorded default.

## Non-Goals

- Changing when checkpoints are taken, or which transitions earn one.
- Migrating existing ledger records in any repository. Legacy records quarantine and regenerate;
  there is no data migration.
- Changing the artifact key `feature_task_runtime_checkpoint_identities`.
- Touching the reconciliation consumer. That is subtask 4.

## Dependency Notes

No dependencies. Runs in parallel with subtask 1; subtasks 3 and 4 depend on both.

The repo's runtime-contract rule in `CLAUDE.md` governs the sequence here: YAML schema first, then
the Kotlin constant, then the parity check, then the typed error, then loud-fail at every parse
seam, then the classpath `Copy` with `inputs.file` and a `doFirst {}` existence guard.

## Open Tension

`CLAUDE.md` forbids new tests and simultaneously requires a parity test for every contract version
bump. Acceptance criterion 8 forces this into the open rather than letting the executing agent pick
a side quietly. Surface the choice, record the decision, then proceed.

## Validation Strategy

- A current-version record round-trips through `fromArtifactMap` and the artifact codecs.
- A record missing the new ref or subtask field is rejected.
- A previous-version record produces the typed version error with both versions populated.
- A mixed ledger fails whole rather than partially.
- Two identity records that share a commit sha but differ in ref and sequence are accepted, which is
  the case the old invariant wrongly rejected.

Then the `runtime-domain`, `runtime-contracts`, and `runtime-infra-fs` module checks, and
`skill-bill validate` for the contract surface.

## Next Path

Subtask 3 makes the run loop create-or-amend one subtask commit and write these ref-backed
checkpoint identities.
