---
status: Ready for implementation
issue_key: SKILL-119
parent_issue_key: SKILL-119
subtask_id: 3
---

# SKILL-119 Subtask 3: Prose-goal review-policy parity

## Outcome

Every decomposed prose-goal child receives a self-contained, durable review
briefing that enforces the same selection, exact-delta, parallel, pass-cap, and
compact-summary policy as the runtime goal path.

## Scope

- Update `bill-feature-goal`, `bill-feature-task-prose`, and the provider-neutral
  `bill-feature-task-subtask-runner` source guidance.
- Update the native-agent template fields and body so a fresh prose child gets
  its mode, optional parallel agent, immutable baseline, baseline-untracked
  inventory, completed/reserved pass state, and cap disposition.
- Specify exact base-to-current review scope, non-recursive parallel lanes,
  two-pass accounting, crash-resume reuse, raw-evidence retention, and compact
  goal-facing return content.
- Keep the existing single confirmation gate and suppress only review blocking
  for the decomposed goal-cap continuation rule.

## Acceptance Criteria

1. Decomposed prose-goal dispatch forwards the durable mode and optional
   parallel lane to every fresh child and rejects incompatible resume changes.
2. The subtask-runner briefing exposes baseline and pass state sufficient to
   reconstruct only the complete child-owned delta after repair or resume.
3. Both prose lanes receive the same canonical execution mode and exact scope,
   do not recursively request parallel review, and count together as one pass.
4. A reserved pass is resumed, never duplicated; no third pass starts; a
   second unresolved pass returns `review_cap_reached` without reporting
   approval or blocking audit, validation, history, dependency, commit, or
   final reporting.
5. Goal-facing prose output is compact and path-free while durable artifacts
   retain complete location-bearing evidence.
6. Standalone prose feature tasks retain their normal review behavior.

## Non-goals

- Do not hand-author generated `SKILL.md`, support-pointer, or provider output.
- Do not reimplement runtime persistence or Git-delta construction in prose.

## Dependencies

Depends on subtask 1 for canonical execution-mode semantics. It must agree with
the runtime policy delivered by subtask 2 before integration is complete.

## Validation Strategy

Add source-contract coverage for all template fields and every continuation
rule. Run `skill-bill validate`, `npx --yes agnix --strict .`, and native-agent
configuration validation; render/install only after source validation passes.

## Next Path

Proceed to subtask 4 for cross-path validation and install verification.
