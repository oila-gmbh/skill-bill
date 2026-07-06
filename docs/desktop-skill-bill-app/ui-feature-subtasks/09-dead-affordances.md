# Subtask 09: Dead and Misleading Affordances

Status: Complete

## Parent Context

This subtask belongs to
`docs/desktop-skill-bill-app/ui-feature-task-specs.md`. It cleans up
UI elements that announce themselves as buttons (visual chrome, hover
affordance, `Role.Button` semantics) but carry an empty or missing `onClick`,
producing a "looks broken" experience for both pointer and screen-reader users.

## UI Entry Points

- Top toolbar source-control button (the branch label rendered as a
  `ToolbarButton`).
- Top toolbar read-only mode chip (the yellow primary `ToolbarButton`).
- Left navigation pane `Validation` row (the `RepositoryAction` with the issue
  badge).
- Left navigation pane `Read-only browsing` row.
- Top toolbar `NEW...` action (the ellipsis label currently bypasses a kind
  picker and jumps straight to a single hardcoded `ScaffoldKind`).

## Goal

Every element that is rendered with button affordance and exposes
`Role.Button` semantics must either invoke a real handler or be reshaped into a
non-interactive label. The toolbar `NEW...` ellipsis must match its label by
opening a kind picker rather than a single hardcoded wizard variant.

## Scope

- Audit every call site that uses `.clickable(...)` with `Role.Button` and
  confirm the `onClick` is non-empty and routed to a real route callback.
- Toolbar source-control button: either route to a real action (jump to
  `DockTab.Changes`, surface a branch-info popover, or open the configured
  compare URL) or demote to a non-interactive status label that drops
  `Role.Button` and `.clickable`.
- Toolbar read-only mode chip: same treatment; either toggle a documented
  read-only mode or demote to a labeled status pill that drops
  `Role.Button` and `.clickable`.
- Sidebar `Validation` row: route the click to activating `DockTab.Validation`
  and (if not currently running) triggering `runValidate()` only when the
  badge is non-zero; otherwise demote to a non-interactive header.
- Sidebar `Read-only browsing` row: demote to a non-interactive indicator
  (the read-only contract is mode-wide, not a per-row toggle).
- Toolbar `NEW...` action: either rename to `New horizontal skill...` so the
  hardcoded `ScaffoldKind.HORIZONTAL_SKILL` matches the label, or open a
  lightweight kind picker that mirrors the in-wizard `KindPicker` so each
  supported `ScaffoldKind` is reachable from a single click.

## Runtime and Service Requirements

- No new runtime services. Wire-up only routes existing route callbacks
  already available in `SkillBillRoute`.
- For the `NEW...` kind menu (if chosen over a rename), reuse
  `ScaffoldKind.values()` — do not introduce a parallel enum.

## Acceptance Criteria

- No `.clickable(...)` call in `SkillBillFrame.kt` (or files it composes)
  has a literal empty lambda or a missing `onClick` parameter.
- Every visible element with button affordance either performs a defined
  action or is rendered without `Role.Button` and without
  `.clickable(...)`.
- The sidebar `Validation` row activates the Validation dock tab.
- The sidebar `Read-only browsing` row is rendered as a labeled status
  indicator, not a button.
- The toolbar source-control element behaves consistently with its visual
  treatment: clickable if it performs an action, label-only otherwise.
- The toolbar read-only mode element behaves consistently with its visual
  treatment.
- The toolbar `NEW...` entry point opens every supported `ScaffoldKind`
  reachable from the wizard's existing `KindPicker`, either via a pre-wizard
  kind menu or by retitling to match a single hardcoded kind.
- Accessibility semantics (`Role.Button`, `contentDescription`,
  `disabled()`) match each element's real behavior — no element announces as
  a button while doing nothing.

## Validation

```bash
cd runtime-kotlin
./gradlew --no-configuration-cache :runtime-desktop:feature:skillbill:jvmTest
```

Manual smoke:

1. Hover every toolbar button and sidebar row; confirm cursor and visual
   affordance match real behavior.
2. With validation issues present, click the sidebar `Validation` row and
   confirm the dock switches to the Validation tab.
3. Click the toolbar `NEW...` entry point and confirm every supported
   `ScaffoldKind` is reachable without manually changing the picker inside
   the wizard.
4. Run the app with a screen reader and confirm no element announces as
   "button" while performing no action.

## Non-Goals

- New validation, commit, push, render, or scaffold logic.
- Adding new ViewModel operations beyond wiring existing callbacks.
- Multi-tab editing or per-row read-only toggles.
