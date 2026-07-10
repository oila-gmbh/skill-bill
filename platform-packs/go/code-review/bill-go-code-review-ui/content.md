---
name: bill-go-code-review-ui
description: Use when reviewing Go UI correctness and framework usage for html/template, templ, server-rendered forms, htmx-style fragments, terminal UI, dashboards, and interaction state.
internal-for: bill-code-review
---

# UI Review Specialist

Review only UI correctness and framework-usage issues that can break the rendered experience or make the server-rendered surface behave inconsistently.

## Focus

- `html/template`, `text/template`, templ, htmx-style fragments, generated reports, terminal UI, dashboards, or similar Go UI surface correctness
- Form wiring, validation rendering, and state-sync behavior
- Component ownership and source-of-truth clarity between server and client
- Broken navigation, modal, table, and interactive admin flows
- Render-path behavior that causes obviously incorrect or unstable UI output

## Ignore

- Pure visual taste feedback
- Copy edits without product or UX impact
- Accessibility-only findings that belong to `bill-go-code-review-ux-accessibility`
- Security-only escaping, authorization, or sensitive-data findings that belong to `bill-go-code-review-security`

## Applicability

Use this specialist when changed Go code affects server-rendered templates, interactive fragments, forms, terminal UI, dashboards, or other user-visible rendering and state behavior.

## Project-Specific Rules

### Review Rules

- Verify `html/template` rendering boundaries preserve UI state invariants and surface failures
- Keep business logic out of templates and render hooks unless the project explicitly uses that shape
- Server-rendered templates should receive already-shaped view data rather than performing hidden cross-module orchestration in the view
- Form defaults, submitted values, validation messages, disabled states, and submit affordances must match the backend contract
- Conditional rendering must preserve state-machine correctness across success, empty, loading, and error states
- Server-rendered component state must not trust client-mutated values without explicit server-side validation and authorization
- Interactive components must preserve a clear source of truth; do not split ownership of the same state across controller, view, and client hooks without an explicit synchronization model
- Rendering helpers, template functions, and view-model builders must not trigger hidden N+1 queries or repeated heavy work on common paths
- Route generation, signed links, and action targets used by UI surfaces must stay aligned with the backend contract
- Tables, filters, pagination widgets, and bulk-action surfaces must preserve deterministic behavior when multiple filters or selection states are active
- Server-rendered HTML should degrade gracefully when JavaScript is unavailable unless the product explicitly requires a JS-only interaction model
- In findings, explain the rendered or interactive behavior a user or operator would actually experience
- For Blocker or Major findings, describe the concrete user-visible interaction or rendering failure scenario.
