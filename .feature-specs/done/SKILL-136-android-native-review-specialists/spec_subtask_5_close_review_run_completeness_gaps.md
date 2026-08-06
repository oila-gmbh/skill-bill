---
issue_key: SKILL-136
subtask_id: 5
name: Close review-run completeness gaps
parent_spec: .feature-specs/SKILL-136-android-native-review-specialists/spec.md
---

# Subtask 5 — Close review-run completeness gaps

## Intended Outcome

Every review run records which lanes ran and when it finished, and every
finding carries the lane that produced it, so pack-and-area effectiveness
becomes measurable.

## Scope

- Populate `specialist_reviews` from the composed launch plan at run time
  rather than from agent narration, recorded per lane rather than as one
  comma-joined string.
- Record `review_finished_at` on the terminal path for every run, including
  runs that end without findings.
- Add per-lane attribution to findings: each finding carries the lane that
  produced it.
- Migrations for the per-lane structures and the finding-lane column.

## Acceptance Criteria

1. `specialist_reviews` is recorded from the composed launch plan for every
   run, sourced from the plan rather than agent narration.
2. `specialist_reviews` is stored per lane, not as one comma-joined string.
3. Every finding carries the lane that produced it.
4. Pack-and-area effectiveness is queryable by joining findings and their
   dispositions to the canonical routed skill from Subtask 4.
5. `review_finished_at` is recorded on the terminal path for every run,
   including runs that produce no findings.
6. `execution_mode` is present on the terminal path for every new run.
7. All schema changes ship as migrations that preserve existing rows, verified
   against a copy of a real ~91.5 MB store.
8. Coverage asserts the no-findings terminal path records both
   `review_finished_at` and per-lane `specialist_reviews`.
9. `(cd runtime-kotlin && ./gradlew check)` passes.

## Non-Goals

- Backfilling lane attribution for runs already recorded; the parent spec
  accepts that this is prospective only.
- Outbox, `learnings`, cross-store keying, or retention work (Subtask 6).
- Changing any platform pack.

## Dependencies

Subtask 4 — attribution joins against the canonical routed-skill identifier.

## Validation Strategy

Runtime tests asserting the launch plan is the source of `specialist_reviews`,
per-lane storage shape, finding-to-lane attribution, and terminal-path
recording on both the with-findings and no-findings paths. Migration tests over
a real-store copy. Then:

```bash
(cd runtime-kotlin && ./gradlew check)
skill-bill validate
```

## Next Path

Continue the goal.
