# SKILL-65.1 · Subtask 5 — Decomposition Mode and Planning Stop

Parent: [SKILL-65.1 full parity](./spec.md)
Issue key: SKILL-65.1
Status: Draft

## Scope

Give the runtime's `plan` phase the `mode: decompose` terminal outcome that
`bill-feature-task` Step 3 has (`SKILL.md:175-183`, 984): when a plan would be
oversized (the standard thresholds: >15 atomic tasks, >6 boundaries, multiple
independently-resumable milestones, or verify-separately sequencing), the plan
phase returns a decomposition package and the run **stops** at planning instead
of implementing — writing subtask specs and a parent `decomposition-manifest.yaml`
through the shared feature-spec preparation path. When the run is a goal-driven
subtask (subtask 7), decomposition is skipped (the goal already decomposed).

## Acceptance Criteria

1. The `plan` phase can emit a decompose outcome (`status`/`produced_outputs`
   carrying the decomposition package) distinguishable from an ordinary
   implementable plan.
2. On a decompose outcome the runtime does **not** advance to `implement`:
   it terminates the run as an intentional planning-stage stop (its durable
   workflow status reaches a terminal "decomposed/abandoned-at-planning"
   equivalent, not "failed"), records the reason, and reports it loudly.
3. The runtime writes/updates the parent `spec.md`, ordered `spec_subtask_*.md`
   files, and `.feature-specs/{ISSUE_KEY}-{feature-name}/decomposition-manifest.yaml`
   via the shared feature-spec preparation path (no one-off writer), validating
   the manifest against `orchestration/contracts/decomposition-manifest-schema.yaml`.
4. The decompose terminal outcome is surfaced in `status`/`--monitor` with the
   subtask count and the "work the first subtask first" guidance, equivalent to
   the standard flow's decomposition summary.
5. When invoked as a goal-driven subtask (the goal-continuation context from
   subtask 7 is present), the plan phase **skips decomposition** and proceeds to
   implement the single governed subtask spec — it must not re-decompose work the
   goal already decomposed.
6. The phase-output schema and contract version are unchanged.

## Non-Goals

- No re-implementation of the shared feature-spec preparation path or the
  manifest schema — this subtask *invokes* them.
- No automatic execution of the generated subtasks (that is a fresh run / the
  goal runner's job).

## Dependency Notes

- Depends on: subtask 2 (the `plan` phase that gains the decompose outcome).
- Soft coupling with subtask 7: the "skip decomposition under goal continuation"
  branch relies on the goal-continuation context subtask 7 introduces; gate that
  branch on the context's presence so ordering stays safe.

## Validation Strategy

- Tests: oversized plan → decompose terminal stop (workflow not advanced to
  implement, manifest written + schema-valid); ordinary plan → proceeds to
  implement; goal-continuation context present → decomposition skipped.
- Manifest round-trip validation against the decomposition-manifest schema.
- `(cd runtime-kotlin && ./gradlew check)` and `skill-bill validate` pass.

## Next path

Proceed to [subtask 6 — Lifecycle Telemetry and Stats](./spec_subtask_6_lifecycle-telemetry-and-stats.md),
or run `skill-bill goal SKILL-65.1`.
