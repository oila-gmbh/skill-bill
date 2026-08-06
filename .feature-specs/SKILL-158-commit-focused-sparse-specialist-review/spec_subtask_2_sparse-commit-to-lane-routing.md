# SKILL-158 Subtask 2 - Sparse Commit-To-Lane Routing

Parent spec: [.feature-specs/SKILL-158-commit-focused-sparse-specialist-review/spec.md](./spec.md)
Issue key: SKILL-158

## Scope

Change specialist selection from aggregate PR-path ownership to a sparse,
auditable mapping between commit units and specialist lanes. Clear UI-only,
persistence-only, API/security, testing, and cross-cutting examples must route
without making every lane consume every commit.

In scope:

- Build a commit-level routing matrix from changed hunks, paths, stack signals,
  lane ownership, and the applicable specialist rubric.
- Preserve required baseline lanes while excluding clear non-relevant commit
  units from optional lanes before launch.
- Decide each commit/lane disposition definitively before launch from actual
  changed hunks, and assemble the resulting per-lane bundle. Workers do not
  re-decide relevance, so this decision is the coverage guarantee.
- Record per commit/lane disposition, evidence signals, and a bounded reason.
- Route cross-cutting commits to every lane with a genuine reachable concern,
  while preventing speculative fan-out.
- Preserve existing pack composition, add-on selection, lane order, and
  provenance attribution.

## Acceptance Criteria

1. The routing result contains one auditable, final decision for every
   commit/lane pair — `focused` or `skipped` — with a nonblank reason for every
   skip. There is no deferred `candidate` state, because no worker re-decides
   relevance downstream.
2. A pure UI commit does not enter the security lane, a pure persistence commit
   does not enter the UI lane, and an authentication/API commit reaches the
   security and API-contract lanes when their rubrics apply.
3. A commit that changes a shared contract, authorization boundary, durable
   state, or cross-layer behavior can enter multiple lanes, with each inclusion
   tied to actual changed evidence rather than the commit message alone.
4. Optional specialist lanes do not receive clear irrelevant commit bodies.
   Required baseline coverage remains explicit and cannot be silently dropped
   by sparse routing.
5. The prepared assignment preserves commit order and includes only the hunk
   bodies and commit identities selected for that lane, assembled as the bundle
   the worker receives.
6. Routing and assignment digests change when a commit/lane decision, owned
   hunk, or skip reason changes; provenance remains stable through aggregation
   and deduplication.
7. Existing non-commit scopes retain their current lane selection behavior
   through the single synthetic unit.
8. Tests prove sparse routing reduces the commit/lane matrix for representative
   multi-commit fixtures without reducing required cross-cutting coverage.
9. Because relevance is decided once here with no worker backstop, routing
   quality is tested directly: fixtures assert that a commit carrying risk under
   a lane's rubric — a changed contract, authorization boundary, durable-state
   shape, or cross-layer behavior — is never skipped for that lane, and that
   every skip reason is falsifiable against the actual changed hunks.
10. Parent-side routing analysis runs under an explicit bounded budget covering
    commit and rubric analysis. Exceeding it fails loudly rather than silently
    degrading routing into over-broad or under-broad assignments.

## Non-Goals

- Parsing or validating Git commit deltas; Subtask 1 owns the evidence model.
- Worker execution, bundle segmentation, and prompt behavior; Subtask 3 owns
  those concerns.
- Replacing specialist rubrics or inventing new review areas.

## Dependency Notes

Depends on: 1

This unit consumes the ordered commit-aware packet and produces the sparse
assignments consumed by the delegated worker launcher.

## Validation Strategy

Add routing fixtures for UI, UX/accessibility, persistence, API contracts,
security, testing, architecture, and cross-cutting changes. Assert exact
commit/lane decisions, skip reasons, required-lane preservation, candidate
boundedness, digest stability, and aggregate finding attribution. Because no
worker re-decides relevance, add adversarial routing fixtures where a
risk-bearing change is disguised by path or commit message, asserting it still
reaches the owning lane, plus a parent analysis budget breach asserting a loud
failure. Run `(cd runtime-kotlin && ./gradlew check)`.

## Next Path

Subtask 3 - single-pass bundled lane review.

## Spec Path

.feature-specs/SKILL-158-commit-focused-sparse-specialist-review/spec_subtask_2_sparse-commit-to-lane-routing.md
