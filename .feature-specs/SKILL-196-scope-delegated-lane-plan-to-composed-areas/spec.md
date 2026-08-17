# SKILL-196 — Scope the delegated lane plan to composed areas

## Context

A delegated review of a five-commit Kotlin/Android PR (`rvw-20260817-183143-ilvx`, capmo-android)
planned **13 specialist lanes across 8 distinct areas**. Five areas were claimed by more than one
pack:

| Area | Packs that planned a lane | Required |
|---|---|---|
| platform-correctness | generic, kmp, kotlin | all three |
| architecture | generic, kotlin | both |
| reliability | generic, kotlin | no |
| ui | generic, kmp | no |
| api-contracts | generic | no |
| persistence, testing, ux-accessibility | kotlin | no |

The duplication is not a heuristic that fired too eagerly. Per-area winner selection **already
exists and is correct**; two caller defects route around it.

### The policy already selects one owner per area

`ReviewLaunchPlanPolicy.flatten` walks the composition graph, collects an `AreaCandidate` per
(pack, area), then keeps exactly one winner per area at the nearest composition depth, raising
`AmbiguousLaneOwnershipError` when two packs tie at that depth
(`ReviewLaunchPlanPolicy.kt:80-97`). Within a single plan, `bill-kmp-code-review-platform-correctness`
and `bill-kotlin-code-review-platform-correctness` can never both launch. That is the intended
behaviour, and it works.

### Defect 1 — the launch path uses the forbidden area union

`ReviewLaunchPlanPolicy` exposes `composedAreas(routedSlug, manifests)` for callers that need the
area set, and its KDoc states the constraint explicitly (`ReviewLaunchPlanPolicy.kt:12-17`):

> A caller must not substitute the union across all installed manifests: that puts areas the
> composition never declares into the plan, and a plan lane is read downstream as a lane the run
> launched.

The launch path does exactly that (`ParallelCodeReviewRunner.kt:1030`):

```kotlin
val selectedAreas = manifests.flatMap { it.declaredCodeReviewAreas }.toSet()
```

`composedAreas` has one caller in the tree — `FileSystemReviewAttribution.kt:32`, the attribution
path. So **attribution and launch compute different area sets for the same routed pack**. Attribution
scopes to the composition; launch unions every installed manifest. A lane can be launched for an area
the routed composition never declared, and attribution will not account for it as part of that pack's
plan.

### Defect 2 — winner selection is per-root, and the cross-root merge key is the skill name

`flatten` is invoked once per routed root (`ParallelCodeReviewRunner.kt:1035-1037`), so its
one-winner-per-area guarantee holds only *within* each root's plan. The results are then merged
across roots by skill name (`:1049`):

```kotlin
.groupBy { it.skillName }
```

Winners from different roots carry different `packSlug` values, and the skill name is derived from
the pack slug (`ReviewLaunchPlanPolicy.kt:106`), so `bill-generic-code-review-architecture` and
`bill-kotlin-code-review-architecture` are distinct keys and never merge. The dedupe is structurally
incapable of catching cross-root duplication, which is the only kind that occurs.

### The cost lands on required lanes specifically

A required lane claims the owning pack's entire routed file set, while an optional lane claims only
its signal-matched subset (`ParallelCodeReviewRunner.kt:1039`):

```kotlin
val ownedPaths = if (lane.required) rootOwnedPaths else laneOwnedPaths(lane, rootFiles)
```

`architecture` and `platform-correctness` are `required: true` in all three packs
(`kotlin/platform.yaml`, `kmp/platform.yaml`, `generic/platform.yaml`). The observed run therefore
planned **five required lanes**, each claiming the whole routed diff, where correct per-area
selection plans **two**. Each lane is a separate `claude --print` process paying a full launch
envelope, so the duplicated required lanes are the most expensive lanes in the run.

Correct selection on the observed diff yields **8 lanes instead of 13**, with no area losing
coverage.

## Intended Outcome

A delegated review plans exactly one specialist lane per review area, owned by the nearest pack in
the routed composition, regardless of how many packs routed. The launch path and the attribution
path derive their area sets from the same policy function, so a planned lane and an attributed lane
describe the same set. Coverage is unchanged: every area that would have been reviewed is still
reviewed, once, by its most specific owner.

Cross-root ambiguity is resolved deterministically or fails loudly. It is never resolved by
launching both lanes.

## Acceptance Criteria

1. The launch path derives its area set from `ReviewLaunchPlanPolicy.composedAreas(root.slug,
   manifests)`. No call site in the runtime computes an area set by unioning
   `declaredCodeReviewAreas` across all installed manifests.
2. A single review plans at most one lane per area. For any review run, `review_run_lanes` contains
   no two rows sharing the same `area`.
3. With `generic`, `kotlin`, and `kmp` installed and a Kotlin/Android diff that routes all three,
   `platform-correctness` plans exactly one lane and its `pack_slug` is `kmp`; `architecture` plans
   exactly one lane and its `pack_slug` is `kotlin`.
4. Cross-root duplicate areas are resolved by composition depth, not by skill name. The merge key
   used to reconcile lanes across routed roots is the area, and two lanes for the same area at the
   same nearest depth from different packs raise `AmbiguousLaneOwnershipError` rather than both
   launching.
5. For the same routed pack and manifest set, the launch path and `FileSystemReviewAttribution`
   produce identical area sets, pinned by a parity test.
6. No area is claimed by more than one required lane, so the whole-routed-diff claim at
   `ParallelCodeReviewRunner.kt:1039` is paid at most once per area.
7. A regression test reproduces the observed 13-lane cross-stack plan and asserts it resolves to one
   lane per distinct area, with the owning pack asserted per area.
8. Lane coverage is unchanged: the set of distinct areas in the resolved plan equals the set of
   distinct areas in the pre-change plan for the same diff.
9. `inline` execution mode is behaviourally unchanged.
10. `unreviewedSegmentIds`, segment accounting, coverage facts, and integration terminal state record
    exactly as before. Removing a duplicate lane must not be recorded as unreviewed coverage.

## Scope

- Replace the all-manifest area union at `ParallelCodeReviewRunner.kt:1030` with
  `ReviewLaunchPlanPolicy.composedAreas`, per routed root.
- Reconcile lanes across routed roots by area with nearest-depth ownership, replacing the
  `groupBy { it.skillName }` merge at `ParallelCodeReviewRunner.kt:1049`.
- Extend or reuse the existing `AmbiguousLaneOwnershipError` path so a cross-root tie at equal depth
  fails loudly instead of launching both lanes.
- Add the launch/attribution area-set parity test.
- Add the cross-stack plan regression test covering the observed generic+kotlin+kmp case.

## Constraints

- Coverage must not shrink. This change removes duplicate lanes for an area, never the last lane for
  an area.
- A removed duplicate is not a coverage gap and must not be recorded in `unreviewedSegmentIds` or
  reduce a lane disposition to `incomplete`.
- Ambiguity fails loudly. No silent fallback to a general-purpose or generic-pack lane when the
  owning pack cannot be determined — consistent with the existing loud-fail contract.
- Determinism is required: the same manifest set and diff must always produce the same owner per
  area, independent of manifest iteration order.
- The domain policy is already correct. Fix the callers; do not weaken `flatten`'s winner selection
  or its ambiguity error to accommodate them.
- No comments are added to any changed file.

## Non-Goals

- **Bundling multiple areas into one lane.** Collapsing e.g. platform-correctness + reliability into
  a single worker is a depth tradeoff and a separate design change. This spec reduces lane count only
  by removing duplicates, at zero coverage or depth cost.
- **Tightening `lane_conditions` content triggers.** The optional-lane triggers are broad substring
  matches (`"schema"`, `"retry"`, `"timeout"`, `"database"`) that fire on most non-trivial diffs. That
  is a real second-order cost driver and a follow-up ticket, not part of this correctness fix.
- **A triage pass that nominates areas before fan-out.** Follow-up.
- **Reordering the launch envelope for prompt-cache locality.** `ReviewPacketProjection.kt:79-111`
  emits lane-varying fields (`assignment_digest`, `lane`, `rubric`, `bundle`) ahead of large
  lane-invariant blocks (`forbidden_rediscovery`, `evidence_surface_rules`, `report_structure`,
  `consumer_contract`), which forfeits prefix caching across lanes. Separate ticket; note that after
  SKILL-194 the runtime records no provider cache counters, so the benefit is not measurable from
  within the runtime and the change must be justified on cache semantics alone.
- **Instrumenting provider cache counters to measure any of this.** Explicitly withdrawn: SKILL-194
  removes provider-reported token accounting entirely and its AC 14 forbids a replacement. Lane-count
  reduction remains measurable through the byte and count surfaces SKILL-194 retains.
- **Running specialist lanes as in-process subagents instead of separate processes.** Larger change;
  the per-lane fixed overhead argument stands, but it is independent of plan correctness.
- Changing routing itself, including whether `ReviewStackRouting` should return a single dominant
  root. This spec makes the plan correct for however many roots routing returns.

## Diagnostic Evidence

Policy that already implements the intended behaviour:

- `runtime-domain/.../review/plan/ReviewLaunchPlanPolicy.kt:12-17` — `composedAreas` KDoc stating the
  constraint the launch path violates.
- `:18-30` — `composedAreas`, the correct API.
- `:80-97` — per-area nearest-depth winner selection and `AmbiguousLaneOwnershipError`.
- `:106` — skill name derived from pack slug, which is why the skill-name merge key cannot dedupe
  across packs.

Caller defects:

- `runtime-application/.../review/ParallelCodeReviewRunner.kt:1030` — the forbidden all-manifest area
  union.
- `:1035-1037` — `flatten` invoked per routed root, scoping winner selection to one root.
- `:1039` — required lanes claim `rootOwnedPaths`, the whole routed file set.
- `:1049` — `groupBy { it.skillName }`, structurally unable to merge same-area lanes across packs.

Correct usage that establishes the parity gap:

- `runtime-infra-fs/.../FileSystemReviewAttribution.kt:32` — the only `composedAreas` caller.

Manifest declarations producing the observed overlap:

- `platform-packs/generic/platform.yaml` — declares all ten areas; `architecture` and
  `platform-correctness` `required: true`.
- `platform-packs/kotlin/platform.yaml` — declares all ten areas; `architecture` and
  `platform-correctness` `required: true`; `tie_breakers` already articulate that KMP markers should
  establish ownership over this pack.
- `platform-packs/kmp/platform.yaml` — declares five areas; `platform-correctness` `required: true`;
  composes `kotlin`/`generic` as baseline layers.

Observed run:

- `review_run_lanes` for `rvw-20260817-183143-ilvx` — 13 rows, 8 distinct areas, 5 rows with
  `required = 1` spanning 2 distinct areas.
