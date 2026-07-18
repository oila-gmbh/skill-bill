---
status: Pending
---

# SKILL-130 Subtask 3 - Execution dashboard UI

## Scope

Design and implement the main-pane issue execution dashboard and refined issue rows using Compose and the existing Skill Bill design system. Present current understanding first, then task structure, planning digests, blocks, timeline, attempts, and bounded artifact drill-down through progressive disclosure.

## Acceptance Criteria

1. The dashboard provides a clear header and at-a-glance answer for status, progress, current work, last activity, and required attention.
2. Preplan, plan, task structure, blocks/failures, timeline, attempt history, and additional artifacts have readable intentional presentations and honest absent/malformed states.
3. Attempt drill-down preserves workflow IDs and provenance without adding navigation rows, and retry relationships are easy to follow.
4. Layout, typography, spacing, color, empty states, and disclosure hierarchy use the design system and remain pleasant and readable with dense or long content.
5. Keyboard navigation, focus, activation, scrolling, semantic labels, status descriptions, and non-color cues meet accessibility expectations.
6. UI tests cover the representative running, blocked/retried, completed, partial-data, malformed-section, narrow-window, long-content, loading, and error scenarios.
7. The complete maintainer validation suite passes.

## Non-Goals

- Editing workflow state or artifacts.
- Inventing data that is absent from the durable projection.
- Redesigning unrelated editor and repository-management screens.

## Dependency Notes

Depends on subtasks 1 and 2.

## Validation Strategy

Use deterministic Compose fixtures and semantics assertions, including a `SKILL-128`-shaped multi-attempt issue. Run desktop screenshot/manual visual QA at normal and narrow sizes, focused UI tests, then all repository validation commands.

## Next Path

The feature is ready for full acceptance verification and PR handoff after this subtask.

