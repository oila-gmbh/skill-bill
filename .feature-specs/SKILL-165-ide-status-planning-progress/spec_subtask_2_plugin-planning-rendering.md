# SKILL-165 Subtask 2 — Render planning progress in the IntelliJ status widget

## Scope

Plugin side only (`intellij-plugin`):

- `infrastructure/cli/IdeStatusJsonMapper.kt`: parse the optional `planning`
  object (state, shared_preplan_prepared, planned_subtask_count,
  total_subtask_count, optional current_planning_subtask_id, optional reason)
  into a small domain value. Malformed or partial planning objects degrade to
  "no planning info" — they must never fail the whole status mapping.
- `domain/SkillBillStatusOutcome.kt`: carry the optional planning value on the
  outcomes that render live goal work (`Active`, `Stale`).
- `presentation/StatusUiMapper.kt`, `SkillBillStatusUiState.kt`,
  `SkillBillStatusBarPresentation.kt`: while planning is relevant (planning
  present and state is not `prepared`):
  - headline shows a planning segment, e.g.
    `Skill Bill: SKILL-165 · Planning 1/4` (planned/total subtask counts);
  - tooltip/detail includes `Pre-planning: Done` when
    `shared_preplan_prepared` is true, otherwise `Pre-planning: In progress`;
  - tooltip/detail includes `Planning: <planned>/<total> subtasks`.
  When planning is absent or `prepared`, rendering is byte-for-byte today's
  behavior (execution progress and step label).

## Acceptance Criteria (this subtask)

1. `IdeStatusJsonMapper` maps a goal payload containing `planning` into an
   outcome carrying the planning value, and maps payloads without `planning`
   (or with a malformed `planning` object) exactly as today (mapper tests cover
   present, absent, and malformed cases).
2. With planning state not `prepared`, the widget headline contains
   `Planning <planned>/<total>` and the tooltip/detail contains both the
   pre-planning line (`Pre-planning: In progress` or `Pre-planning: Done`,
   driven by `shared_preplan_prepared`) and the planning-count line
   (presentation/view-model tests cover both pre-planning states).
3. With planning absent, `prepared`, or on non-goal families, headline and
   tooltip are unchanged from current behavior (regression-asserted in
   presentation tests).
4. Stale goal snapshots mid-planning keep the stale treatment and still show
   the planning segment.
5. Plugin architecture test (`PluginArchitectureTest`) and existing
   presentation/mapper suites stay green.

## Non-Goals

- No runtime/schema changes (done in subtask 1).
- No new plugin settings, actions, or widget popups.
- No rendering changes for Blocked/Failed/Idle/Unavailable/Incompatible states
  beyond leaving them untouched.

## Dependency Notes

Depends on subtask 1 (wire shape and emitting runtime must exist so end-to-end
verification against a real `skill-bill work status` payload is possible).

## Validation Strategy

Quality gate runs the plugin test suites (`ProcessRunnerAndMapperTest`,
`StatusUiMapperTest`, `SkillBillStatusViewModelTest`,
`SkillBillStatusBarPresentationTest`, `PluginArchitectureTest`) via the
intellij-plugin Gradle build. Build/test execution belongs to the validate
phase, not implement/review phases.

## Next Path

Feature complete after this subtask; rebuild the plugin zip and reinstall into
the IDE to observe planning progress on a live goal.
