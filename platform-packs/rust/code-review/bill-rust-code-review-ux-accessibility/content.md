---
name: bill-rust-code-review-ux-accessibility
description: Use when reviewing accessibility and UX in Rust-owned UI, including semantics, keyboard and focus flow, feedback, localization, and assistive access.
internal-for: bill-code-review
---

# UX and Accessibility Review Specialist

## Focus

- Semantic structure, accessible names, labels, roles, states, and relationships
- Keyboard navigation, focus order and restoration, shortcuts, and escape behavior
- Validation, progress, errors, completion feedback, and reduced-motion or contrast needs
- Localization, text expansion, Unicode, terminal capabilities, and screen-reader output

## Ignore

- Copy or visual preference without comprehension, access, or task-completion impact
- Accessibility claims for surfaces outside the Rust-owned diff
- Framework-specific prescriptions when equivalent accessible behavior exists
- UI correctness findings that belong to the `ui` specialist
- Security-only findings that belong to the `security` specialist

## Applicability

Apply when changed Rust UI affects semantics, accessible names, keyboard or focus flow, feedback, localization, or assistive access.

## Project-Specific Rules

### Rust Accessibility Rules

- Verify `Rust semantics and input APIs` preserve their documented invariants; reject an accessibility or task-completion failure.
- Every interactive control needs an accessible name and operable keyboard path; custom widgets must reproduce native semantics and states.
- Move or restore focus intentionally after dialogs, navigation, validation failure, and async completion without stealing it during normal updates.
- Associate errors with fields and announce material async status through the platform's accessibility mechanism.
- Do not encode meaning only through color, animation, pointer hover, terminal color, or spatial position.
- Preserve localization keys, placeholders, pluralization, text direction, Unicode width, and expansion across web, desktop, and terminal surfaces.
- Ensure terminal UI shortcuts, focus, status, and fallback text remain usable without mouse, color, or advanced terminal features.
- Findings must describe the blocked user task and use only the shared Risk Register and canonical severity definitions.
- For Blocker or Major findings, describe the concrete accessibility or task-completion failure scenario.
