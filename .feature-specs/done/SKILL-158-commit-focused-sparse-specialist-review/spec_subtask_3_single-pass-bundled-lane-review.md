# SKILL-158 Subtask 3 - Single-Pass Bundled Lane Review

Parent spec: [.feature-specs/SKILL-158-commit-focused-sparse-specialist-review/spec.md](spec.md)
Issue key: SKILL-158

## Scope

Deliver each specialist lane's sparse assignment as one assembled bundle and
review it in a single worker operation. The parent has already resolved scope,
attributed hunks to commits, and decided relevance; the worker's job is to
review the code it was handed, not to rediscover scope or re-decide relevance.

In scope:

- Assemble one bundle per lane holding the assigned hunk bodies with commit
  identity, order, and per-hunk attribution attached as readable metadata.
- Define the worker protocol for reviewing the whole bundle in one pass,
  including relating an earlier assigned commit to a later one within that pass.
- Keep exactly one normal worker per specialist lane per review pass; worker
  count equals lane count and is invariant under commit count.
- Split a bundle that exceeds the worker context budget into the fewest
  size-driven segments that fit, never into a per-commit protocol.
- Allow bounded evidence expansion for a direct dependency with an authorized,
  nonblank reachability reason and no repeated broad read.
- Persist per-lane completion state, findings, and commit attribution so
  timeout, cancellation, retry, and resume operate at lane granularity.
- Preserve the structured finding format and root-cause deduplication.

## Acceptance Criteria

1. A delegated worker receives one assembled bundle containing only its assigned
   hunk bodies with commit identity and order attached, the applicable rubric,
   and bounded evidence access; it never receives the raw complete PR diff.
2. The worker reviews the bundle in one single-pass operation. It does not step
   through commits one at a time, does not emit per-commit relevance decisions,
   and does not restart from the final aggregate diff.
3. Commit identity and order are readable metadata on the bundle. A fixture
   where an earlier assigned commit introduces a contract and a later assigned
   commit changes it produces a finding relating the two, from one pass.
4. Worker launch count equals selected lane count for a review pass and does not
   change when commit count changes. A fixture holding lanes fixed and varying
   commits from one to twenty launches the same number of workers.
5. A pure UI commit is absent from the security lane's bundle because routing
   excluded it, while a later authentication change can cause the security lane
   to inspect a relevant prior contract through bounded evidence expansion.
6. A bundle exceeding the worker context budget splits into the fewest
   size-driven segments that fit. The split is mechanical, never derived from
   commit boundaries as a protocol, each segment carries commit identity and
   order, and each is separately accounted.
7. A lane that cannot be reviewed within budget terminates with an explicit
   incomplete disposition naming the unreviewed segments. Findings already
   produced remain valid; the lane is never aggregated as clean or complete
   coverage, and an incomplete lane is distinguishable from a clean lane in
   launch, progress, result, and resume records.
8. Launch, progress, result, retry, and resume records preserve lane identity,
   bundle composition, segment accounting, and finding-to-commit attribution. A
   resume re-runs only lanes that did not complete and never re-runs a lane whose
   single pass already produced a durable result.
9. Existing worker isolation, tool-call budgets, model-turn budgets, evidence
   expansion limits, cancellation, timeout, and terminal aggregation rules
   remain enforced.
10. Worker result parsing and parent aggregation preserve `F-XXX` finding IDs,
    file/line evidence, severity, confidence, commit attribution, and
    root-cause deduplication.

## Non-Goals

- Choosing which commits and hunks reach which lane; Subtask 2 owns sparse
  routing and the focus/skip dispositions.
- Performing the final cross-commit integration pass; Subtask 4 owns it.
- Reintroducing per-commit worker stepping, cumulative walk state, ordered
  completion watermarks, or worker-side relevance confirmation.
- Changing specialist rubric content or final report formatting beyond the
  minimum commit-attribution fields.

## Dependency Notes

Depends on: 1, 2

This unit consumes the commit-aware sparse assignments and changes the worker
launch/execution contract without changing parent-owned scope discovery or
parent-owned relevance decisions.

## Validation Strategy

Use fake worker and broker fixtures with six ordered commits covering UI,
persistence, API/security, tests, and a cross-cutting contract. Assert one worker
per lane, worker count invariant under commit count, bundle contents limited to
the sparse assignment, single-pass execution with no per-commit stepping,
cross-commit reasoning within one pass, authorized evidence expansion, and
correct finding attribution. Add an oversized-bundle fixture asserting minimal
size-driven segmentation with preserved commit metadata, a budget-exhaustion
fixture asserting an incomplete disposition naming unreviewed segments with no
clean-coverage aggregation, and a resume fixture asserting lane-granular retry.
Run `(cd runtime-kotlin && ./gradlew check)`.

## Next Path

Subtask 4 - integration pass, governed contracts, native-agent parity, and
end-to-end validation.

## Spec Path

.feature-specs/SKILL-158-commit-focused-sparse-specialist-review/spec_subtask_3_single-pass-bundled-lane-review.md
