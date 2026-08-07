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
  Planning is relevant only until implementation starts. Once it starts —
  planning state is `prepared`, or the snapshot reports execution work (a current
  subtask present, or `progress.completed > 0`) — the planning segment and the
  pre-planning line are dropped from both headline and tooltip, and rendering
  keeps today's behavior (execution progress and step label) apart from the
  progress-numerator fix below. Planning info is never re-shown after execution
  has been observed.
- `SkillBillStatusBarPresentation.validProgress`/`progressText`: the rendered
  numerator becomes the **current** subtask position rather than the completed
  count. With `completed = 1, total = 4` and a subtask in flight the widget must
  read `2/4`, not `1/4` — today's output reads as "1 of 4 done" and contradicts
  the step/subtask elapsed shown beside it. Rule: for `Active`/`Stale` with
  `completed < total`, render `min(completed + 1, total)`; otherwise render
  `completed`. `total` and the wire fields are unchanged. This is independent of
  the planning segment above — planning counts subtasks planned so far, execution
  progress marks the running subtask; both may appear at once with different
  numerators, and neither derives from the other.
- Distinct paused presentation: `IdeStatusJsonMapper` currently folds a fresh
  `lifecycle_state: "paused"` into the `Active` outcome (`"active", "paused" ->
  Active`), so a paused goal is indistinguishable from a running one. Introduce a
  paused representation (a `Paused` outcome or an equivalent flag carried through
  `StatusUiMapper` / `SkillBillStatusUiState` / `SkillBillStatusBarPresentation`)
  whose headline names the paused state (e.g. `Skill Bill: SKILL-165 · paused`)
  and whose tooltip keeps step, progress, and elapsed anchors. Elapsed durations
  for paused work anchor to the last authoritative update (settled), not
  wall-clock now: no live-activity spinner and no ticking clocks (observed live
  2026-08-07 09:58Z — a paused SKILL-164 goal with no running process showed the
  Active spinner and a subtask timer advancing in real time). Stale masking is
  unchanged: a stale paused snapshot still maps to the `Stale` outcome.

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
3a. Planning disappears once implementation starts: a snapshot with planning
   state `prepared`, and a snapshot with a non-`prepared` planning state but
   execution work reported (current subtask present or
   `progress.completed > 0`), both render no planning segment and no
   pre-planning line — asserted in presentation tests for headline and tooltip.
4. Stale goal snapshots mid-planning keep the stale treatment and still show
   the planning segment.
5. Execution progress renders the current position: an `Active` state with
   `completed = 1, total = 4` produces `2/4` in both the bar segment and the
   `Progress:` tooltip line; `completed = 0, total = 4` produces `1/4`;
   `completed = 4, total = 4` produces `4/4` (never `5/4`). Covered by
   `SkillBillStatusBarPresentationTest` cases for in-flight, zero-completed, and
   all-complete, plus a `Stale` case.
6. A fresh `lifecycle_state: "paused"` payload renders the paused presentation
   (headline names the paused state; tooltip keeps step/progress/elapsed); a
   stale paused payload still renders the `Stale` treatment; `active` payload
   rendering is byte-for-byte unchanged (mapper and presentation tests cover
   all three).
7. Plugin architecture test (`PluginArchitectureTest`) and existing
   presentation/mapper suites stay green (existing progress assertions updated
   to the new numerator, not deleted).

## Non-Goals

- No runtime/schema changes (done in subtask 1); the progress-numerator fix is
  presentation-only and must not touch `progress.completed` on the wire.
- No new plugin settings, actions, or widget popups.
- No rendering changes for Blocked/Failed/Idle/Unavailable/Incompatible states
  beyond leaving them untouched (the paused presentation is new, not a change
  to those states).
- No pause-reason plumbing on the wire (e.g. distinguishing an operator pause
  from a needs-operator-decision pause); the wire summary line remains the only
  reason carrier.

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
