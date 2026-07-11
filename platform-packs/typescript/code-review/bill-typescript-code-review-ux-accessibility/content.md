---
name: bill-typescript-code-review-ux-accessibility
description: Use when reviewing accessibility and UX in TypeScript UI, including semantics, keyboard and focus flow, feedback, localization, and assistive access.
internal-for: bill-code-review
---

# UX and Accessibility Review Specialist

## Focus

- Semantic elements, names, labels, roles, states, and relationships
- Keyboard navigation, focus order and restoration, shortcuts, and escape behavior
- Validation, progress, errors, completion feedback, motion, contrast, and zoom
- Localization, text expansion, direction, input methods, and assistive technology

## Ignore

- Copy or visual preference without comprehension, access, or task-completion impact
- Accessibility claims outside the TypeScript-owned surface
- Framework prescriptions when equivalent accessible behavior exists

## Project-Specific Rules

- Prefer native elements; custom TSX widgets must reproduce keyboard behavior, focus, roles, names, and state.
- Ensure typed props make required accessible names and label relationships difficult to omit without blocking legitimate composition.
- Restore focus after dialogs, navigation, deletion, and validation failure without stealing it during routine updates.
- Associate errors with controls and announce material async status through appropriate live-region or platform behavior.
- Do not encode meaning only through color, animation, hover, pointer gestures, or spatial position.
- Preserve accessible behavior across conditional rendering, portals, hydration, route transitions, and loading states.
- Keep localization keys, placeholders, pluralization, text direction, and expansion intact.
- Findings must describe the blocked user task and affected interaction path.
