---
name: bill-ios-code-review-ux-accessibility
description: Use when reviewing iOS UX and accessibility risks including localization-string completeness and VoiceOver label coverage.
internal-for: bill-code-review
---

# UX & Accessibility Review Specialist

Review only UX correctness and accessibility issues that can make the app harder to understand, harder to operate, or inaccessible to assistive-technology users.

## Focus

- Localization-string completeness across supported languages
- VoiceOver and other accessibility-label coverage
- Validation feedback clarity and error-state discoverability
- Interaction patterns where the UI technically renders but is confusing or inaccessible

## Ignore

- Pure visual design preference
- UI framework and rendering correctness findings that belong to `bill-ios-code-review-ui`
- Security-only findings that belong to `bill-ios-code-review-security`

## Applicability

Use this specialist when changed user-facing code or resources affect VoiceOver semantics, Dynamic Type, keyboard or switch-control flow, validation feedback, localization completeness, or whether a user can complete a task.

## Project-Specific Rules

### Localization And Semantics

- New or changed user-facing strings must be added through the project's centralized strings surface (e.g. a `Strings.swift`-style accessor) rather than hardcoded inline, and must have translations present across all supported `.lproj` locales
- A new string added to one locale without corresponding entries in the other supported locales is a localization-completeness gap, not just a nice-to-have
- Every new interactive control (button, tappable element, custom control) must have a VoiceOver-accessible label that matches the control's actual action, not a generic or missing label
- Images and icons that convey meaning (not purely decorative) must have accessibility labels or be explicitly marked decorative so VoiceOver does not announce them incorrectly
- Focus order for VoiceOver navigation should follow the visual/logical reading order; custom layouts that reorder visual elements must not scramble the accessibility traversal order

### Adaptation And Task Completion

- Validation errors and status changes must be both visually distinguishable and announced or discoverable via VoiceOver, not conveyed by color alone
- Dynamic Type and larger accessibility text sizes should not clip, truncate, or overlap critical content in new or changed views
- In findings, make the user-visible UX or accessibility consequence explicit
- For Blocker or Major findings, describe the concrete accessibility or task-completion failure scenario.
