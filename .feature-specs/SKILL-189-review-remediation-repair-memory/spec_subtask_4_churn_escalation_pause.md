# SKILL-189 · Subtask 4 — Churn detection and the escalation pause

## Scope

Give the remediation loop an off-ramp that engages on churn, not only on
literal repetition.

`detectReviewRemediationNonProgress` blocks only when the advance-blocking
finding identity set is byte-identical *and* the repository fingerprint or
reviewed-delta digest is unchanged. In the SKILL-16 incident every round
produced a different finding against a changed tree, so the conjunction never
held; the advisory threshold warned once at round three and the loop continued.
A run in that state has no off-ramp, because the operator decision surface
requires an already-paused subtask.

- `runtime-kotlin/runtime-domain`: the widened detection predicate and its
  inputs.
- `runtime-kotlin/runtime-application`: evaluation at the remediation edge,
  pause recording, and the sanitized goal-facing reason.

## Acceptance Criteria

1. Non-convergence detection additionally recognizes churn: advance-blocking
   findings recurring against constructs already recorded in the remediation
   repair ledger, across a bounded number of consecutive rounds, with a
   remediation delta that is not shrinking.
2. The existing condition — identical advance-blocking finding set plus
   unchanged fingerprint or digest — keeps working exactly as it does today and
   is not weakened by the new branch.
3. A detected churn state pauses the subtask resumably for an operator decision
   instead of re-entering the loop, using the existing pause path and the
   existing `retry_fix` / `accept_and_advance` / `abandon_subtask` decisions.
4. An active operator retry grant suppresses the churn pause for exactly one
   transition, matching the behavior of the existing non-convergence pause.
5. A `plan_fix` escalation verdict reaches the same pause with the same
   evidence shape, so an operator sees one consistent surface whether
   escalation was declared by `plan_fix` or detected by churn.
6. The pause reason names the recurring constructs, the round count, and the
   severity mix, and is sanitized: no diff hunks, no line numbers, no raw
   review output, no source bodies. Location-bearing detail remains reachable
   only through `skill-bill goal findings --issue-key <KEY>`.
7. Churn detection never abandons or auto-accepts an unresolved Blocker or
   Major. It pauses; the operator decides. The loop remains uncapped by count.
8. The detection thresholds are named constants declared once, alongside the
   existing advisory warning threshold, so no seam drifts to its own literal.
9. The advisory warning at the existing threshold continues to fire and remains
   a warning, distinct from this pause.
10. Detection is evaluated from durable state, so a pause decision is
    reproducible after process death or parent resume rather than depending on
    in-memory round history.
11. Goal status reports the paused state and its reason through the existing
    projection without new payload-bearing fields.

## Non-Goals

- No finite iteration cap on the remediation loop.
- No automatic replanning, abandonment, or acceptance on churn.
- No change to review scope, remediation base sha, or severity semantics.
- No new operator decision value.

## Dependency Notes

Depends on subtask 2 for ledger construct identity and on subtask 3 for the
escalation verdict it routes to the shared pause.

## Validation Strategy

```bash
cd /home/sermilion/StudioProjects/skill-bill/runtime-kotlin
./gradlew check -x sourcesJar
```

Focused: progress-detection domain tests including the preserved existing
condition, pause recording and retry-grant suppression tests, sanitized
pause-reason privacy assertions, and a durable-state replay asserting the same
decision after resume.

## Next Path

Subtask 5 proves the whole loop against a replayed SKILL-16-shaped sequence.
