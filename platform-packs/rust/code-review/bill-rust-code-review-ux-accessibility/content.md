---
name: bill-rust-code-review-ux-accessibility
description: Use when reviewing accessibility, semantics, keyboard and focus flow, feedback, localization, and usability in Rust-powered interfaces.
internal-for: bill-code-review
---

# Rust UX and Accessibility Review

Review user-impacting accessibility and interaction barriers.

## Checks

- Use semantic elements, roles, names, labels, descriptions, and relationships supported by the chosen Rust UI framework.
- Keep all actions reachable by keyboard or equivalent non-pointer input with visible, predictable focus.
- Restore or move focus intentionally after navigation, dialogs, validation failures, and async updates.
- Announce errors, progress, completion, and dynamically inserted content through appropriate platform affordances.
- Preserve contrast, scalable text, zoom/reflow, motion preferences, and target sizes when the surface supports them.
- Do not encode status or instructions through color, icon, sound, or position alone.
- Keep terminal UIs usable with conventional keys, screen-reader-friendly output modes where supported, and reliable terminal restoration.
- Keep copy actionable and localization-safe; avoid concatenation or fixed geometry that breaks translated content.
- Test framework adapters and native accessibility bridges when custom widgets bypass standard controls.

Report the affected user flow, barrier, and expected accessible behavior rather than generic advice.
