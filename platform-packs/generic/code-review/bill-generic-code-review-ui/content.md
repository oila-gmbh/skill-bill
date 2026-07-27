---
name: bill-generic-code-review-ui
description: Technology-neutral UI review for state ownership, rendering identity, navigation, feedback, and interactions.
internal-for: bill-code-review
---

# Generic UI Review

## Focus

Check state ownership, identity, navigation, loading and error states, optimistic updates, stale responses, destructive confirmation, focus restoration, and feedback after actions. Verify repeated rendering or event delivery cannot duplicate user-visible effects.

## Ignore

- UX-accessibility semantics and security concerns owned by their respective specialists.

## Applicability

Use when changes affect visual state, rendering identity, navigation, feedback, or interaction correctness.

## Project-Specific Rules

### UI Rules

- Require each changed `UI state` to have stable ownership and visible feedback; reject stale or conflicting state that breaks an interaction.
- For Blocker or Major findings, describe the concrete user-visible interaction or rendering failure scenario.
