# SKILL-184 · Subtask 2 — Render planning and current-phase execution in IntelliJ

## Scope

Plugin-side change under `intellij-plugin`, consuming the additive runtime
projection from subtask 1:

- Update `presentation/SkillBillStatusBarPresentation.kt` to convert the
  supported wire planning states to readable labels and compose the full
  planning line from the parsed state and counts.
- While planning is relevant, source the compact/detail progress pair from
  `plannedSubtaskCount/totalSubtaskCount`; do not apply the execution
  `completed + 1` current-subtask interpretation before a subtask is running.
- Once execution begins, render the current-phase execution value from the
  runtime projection. Looping phases such as audit and review show their
  semantic loop/pass number; phases with only an attempt or gate counter label
  that value honestly.
- Preserve a compact bounded execution segment for the status bar while sending
  the full line to the tooltip and accessibility description.
- Extend the popup-facing `StatusBarDetails` and
  `ui/StatusDetailsPopupContent.kt` so the clicked details view contains one
  `Planning` row when planning is relevant.
- Keep `StatusUiMapper`'s existing relevance rule: planning is hidden for
  `prepared` state or once a current subtask or completed execution progress
  shows that implementation has started.
- Update the IntelliJ plugin README status-bar behavior description if needed
  to document the full planning line.

The runtime producer and schema are delivered by subtask 1. The plugin must
remain tolerant of absent, malformed, or older-runtime optional execution
data.

## Acceptance Criteria

1. A relevant `GoalPlanningInfo` with state `partially_planned`, planned count
   `10`, and total count `15` produces the exact full line
   `Planning: partially planned, 10/15 plans saved` in the tooltip,
   accessibility description, and popup planning row.
2. Supported planning state values render as readable labels; the counts are
   rendered from `plannedSubtaskCount` and `totalSubtaskCount` without changing
   their wire semantics.
3. With one saved plan out of fifteen and no current subtask, the compact and
   detail progress pair renders `1/15` from planning counts; it does not derive
   `1/15` by treating the first execution subtask as active.
4. The status-bar text remains within `BAR_TEXT_MAX_LENGTH` and retains a
   concise planning segment such as `Planning 1/15`.
5. An active audit execution renders its authoritative audit-gap loop number,
   and an active review execution renders its authoritative review pass/loop
   number, in both the compact slot and full detail surfaces.
6. A phase with an attempt or validation-gate counter renders that counter with
   an honest label and does not invent a loop total.
7. Planning text and planning-based progress are absent from the bar, tooltip,
   accessibility description, and popup after execution starts; the current
   phase execution value replaces it. Non-goal workflows preserve their
   existing behavior.
8. A stale mid-planning state retains stale treatment and the full planning
   line; absent or malformed optional execution data retains the existing
   fallback behavior.
9. The popup renders exactly one planning or current-phase execution row when
   relevant and none when no value is available.
10. Existing mapper, UI-mapper, presentation, and fixture tests continue to
   cover the unchanged lifecycle, progress, elapsed-time, and control behavior.
11. `./gradlew check` passes from `intellij-plugin`.

## Non-Goals

- No runtime computation or schema changes in this subtask; subtask 1 owns the
  additive wire projection.
- No new status command, contract version, plugin setting, tool window, or
  notification.
- No changes to planning or loop computation, execution progress semantics,
  lifecycle mapping, pause controls, or status polling.
- No rendering of raw planning reasons or planning artifacts.

## Dependency Notes

The subtask depends on the existing `planning` field and `GoalPlanningInfo`
parsing delivered by the completed `SKILL-165` feature, plus subtask 1's
current-phase execution wire projection.

## Validation Strategy

Add only behavior tests that catch presentation regressions:

- `SkillBillStatusBarPresentationTest` for state wording, exact planning text,
  planning-based compact progress, audit/review execution labels and counts,
  compact bar bounds, tooltip/accessibility output, and hidden-state behavior.
- `SkillBillStatusBarWidgetFixtureTest` for the popup planning row and its
  replacement current-phase execution row.
- `ProcessRunnerAndMapperTest` for optional execution-data parsing and
  malformed-data degradation.
- `StatusUiMapperTest` for planning relevance and post-planning handoff.

Run:

```bash
cd /home/sermilion/StudioProjects/skill-bill/intellij-plugin
./gradlew check
```

## Next Path

Build the plugin archive and verify planning, audit-loop, and review-pass
display against live `skill-bill work status --format json` responses.
