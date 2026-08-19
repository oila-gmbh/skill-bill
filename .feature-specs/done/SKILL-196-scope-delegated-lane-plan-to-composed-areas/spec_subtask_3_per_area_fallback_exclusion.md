# SKILL-196 Subtask 3 — Exclude fallback lanes per area

## Scope

`platform-packs/generic/platform.yaml` declares `fallback_capabilities: [code-review]`, empty
`tie_breakers`, and one non-substantive routing signal. It is in no native pack's
`code_review_composition` chain, so depth-based selection cannot demote it: it arrives at depth 0 of
its own plan and wins every area it declares.

`ReviewStackRouting.route` already excludes fallback packs from `concreteManifests` when scoring
routing signals, so `generic` never wins a file on signal strength. It becomes a routed root only
through the per-file fallback branch, for changed files that matched no native pack's path signal.
The defect is that a fallback root, once present for those files, plans a lane for every area it
declares — including areas a native routed pack already owns.

This subtask makes fallback participation per area.

- A pack declaring `code-review` in `fallback_capabilities` contributes a lane for an area if and
  only if no native routed pack in the assembled cross-root plan declares that area.
- Exclusion is evaluated per area, never per review. A fallback pack routed alongside native packs
  still supplies lanes for the areas no native routed pack declares.
- Fallback exclusion is decided over the assembled cross-root plan from subtask 2, not by removing
  the fallback slug from `ReviewStackRouting.routedSlugs`. Removing the root would orphan the very
  files that routed to it through the fallback branch.
- Resolve the fallback owner through `ReviewFallbackResolver`, not by re-reading
  `fallbackCapabilities` at a new site, so the single-owner and baseline-present invariants keep
  firing.

### Required decision: owned paths of an excluded fallback lane

A fallback root exists because some changed files matched no native pack. When a fallback lane for
area A is excluded because a native pack declares A, those files are no longer claimed by any lane
for A, while AC 13 of the parent spec requires coverage accounting to record exactly as before and
forbids recording a removed lane as unreviewed coverage.

Resolve this explicitly and record the choice in `agent/decisions.md` for the review boundary. The
two admissible resolutions:

- Fold the excluded fallback lane's `ownedPaths` and `changedHunkIds` into the winning native lane
  for that area, so the area's file claim is unchanged and only the lane count falls. This keeps
  coverage identical and does not compose rubric content, so it does not violate the parent spec's
  rejected non-goal.
- Leave the files unclaimed for that area and prove, with a test, that the coverage ledger already
  treats them as out of scope for that pack rather than as unreviewed.

Do not resolve it by silently dropping the paths. Whichever path is chosen, the coverage assertions
in acceptance criterion 7 are the gate.

## Acceptance Criteria

1. A pack declaring `code-review` in `fallback_capabilities` contributes a lane for an area if and only if no native routed pack in the assembled plan declares that area.
2. With `generic`, `kotlin`, and `kmp` installed and a Kotlin/Android diff, the planned lane set — and therefore `review_run_lanes` — contains zero rows with `pack_slug = 'generic'`, because `kotlin` and `kmp` between them declare every area `generic` declares.
3. With the same setup, `platform-correctness` plans exactly one lane with `pack_slug = 'kmp'`, and `architecture` plans exactly one lane with `pack_slug = 'kotlin'`.
4. An area declared only by the fallback pack, with no native routed pack declaring it, still plans its fallback lane. A test constructs this case explicitly.
5. A regression test reproduces the observed 13-lane cross-stack plan for `generic` + `kotlin` + `kmp` and asserts it resolves to exactly one lane per distinct area, with no `generic` lane, asserting the owning pack slug per area.
6. Every area present in the pre-change plan is present in the post-change plan. The set of distinct areas does not shrink when a fallback lane is excluded.
7. `unreviewedSegmentIds`, segment accounting, coverage facts, and integration terminal state record exactly as before. An excluded fallback lane is not recorded as unreviewed coverage and does not reduce any lane disposition to `incomplete`.
8. The fallback owner is resolved through `ReviewFallbackResolver`, so the multiple-owner and missing-baseline errors still fire.
9. `inline` execution mode is behaviourally unchanged.
10. The owned-paths decision above is resolved, its rationale recorded in the review boundary's `agent/decisions.md`, and backed by the coverage test in acceptance criterion 7.
11. `./gradlew check` passes, and no comment is added to any changed file.

## Non-Goals

- Composing the fallback pack's rubric into the winning native lane. Rejected in the parent spec: the
  generic pack is a fallback for a missing native lane, not a second opinion on a covered area.
  Transferring owned paths is a claim transfer, not rubric composition, and is not covered by this
  rejection.
- Repairing native content this exclusion newly exposes. Tracked and landed as SKILL-197
  (`7ffac2f4f`); any residual gap is a follow-up on that ticket, not a reason to keep the fallback
  lane.
- Changing `ReviewStackRouting` scoring, its `concreteManifests` filter, or the per-file fallback
  branch.
- Bundling multiple areas into one lane, tightening `lane_conditions` triggers, or adding a triage
  pass before fan-out.

## Dependency Notes

Depends on subtask 2. Per-area fallback exclusion is a rule applied to the area-keyed cross-root
reconciliation that subtask 2 introduces; with the skill-name merge still in place there is no
assembled per-area view to exclude against.

The parent spec's original ordering constraint — AC 4 must not ship before SKILL-197 — is satisfied:
SKILL-197 landed in `7ffac2f4f`.

## Validation Strategy

- Regression test as described in acceptance criterion 5, built from the `generic` + `kotlin` + `kmp`
  manifest set and a Kotlin/Android changed-file set, asserting the full lane list with owning pack
  per area.
- Test for acceptance criterion 4 using a constructed manifest set where the fallback pack declares an
  area no native routed pack declares, asserting the fallback lane is planned.
- Coverage test for acceptance criterion 7 asserting `unreviewedSegmentIds` and terminal state match
  the equivalent run, whichever owned-paths resolution is chosen.
- Test that a manifest set with two fallback owners still raises through `ReviewFallbackResolver`.
- `(cd runtime-kotlin && ./gradlew check)` plus `skill-bill validate`.

## Next Path

Feature complete. Run the delegated review over the branch and open the PR.
