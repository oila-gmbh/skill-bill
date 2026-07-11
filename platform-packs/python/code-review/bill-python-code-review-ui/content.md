---
name: bill-python-code-review-ui
description: Review Python-rendered UI, admin flows, templates, notebooks, dashboards, forms, and generated reports.
internal-for: bill-code-review
---

# Python UI Review

Review user-visible behavior authored, rendered, or orchestrated by Python.

## Focus

- Templates, forms, admin flows, HTMX fragments, notebooks, dashboards, reports, state, validation, navigation, and rendering behavior

## Ignore

- Defer accessibility-only findings to the `ux-accessibility` specialist.
- Defer escaping, injection, and other trust-boundary findings to the `security` specialist.
- Pure visual taste without a user-visible interaction, rendering, state, or comprehension failure

## Applicability

Use this specialist for Django or Jinja templates, forms, admin pages, HTMX-style fragments, email and report templates, Streamlit, Dash, Panel, notebooks, generated HTML/PDF, and operations dashboards.

## Project-Specific Rules

### State and Interaction

- Require a single source of truth for server and client state; reject duplicated form, filter, pagination, selection, or permission state that can render stale or contradictory UI.
- Require intentional form defaults, preserved input, validation display, navigation after actions, flash and error messages, empty/loading/error states, and permission-aware controls.
- Require server-rendered actions and navigation to degrade gracefully without JavaScript when the product boundary promises progressive enhancement; reject core tasks available only through an optional script path.
- Preserve timezone, locale, aggregation, formatting, and route/view consistency so the UI does not misrepresent backend state.

### Rendering and Data Access

- Reject template helpers, inclusion tags, properties, and `context_processors` that execute per-row ORM access or repeated downstream calls and create an N+1 latency failure.
- Verify template context ownership, static and media references, admin customization, fragments, notebooks, dashboard callbacks, and generated reports render under empty, partial, and error data.
- Require user-visible state transitions to reflect durable backend outcomes rather than optimistic success that can diverge after a failed request.
- For Blocker or Major findings, describe the concrete user-visible interaction or rendering failure scenario.
