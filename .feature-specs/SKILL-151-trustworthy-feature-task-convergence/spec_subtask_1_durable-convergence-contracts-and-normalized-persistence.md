# SKILL-151 Subtask 1 - Durable convergence contracts and normalized persistence

Parent spec: [.feature-specs/SKILL-151-trustworthy-feature-task-convergence/spec.md](./spec.md)
Issue key: SKILL-151

## Scope

Version bounded convergence schemas under `orchestration/contracts/` for implementation receipts, audit generations and repairs, review generations and dispositions, checkpoint identity, and adaptive policy projections. Align Kotlin constants, typed schema errors, validators, classpath bundling, and loud-fail parse seams. Extend normalized SQLite persistence and ports for append-only attempts, obligations, gaps, repair batches and results, findings, dispositions, checkpoints, and idempotent legacy imports. Keep artifact JSON as a bounded derived projection.

## Acceptance Criteria

1. Every implementation outcome, audit gap and repair result, review finding and disposition, and checkpoint has durable generation-aware evidence that later checkpoints cannot delete.
2. Append-only records expose stable identity and bounded provenance while raw prompts, responses, diagnostics, diffs, and unbounded text remain private.
3. Contract or version drift loud-fails through typed errors, compatible replays are idempotent, and conflicting replays cannot overwrite evidence.
4. Schema constants, validators, persistence models, migrations, runtime mappings, and parse seams remain bijective.
5. Cross-generation queries return every unresolved governed obligation without relying on replaceable artifact snapshots.

## Non-Goals

- Storing full prompts, responses, private diagnostics, diffs, or unbounded review text.
- Replacing manifest-driven platform routing with a fixed platform catalogue.
- Adding new tests or test infrastructure.

## Dependency Notes

Depends on: none

This foundation owns the stable identities, transactions, and query seams used by every later subtask.

## Validation Strategy

Run existing contract, domain, persistence, build, test, and repository validation commands. Fix failing production behavior and correct stale existing test expectations when necessary. Add no new tests or fixtures.

## Next Path

Proceed to truthful implementation settlement and continuation semantics.

## Spec Path

.feature-specs/SKILL-151-trustworthy-feature-task-convergence/spec_subtask_1_durable-convergence-contracts-and-normalized-persistence.md
