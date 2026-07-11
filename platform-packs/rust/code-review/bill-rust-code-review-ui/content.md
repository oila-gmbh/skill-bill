---
name: bill-rust-code-review-ui
description: Use when reviewing Rust wasm, server-rendered, desktop GUI, terminal UI, and interactive CLI behavior.
internal-for: bill-code-review
---

# UI Review Specialist

## Focus

- State, event, navigation, validation, loading, error, and empty-state behavior
- wasm browser integration, hydration, server rendering, desktop GUI, and terminal UI lifecycle
- Async UI work, cancellation, thread affinity, rendering cost, and stale updates
- User-controlled content, escaping, command output stability, and interaction consistency

## Ignore

- Visual taste without a repository design or usability contract
- Framework preference across Leptos, Yew, Dioxus, egui, iced, Slint, ratatui, or server templates
- Styling details unrelated to behavior, accessibility, or regression

## Project-Specific Rules

- Keep one source of truth for user-visible state and prevent stale async completions from overwriting newer actions.
- Preserve framework thread-affinity and ownership rules; background tasks must return updates through supported channels.
- Make loading, disabled, retry, validation, empty, and failure states reachable and recoverable.
- Escape or safely render untrusted content in HTML, rich text, terminal control sequences, and generated output.
- Avoid blocking executor or UI threads with filesystem, database, network, or CPU-heavy work.
- Preserve hydration and server/client state compatibility for wasm or server-rendered surfaces.
- Findings must give the user-visible failure sequence and use only the shared Risk Register and canonical severities.
