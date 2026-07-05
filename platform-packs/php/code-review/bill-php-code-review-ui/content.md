---
name: bill-php-code-review-ui
description: Use when reviewing PHP UI correctness and framework usage for Blade, Twig, Livewire, Inertia, Filament, forms, components, and server-rendered interaction state.
internal-for: bill-code-review
---

# UI Review Specialist

Review only UI correctness and framework-usage issues that can break the rendered experience or make the server-rendered surface behave inconsistently.

## Focus

- Blade, Twig, Livewire, Inertia, Filament, or similar PHP UI surface correctness
- Form wiring, validation rendering, and state-sync behavior
- Component ownership and source-of-truth clarity between server and client
- Broken navigation, modal, table, and interactive admin flows
- Render-path behavior that causes obviously incorrect or unstable UI output

## Ignore

- Pure visual taste feedback
- Copy edits without product or UX impact
- Accessibility-only findings that belong to `bill-php-code-review-ux-accessibility`

## Project-Specific Rules

- Keep business logic out of templates and render hooks unless the project explicitly uses that shape
- Server-rendered templates should receive already-shaped view data rather than performing hidden cross-module orchestration in the view
- Form defaults, `old()` values, validation messages, disabled states, and submit affordances must match the backend contract
- Conditional rendering must preserve state-machine correctness across success, empty, loading, and error states
- Livewire, Filament, or similar component state must not trust client-mutated values without explicit server-side validation and authorization
- Interactive components must preserve a clear source of truth; do not split ownership of the same state across controller, view, and client hooks without an explicit synchronization model
- Rendering helpers, accessors, and view composers must not trigger hidden N+1 queries or repeated heavy work on common paths
- Route generation, signed links, and action targets used by UI surfaces must stay aligned with the backend contract
- Tables, filters, pagination widgets, and bulk-action surfaces must preserve deterministic behavior when multiple filters or selection states are active
- Server-rendered HTML should degrade gracefully when JavaScript is unavailable unless the product explicitly requires a JS-only interaction model
- In findings, explain the rendered or interactive behavior a user or operator would actually experience
