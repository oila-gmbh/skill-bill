# SKILL-168 · Subtask 5 — Plugin goal controls and popup presentation

## Scope

Give the status widget two operator controls and make its details popup legible. Entirely
within `intellij-plugin/`.

Controls, both shown only while an incomplete goal exists for this repository:

- **Stop** — invokes the immediate stop verb from subtask 3.
- **Pause after current subtask** — invokes the existing `skill-bill goal pause <KEY>`, and
  becomes disabled once a pause is pending, so the operator can see the request registered.

### Already written and reusable

Landed on `main` ahead of this subtask, unwired:

- `application/GoalPauseRepository.kt` — port returning `Requested` / `Failed(summary)`.
- `infrastructure/cli/CliGoalPauseRepository.kt` — runs
  `goal pause <key> --repo-root <canonical>`.

A sibling stop repository should follow the same shape. Both must keep the property
documented in `CliGoalPauseRepository`: they run on **their own `ProcessRunner`**, because
`ProcessRunner.runCoalesced` coalesces per instance, so a mutating call sharing the poll
runner would join an in-flight status poll and return that poll's exit code as its own
result.

### Design decision: eligibility comes from the snapshot, not from local memory

Show the controls when `lifecycle_state` is `active`, `workflow_family` is `feature-goal`,
and `issue_key` is present. Disable the pause control when subtask 4's pause-requested flag
is true. Deriving both from the polled snapshot — rather than from a local "I clicked it"
flag — means the disabled state is correct for a pause requested from the CLI, and survives
an IDE restart.

Optimistic local disable between the click and the next poll is acceptable *in addition*,
so the button does not appear inert for up to a poll interval, but it must never be the
only source of truth.

### Design decision: honest labels

`goal pause` lands at the next subtask boundary, which can be minutes away. Label the
control for what it does — pause *after the current subtask* — and, once requested, say the
request is registered rather than claiming the goal is paused. The whole point of this
feature is that the widget stops overstating what it knows.

Stop is deliberately labelled Stop, not Pause: it terminates work in flight.

### Design decision: no Resume

Launching a goal already clears a pause (`GoalRunner.resumeForRun`,
`GoalRunner.kt:247-265`), and `goal resume` explicitly does not start child runs. A Resume
control would clear a flag, flip the widget to active, and start nothing.

### Presentation

The popup is currently a single unpadded `JLabel` of `<br/>`-joined HTML built inline in
`SkillBillStatusBarWidget.onClick` (`:151-171`). Replace it with a laid-out panel: padded
edges, label/value alignment, visual separation between the status block and the action
row, and theme-derived colours rather than hardcoded ones. Keep the detail *content*
unchanged — the existing lines are covered by presentation tests.

Popup construction belongs behind a testable seam, not inline in the click handler; the
widget must stay a thin consumer per its own class contract.

### Architecture-policy reversal

`intellij-plugin/README.md` states the plugin offers no "start / resume / retry / cancel /
abandon actions" and lists "Workflow mutation" under what the release excludes;
`ARCHITECTURE.md` carries the same read-only framing. This subtask makes that false and must
update both deliberately, naming the two mutating verbs and the reason they are safe (bounded
CLI invocations, no database access, no process control in the plugin).
`PluginArchitectureTest` forbids runtime/JDBC/SQLite imports only, so nothing fails
automatically — the policy is documentation-enforced, which is exactly why it must be edited
rather than left to drift.

## Acceptance Criteria

1. A Stop control and a Pause-after-current-subtask control appear in the status details
   popup only when the snapshot reports `lifecycle_state: active`, `workflow_family:
   feature-goal`, and a non-null `issue_key`; they are absent for every other state.
2. Activating Stop invokes the runtime stop verb for the snapshot's issue key and the
   canonical project root, off the EDT.
3. Activating Pause invokes `goal pause` for the snapshot's issue key and the canonical
   project root, off the EDT.
4. The pause control renders disabled whenever the snapshot reports a pause as requested and
   not yet consumed, including when the request originated from the CLI and after an IDE
   restart, and its text communicates that the request was registered.
5. Each mutating repository runs on a `ProcessRunner` instance not shared with status
   polling, so a mutating call can never return a status poll's result.
6. A failed or refused mutating call surfaces a bounded, human-readable summary and never
   leaks raw process output, stderr, or filesystem paths; the widget continues polling and
   the next snapshot remains authoritative.
7. Both controls are keyboard reachable and carry accessible names describing their effect,
   consistent with the widget's existing accessible-name handling.
8. The popup renders as a padded, aligned panel with the action row visually separated from
   the status lines, using theme-derived colours; existing detail lines and their content
   are unchanged.
9. Popup construction is exercisable in tests without showing a Swing popup, consistent with
   the widget's existing unit-test-mode handling.
10. `README.md` and `ARCHITECTURE.md` describe the controls, name the two mutating verbs,
    and no longer claim the plugin performs no workflow mutation.
11. The plugin still depends only on `com.intellij.modules.platform`, still reads no Skill
    Bill database, and still performs no process termination itself; `PluginArchitectureTest`
    stays green.

## Non-Goals

- No Resume, Start, retry, abandon, or replan controls.
- No tool window; the widget popup is the only surface.
- No goal launching from the IDE.
- No settings UI for the controls.
- No change to poll cadence, the coalescing policy for status polls, or the elapsed-clock
  ticker.
- No new wire fields; subtask 4 owns the contract.

## Dependency Notes

Depends on subtask 3 for the stop verb and on subtask 4 for the pause-requested flag that
drives AC 4. The Stop control and the popup presentation work (AC 1–3, 6–11) could proceed
against subtask 3 alone, but AC 4 cannot be satisfied until subtask 4 lands, so this subtask
is sequenced after both.

No dependency on subtasks 1 or 2, though subtask 1 also edits
`StatusRefreshCoordinator`; if both are in flight, expect a merge point there.

## Validation Strategy

- ViewModel/presentation unit tests for eligibility across every lifecycle state and
  workflow family (AC 1) and for the disabled-pause rendering (AC 4), using the existing
  `TestFakes.kt` seams.
- Repository tests with a fake `ProcessFactory` asserting the exact argv for both verbs
  (AC 2, 3) and asserting the failure surface carries no stderr or path content (AC 6).
- A test asserting the mutating repositories do not share a `ProcessRunner` with the status
  path — for example, that a long in-flight poll does not complete a concurrent pause
  (AC 5).
- A widget fixture test constructing the popup in unit-test mode and asserting padding,
  action-row presence, and accessible names (AC 7, 8, 9), extending the existing
  `SkillBillStatusBarWidgetFixtureTest`.
- Full `./gradlew check` in `intellij-plugin`, plus `verifyPlugin` against the declared
  2025.2 and 2026.1 baselines since the popup gains new Swing components.

## Next Path

Goal complete. If a tool window is ever built, the eligibility predicate and both
repositories move to it unchanged; only the rendering seam is widget-specific.
