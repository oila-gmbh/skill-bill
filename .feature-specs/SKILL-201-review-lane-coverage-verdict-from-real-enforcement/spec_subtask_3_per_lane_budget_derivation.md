# SKILL-201 Subtask 3 — Reconcile max_lane_evidence_bytes meaning and per-lane budgets

## Intended Outcome

`max_lane_evidence_bytes` has exactly one meaning across the runtime. Surviving per-lane byte budgets
scale with what a lane was assigned instead of applying one flat value to lanes owning 100% and 10%
of the changed paths. The parent derivation at `ParallelCodeReviewRunner.kt:1568` either resolves to
the same meaning as the specialist value or is replaced by a separately named parent budget.

## Scope

- `ReviewContextModels.kt`: `ReviewContextBudgetPolicy` and its `init` requirements; the budget line
  the lane launch prints around `:1309`.
- `ParallelCodeReviewRunner.kt`: parent budget derivation at `:1568`.
- `ReviewPreparationService.kt` if per-assignment budget derivation lands alongside
  `composeAssignments`.

## Acceptance Criteria

1. Every per-lane byte budget that survives is derived from the lane's own assignment rather than applied as one flat value across lanes with different assigned path counts, or the flat value is documented as intentional at its definition with the reason.
2. `max_lane_evidence_bytes` has exactly one meaning across the runtime. The `budget.maxLaneEvidenceBytes * laneCount` derivation at `ParallelCodeReviewRunner.kt:1568` either resolves to the same meaning as the specialist value or is replaced by a separately named parent budget.
3. A test asserts two lanes with different assigned path counts receive different derived budgets when assignment-scaled derivation is the chosen approach.
4. A test asserts one consistent meaning across specialist and parent derivations.
5. `(cd runtime-kotlin && ./gradlew check)` passes.
6. `skill-bill validate`, `npx --yes agnix --strict .`, and `scripts/validate_agent_configs` pass.

## Non-Goals

- Reporting seam or schema changes (subtask 4).
- End-to-end delegated review reproduction (subtask 5).
- Raising `max_lane_evidence_bytes` in `.skill-bill/config.yaml`.
- Changing lane selection or which paths an area owns.

## Dependency Notes

Depends on subtask 2. Budget derivation should reflect the corrected incomplete semantics from
subtasks 1 and 2.

## Validation Strategy

Test that two lanes with different assigned path counts receive different derived budgets. Test one
consistent meaning across specialist and parent derivations. Update `ReviewPreparationServiceTest` if
budget derivation lands in `composeAssignments`.

## Next Path

Proceed to subtask 4.
