# SKILL-128 Subtask 1 - Goal planning persistence contract

## Scope

Define the typed domain and persistence contract for checkpointing one shared
goal-level `preplan` output plus one distinct `plan` output per decomposed
subtask. Reuse the existing feature-task runtime phase-output validator for the
embedded phase payloads, and add only the goal-owned envelope/provenance
contract needed to identify, resume, reset, and hydrate those immutable outputs.

The persistence design may extend an existing goal/workflow record or add a
normalized table, but it must expose one application-facing port and preserve
the workflow database as the only authority.

## Acceptance Criteria

1. A typed planning-preparation model identifies the parent goal workflow,
   normalized issue key, repository identity, singleton shared-preplan payload,
   and per-subtask governed spec, preparation status, contract provenance, and
   plan payload.
2. The stored preplan and plan payloads are validated with the existing
   feature-task runtime phase-output validator and are rejected unless their
   `phase_id`, contract version, status, and produced outputs match their
   intended phases.
3. Persistence supports atomic shared-preplan checkpointing, per-subtask plan
   checkpointing, ordered listing, lookup by goal and subtask, planned-count
   status, and recovery of the first missing plan without exposing SQLite types
   outside infrastructure.
4. Duplicate, cross-goal, cross-repository, wrong-spec, malformed, and
   incompatible-version records fail loudly with typed contract/persistence
   errors rather than being ignored or treated as pending.
5. Immutable provenance covers hashes or equivalent stable identities for the
   parent spec, sub-spec, decomposition manifest, and planning/output contract.
6. Once the shared preplan or a subtask plan is marked prepared, normal resume
   cannot overwrite or regenerate it. Explicit incompatible recovery fails
   loudly.
7. Schema/migration, repository, mapping, and contract-version parity tests
   cover acceptance and rejection behavior, transaction rollback, ordering,
   and restart recovery.
8. No standalone feature-task query or repository method reads this
   goal-scoped preparation store.
9. Hard goal reset atomically deletes the goal's shared preplan, every subtask
   plan, and related hydration/continuation preparation state before manifest
   runtime fields are reset. Tests prove no stale row survives a hard reset.
10. Soft goal reset preserves schema-valid shared-preplan and subtask-plan
    checkpoints, while incompatible provenance still fails loudly and requires
    hard reset or explicit operator migration.

## Non-Goals

- Do not implement the planning-agent loop.
- Do not hydrate child feature-task workflows.
- Do not alter the feature-task phase topology or standalone persistence path.
- Do not create a second Git-tracked planning ledger.

## Dependency Notes

This is the first subtask and has no dependencies. Subtasks 2 and 3 consume its
typed application port and durable model.

## Validation Strategy

- Add schema/contract parity and validator tests where a new or extended
  runtime contract is required.
- Add SQLite migration and repository tests using a temporary database.
- Add application-port tests proving infrastructure types do not leak and
  standalone feature-task access is absent.
- Run the affected runtime contract, domain, and persistence test tasks.

## Next Path

Run `skill-bill goal SKILL-128`; after this subtask completes, continue with
`spec_subtask_2_goal_planning_sweep.md`.
