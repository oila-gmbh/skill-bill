---
status: Complete
---

# SKILL-67 Subtask 4 - Dispatcher, Docs, and Recorded Promote Decision

Parent spec: [.feature-specs/SKILL-67-promote-runtime-feature-task/spec.md](./spec.md)
Issue key: SKILL-67

## Scope

Point routing and documentation at the promoted path, and record the maintainer
promote decision in the single authoritative source so no doc drifts or restates
the criterion.

- Update `skills/bill-feature/content.md`: single-spec work routes to the
  canonical (runtime-backed) `bill-feature-task`; state that `bill-feature-task`
  is now runtime-driven; `bill-feature-task-legacy` is never auto-routed.
- Record the promote decision in the single authoritative source: add a dated
  "Promote Decision (Recorded)" note under the existing "Promote / Kill Criterion
  (Authoritative)" section of
  `.feature-specs/SKILL-65-experimental-feature-task-runtime/spec.md`, naming the
  maintainer, the date, and the SKILL-67 follow-up that executes it.
- Update the root `agent/decisions.md` pointer entry
  (`feature-task-runtime-promote-kill-criterion-pointer`) to reflect that the
  maintainer has recorded a promote decision and that SKILL-67 executes it; keep
  it a pointer, do not restate the rule (avoid dual maintenance).
- Update `runtime-kotlin/ARCHITECTURE.md`, README, and the AGENTS.md skill
  catalog: the runtime workflow family / skill is canonical, not experimental;
  remove "must not destabilize `bill-feature-task`" experimental language; add the
  deprecated `bill-feature-task-legacy` entry. Docs point to the authoritative
  source and do not restate the criterion.
- Write the **Deprecation Window Contract** in exactly one authoritative place
  (per the parent spec): name the window, state that `bill-feature-task-legacy`
  and the `feature_implement_*` family retire together at window close, forbid
  leaving either alive past the window, and mark the removal a scheduled
  follow-up.

## Acceptance Criteria

1. `bill-feature` routes single-spec work to canonical `bill-feature-task` and
   never to `bill-feature-task-legacy`; decomposed work still routes to
   `bill-feature-goal`.
2. The SKILL-65 parent spec records the dated maintainer promote decision under
   its existing authoritative section, referencing SKILL-67.
3. The root `agent/decisions.md` pointer reflects the recorded promote decision
   and still does not restate the rule.
4. `ARCHITECTURE.md`, README, and AGENTS.md catalog describe the path as canonical
   (no EXPERIMENTAL language), list `bill-feature-task-legacy` as deprecated, and
   point to the authoritative source rather than restating it.
5. The Deprecation Window Contract exists in exactly one authoritative location,
   names the window, ties the legacy skill and `feature_implement_*` retirement
   together, and scopes the actual removal as a follow-up.
6. No doc restates or contradicts the promote/kill criterion; grep confirms a
   single authoritative source.

## Non-Goals

- No code/CLI/MCP/skill behavior changes (subtasks 1-3); this subtask is routing,
  docs, and decision records only.
- Do not perform the legacy removal; only document its window and trigger.
- Do not duplicate the criterion or the window contract into multiple docs.

## Dependency Notes

Depends on: Subtask 2 (canonical skill exists to route to) and Subtask 3 (goal
coupling done, so docs describe the final wiring).

## Validation Strategy

`npx --yes agnix --strict .` and `scripts/validate_agent_configs` for skill/doc
content; a grep audit confirming exactly one authoritative source for both the
criterion and the window contract; manual read-through of routing prose.

## Next Path

Subtask 5 runs the closing maintainer validation gate and the rename test sweep.

## Spec Path

.feature-specs/SKILL-67-promote-runtime-feature-task/spec_subtask_4_dispatcher-docs-and-recorded-promote-decision.md
