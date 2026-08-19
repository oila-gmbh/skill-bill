# SKILL-196 — Scope the delegated lane plan to composed areas

## Context

A delegated review of a five-commit Kotlin/Android PR (`rvw-20260817-183143-ilvx`, capmo-android)
planned **13 specialist lanes across 8 distinct areas**. Five areas were claimed by more than one
pack, and the fallback pack claimed five lanes of its own:

| Area | Packs that planned a lane | Required |
|---|---|---|
| platform-correctness | generic, kmp, kotlin | all three |
| architecture | generic, kotlin | both |
| reliability | generic, kotlin | no |
| ui | generic, kmp | no |
| api-contracts | generic | no |
| persistence, testing, ux-accessibility | kotlin | no |

Neither the duplication nor the generic participation is a heuristic that fired too eagerly. Both
the per-area winner selection and the fallback-only status of the generic pack are **already
declared and already implemented**; caller defects route around them.

### `generic` is a declared fallback pack that must never compete with a native pack

`platform-packs/generic/platform.yaml` declares:

```yaml
routing_signals:
  strong:
    - "manifest-declared code-review fallback"
  tie_breakers: []

fallback_capabilities:
  - code-review
```

Its only routing signal is a literal description of its fallback status — not a path marker, not a
content marker. It is a fallback for the case where **no native pack declares an area**, not a peer
that reviews alongside one.

The composition graph confirms it is not meant to appear in a native plan. `kmp` composes `kotlin`
as a required baseline layer (`kmp/platform.yaml`, `code_review_composition.baseline_layers`), and
`kotlin` declares no `code_review_composition` at all. **`generic` is therefore not in the
composition chain of any native pack.** The only way its five lanes entered this plan is
`ReviewStackRouting` returning `generic` as a routed root alongside `kotlin` and `kmp`, ignoring the
pack's own `fallback_capabilities` declaration.

Because generic sits in no composition chain, depth-based winner selection cannot demote it. It
arrives at depth 0 of its own plan and wins every area it declares — all ten.

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

### Defect 3 — a fallback pack is routed as a root

`ReviewStackRouting` returns `generic` among `routedSlugs` for a diff that already routed `kotlin`
and `kmp`, despite `generic` declaring `fallback_capabilities: [code-review]` and no substantive
routing signal. Fallback status is per-capability and must be evaluated per area, not per review: a
fallback pack supplies a lane for an area **only when no native routed pack declares that area**.

The correct plan for the observed diff routes `kmp` (which composes `kotlin`) and plans **zero**
generic lanes, because every area generic declares is also declared by `kotlin` or `kmp`.

### The cost lands on required lanes specifically

A required lane claims the owning pack's entire routed file set, while an optional lane claims only
its signal-matched subset (`ParallelCodeReviewRunner.kt:1039`):

```kotlin
val ownedPaths = if (lane.required) rootOwnedPaths else laneOwnedPaths(lane, rootFiles)
```

`architecture` and `platform-correctness` are `required: true` in all three packs. The observed run
therefore planned **five required lanes**, each claiming the whole routed diff, where correct
selection plans **two**. Each lane is a separate `claude --print` process paying a full launch
envelope, so the duplicated required lanes are the most expensive lanes in the run.

## Intended Outcome

A delegated review plans exactly one specialist lane per review area, owned by the nearest native
pack in the routed composition. A fallback pack supplies a lane for an area only when no native
routed pack declares that area. The launch path and the attribution path derive their area sets from
the same policy function, so a planned lane and an attributed lane describe the same set.

Cross-root ambiguity is resolved deterministically or fails loudly. It is never resolved by
launching both lanes.

## Acceptance Criteria

1. The launch path derives its area set from `ReviewLaunchPlanPolicy.composedAreas(root.slug,
   manifests)`. No call site in the runtime computes an area set by unioning
   `declaredCodeReviewAreas` across all installed manifests.
2. A single review plans at most one lane per area. For any review run, `review_run_lanes` contains
   no two rows sharing the same `area`.
3. A pack declaring a capability in `fallback_capabilities` is never planned as a routed root
   alongside a native pack. It supplies a lane for an area if and only if no native routed pack in
   the composition declares that area.
4. With `generic`, `kotlin`, and `kmp` installed and a Kotlin/Android diff, `review_run_lanes`
   contains **zero** rows with `pack_slug = 'generic'`, because `kotlin` and `kmp` between them
   declare every area `generic` declares.
5. With the same setup, `platform-correctness` plans exactly one lane with `pack_slug = 'kmp'`, and
   `architecture` plans exactly one lane with `pack_slug = 'kotlin'`.
6. An area declared only by a fallback pack, with no native routed pack declaring it, still plans its
   fallback lane. Fallback exclusion is per-area, never per-review.
7. Cross-root duplicate areas are resolved by composition depth, not by skill name. The merge key
   used to reconcile lanes across routed roots is the area, and two native packs tying at the same
   nearest depth for one area raise `AmbiguousLaneOwnershipError` rather than both launching.
8. For the same routed pack and manifest set, the launch path and `FileSystemReviewAttribution`
   produce identical area sets, pinned by a parity test.
9. No area is claimed by more than one required lane, so the whole-routed-diff claim at
   `ParallelCodeReviewRunner.kt:1039` is paid at most once per area.
10. A regression test reproduces the observed 13-lane cross-stack plan and asserts it resolves to one
    lane per distinct area with no generic lane, asserting the owning pack per area.
11. Every area present in the pre-change plan is present in the post-change plan. Lane count falls;
    the set of distinct areas does not.
12. `inline` execution mode is behaviourally unchanged.
13. `unreviewedSegmentIds`, segment accounting, coverage facts, and integration terminal state record
    exactly as before. Removing a duplicate or fallback lane must not be recorded as unreviewed
    coverage.

## Scope

- Honour `fallback_capabilities` in routing so a fallback pack is never a routed root beside a native
  pack, and contributes an area only when no native pack declares it.
- Replace the all-manifest area union at `ParallelCodeReviewRunner.kt:1030` with
  `ReviewLaunchPlanPolicy.composedAreas`, per routed root.
- Reconcile lanes across routed roots by area with nearest-depth ownership, replacing the
  `groupBy { it.skillName }` merge at `ParallelCodeReviewRunner.kt:1049`.
- Extend or reuse the existing `AmbiguousLaneOwnershipError` path so a cross-root tie at equal depth
  fails loudly instead of launching both lanes.
- Add the launch/attribution area-set parity test.
- Add the cross-stack plan regression test covering the observed generic+kotlin+kmp case.

## Constraints

- Area coverage must not shrink. This change removes duplicate and fallback-redundant lanes for an
  area, never the last lane for an area.
- A removed duplicate is not a coverage gap and must not be recorded in `unreviewedSegmentIds` or
  reduce a lane disposition to `incomplete`.
- Ambiguity fails loudly. No silent fallback to a general-purpose lane when the owning native pack
  cannot be determined — consistent with the existing loud-fail contract.
- Determinism is required: the same manifest set and diff must always produce the same owner per
  area, independent of manifest iteration order.
- The domain policy is already correct. Fix the callers and the router; do not weaken `flatten`'s
  winner selection or its ambiguity error to accommodate them.
- **AC 4's SKILL-197 dependency is satisfied.** SKILL-197 landed in `7ffac2f4f`, so the native packs
  now carry Android-appropriate content for the areas `kmp` does not declare. The original ordering
  constraint is recorded here for provenance: excluding the generic lane is only safe once the
  owning native pack can ask the questions the generic rubric was asking. On
  `rvw-20260817-183143-ilvx`, `bill-generic-code-review-architecture` produced the run's sharpest
  architectural findings — **F-001, Major severity at High confidence, adjudicated `confirmed`**: a
  schema pull added `OF_workerEntryV2.description`, only the new subscription document selected it,
  and the same backend payload therefore persisted a silent null description on legacy VISITS
  projects. It also produced F-015, naming the byte-for-byte fragment clone that hid F-001.
  `bill-kotlin-code-review-architecture` found neither. Landing AC 4 before SKILL-197 would remove
  the only lane that caught a confirmed Major, so the two ship together or SKILL-197 ships first.
  Every other acceptance criterion in this spec is independent of that ordering.
- No comments are added to any changed file.

## Non-Goals

- **Composing a fallback pack's rubric into the winning native lane.** Considered and rejected. The
  generic pack is a fallback for a missing native lane, not a second opinion on a covered area. Where
  a native rubric asks fewer or weaker questions than the generic one for the same area, the repair
  is to strengthen that native pack's area content, not to run both lanes or merge their rubrics.
- **Repairing the native content this change newly exposes.** Tracked as **SKILL-197**. Excluding the
  generic lane means `architecture`, `performance`, `security`, `testing`, and `api-contracts` are
  reviewed on Android by `kotlin` content written for backend and desktop JVM, because `kmp` does not
  declare those areas. On the observed run `bill-generic-code-review-architecture` found 2 findings
  against `bill-kotlin-code-review-architecture`'s 1, and `architecture` is the most backend-skewed
  area at 7 of 15 rule lines. That is a content-ownership defect, not a reason to keep the fallback
  lane, and it must not be read as a coverage regression introduced by AC 4.
- **Bundling multiple areas into one lane.** Collapsing e.g. platform-correctness + reliability into
  a single worker is a depth tradeoff and a separate design change. This spec reduces lane count only
  by removing duplicate and fallback-redundant lanes, at zero coverage cost.
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

## Diagnostic Evidence

Declarations that already encode the intended behaviour:

- `platform-packs/generic/platform.yaml` — `fallback_capabilities: [code-review]`, empty
  `tie_breakers`, and a single non-substantive routing signal.
- `platform-packs/kmp/platform.yaml` — `code_review_composition.baseline_layers` composing `kotlin`
  with `required: true`.
- `platform-packs/kotlin/platform.yaml` — no `code_review_composition`, so `generic` is in no native
  composition chain; `tie_breakers` already state that KMP markers establish ownership over this pack.

Policy that already implements per-area selection:

- `runtime-domain/.../review/plan/ReviewLaunchPlanPolicy.kt:12-17` — `composedAreas` KDoc stating the
  constraint the launch path violates.
- `:18-30` — `composedAreas`, the correct API.
- `:80-97` — per-area nearest-depth winner selection and `AmbiguousLaneOwnershipError`.
- `:106` — skill name derived from pack slug, which is why the skill-name merge key cannot dedupe
  across packs.

Caller and router defects:

- `runtime-application/.../review/ParallelCodeReviewRunner.kt:1030` — the forbidden all-manifest area
  union.
- `:1035-1037` — `flatten` invoked per routed root, scoping winner selection to one root.
- `:1039` — required lanes claim `rootOwnedPaths`, the whole routed file set.
- `:1049` — `groupBy { it.skillName }`, structurally unable to merge same-area lanes across packs.
- `ReviewStackRouting` — returns `generic` among `routedSlugs` beside native packs, ignoring
  `fallback_capabilities`.

Correct usage that establishes the parity gap:

- `runtime-infra-fs/.../FileSystemReviewAttribution.kt:32` — the only `composedAreas` caller.

Rubric resolution, establishing that a dropped lane drops its questions:

- `runtime-infra-fs/.../FileSystemReviewRubricResolver.kt:20-50` — resolves only the owning pack's
  declared baseline and area content; rubric content is not composed across baseline layers. This is
  why fallback exclusion must be decided per area (AC 3, AC 6) rather than by assuming a winning
  lane inherits the fallback pack's questions.

Observed run:

- `review_run_lanes` for `rvw-20260817-183143-ilvx` — 13 rows, 8 distinct areas, 5 rows with
  `pack_slug = 'generic'`, 5 rows with `required = 1` spanning 2 distinct areas.

## Subtask Decomposition

Three dependency-ordered subtasks. Each lands independently and leaves the delegated review plan in a
valid state.

1. **Scope the launch path's area set to the routed composition** — replaces the all-manifest union at
   `ParallelCodeReviewRunner.kt:1030` with `ReviewLaunchPlanPolicy.composedAreas` per routed root, and
   pins launch/attribution area-set parity. Covers AC 1, AC 8, AC 12.
2. **Reconcile lanes across routed roots by area** — replaces the `groupBy { it.skillName }` merge at
   `:1049` with area-keyed nearest-depth ownership, extends `AmbiguousLaneOwnershipError` to
   cross-root ties, and holds coverage accounting unchanged. Covers AC 2, AC 7, AC 9, AC 11, AC 13.
3. **Exclude fallback lanes per area** — a pack declaring `code-review` in `fallback_capabilities`
   contributes a lane for an area only when no native routed pack declares that area, with the
   cross-stack regression test reproducing the observed 13-lane plan. Covers AC 3, AC 4, AC 5, AC 6,
   AC 10.

Subtask 2 depends on subtask 1: comparing composition depth across roots is only meaningful once each
root's area set is scoped to its own composition. Subtask 3 depends on subtask 2: per-area fallback
exclusion is expressed as a rule inside the cross-root reconciliation the second subtask introduces.

### Routing observation carried into the subtasks

`ReviewStackRouting.route` already excludes fallback packs from `concreteManifests` when scoring
routing signals. `generic` therefore never wins a file on signal strength. It becomes a routed root
only through the per-file fallback branch, for changed files that matched no native pack's path
signal. The defect is not that routing ignores `fallback_capabilities` when scoring; it is that a
fallback root, once present for unmatched files, then plans a lane for every area — including areas a
native routed pack already owns. Fallback exclusion is therefore decided per area over the assembled
cross-root plan, not by deleting the fallback root from routing.
