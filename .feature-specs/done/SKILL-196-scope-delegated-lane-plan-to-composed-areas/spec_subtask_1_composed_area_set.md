# SKILL-196 Subtask 1 — Scope the launch path's area set to the routed composition

## Scope

`ParallelCodeReviewRunner.resolvePlannedRubrics` computes the area set it hands to
`ReviewLaunchPlanPolicy.flatten` as the union of `declaredCodeReviewAreas` across every installed
manifest (`ParallelCodeReviewRunner.kt:1030`):

```kotlin
val selectedAreas = manifests.flatMap { it.declaredCodeReviewAreas }.toSet()
```

`ReviewLaunchPlanPolicy.composedAreas` exists precisely for this call and its KDoc forbids the union
(`ReviewLaunchPlanPolicy.kt:12-17`). Its only caller today is `FileSystemReviewAttribution.kt:32`, so
the launch path and the attribution path disagree about which areas belong to a routed pack's plan.

This subtask replaces the union with a per-routed-root `composedAreas` call and pins the two paths to
one area set.

- Compute the area set inside the `routedManifests.flatMap { root -> ... }` loop, per root, as
  `ReviewLaunchPlanPolicy.composedAreas(root.slug, manifests)`.
- Leave `flatten`, its winner selection, and `AmbiguousLaneOwnershipError` untouched. The domain
  policy is correct; this is a caller fix.
- Leave the `groupBy { it.skillName }` merge at `:1049` in place. Replacing it is subtask 2.
- Leave fallback-pack participation in place. Excluding it is subtask 3.
- Keep the existing `require(lanes.isNotEmpty())` guard meaningful: a routed root whose composition
  declares no area is a loud failure, not a silently empty plan.

## Acceptance Criteria

1. `ParallelCodeReviewRunner` derives its area set from `ReviewLaunchPlanPolicy.composedAreas(root.slug, manifests)`, evaluated per routed root.
2. No call site in the runtime computes a review area set by unioning `declaredCodeReviewAreas` across all installed manifests. A grep for that shape over `runtime-*/src/main` returns nothing.
3. A parity test asserts that, for the same routed pack slug and the same manifest set, the launch path and `FileSystemReviewAttribution.composedLaunchPlan` produce identical area sets. The test covers a pack with a composed baseline layer (`kmp` composing `kotlin`) and a pack with none (`kotlin`).
4. `inline` execution mode is behaviourally unchanged: no `inline`-path test changes, and the inline planning seam produces the same lanes as before this subtask.
5. A routed root whose composed area set is empty still fails loudly through the existing `require(lanes.isNotEmpty())` guard rather than contributing zero lanes silently.
6. `./gradlew check` passes, and no comment is added to any changed file.

## Non-Goals

- Cross-root lane reconciliation, the area-keyed merge key, and cross-root ambiguity. Subtask 2.
- Fallback-pack exclusion and the `generic` lane count. Subtask 3.
- Any weakening of `flatten`'s per-area winner selection or its ambiguity error.
- Changes to `ReviewStackRouting` scoring or ownership.
- Composing rubric content across baseline layers.

## Dependency Notes

No dependencies. This is the first subtask and lands on the feature branch first.

After this subtask, the plan still contains cross-root duplicates and still contains fallback lanes.
That is expected: this subtask narrows each root's plan to areas its own composition declares, which
is the precondition for subtask 2's depth comparison to mean anything.

## Validation Strategy

- Unit test in `runtime-application` covering the per-root area set: with `generic`, `kotlin`, and
  `kmp` installed, the `kotlin` root's selected areas equal `kotlin`'s declared areas, and the `kmp`
  root's selected areas equal `kmp`'s declared areas plus `kotlin`'s.
- Parity test as described in acceptance criterion 3, asserting set equality in both directions so a
  future divergence on either side fails.
- Existing delegated-review planning tests must continue to pass; a lane that disappears here is a
  lane an unrelated root never should have planned, and the diff to those expectations must be
  explained in the commit body.
- `(cd runtime-kotlin && ./gradlew check)`.

## Next Path

Subtask 2 — reconcile lanes across routed roots by area with nearest-depth ownership.
