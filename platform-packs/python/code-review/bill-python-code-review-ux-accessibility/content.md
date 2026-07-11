---
name: bill-python-code-review-ux-accessibility
description: Review accessibility and UX of Python-rendered forms, templates, dashboards, validation feedback, keyboard flow, and localization-sensitive copy.
internal-for: bill-code-review
---

# Python UX Accessibility Review

Review whether Python-rendered interfaces support understanding, operation, recovery, and assistive-technology use.

## Focus

- Semantic structure, forms, errors, keyboard and focus behavior, assistive technology, dynamic fragments, localization, and truthful copy

## Ignore

- Defer non-accessibility UI behavior and rendering correctness to the `ui` specialist.
- Defer escaping, injection, and other trust-boundary findings to the `security` specialist.
- Pure visual taste that does not impair accessibility, understanding, recovery, or task completion

## Applicability

Use this specialist for server-rendered templates, Django forms, WTForms, admin flows, HTMX-style swaps, dashboards, generated reports, validation feedback, keyboard flows, and localization-sensitive UX.

## Project-Specific Rules

### Python-Owned Accessibility Rules

- Require Django `Form` and WTForms controls to render associated `label`, help text, required status, and field errors through stable IDs; missing relationships create invalid form semantics for screen readers.
- Require an error summary linked through `aria-describedby` to invalid controls and move focus after failed submission; missing focus makes rejection errors undiscoverable to keyboard users.
- Verify Python-rendered templates use `main`, `nav`, headings, table headers, and native button or link semantics before ARIA; generic containers create invalid document structure and navigation failure.
- Require modal or dialog widgets to expose `role="dialog"` or the detected PyQt/Tk accessibility API, initial focus, Escape behavior, and focus restoration; broken lifecycle can trap focus and fail keyboard access.
- Require every Python-owned custom control to expose native-equivalent keyboard activation and state through `aria-pressed`, Qt properties, or its framework API; mouse-only widgets create an accessibility failure.
- Verify dynamic updates use `aria-live`, Dash loading state, Streamlit status, or desktop accessibility notifications only for meaningful changes; silent failures and excessive announcements both prevent task completion.
- Require focus to move through `autofocus`, an HTMX swap hook, or widget focus API after fragment replacement; unexpected reset creates a keyboard navigation regression and task failure.
- Reject charts, heatmaps, admin badges, and notebook outputs that communicate status only through color; require an `alt` summary, symbols, patterns, or table data or critical contract state is invalidly hidden.
- Require configured foreground, background, and focus styles to pass the repository's `axe` or contrast check in templates and desktop themes; low contrast can hide controls and cause task failure.
- Require feedback rendered through Django `messages`, Streamlit status, or desktop notifications to reflect durable backend state and recovery; premature success causes destructive action repetition and data-loss risk.
- Verify `gettext`, `ngettext`, Babel, and locale-aware date, number, and timezone formatting wrap user-visible Python strings; hard-coded grammar or server locale causes invalid localized output.
- Require Jupyter notebooks and generated reports to provide Markdown `#` headings, table headers, plot alt summaries, and logical reading order; visual-only output creates an accessibility failure in exported artifacts.
- Require an accessible `DataTable` or textual alternative for interactive Dash, Panel, or Streamlit visualizations when chart semantics cannot expose the data; absent alternatives cause analysis failure for assistive-technology users.
- Require large accessible tables rendered by `DataTable` to paginate or virtualize without dropping headers and names; unbounded DOM resources cause performance failure and inaccessible navigation.
- For Blocker or Major findings, describe the concrete accessibility or task-completion failure scenario.
