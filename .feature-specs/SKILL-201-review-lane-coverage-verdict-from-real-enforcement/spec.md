# SKILL-201 — Derive review lane coverage from enforcement, not from a projected budget

## Context

A delegated review of a 59-file branch reported `Coverage: NOT clean — 2 lane(s) ended with
incomplete coverage` and named 20 files as unreviewed, including every git and checkpoint-identity
file the branch changed. The lanes had reviewed all of them. The verdict was false.

### The projection and the enforcement measure different resources

`ReviewLaneCompletionState.withLaneEvidenceBudget`
(`runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/review/context/model/ReviewLaneBundleAssembly.kt:306`)
sums `entry.hunk.contentBytes` over the lane's assembled diff bundle and compares the running total
against `maxLaneEvidenceBytes`. On overflow it flips the lane to `INCOMPLETE`, sets
`budgetDimension` to `lane_evidence_bytes`, sets `unreviewedSegmentIds` to
`["evidence-unreviewable"]`, and fills `unreviewedUnits` with `commit@path` labels for the tail.

`maxLaneEvidenceBytes` is the broker's cumulative `read_evidence` allowance.
`FileSystemReviewEvidenceBroker.assignedHunkBudgetOutcome`
(`runtime-kotlin/runtime-infra-fs/src/main/kotlin/skillbill/infrastructure/fs/FileSystemReviewEvidenceBroker.kt:243`,
and the whole-file path at `:383`) meters `cumulativeBytes` from reads the worker actually
performed and refuses the read that would cross the limit. That is the enforcement, and it already
reports itself through `ReviewEvidenceResult`.

So the projection answers "if this lane re-read its entire assigned diff through the broker, would
it exhaust the allowance?" and then writes the answer into the field that means "this code was not
reviewed."

### The projection withholds nothing

The lane's payload comes from `deliveredEntries`, which is
`segmentation.segments.flatMap { it.entries }`
(`runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/review/context/model/ReviewContextModels.kt:1123`),
and `canonicalPayload` renders from that at `:1125`. Segmentation is governed by
`maxLaneLaunchBytes`, not `maxLaneEvidenceBytes`. `withLaneEvidenceBudget` runs afterwards at
`:1115` and only rewrites accounting fields on the completion state. It never removes an entry from
`deliveredEntries`.

A lane whose disposition reads `incomplete` for this reason therefore received every assigned hunk.

### What the observed run actually showed

Review `rvw-20260820-052146-53de`, `mode:delegated`, branch scope, 59 files, +5117/-450:

| measure | value |
|---|---|
| lane assigned diff content | 344,781 bytes |
| `max_lane_evidence_bytes` | 262,144 bytes |
| counted as delivered | 246,721 bytes |
| flagged `evidence-unreviewable` | 98,060 bytes, 57 of 190 entries |
| segmentation result | 12 segments, seg-000 to seg-011, zero `unreviewable` entries |
| broker expansions | 0 |
| broker tool calls | 0 |

The broker never ran. The segmentation that governs the payload succeeded cleanly. The only thing
that failed was a projection against a budget for an operation the worker never attempted.

Both flagged lanes were `bill-kmp-code-review-architecture` and
`bill-kmp-code-review-platform-correctness`. `ReviewPreparationService.composeAssignments` scopes a
lane to `packet.focusedHunkIds(decision)` over `decision.normalizedOwnedPaths`
(`runtime-kotlin/runtime-application/src/main/kotlin/skillbill/application/review/ReviewPreparationService.kt:188`).
Those two areas own the broadest path sets in the `kmp` pack, so their assignment is effectively the
whole diff. The six narrower lanes stayed under the cap and reported clean. A lane that owns 100% of
the changed paths and a lane that owns 10% receive the same flat cap, so on a large diff the broad
lanes are structurally guaranteed to report dirty.

### The named files are an artifact of path sort order

`ReviewLaneAssembledBundle.ENTRY_ORDER` sorts by `orderIndex`, then `hunk.path`, then `newStart`,
then `oldStart` (`ReviewLaneBundleAssembly.kt:60`). The reviewed scope resolved to one synthetic
aggregate commit, so ordering collapsed to a pure path sort, and `withLaneEvidenceBudget` takes a
greedy prefix. The reported gap was the alphabetical tail: `runtime-cli/`, `runtime-contracts/`,
`runtime-domain/`, `runtime-infra-fs/`, `runtime-ports/`. It read as the git and checkpoint core of
the feature under review purely because those module names sort last.

A coverage report whose contents depend on path spelling is not a coverage report.

### Raising the limit is not the fix

`.skill-bill/config.yaml` in this repository sets `max_parent_packet_bytes: 1048576` and leaves
`max_lane_evidence_bytes` at its `262_144` default (`ReviewContextModels.kt:341`). The config is
read on the live path (`ParallelCodeReviewRunner.kt:244`) and `max_lane_evidence_bytes` is an
accepted key (`FileSystemRepoLocalConfig.kt:229`), so a raise would take effect and would flip both
lanes to `complete` on this branch. It would change nothing else, because the lanes already reviewed
everything. Any threshold keeps producing a false verdict at a larger diff size.

There is a parallel asymmetry that shows the projection was never load-bearing:
`ParallelCodeReviewRunner.kt:1568` hands the parent lane `budget.maxLaneEvidenceBytes * laneCount`
while each specialist gets the flat value. The same number means two different things one call apart.

### Why the mechanism exists, and which half is sound

The test name states the original intent: `lane evidence overflow names undelivered units and does
not truncate a hunk`
(`runtime-kotlin/runtime-domain/src/test/kotlin/skillbill/review/context/model/ReviewLaneBundleAssemblyTest.kt`).
Refusing to cut a hunk in half and naming what did not fit is the right instinct. The
`asFailedLaneRun` path is the same instinct wired correctly: a lane whose worker run failed is
downgraded to incomplete and names its whole bundle
(`ParallelCodeReviewRunner.kt:1656`). Segmentation's own `unreviewable` constant, for an entry too
large to fit a launch segment alone, is also sound: that entry genuinely cannot be delivered.

Only the evidence projection is misaimed, and it is the sole budget in the policy whose only effect
is to write a pessimistic string into a report.

### Cost of leaving it

False coverage gaps are worse than no coverage accounting. An operator who is told the git core of a
branch went unreviewed either re-runs a full delegated fan-out for nothing, or learns to ignore the
coverage line, which then hides the real gaps that `asFailedLaneRun` and the broker exist to surface.

## Intended Outcome

A lane reports `incomplete` when something was actually not reviewed: the broker refused a read, an
entry could not fit a launch segment alone, or the worker run failed. The assembled diff bundle's
size is never by itself a coverage verdict.

Projected headroom, if reported at all, is reported as headroom, in its own field, with wording that
cannot be mistaken for unreviewed code, and never feeds `unreviewedUnits`.

Per-lane budgets that remain scale with what a lane was assigned instead of applying one flat value
to lanes owning 100% and 10% of the changed paths.

## Acceptance Criteria

1. A lane whose assembled diff bundle exceeds `max_lane_evidence_bytes`, whose segmentation produced no unreviewable entry, whose worker run succeeded, and whose broker refused no read reports `lane_disposition: complete`, an empty `unreviewed_segment_ids`, an empty `unreviewed_units`, and no `budget_dimension`.
2. A lane whose broker refused a `read_evidence` call for `lane_evidence_bytes` reports `incomplete`, names `lane_evidence_bytes` as its `budget_dimension`, and names only the units the refusal actually denied.
3. A lane holding an entry that cannot fit `max_lane_launch_bytes` alone still reports `incomplete` with the `unreviewable` segment id and that entry in `unreviewed_units`.
4. A lane whose worker run failed still reports `incomplete` naming its whole assigned bundle, unchanged from `asFailedLaneRun` today.
5. `unreviewedUnits` is populated only from units that were withheld, refused, or lost to a failed run. No code path derives it from a size comparison against an allowance for an operation that was not attempted.
6. `Coverage: NOT clean` and the per-lane `left unreviewed:` line (`ReviewCoverageReport.kt:50`) are emitted only for the cases in criteria 2, 3, and 4.
7. The integration pass's `Coverage gap — this lane left unreviewed:` prompt line (`ReviewIntegrationPassRunner.kt:186`) is emitted on the same conditions, so the parent is never told to compensate for a gap that does not exist.
8. If projected expansion headroom is still reported, it occupies a field distinct from `unreviewed_segment_ids`, `unreviewed_units`, and `budget_dimension`; its wording names headroom or allowance rather than unreviewed code; and it does not change `lane_disposition`.
9. Every per-lane byte budget that survives is derived from the lane's own assignment rather than applied as one flat value across lanes with different assigned path counts, or the flat value is documented as intentional at its definition with the reason.
10. `max_lane_evidence_bytes` has exactly one meaning across the runtime. The `budget.maxLaneEvidenceBytes * laneCount` derivation at `ParallelCodeReviewRunner.kt:1568` either resolves to the same meaning as the specialist value or is replaced by a separately named parent budget.
11. Re-running `mode:delegated` over the SKILL-190 branch range produces `Coverage: clean` with the same finding set, and no lane names a file the worker received.
12. `orchestration/contracts/review-context-schema.yaml` still requires `budget_dimension` whenever `lane_disposition` is `incomplete` (the `allOf` rule at line 301), and `ReviewIntegrationPass`'s invariant that an incomplete summary must name what it left unreviewed (`ReviewIntegrationPass.kt:27`) still holds for every remaining incomplete path.
13. Any schema change to the lane completion or accounting records lands as a contract version bump with a parity test and a typed rejection, per the runtime contract rules.
14. `evidence-unreviewable` is absent from the runtime, or retained only under criterion 8 with the segment id renamed to match what it now means.
15. Legacy accounting records carrying the old `evidence-unreviewable` segment id do not crash a read; they are quarantined or migrated in band and the degradation is recorded.
16. Every test that pinned the removed behavior is deleted or rewritten against the new rule, not weakened to keep passing. The five suites naming this path are `ReviewLaneBundleAssemblyTest`, `ReviewPreparationServiceTest`, `ReviewContextSchemaValidatorTest`, `FileSystemRepoLocalConfigTest`, and `FileSystemReviewEvidenceBrokerTest`.
17. `skill-bill validate`, `(cd runtime-kotlin && ./gradlew check)`, `npx --yes agnix --strict .`, and `scripts/validate_agent_configs` all pass.

## Scope

`ReviewLaneBundleAssembly.kt`: `withLaneEvidenceBudget`, `EVIDENCE_UNREVIEWABLE_SEGMENT_ID`,
`LANE_EVIDENCE_BYTES_DIMENSION`, and the `ReviewLaneCompletionState` invariants.

`ReviewContextModels.kt`: the `completionState` composition at `:1115`, `ReviewContextBudgetPolicy`
and its `init` requirements, and the budget line the lane launch prints at `:1309`.

`ParallelCodeReviewRunner.kt`: the parent budget derivation at `:1568` and the lane disposition
rollup that distinct-flattens incomplete lanes into the parent.

`ReviewPreparationService.kt` if per-assignment budget derivation lands there alongside
`composeAssignments`.

`ReviewCoverageReport.kt` and `ReviewIntegrationPassRunner.kt` reporting seams.
`ReviewAccountingProjection.kt` and `ReviewCommitEnvelopeFragments.kt` wire projections.
`orchestration/contracts/review-context-schema.yaml` if any record shape changes.

## Constraints

Fewer incomplete verdicts must come from removing false ones, never from suppressing true ones. A
review that silently drops a hunk is a worse failure than a review that over-reports, so criteria 2,
3, and 4 are the floor and every one of them needs a test that fails if its path stops reporting.

The broker is the only component that knows the true cumulative evidence figure. Coverage claims
belong downstream of it, not upstream in a pre-flight estimate.

`max_lane_launch_bytes` and its segmentation stay exactly as they are. They govern payload delivery
and they worked correctly on the observed run.

Schema and durable record changes follow the runtime contract rules: YAML schema first, then the
Kotlin `*_CONTRACT_VERSION`, then the parity test, then the typed error, then loud-fail at every
parse seam.

Do not raise `max_lane_evidence_bytes` in `.skill-bill/config.yaml` as part of this work. That would
mask the defect on this repository's current diffs and remove the reproduction.

Reproduction range for verification is `a1afecd5f..feat/SKILL-190-one-commit-per-subtask`, which
produced review `rvw-20260820-052146-53de`.

## Non-Goals

Changing `max_lane_launch_bytes`, segmentation, or `deliveredEntries`.

Removing the broker's cumulative evidence enforcement, `asFailedLaneRun`, the parent packet gate at
`ReviewPreparationService.kt:407`, the specialist tool-call and model-turn stops, or any other budget
with real teeth.

Changing lane selection, specialist routing, the finding admission gate, the severity vocabulary, or
the risk register format.

Narrowing which paths an area owns. Assignment breadth is `feat/SKILL-196`'s subject; this work only
stops a broad assignment from being reported as a coverage failure.

Re-litigating the provider token thresholds, which `feat/SKILL-194` is removing.

## Subtasks

1. Sever the coverage verdict from the projected evidence budget: remove `withLaneEvidenceBudget`
   from the completion path and prove the three real incomplete paths still fire.
2. Make the broker's refusal the source of a `lane_evidence_bytes` incomplete verdict, carrying the
   refused units through to the lane completion state.
3. Reconcile `max_lane_evidence_bytes` to one meaning and derive surviving per-lane byte budgets from
   the lane's own assignment.
4. Reconcile the reporting seams and wire projections, including any schema change with its version
   bump, parity test, typed error, and legacy-record quarantine.
5. Verify against the recorded reproduction: re-run `mode:delegated` over the SKILL-190 range and
   confirm `Coverage: clean` with an unchanged finding set.

## Validation Strategy

Subtask 1 is a behavior change verified by `(cd runtime-kotlin && ./gradlew check)`. Expect
`ReviewLaneBundleAssemblyTest`'s evidence-overflow case to fail first, because it asserts exactly the
behavior being removed; it is deleted or rewritten, not weakened. Criterion 1 needs a test
constructing a lane over the cap with clean segmentation, a successful run, and no broker refusal,
asserting `complete`. Criteria 3 and 4 need tests that fail if the segmentation `unreviewable` path
or `asFailedLaneRun` stops reporting.

Subtask 2 is verified at the broker seam in `FileSystemReviewEvidenceBrokerTest` plus a lane-level
test that a refused read surfaces as `incomplete` naming only the denied units, not the tail of a
path sort.

Subtask 3 is verified by a test that two lanes with different assigned path counts receive different
derived budgets, and by asserting one consistent meaning across the specialist and parent
derivations.

Subtask 4 is verified by `ReviewContextSchemaValidatorTest` for any record change, a contract-version
parity test in the `PlatformPackSchemaContractVersionTest` pattern, a rejection test for the typed
error, and a test that a legacy record carrying `evidence-unreviewable` is quarantined rather than
crashing a read.

Subtask 5 is the end-to-end check. Re-run `mode:delegated` over
`a1afecd5f..feat/SKILL-190-one-commit-per-subtask` and compare against the recorded
`rvw-20260820-052146-53de` register: coverage clean, no lane naming a delivered file, and the same
findings.

Every subtask finishes with `skill-bill validate`, `npx --yes agnix --strict .`, and
`scripts/validate_agent_configs`.

## Next Path

```bash
skill-bill goal SKILL-201
```
