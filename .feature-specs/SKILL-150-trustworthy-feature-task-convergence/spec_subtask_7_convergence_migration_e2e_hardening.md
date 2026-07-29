# SKILL-150 Subtask 7: Convergence Migration and End-to-End Hardening

## Scope

Complete compatibility, crash recovery, observability, documentation, and end-to-end coverage for the durable convergence model across implementation, audit, non-test quality checks, review, validation, goal continuation, and scoped checkpointing.

## Acceptance Criteria

1. Every supported legacy active workflow version either migrates implementation, audit, and review state into the new authority idempotently or loud-fails through a typed incompatibility and documented quarantine or restart path.
2. Migration preserves unresolved implementation items, audit gaps and repair outcomes, review findings and dispositions, operator decisions, phase attempts, repository checkpoints, and goal-child linkage without duplicating events.
3. Crash injection before and after every new database transaction and Git checkpoint boundary resumes to one authoritative current state with complete history and no skipped obligation.
4. Status and watch render bounded current-state summaries plus historical generation counts from durable queries and remain stable across retry and resume.
5. Telemetry derives implementation continuations, true schema retries, audit first-pass convergence, recurring and new gaps, repair outcomes, review generations, carried Blockers, non-test quality repairs, and checkpoint-policy blocks from durable evidence.
6. Telemetry and status counts agree with the append-only records and phase ledger for every end-to-end fixture.
7. A deterministic partial-implementation fixture remains in implementation until every planned task closes and never reaches audit through a status-only change.
8. A deterministic audit fixture emits one closure-complete repair batch, applies a correct repair, and clears after one follow-up audit; repeated faulty repairs remain visible as recurrence rather than overwritten history.
9. A deterministic review fixture carries an unresolved Blocker across at least two semantic or bookkeeping delta changes and prevents approval until a terminal disposition is recorded.
10. A concurrent-worktree fixture proves unrelated feature specs and user changes are absent from checkpoint commits, review inputs, validation attribution, and PR output.
11. Standalone and goal-child runs have equivalent completion, audit, review, checkpoint, crash, and migration behavior.
12. Operator retry, accept-and-advance, and abandon flows preserve history and cannot grant repeated authority from one decision.
13. Operational documentation explains durable authority, generation semantics, migration, status interpretation, checkpoint isolation, recovery, and why cycle counts no longer rely on replaceable snapshots.
14. The complete repository validation suite passes without generated source artifacts entering the repository.

## Non-Goals

- Migrating terminal historical workflows whose data is neither needed for active recovery nor required telemetry.
- Uploading local convergence history to a remote service by default.
- Automatically editing or squashing user Git history.
- Claiming a probabilistic agent will always converge within a fixed wall-clock duration.

## Dependency Notes

Depends on Subtasks 1 through 6. This subtask is the integration and compatibility gate for the complete feature.

## Validation Strategy

- Build fixture databases for every supported migration source version and compare exact logical records after repeated migration.
- Add failpoints around database and Git boundaries and test restart from each one.
- Run complete standalone and goal-child workflows using deterministic phase agents for partial, recurring, changed-delta, operator, and concurrent-worktree scenarios.
- Compare CLI status, watch, telemetry, database records, phase ledger, Git commits, and final manifest state.
- Run:

```bash
skill-bill validate
(cd runtime-kotlin && ./gradlew check)
npx --yes agnix --strict .
scripts/validate_agent_configs
```

## Next Path

Finish SKILL-150 after migration, end-to-end convergence, documentation, and full validation succeed.
