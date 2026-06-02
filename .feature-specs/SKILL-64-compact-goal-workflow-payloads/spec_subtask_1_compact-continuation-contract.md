---
status: Complete
---

# SKILL-64 Subtask 1 - Compact Continuation Contract

Parent spec: [.feature-specs/SKILL-64-compact-goal-workflow-payloads/spec.md](./spec.md)
Issue key: SKILL-64

## Scope

Define and implement the compact `workflow continue` response contract. The
command remains a mutating activation path, but its default JSON output must be
safe to feed into a fresh goal child without carrying the full workflow snapshot
and every historical artifact.

This subtask owns the domain/application projection shape and the CLI/MCP mapper
contract for continuation output.

## Acceptance Criteria

1. `workflow continue` still activates/reopens the resumable workflow when
   appropriate and is not documented as read-only.
2. Default continuation output omits the nested full workflow snapshot and full
   `artifacts` map.
3. Default continuation output includes:
   - workflow id;
   - skill name;
   - continue status;
   - resume step id and label;
   - continue step directive;
   - required artifact keys;
   - available artifact keys;
   - compact current-step artifact summaries;
   - omitted artifact keys when relevant;
   - continuation brief;
   - continuation entry prompt;
   - read-only full-state command guidance.
4. Required current-step artifacts are either represented inline when small or
   summarized with explicit truncation/omission metadata when large.
5. The contract has an explicit full/debug opt-in strategy or a documented
   read-only fallback through `workflow show`.
6. Malformed durable workflow state continues to loud-fail before any compact
   projection is emitted.
7. Unit tests cover small, large, missing, and malformed artifact scenarios.

## Non-Goals

- Do not change workflow persistence schema unless the compact projection needs
  a versioned runtime contract.
- Do not make `workflow continue` read-only.
- Do not implement update acknowledgements in this subtask.
- Do not add model/reasoning controls.

## Dependency Notes

Depends on: none

This subtask establishes the compact continuation shape consumed by the goal
runner integration subtask.

## Validation Strategy

Add domain/application tests for compact continuation projection and CLI/MCP
golden coverage for default compact output plus any explicit full/debug mode.

## Next Path

Run bill-feature-task on spec_subtask_2_compact-workflow-update-acks.md.

## Spec Path

.feature-specs/SKILL-64-compact-goal-workflow-payloads/spec_subtask_1_compact-continuation-contract.md
