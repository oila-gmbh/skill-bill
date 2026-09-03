# SKILL-228 · Subtask 2: IdeStatus and goal blocked visibility

## Scope

Project operator-visible blocked state when a child validate/build phase is
`BLOCKED` with `NEEDS_USER_ACTION`. Extend `operatorDecisionPause` and
`IdeStatusProjector.goalLifecycle` so IDE status and `skill-bill work status`
surface actionable blocked reasons instead of showing active/running validate.

Update `ide-status-schema.yaml`, IntelliJ status mappers, and golden fixtures.

## Acceptance Criteria

1. When the current child workflow has validate/build `BLOCKED` +
   `NEEDS_USER_ACTION`, IdeStatus reports `lifecycle_state: blocked` (not
   `active`) with summary/pause reason containing actionable operator text.
2. `operatorDecisionPause` surfaces `NEEDS_USER_ACTION` on blocked quality-gate
   phases, not only on `PAUSED` records.
3. Goal issue progress / monitor output reflects non-zero blocked state with the
   operator reason (WE-4364-shaped fixture: no `blockedCount == 0` while blocked).
4. IdeStatus projector and schema validator tests cover blocked lifecycle with
   operator reason.

## Constraints

- IntelliJ and `skill-bill work status` are in scope; VS Code extension parity is
  follow-up only.
- Reuse existing goal/blocked propagation; do not introduce a parallel status
  system.

## Validation Strategy

- `IdeStatusServiceGoalProjectionTest` and `IdeStatusGoldenFixturesTest`.
- `skill-bill work status` / goal status integration coverage for blocked reason
  visibility.
