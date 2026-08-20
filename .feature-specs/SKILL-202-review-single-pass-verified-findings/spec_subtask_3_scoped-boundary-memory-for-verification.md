# SKILL-202 Subtask 3 — Scoped boundary memory for verification

## Intended Outcome

Finding verification reads boundary memory by title first, scoped to the
boundaries that own the finding's paths, under caps tighter than planning's. It
receives a catalog of `## [<date>] <title>` headings with stable ids, selects ids
semantically, and receives only the selected bodies. No verification path
delivers a whole `history.md` or `decisions.md` to a prompt, and no path widens
discovery to boundaries that own none of the finding paths. A finding whose paths
match no eligible boundary falls back to intent-only verification and says so.

## Scope

- Reuse the existing ports and adapters: `GoalPlanningContextDiscovery` with
  `FileSystemGoalPlanningContextDiscovery`, and
  `GoalPlanningBoundaryBodyResolver` with
  `FileSystemGoalPlanningBoundaryBodyResolver`. `DefaultGoalPlanningSweep` is the
  reference consumer. Do not fork a parallel discovery or resolution
  implementation.
- Scope discovery by the finding's paths: resolve owning boundaries from the
  paths, honouring `goal-planning-discovery-exclusions.yaml` so an excluded root
  contributes nothing.
- Declare verification-specific caps in contract data, each tighter than its
  planning counterpart: discovery file count, headings per file, catalog
  headings, selected bodies, per-body bytes, and total body bytes.
  `MAX_BOUNDARY_FILE_BYTES` is shared between discovery and body resolution and
  is not relaxed or forked, since reading one file under two limits lets the
  catalog publish a heading the resolver can never reproduce.
- Enforce the caps at resolution time. Exceeding one loud-fails with a named
  error rather than truncating silently.
- Record the selected heading ids and their source paths on the verification
  disposition, so a disposition's evidence is auditable after the fact.
- Surface that provenance where the disposition is already shown, including
  `skill-bill goal findings`.
- A finding with no eligible owning boundary proceeds intent-only and records
  that boundary context was unavailable, which is the same fallback subtask 2
  ships as verification's baseline.

## Acceptance Criteria

1. Verification receives a catalog of headings with stable ids scoped to the boundaries that own the finding's paths, selects ids semantically, and receives only the selected bodies.
2. Verification-specific caps for discovery file count, headings per file, catalog headings, selected bodies, per-body bytes, and total body bytes are declared in contract data and are each tighter than the corresponding planning cap.
3. Exceeding a verification cap at resolution time loud-fails with a named error rather than truncating silently.
4. No verification path delivers a whole `history.md` or `decisions.md` to a prompt. An unselected body never reaches a prompt payload.
5. No verification path widens discovery to a boundary that owns none of the finding paths, and an excluded root contributes nothing.
6. Verification reuses the `GoalPlanningContextDiscovery` and `GoalPlanningBoundaryBodyResolver` machinery. No parallel discovery implementation is introduced and `MAX_BOUNDARY_FILE_BYTES` is neither relaxed nor forked.
7. A finding whose paths match no eligible boundary proceeds with intent-only verification and records that boundary context was unavailable.
8. Each disposition records the heading ids it selected and their source paths, and that provenance is retrievable through `skill-bill goal findings --issue-key <KEY>`.
9. Planning's own boundary discovery, its caps, and its allowlist are unchanged.
10. `(cd runtime-kotlin && ./gradlew check --continue)` passes.

## Non-Goals

- Changing planning's boundary discovery, caps, or allowlist.
- Cross-subtask or cross-goal learning from rejection reasons.
- Teaching verification to consult git history or PR review comments.
- Adding a second verification pass or reopening review on a boundary-memory
  result.

## Dependency Notes

Depends on subtask 2 for the `verify_findings` phase, its disposition record, and
the intent-only verification this subtask adds boundary evidence to.

## Validation Strategy

Add a scoping test that a finding path yields a catalog covering only the owning
boundaries, and a negative case for an excluded root. Add a test that an
unselected body never appears in the prompt payload, which is the leak this
subtask exists to prevent. Add a cap test that over-budget resolution raises the
named error instead of truncating. Add a test that a path with no matching
boundary falls back to intent-only and records the unavailability. Assert the
planning caps and allowlist are untouched. Run the repository validation gate.

## Next Path

Goal complete after this subtask lands and the parent acceptance criteria hold
end to end.
