# SKILL-196 Subtask 2 — Reconcile lanes across routed roots by area

## Scope

`ReviewLaunchPlanPolicy.flatten` guarantees one winner per area, but it is invoked once per routed
root (`ParallelCodeReviewRunner.kt:1035-1037`), so the guarantee holds only within a root's own plan.
The cross-root results are then merged by skill name (`:1049`):

```kotlin
.groupBy { it.skillName }
```

The skill name is derived from the pack slug (`ReviewLaunchPlanPolicy.kt:106`), so
`bill-kotlin-code-review-architecture` and `bill-kmp-code-review-architecture` are distinct keys and
never merge. The merge is structurally incapable of catching cross-root duplication, which is the
only kind that occurs.

This subtask makes the area the reconciliation key across roots and resolves the owner by composition
depth.

- Key the cross-root reconciliation on `lane.area`, not `lane.skillName`.
- Resolve a multi-owner area to the lane at the nearest composition depth, using the `depth` each
  lane already carries from `flatten`.
- A tie at the same nearest depth between two distinct pack slugs raises `AmbiguousLaneOwnershipError`
  with both slugs and the area named, consistent with the in-plan tie behaviour. Never launch both.
- Ordering must be independent of manifest iteration order and of routed-root iteration order: sort
  the reconciliation inputs deterministically before selecting, so the same manifest set and diff
  always yield the same owner and the same `orderIndex` sequence.
- Preserve the existing same-lane union semantics for the surviving lane: `required` is the
  disjunction over the merged inputs for that area, `ownedPaths` is the sorted distinct union, and
  `changedHunkIds` is the distinct union. A losing lane's owned paths must not be dropped from the
  winner's claim, or the removed duplicate becomes a coverage hole.
- Keep the existing conflicting-ownership `require` meaningful under the new key: after area-keyed
  selection, the surviving inputs for an area must agree on pack slug, skill name, and add-ons.
- Do not change `flatten`, its winner selection, or its ambiguity error.

## Acceptance Criteria

1. The cross-root reconciliation in `ParallelCodeReviewRunner` is keyed on `area`. `groupBy { it.skillName }` no longer appears on the delegated launch path.
2. For any review run, the planned lane set contains no two lanes sharing the same `area`, and `review_run_lanes` therefore contains no two rows sharing the same `area`.
3. When two routed roots plan the same area, the lane at the nearest composition depth wins; the lane at greater depth is dropped rather than launched.
4. Two distinct native packs tying at the same nearest depth for one area raise `AmbiguousLaneOwnershipError` naming the area and both pack slugs, instead of launching both lanes.
5. The surviving lane for an area claims the sorted distinct union of the reconciled lanes' `ownedPaths` and the distinct union of their `changedHunkIds`, and is `required` if any reconciled input was required.
6. No area is claimed by more than one required lane, so the whole-routed-diff claim at `ParallelCodeReviewRunner.kt:1039` is paid at most once per area.
7. Every area present in the pre-change plan is present in the post-change plan for the same inputs. Lane count falls; the set of distinct areas does not.
8. `unreviewedSegmentIds`, segment accounting, coverage facts, and integration terminal state record exactly as before. A lane dropped as a cross-root duplicate is not recorded as unreviewed coverage and does not reduce any lane disposition to `incomplete`.
9. Owner selection and lane ordering are deterministic: shuffling manifest order and routed-root order produces an identical planned lane list including `orderIndex`.
10. `inline` execution mode is behaviourally unchanged.
11. `./gradlew check` passes, and no comment is added to any changed file.

## Non-Goals

- Fallback-pack exclusion. A `generic` lane that survives reconciliation here because no native pack
  ties it at a nearer depth is still expected at the end of this subtask. Subtask 3 removes it.
- Weakening `flatten`'s in-plan winner selection or its ambiguity error to accommodate the caller.
- Bundling multiple areas into one lane.
- Tightening `lane_conditions` content triggers.
- Composing a losing lane's rubric content into the winner.

## Dependency Notes

Depends on subtask 1. Depth-based cross-root ownership is only sound once each root's area set is
scoped to its own composition; with the all-manifest union still in place, a root would carry depth-0
candidates for areas it does not declare and would win them incorrectly.

## Validation Strategy

- Unit test asserting no duplicate area in the planned lane set for a `kotlin` + `kmp` cross-root
  plan, with `platform-correctness` resolving to the nearer owner.
- Unit test asserting `AmbiguousLaneOwnershipError` for a constructed manifest set where two native
  packs declare the same area at equal nearest depth, asserting the message names the area and both
  slugs.
- Determinism test: build the same manifest set in two different orders and assert identical planned
  lane lists including `orderIndex`.
- Coverage-accounting test asserting that a run whose plan lost a cross-root duplicate records the
  same `unreviewedSegmentIds` and terminal state as the equivalent run without the duplicate.
- Existing coverage, segment, and integration terminal-state tests must pass unchanged.
- `(cd runtime-kotlin && ./gradlew check)`.

## Next Path

Subtask 3 — exclude fallback lanes per area.
