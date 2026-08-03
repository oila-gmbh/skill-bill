# SKILL-158 Subtask 3 - Sequential Cumulative Lane Execution

Parent spec: [.feature-specs/SKILL-158-commit-focused-sparse-specialist-review/spec.md](./spec.md)
Issue key: SKILL-158

## Scope

Teach each delegated specialist worker to review its assigned commit units in
order inside one continuing context. A worker focuses only on its sparse
assignment, carries prior understanding forward, and records explicit
focus/skip dispositions instead of restarting on the aggregate PR diff.

In scope:

- Project ordered assigned commit units and bounded hunk bodies into the lane
  launch contract and evidence broker.
- Define the worker protocol for oldest-first processing, cumulative context,
  per-commit relevance confirmation, and explicit skip reasons.
- Keep one normal worker per specialist lane per review pass; do not create a
  worker for every commit/lane pair.
- Allow a later commit to request bounded evidence for an earlier dependency
  only with an authorized, nonblank reachability reason and no repeated broad
  read.
- Persist per-commit worker progress, findings, and dispositions so timeout,
  cancellation, retry, and resume do not repeat completed work by default.
- Preserve the structured finding format and root-cause deduplication.

## Acceptance Criteria

1. A delegated worker receives an ordered list of only its assigned commit
   units, the applicable rubric, and bounded evidence access; it never receives
   the raw complete PR diff.
2. The worker processes assigned commits oldest-first in one continuing worker
   context and is instructed to carry prior commit understanding forward.
3. For every assigned candidate commit, the worker emits exactly one bounded
   `focused` or `skipped` disposition with a reason; a skipped unit emits no
   specialist finding.
4. A worker does not re-review a completed earlier commit when it advances to a
   later commit. Revisiting earlier code is allowed only when the later diff
   creates a reachable dependency or cross-commit correctness question, and
   that revisit is recorded.
5. A pure UI commit is not reviewed by the security worker when routing excludes
   it, while a later authentication change can cause the security worker to
   inspect the relevant prior contract through bounded evidence.
6. Launch, progress, result, retry, and resume records preserve the current
   commit unit, ordered completion watermark, focus/skip dispositions, and
   finding-to-commit attribution.
7. A retry after a worker failure resumes from the governed commit position or
   explicitly restarts the bounded lane according to durable retry policy; it
   never silently reviews every commit again.
8. Existing worker isolation, tool-call budgets, model-turn budgets, evidence
   expansion limits, cancellation, timeout, and terminal aggregation rules
   remain enforced.
9. Worker result parsing and parent aggregation preserve `F-XXX` finding IDs,
   file/line evidence, severity, confidence, commit attribution, and
   root-cause deduplication.

## Non-Goals

- Choosing which lanes are candidates for a commit; Subtask 2 owns sparse
  routing.
- Performing the final cross-commit integration pass; Subtask 4 owns it.
- Changing specialist rubric content or final report formatting beyond the
  minimum commit-disposition and attribution fields.

## Dependency Notes

Depends on: 1, 2

This unit consumes the commit-aware sparse assignments and changes the worker
launch/execution contract without changing parent-owned scope discovery.

## Validation Strategy

Use fake worker and broker fixtures with six ordered commits covering UI,
persistence, API/security, tests, and a cross-cutting contract. Assert one
worker per lane, sparse assigned commit units, cumulative prompt state,
explicit skips, authorized revisits, no duplicate completed work after resume,
and correct finding attribution. Run `(cd runtime-kotlin && ./gradlew check)`.

## Next Path

Subtask 4 - integration pass, governed contracts, native-agent parity, and
end-to-end validation.

## Spec Path

.feature-specs/SKILL-158-commit-focused-sparse-specialist-review/spec_subtask_3_sequential-cumulative-lane-execution.md
