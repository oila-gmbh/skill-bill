# SKILL-151 Subtask 7 - Legacy reconciliation, observability, and end-to-end recovery

Parent spec: [.feature-specs/SKILL-151-trustworthy-feature-task-convergence/spec.md](./spec.md)
Issue key: SKILL-151

## Scope

Migrate or reconcile active legacy database and artifact-only workflows into normalized convergence records. Make reconciliation idempotent and quarantine incompatible state through typed failures without evidence loss. Update feature-task and goal status, watch output, CLI mappings, durable telemetry, counters, and outbox records. Report implementation continuations, schema retries, audit recurrence, repair outcomes, review generations, and carried findings from durable records without exposing raw phase output. Align crash, resume, operator-decision, changed-delta, concurrent dirty-worktree, standalone, and goal-child production paths.

## Acceptance Criteria

1. Compatible legacy active workflows reconcile exactly once, while incompatible state loud-fails or quarantines without silent deletion.
2. Status, watch, and telemetry derive bounded explanations from durable records rather than aggregate agent claims or raw phase output.
3. Production crash, resume, operator-decision, changed-delta, and concurrent-dirty-worktree paths preserve identical convergence and isolation guarantees in standalone and goal-child execution.
4. Production convergence guarantees keep partial implementation incomplete, allow a correct closure-complete audit repair to clear after at most one subsequent verification pass, and preserve carried review Blockers across repeated delta changes.
5. Aggregate counters remain derivable from append-only generation and disposition records.
6. Full final validation remains authoritative.

## Non-Goals

- Rewriting or completing SKILL-134.
- Rewriting or squashing feature branches.
- Using out-of-band database mutation as the normal migration path.
- Adding or modifying tests or test infrastructure.

## Dependency Notes

Depends on: 1, 2, 3, 4, 5, 6

This final integration subtask consumes every prior production contract and exposes their bounded durable evidence.

## Validation Strategy

Add or modify no tests, fixtures, test resources, or test infrastructure. Run:

```bash
skill-bill validate
(cd runtime-kotlin && ./gradlew check)
npx --yes agnix --strict .
scripts/validate_agent_configs
```

## Next Path

Complete the parent goal after authoritative final validation and PR preparation.

## Spec Path

.feature-specs/SKILL-151-trustworthy-feature-task-convergence/spec_subtask_7_legacy-reconciliation-observability-and-end-to-end-recovery.md
