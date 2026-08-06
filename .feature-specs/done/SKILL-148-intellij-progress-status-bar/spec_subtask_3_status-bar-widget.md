# SKILL-148 · Subtask 3: status-bar widget

## Scope

Register a project-aware IntelliJ status-bar widget that renders the ViewModel state
from subtask 2. Keep the widget thin: it chooses concise text, tooltip, accessibility
metadata, and a bounded visual progress treatment from already-mapped UI state. A click
may trigger an immediate refresh or a small informational popup, but must not mutate a
workflow.

## Acceptance Criteria

1. The plugin registers a `StatusBarWidgetFactory` whose extension ID matches the factory and widget IDs and whose visibility is scoped to normal project windows with a resolvable project context.
2. The widget subscribes to the project-scoped ViewModel, updates through IntelliJ-safe UI scheduling, and disposes its subscription and project consumer without leaking coroutines, processes, listeners, or project references.
3. Active work displays `Skill Bill · <step label>` plus compact goal/work and current-subtask elapsed durations, and includes `<completed>/<total>` only when the contract provides a valid bounded total; long or unsafe labels are normalized and truncated for the status bar while full safe context remains in the tooltip.
4. Goal/work elapsed time is calculated from authoritative `started_at`; subtask elapsed time is calculated independently from authoritative `subtask_started_at`; missing legacy timestamps render as unavailable rather than zero, and negative durations caused by clock skew clamp to zero.
5. Elapsed durations update from a lightweight local UI ticker without launching a CLI poll per tick; a new status snapshot re-anchors the ticker, and disposal stops it.
6. Idle, stale, blocked, failed, unavailable, and incompatible states have distinct concise text and tooltip semantics; only confirmed active work may use an activity/progress animation, and stale cached state is visibly marked stale.
7. The widget exposes an accessible name and description containing Skill Bill, lifecycle state, current step, both available elapsed durations, and progress where present, without relying on color or animation alone.
8. A user click performs only a read-only action: an immediate coalesced refresh or a small details popup showing safe issue/workflow, state, step, progress, both elapsed durations, last update, and typed problem summary.
9. Widget enablement and display are isolated per IntelliJ project, including two open projects with different Skill Bill states and one project with no matching work.
10. Presentation tests cover every UI state, both elapsed clocks, missing timestamps, clock skew, progress-bound validation, truncation, tooltip and accessibility text, click behavior, update scheduling, factory identity, and disposal.
11. IntelliJ fixture tests demonstrate widget registration, project close/reopen behavior, polling and ticker cancellation, and independent updates in multiple project contexts.
12. Plugin documentation includes a screenshot or documented expected states, setup for the Skill Bill CLI path, refresh and elapsed-time behavior, troubleshooting for unavailable/incompatible status, and the explicitly deferred full tool window.

## Non-Goals

- Workflow start, resume, retry, cancel, or abandon actions.
- A full tool window, notification center, event timeline, logs, or artifact viewer.
- Streaming status events.
- Remote Development/Split Mode behavior in this release.
- Marketplace publication and signing.

## Dependency Notes

- Depends on subtask 1's IDE status contract and subtask 2's plugin architecture,
  ViewModel, and project-scoped services.
- No later subtask is required for the initial status-bar release.

## Validation Strategy

- Run pure presentation tests for text, tooltip, accessibility, and click mappings.
- Run IntelliJ Platform fixture tests for registration, updates, project isolation, and
  disposal.
- Build and install the plugin into a sandbox IDE, start fixture Skill Bill work, and
  observe active, step transition, blocked, terminal, stale, and unavailable states.
- Run Plugin Verifier for the declared supported IDE range and the full plugin `check`.
- Run `git diff --check`.

## Next Path

Release the read-only status-bar slice, gather usage feedback, and prepare a separate
spec for the full Skill Bill tool window.
