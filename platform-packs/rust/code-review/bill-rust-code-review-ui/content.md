---
name: bill-rust-code-review-ui
description: Use when reviewing Rust-powered web, desktop, terminal, embedded, server-rendered, or WASM user-interface correctness.
internal-for: bill-code-review
---

# Rust UI Review

Review observable interaction defects without requiring a specific framework.

## Checks

- Apply to Leptos, Yew, Dioxus, egui, iced, Slint, Tauri, Ratatui, templates, WASM, embedded displays, or comparable local choices.
- Keep UI state, ownership, subscriptions, callbacks, and component lifetimes consistent across rerender and navigation.
- Avoid blocking I/O or CPU work on event loops and WASM/browser main threads.
- Preserve loading, empty, success, validation, error, retry, and stale-data states.
- Ensure async results cannot overwrite newer state after cancellation, route changes, or component teardown.
- Keep server/client serialization and hydration contracts aligned where applicable.
- Bound lists, logs, terminal buffers, textures, and retained handles that can grow during long sessions.
- Verify keyboard, pointer, resize, focus, and terminal cleanup behavior for the affected surface.
- Keep unsafe native handles and FFI UI resources behind explicit lifetime and thread-affinity boundaries.

Ignore visual taste unless project guidance declares it or behavior is materially impaired.
