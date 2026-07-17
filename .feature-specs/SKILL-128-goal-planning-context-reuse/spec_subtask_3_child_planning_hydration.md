# SKILL-128 Subtask 3 - Child planning hydration

## Scope

Promote a prepared goal subtask's saved preplan and plan into its child
feature-task runtime workflow as normal durable completed phase records, then
launch or resume that child at `implement`. Preserve the existing runtime DAG,
ledger, artifact, audit-remediation, and crash-recovery semantics.

## Acceptance Criteria

1. When a prepared subtask is selected, child setup loads the exact saved pair
   by goal workflow, repository, issue key, and subtask ID and revalidates its
   immutable provenance and phase payloads.
2. Child creation/hydration atomically persists completed `preplan` and `plan`
   records with valid outputs, attempts, step/ledger state, and provenance
   before the child can become runnable at `implement`.
3. A fresh prepared child launches at `implement`; no preplan or plan phase
   agent is resolved or started for that child.
4. The completed plan is supplied to implementation through the existing DAG,
   and both original planning outputs remain available to audit-gap
   remediation and status/resume inspection without being regenerated.
5. Repeating hydration or resuming after a crash is idempotent. An identical
   imported pair is accepted without duplicate records or telemetry; a
   conflicting pair fails loudly before implementation.
6. Review, audit, validation, history, commit/push, goal outcome propagation,
   review-fix, and audit-gap behavior remain unchanged after the imported
   planning boundary.
7. Earlier subtask commits and the current feature-branch head do not cause
   imported goal planning to be discarded or rerun.
8. Standalone feature-task creation and resume never invoke the goal hydration
   path. Regression tests prove a fresh standalone task still resolves and
   executes its own preplan and plan agents, persists their outputs in its own
   workflow, and only then advances to implement.
9. Integration tests cover normal hydration, duplicate resume, crash between
   hydration and implementation, provenance conflict, missing/corrupt prepared
   planning, audit-gap reuse, and standalone isolation.

## Non-Goals

- Do not remove preplan or plan from the feature-task workflow definition.
- Do not change standalone feature-task planning or persistence.
- Do not recompute or update a prepared plan during child execution.
- Do not alter review or validation policy.

## Dependency Notes

Depends on subtask 1 for the persisted planning contract and subtask 2 for
complete prepared goal state.

## Validation Strategy

- Add domain/application tests for completed-phase seeding and required
  artifact resolution.
- Add goal-child runtime integration tests around launch, resume, and
  audit-gap reentry.
- Add explicit standalone rejection/regression coverage at the same child-open
  seam so goal-only behavior cannot leak into task execution.
- Run affected workflow-domain, runtime-application, CLI goal, and persistence
  tests.

## Next Path

After completion, continue with
`spec_subtask_4_goal_planning_observability.md`.

