---
status: Complete
---

# SKILL-85 Subtask 5 - M2: Audit-Gap Re-Plan/Re-Implement Loopback

Parent spec: [.feature-specs/SKILL-85-runtime-remediation-loops/spec.md](./spec.md)
Issue key: SKILL-85

## Scope

Implement M2: restore prose's audit-gap -> plan -> implement -> review -> audit
loopback to the runtime, bounded at 2 iterations. This is the wider backward edge
that re-enters `plan` and `implement`, reusing the Subtask 2 executor, the
Subtask 3 mutating-phase idempotency, and the Subtask 4 verdict pattern. It is
built last because it exercises the mutating re-entry most aggressively (re-plan
then re-implement) and depends on every prior piece being in best shape.

## Acceptance Criteria

1. `audit` emits a structured, schema-validated verdict: `satisfied` (all
   acceptance criteria met) or `gaps_found`, with the failing criteria carried in
   the output to scope the re-plan/re-implement handoff. The runtime evaluates
   the verdict; an agent cannot advance past unmet criteria with prose.
2. The backward edge is declared over the Subtask 2 executor: `audit`
   `gaps_found` -> `plan` -> `implement` -> `review` -> `audit`, capped at 2
   audit-gap iterations via a durable per-edge counter. On a `satisfied` verdict
   the run advances to `validate`.
3. The re-entered `plan` and `implement` are scoped by the audit gaps: the
   handoff carries which criteria failed so the loop addresses the gaps rather
   than redoing settled content. (Confirm during pre-planning whether to re-enter
   full `plan` or a scoped `replan`, per the parent Open Questions.)
4. The re-entered `implement` is idempotent (Subtask 3): re-implementing
   reconciles the tree toward the updated plan without double-applying, and a
   crash mid-loop resumes at the correct phase and iteration.
5. The re-run passes back through `review` (including M1's review-fix loop) before
   re-`audit`, so audit-driven changes are themselves reviewed; the two loops
   compose without double-counting iterations (the `review_fix` counter resets
   per audit-gap iteration; the `audit_gap` counter is independent).
6. Cap exhaustion blocks loudly: after 2 unsuccessful audit-gap iterations the run
   blocks with loop id `audit_gap`, the iteration count, and the unmet criteria,
   as a durable terminal blocked record plus observability/ledger event - it
   never advances to `validate` on unmet acceptance criteria.
7. Observability records each audit-gap iteration and the nested re-plan ->
   re-implement -> review -> audit traversal as ground truth; finished telemetry
   reflects the audit-gap iteration count.
8. Ceremony scaling is preserved: audit ceremony by feature size
   (`FeatureTaskRuntimePhaseWorkflowDefinition` ceremony scaling) and review
   scope continue to apply on re-entry; the loopback adds the backward edge
   without changing existing scaling.
9. The runtime skill content documents the M2 loop (trigger, cap, scoping, and
   block-on-exhaustion) and how it composes with M1.
10. Architecture boundaries hold; the audit verdict type is a domain `model`
    type; `RuntimeArchitectureTest` passes. Maintainer validation passes for the
    whole feature: `skill-bill validate`, `(cd runtime-kotlin && ./gradlew
    check)`, `npx --yes agnix --strict .`, `scripts/validate_agent_configs`.
11. Application tests with a fake launcher prove: `satisfied` advances; one
    `gaps_found` iteration loops plan->implement->review->audit then advances on
    `satisfied`; 2 unsuccessful iterations block loudly; the re-plan handoff
    carries the failing criteria; M1 and M2 compose with independent counters;
    idempotent re-implement; crash/resume mid-loopback; and cap enforcement
    across resume.

## Non-Goals

- Do not change `validate` semantics or the decomposition-at-planning model.
- Do not modify the prose family.
- Do not make the loop unbounded or let cap exhaustion silently advance.
- Do not re-decide settled plan content beyond the audit gaps (favor scoped
  re-planning).

## Dependency Notes

Depends on: Subtask 1 (resume integrity), Subtask 2 (cyclic executor), Subtask 3
(mutating-phase idempotency for the re-entered `implement`), and Subtask 4 (the
verdict pattern and the review-fix loop that the re-run passes back through). This
is the terminal subtask; it carries the whole-feature maintainer gate.

## Validation Strategy

Domain tests for the audit verdict schema and the `audit_gap` transition over
verdict x iteration; application tests with a fake launcher for the full
loopback matrix (advance/converge/exhaust), gap-scoped handoff contents, M1+M2
composition with independent counters, idempotent re-implement, crash/resume, and
cap-across-resume; skill-content lint (agnix); assertions the prose family is
untouched; `RuntimeArchitectureTest`; the full maintainer command set.

## Next Path

Final subtask - run the whole-feature maintainer gate and open the PR.

## Spec Path

.feature-specs/SKILL-85-runtime-remediation-loops/spec_subtask_5_audit-gap-loopback.md
