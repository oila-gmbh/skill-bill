---
name: bill-python-code-review-ui
description: Review Python-rendered UI, admin flows, templates, notebooks, dashboards, forms, and generated reports.
internal-for: bill-code-review
---

# Python UI Review

Focus on UI surfaces that are authored, rendered, or orchestrated by Python.

## Review Focus

- Server-rendered UI: Django/Jinja templates, forms, admin pages, HTMX-style fragments, email templates, and report templates.
- Python dashboard surfaces: Streamlit, Dash, Panel, notebook outputs, generated HTML/PDF reports, and internal operations dashboards.
- State and validation: form defaults, validation display, navigation after actions, flash/error messages, pagination/filter state, and permission-aware UI state.
- Framework behavior: template context ownership, escaping defaults, static/media asset references, admin customization, and route/view consistency.
- Data presentation: formatting, timezone/locale display, empty states, loading/error states, and avoiding misleading aggregations.

## Findings Standard

Report UI behavior bugs, confusing state, broken rendering, missing permission constraints, or mismatches between backend state and what the user sees.
