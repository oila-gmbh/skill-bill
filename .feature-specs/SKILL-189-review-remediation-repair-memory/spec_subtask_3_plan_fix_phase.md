# SKILL-189 · Subtask 3 — `plan_fix` loop-only phase and escalation verdict

## Scope

Add a planning step to the remediation loop so a round decides root cause
before it edits, and can declare that a finding is a symptom of a design defect
rather than a patch site.

Today `implement_fix` is handed findings and a checkpoint with no planning
input and is instructed to make the narrowest local change. A band-aid is the
only artifact it is equipped to produce. `plan_fix` supplies the missing
judgment step without granting any phase the authority to rewrite the subtask's
durable plan.

- `runtime-kotlin/runtime-domain`: the `plan_fix` phase id, labels, required
  artifacts, projection matrix entry, loop-only declaration, transition
  topology, and the escalation verdict.
- `runtime-kotlin/runtime-application`: launch, output gating, repair-plan
  persistence, and the transition from `plan_fix` to `implement_fix`.
- `skills/bill-feature-task-runtime/content.md` for the changed loop shape.

## Acceptance Criteria

1. A `plan_fix` phase exists and is loop-only: it is declared alongside
   `implement_fix` in `loopOnlyPhaseIds`, and the forward edge skips both, so a
   clean run launches neither.
2. The `review_fix` backward edge from a `changes_requested` review verdict
   targets `plan_fix`, which then transitions to `implement_fix`, which
   re-enters `review`. The loop keeps one loop id.
3. `plan_fix` receives the carried findings, the remediation repair ledger, and
   the immutable initial preplan and plan outputs, through named versioned
   projections subject to the existing budgets.
4. `plan_fix` emits a bounded repair plan naming, per carried finding: the root
   cause, the minimal change that addresses it, and a symptom-or-design
   classification stating whether the finding is a consequence of an earlier
   round's remedy.
5. `plan_fix` never regenerates, mutates, or overwrites the durable `preplan`
   or `plan` outputs. Its repair plan is its own artifact, and the existing
   invariant that subtask planning outputs are immutable within a subtask is
   preserved and asserted.
6. `implement_fix` consumes the repair plan as a named upstream projection and
   implements it. Its required upstream artifact set is widened accordingly,
   and its existing prohibitions on plan re-application and scope expansion
   remain in force.
7. `plan_fix` emits an escalation verdict when the carried findings are
   classified as design symptoms. The escalation verdict does not advance to
   `implement_fix` and does not route back to `plan`.
8. An escalation verdict routes to the existing resumable operator pause,
   carrying the ledger and root-cause analysis as durable evidence, and is
   released through the existing `retry_fix` / `accept_and_advance` /
   `abandon_subtask` decisions.
9. `review_fix` iteration accounting counts remediation rounds, not phase
   launches. Adding `plan_fix` leaves the durable per-edge counter, the
   advisory warning threshold, and finished telemetry semantically unchanged.
10. Crash safety holds: death during `plan_fix` resumes at `plan_fix` for the
    same round with no lost repair plan, no double-reserved review pass, and no
    double-applied mutation.
11. `plan_fix` performs no repository mutation. It is not a mutating phase and
    is excluded from the mutating-phase idempotency contract.
12. Runtime contract documentation describes the new loop shape, the repair
    plan artifact, and the escalation verdict.

## Non-Goals

- No `replan_subtask` operator decision; escalation reuses the existing three.
- No automatic routing back to `plan`, and no regeneration of planning outputs.
- No change to the audit-gap loop, the regeneration edges, or entry gates.
- No finite cap on the remediation loop.
- No churn detection; that is subtask 4.

## Dependency Notes

Depends on subtask 2 for the ledger it reads. Subtask 4 consumes the escalation
verdict and the symptom-or-design classification.

## Validation Strategy

```bash
cd /home/sermilion/StudioProjects/skill-bill/runtime-kotlin
./gradlew check -x sourcesJar
```

Focused: workflow definition topology tests, transition and loop-only
declaration tests, `FeatureTaskRuntimeRunnerTest`, phase projection matrix
tests, planning-immutability assertions, and resume coverage for a death inside
`plan_fix`.

## Next Path

Subtask 4 makes churn detectable so the loop reaches this escalation path
without relying on the model to volunteer it.
