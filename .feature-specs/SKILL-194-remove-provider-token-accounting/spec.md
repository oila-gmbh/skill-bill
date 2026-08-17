# SKILL-194 — Remove provider-reported token accounting

## Context

The runtime models provider token usage with a single type, `ProviderTokenUsage`
(`runtime-domain/.../review/context/model/ReviewContextModels.kt:380-405`), whose invariants encode
**one** provider's convention as if it were universal:

```kotlin
require(cachedInputTokens == null || cachedInputTokens <= inputTokens!!) {
  "Cached-input tokens cannot exceed input tokens."
}
```

That holds for OpenAI/Codex, where `input_tokens` is inclusive of `cached_input_tokens`. It is false
for Anthropic, where `input_tokens` is the *uncached remainder* and `cache_read_input_tokens` /
`cache_creation_input_tokens` are additive: total prompt = `input + cache_read + cache_creation`.

The decoders are faithful. `AgentRunAdapters.kt:230` and `:259` map `cache_read_input_tokens` →
`cachedInputTokens` exactly as reported, and `AgentRunLaunchFacts`
(`AgentRunLauncherModels.kt:272-284`) only checks non-negativity. So a truthful Anthropic value
crosses the port boundary intact and detonates one layer up, when the review domain constructs
`ProviderTokenUsage` from it.

### The SKILL-190 incident

A cached Claude review lane reported `input_tokens: 2, cache_read_input_tokens: 17931`. The throw
site is `ParallelCodeReviewRunner.kt:1247`, inside `launchedParentOutcome`, which runs **after** the
child review agent has finished and its findings already sit in `outcome.stdout`. The exception
discarded a completed review pass and blocked the goal:

```
goal SKILL-190: blocked at subtask 2 — Feature-task-runtime phase 'review' lane launch threw
IllegalArgumentException: Cached-input tokens cannot exceed input tokens.
```

Nothing about the review failed. The accounting layer rejected a correct measurement.

### It never worked, and the repository already knew

The violating payload shape is checked into the repository as a decoder fixture:

```
runtime-infra-fs/.../AgentRunCommandBuildersTest.kt:80
{"input_tokens":2,"cache_read_input_tokens":17931,"output_tokens":6,"total_tokens":120}
```

That test asserts the decode round-trips, and it passes, because the port model permits the value.
Nothing constructs `ProviderTokenUsage` from the fixture, so the contradiction between the two
layers has no test that can observe it. Meanwhile `ReviewContextModelsTest.kt:130` asserts that
`input=1, cached=2` *must* throw — pinning the wrong convention as intended behaviour.

All 52 rows in `~/.skill-bill/review-metrics.db` recorded `cached_input_tokens: 0`. The invariant had
never once been exercised against a nonzero Anthropic cache read. The large recorded `input_tokens`
values (775872, 245513, 217156) are Codex-shaped inclusive totals; the single `input_tokens: 2` row
is Anthropic-shaped but happened to report zero cache reads.

A second defect follows from the same assumption:

```kotlin
val freshTokenApproximation get() = (inputTokens ?: 0) - (cachedInputTokens ?: 0) + (outputTokens ?: 0)
```

Under Anthropic semantics `input_tokens` already excludes cache reads, so this subtracts them twice
and goes negative. That was patched downstream rather than at the model —
`ReviewAccountingProjection.kt:114` applies `it.coerceAtLeast(0)`. The coercion is the fingerprint of
additive semantics leaking through before anyone named the cause.

`cache_creation_input_tokens` is never decoded by any adapter, so cache-write cost has always been
invisible. The accounting has therefore never produced a correct number for either provider: wrong
by construction for Anthropic, and silently missing a cost dimension for both.

### Why removal rather than repair

Repair means teaching every seam which provider convention it is holding, then re-deriving
thresholds that were picked against the wrong units. The `cachedInputTokens` threshold is 30,000
(`ReviewContextModels.kt:324`) against real Anthropic cache reads well above 100,000 — so on the one
path where thresholds affect behaviour (`NativeReviewOperationProtocol.kt:42` passes
`enforceable = true`, letting a breach terminate a lane) the current configuration would fire on
nearly every cached lane. The subsystem is a hazard, not a safeguard, and nothing consumes its output
for a decision anyone relies on.

## Intended Outcome

No provider-reported token value is read, computed, stored, projected, or enforced anywhere in the
runtime. The review accounting tree keeps its byte, count, coverage, routing, and integration
surfaces, which are orthogonal and correct. Locally computed byte-derived token *estimates* stay,
because they are provider-independent arithmetic on bytes the runtime owns and one of them is
load-bearing for the least-context projection budget.

Removal is invisible at every boundary. A pre-existing durable record carrying retired token keys
reads without quarantine or regeneration; a `.skill-bill/config.yaml` carrying
`provider_token_thresholds` parses and the key is ignored; a goal whose stored artifacts include
`goal_session_accounting` reports observability without it. Every such tolerated path emits a
degradation record. Loud-fail remains intact for genuine contract drift.

## Acceptance Criteria

1. `ProviderTokenUsage`, `ProviderTokenThresholds`, `TokenOwnership`, and `freshTokenApproximation`
   no longer exist, and no provider-reported token value is read, computed, stored, projected, or
   enforced anywhere in the runtime.
2. The five provider token fields, `tokenOwnership`, and `providerUsageEnforceable` are gone from
   `AgentRunLaunchFacts` and `DecodedAgentRunOutput`, and none of the four decoders in
   `AgentRunAdapters.kt` reads a provider usage key.
3. `GoalSessionAccounting`, its parser, fields, history, artifact key, retention entry, and MCP
   projection are gone; `GoalAttemptLedger` and `GoalAttemptLedgerEntry` are unchanged.
4. A goal run whose review lane reports `input_tokens` smaller than `cache_read_input_tokens`
   completes its review phase without error.
5. Reading a pre-existing `review_accounting` row that still carries `aggregate_direct_usage` or
   `aggregate_inclusive_usage` succeeds, is not quarantined, and is not regenerated.
6. Reading a pre-existing `goal_session_accounting` workflow artifact succeeds, and the goal's
   observability projection omits it without failing.
7. A `.skill-bill/config.yaml` containing `provider_token_thresholds` parses successfully and the key
   is ignored rather than rejected.
8. Every ignored retired-key path emits an observability record per `docs/observability-policy.md`.
9. `review-context-schema.yaml` is at contract version `2.1`, defines no provider token fields, no
   longer requires `provider_token_thresholds` or the aggregate usage fields, and has a
   contract-version parity test.
10. `telemetry-event-schema.yaml` no longer defines `reviewAccountingUsage`, its contract version is
    bumped, and it has a contract-version parity test.
11. The byte and count review accounting surface is behaviourally unchanged: `ReviewAccountingCounters`,
    segment accounting, `unreviewedSegmentIds`, commit routing, parent analysis, and integration
    terminal state all record exactly as before.
12. The local byte-derived token estimates are untouched: `estimated_tokens` on the projection
    measurement and `estimated_input_tokens` / `estimated_output_tokens` in lifecycle telemetry still
    record.
13. Tests that pinned the OpenAI convention are deleted rather than adapted, specifically the
    `ReviewContextModelsTest` assertion that `input=1, cached=2` throws.
14. The delegated review path no longer terminates or regresses a lane on a token threshold, and no
    replacement enforcement is introduced.
15. Governed boundary records that name provider token accounting are updated to record its removal.

## Scope

- Delete the review-side token model, thresholds, evaluator path, regression type, tree folding, and
  projection emission.
- Delete the transport-level provider token fields and their decoding in all four agent adapters.
- Delete `GoalSessionAccounting` and its durable artifact, retention, port, recorder, store, and MCP
  surfaces.
- Delete the planning-sweep token counters and their progress rendering, and the repo-local
  `provider_token_thresholds` config read.
- Version `review-context-schema.yaml` to `2.1` and bump `telemetry-event-schema.yaml`, each with the
  full contract ceremony.
- Introduce one read-side normalization seam that accepts and ignores retired token keys, with a
  degradation record, so no legacy record or config can fail a read.

## Constraints

- The removal must never fail a run, a durable read, or a config parse. Retired token keys are
  accepted and ignored; they are not a drift signal.
- Loud-fail behaviour is retained for genuine contract drift. Only the specific retired token keys
  become tolerated.
- Existing durable records are neither quarantined nor regenerated. Normalize on read so
  `additionalProperties: false` stays honest in `2.1` without rejecting legacy payloads.
- Each contract version bump lands schema, Kotlin `*_CONTRACT_VERSION`, parity test, and typed error
  together, so no intermediate subtask leaves the tree failing.
- The tolerant-read seam lands before any field is removed, so no subtask window exists in which a
  legacy record fails.
- Every tolerated degradation emits a record per `docs/observability-policy.md`.
- No comments are added to any changed file.

## Non-Goals

- Repairing, normalizing, or re-deriving provider token accounting. It is deleted, not fixed.
- Replacing the delegated-review token threshold with any other lane-termination signal.
- Touching the local byte-derived estimates: `estimatedTokens = (projection.utf8ByteSize + 3) / 4`
  (`FeatureTaskRuntimePhaseRecorder.kt:1298`, `FeatureTaskRuntimeHandoffFoundationModels`), required
  by `feature-task-runtime-projection-measurement-schema.yaml:16`; and `serializeTokenData` /
  `phaseTokenAccumulator` / `estimatedTotalTokens`
  (`FeatureTaskRuntimeRunnerPolicies.kt:30`), which emit `estimated_input_tokens` and
  `estimated_output_tokens`.
- Touching `ReviewAccountingCounters`, segment accounting, `unreviewedSegmentIds`, commit routing,
  parent analysis, or integration terminal state.
- Touching `GoalAttemptLedger` or `GoalAttemptLedgerEntry`.
- Dropping the `review_accounting` table or migrating its historical rows.
- Adding cache-write (`cache_creation_input_tokens`) decoding.
- Unblocking SKILL-190 by any means other than this removal landing.

## Diagnostic Evidence

Model and invariants being deleted:

- `runtime-domain/.../review/context/model/ReviewContextModels.kt:380-405` — `ProviderTokenUsage`,
  the two cross-field requires, `freshTokenApproximation`.
- `:322-336` — `ProviderTokenThresholds`, including the 30,000 `cachedInputTokens` default.
- `:378` — `TokenOwnership`.
- `:1397-1453` — `ReviewBudgetEvaluator`, specifically `providerUsageOutcome` at `:1413-1433`.

Producers and consumers:

- `runtime-infra-fs/.../launcher/agentrun/AgentRunAdapters.kt:224-235` (claude json), `:242-264`
  (claude stream-json), `:266-285` (codex jsonl), `:369` (cursor stream-json).
- `runtime-ports/.../agentrun/model/AgentRunLauncherModels.kt:247-253`, `:272-284`.
- `runtime-application/.../review/ParallelCodeReviewRunner.kt:1247` (throw site), `:1776-1797`
  (`providerTokenUsage`), `:1622`, `:1688`.
- `runtime-domain/.../review/context/ReviewTreeAccounting.kt:22`, `:30-39`, `:60-70`.
- `runtime-application/.../review/ReviewAccountingProjection.kt:109-114`, including the
  `coerceAtLeast(0)` symptom patch.
- `runtime-infra-fs/.../FileSystemReviewEvidenceBroker.kt:344` and
  `runtime-ports/.../review/NativeReviewOperationProtocol.kt:42` — the only path where a token
  threshold can terminate a lane.
- `runtime-domain/.../goalrunner/model/GoalRunnerAccountingModels.kt:5-137` —
  `GoalSessionAccounting` and its parser, fields, history, and artifact keys.
- `runtime-application/.../model/GoalPlanningSweepModels.kt:104-122` and
  `runtime-application/.../goalrunner/GoalPlanningSweep.kt:1266-1267`.
- `runtime-infra-fs/.../FileSystemRepoLocalConfig.kt:106`.

Contracts that make the fields required:

- `orchestration/contracts/review-context-schema.yaml:616` — `provider_token_thresholds` required on
  the budget object; `:628-638` its def; `:660-665` `provider_token_usage` and
  `fresh_token_approximation`; `:670` `aggregate_direct_usage` and `aggregate_inclusive_usage`
  required on the accounting summary.
- `orchestration/contracts/telemetry-event-schema.yaml:1219-1227` — the `reviewAccountingUsage` def.

Boundary records to update:

- `runtime-kotlin/agent/decisions.md`
- `runtime-kotlin/.../review/agent/history.md` where it records provider usage accounting

## Subtasks

1. Retired-key tolerance foundation.
2. Review token accounting removal and review-context contract 2.1.
3. Goal session accounting removal.
4. Planning-sweep counters and repo-local config retirement.
5. Transport-layer provider token removal.
6. Telemetry contract bump, convention-pinning test removal, and integrated verification.

## Validation Strategy

Each subtask validates its own seam, then the module checks. The behaviour that matters is observable
at two boundaries: a review lane whose provider report violates the deleted invariant, and a legacy
durable record or config that must still read.

The load-bearing scenarios:

- A review lane fed `input_tokens: 2, cache_read_input_tokens: 17931` completes and records its
  findings.
- A `review_accounting` row written before this change, still carrying `aggregate_direct_usage`,
  reads back without quarantine and without regeneration.
- A stored `goal_session_accounting` artifact reads back and the goal observability projection omits
  it without failing.
- A `.skill-bill/config.yaml` carrying `provider_token_thresholds` parses and the key is ignored,
  with a degradation record emitted.
- Byte, count, coverage, routing, and integration accounting are unchanged across a full review.
- `estimated_tokens` and the lifecycle `estimated_*_tokens` values still record.

Close with `skill-bill validate`, `(cd runtime-kotlin && ./gradlew check)`, and
`scripts/validate_agent_configs`. Note the known pre-existing `:runtime-infra-fs:sourcesJar` failure
on `./gradlew build`; verify with `-x sourcesJar` if that path is used.

## Next Path

```bash
skill-bill goal SKILL-194
```
