---
status: Complete
---

# SKILL-65 Subtask 5 - Comparison Harness and Promote/Kill Criteria

Parent spec: [.feature-specs/SKILL-65-experimental-feature-task-runtime/spec.md](./spec.md)
Issue key: SKILL-65

## Scope

Make the experiment evaluable and bounded. Provide a reproducible procedure for
running the same governed spec through both `bill-feature-task` and
`feature-task-runtime`, add observability/payload guardrail tests, update docs to
record the new experimental capability and its boundary, and write the explicit
promote/kill decision rule. This subtask is the evaluation gate and runs the
final maintainer validation.

## Acceptance Criteria

1. A documented, reproducible comparison procedure runs the same governed spec
   through both `bill-feature-task` and `feature-task-runtime` and captures, for
   each: per-phase timings, observability completeness (runtime-owned vs
   self-reported), state-reliability and resume behavior, token/session cost, and
   the method used to compare output quality.
2. Guardrail tests assert that per-phase records contain runtime-owned
   timestamps, agent id, and status; that a phase cannot advance without a
   validated output (regression test); and that the assembled handoff payload
   stays within a documented per-phase budget consistent with SKILL-64
   compaction.
3. A written promote/kill criterion is recorded with a single authoritative
   source (parent spec and/or `agent/decisions.md`): the conditions under which
   `feature-task-runtime` would replace the prose orchestrator versus be retired.
   It explicitly forbids an indefinite dual-maintenance state and names who/what
   evidence decides.
4. Docs are updated: `runtime-kotlin/ARCHITECTURE.md` notes the new workflow
   family and its experimental status; the orchestration playbook or skill
   catalog notes the experimental skill and that it must not destabilize
   `bill-feature-task`.
5. The experimental status is explicit in the skill description and docs, and the
   capability is not wired as a default or auto-routed.
6. Maintainer validation passes:
   - `skill-bill validate`
   - `(cd runtime-kotlin && ./gradlew check)`
   - `npx --yes agnix --strict .`
   - `scripts/validate_agent_configs`

## Non-Goals

- Do not promote, retire, or re-route `bill-feature-task` in this feature; this
  subtask only records the decision rule and the evidence procedure.
- No dual-agent cross-review merge.
- Do not weaken any existing review/audit/validation gate to make the comparison
  look favorable.

## Dependency Notes

Depends on: Subtask 4 (the CLI surface the comparison procedure drives) and,
transitively, all prior subtasks.

Final subtask of SKILL-65.

## Validation Strategy

Run the comparison procedure end-to-end on a representative spec; guardrail tests
for observability and payload budgets; the full maintainer command set as the
closing gate.

## Next Path

Final subtask. On completion the SKILL-65 goal is complete; no further subtask
spec follows.

## Spec Path

.feature-specs/SKILL-65-experimental-feature-task-runtime/spec_subtask_5_comparison-harness-and-promote-kill-criteria.md
