# SKILL-194 Subtask 2 — Review token accounting removal and review-context contract 2.1

## Scope

Delete the review-side provider token model and every seam that reads it, and land the
`review-context-schema.yaml` version bump in the same subtask so the tree is never left with a schema
that requires fields the code no longer produces.

Delete from `runtime-domain/.../review/context/model/ReviewContextModels.kt`:

- `ProviderTokenUsage` (`:380-405`), including both cross-field requires and `freshTokenApproximation`
- `ProviderTokenThresholds` (`:322-336`)
- `TokenOwnership` (`:378`)
- `ReviewBudgetEvaluator.providerUsageOutcome` (`:1413-1433`)
- `ReviewBudgetRegression` and `REVIEW_BUDGET_REGRESSION`, whose only producer is
  `providerUsageOutcome`
- `providerTokenThresholds` from `ReviewContextBudgetPolicy` (`:350`)

Delete the usage fields and their folding:

- `usage` from `ReviewAccountingInput` (`ReviewAccountingModels.kt:30`) and
  `ReviewIntegrationAccounting` (`:110`)
- `providerUsage`, `directUsage`, `inclusiveUsage` from `ReviewAccountingNode` (`:49-52`)
- `aggregateDirectUsage`, `aggregateInclusiveUsage`, `budgetRegression` from
  `ReviewAccountingSummary` (`:132-134`)
- the usage folding and `List<ProviderTokenUsage>.sum` in `ReviewTreeAccounting.kt:22`, `:30-39`,
  `:60-70`, and the `budgetRegression` derivation at `:24`
- usage emission in `ReviewAccountingProjection.kt:109-114`, including the `coerceAtLeast(0)` symptom
  patch

Delete the consumer seams:

- `providerTokenUsage` and its call site in `ParallelCodeReviewRunner.kt:1247`, `:1776-1797`, and the
  `ProviderTokenUsage()` defaults at `:1622` and `:1688`
- `evaluateProviderUsage` from `ReviewEvidenceBroker`, its implementation in
  `FileSystemReviewEvidenceBroker.kt:344`, and its call in `NativeReviewOperationProtocol.kt:42`
- usage fields on `ParallelReviewLaneModels`, `ReviewEvidenceModels`, `ReviewIntegrationPassOutcome`,
  and `ParallelCodeReviewModels`
- `providerTokenThresholds` from `ReviewEnvelopeFragments.kt:97` and `FanOutReviewEvidenceBroker`

Version the contract to `2.1` in `orchestration/contracts/review-context-schema.yaml`: drop
`provider_token_thresholds` from the budget object's `required` list (`:616`) and its `$defs`
(`:628-638`); drop the `provider_token_usage` def and `fresh_token_approximation` (`:660-665`); drop
`aggregate_direct_usage`, `aggregate_inclusive_usage`, and `budget_regression` from the accounting
summary's `required` list (`:670`). Land the schema, the Kotlin `*_CONTRACT_VERSION`, the
contract-version parity test on the `PlatformPackSchemaContractVersionTest` pattern, and the typed
`Invalid<Contract>SchemaError` together.

Removing `ReviewBudgetRegression` deletes the only path on which a token threshold could terminate a
lane. That is intended; introduce no replacement.

## Acceptance Criteria

1. `ProviderTokenUsage`, `ProviderTokenThresholds`, `TokenOwnership`, `freshTokenApproximation`,
   `providerUsageOutcome`, `ReviewBudgetRegression`, and `REVIEW_BUDGET_REGRESSION` no longer exist.
2. No review accounting type carries a provider token field, and `ReviewTreeAccounting` folds only
   counters, segments, and coverage.
3. `ReviewAccountingProjection` emits no token key and no longer applies `coerceAtLeast(0)` to any
   value.
4. `evaluateProviderUsage` is gone from the broker port, its filesystem implementation, and the native
   review protocol, and no code path can terminate or regress a lane on a token threshold.
5. A review lane whose provider report has `input_tokens` smaller than `cache_read_input_tokens`
   completes and records its findings.
6. `review-context-schema.yaml` is at contract version `2.1`, defines no provider token fields, and
   does not require `provider_token_thresholds`, `aggregate_direct_usage`,
   `aggregate_inclusive_usage`, or `budget_regression`.
7. A contract-version parity test pins `2.1` against the Kotlin `*_CONTRACT_VERSION`, following the
   `PlatformPackSchemaContractVersionTest` pattern.
8. A pre-existing `review_accounting` row carrying `aggregate_direct_usage` reads back successfully via
   the subtask 1 seam, is not quarantined, and is not regenerated.
9. `ReviewAccountingCounters`, segment accounting, `unreviewedSegmentIds`, commit routing, parent
   analysis, and integration terminal state record exactly as before.
10. `(cd runtime-kotlin && ./gradlew check)` passes.

## Non-Goals

- Touching the transport-layer token fields on `AgentRunLaunchFacts` or `DecodedAgentRunOutput`, or the
  decoders. Subtask 5 owns those; this subtask stops at the review domain's consumption of them.
- Touching `telemetry-event-schema.yaml`. Subtask 6 owns that bump.
- Touching goal-runner or planning-sweep token surfaces.
- Introducing any replacement for the deleted lane-termination threshold.
- Dropping the `review_accounting` table or migrating historical rows.

## Dependency Notes

Depends on subtask 1. The tolerant-read seam must already exist, because the moment the schema stops
requiring the aggregate usage fields, existing rows that still carry them must normalize on read
rather than fail.

## Validation Strategy

- Feed a review lane the recorded incident shape, `input_tokens: 2, cache_read_input_tokens: 17931`,
  and assert the lane completes with its findings attributed.
- Read a `review_accounting` payload containing `aggregate_direct_usage` and assert success with no
  quarantine and no regeneration.
- Assert the contract-version parity test fails if schema and Kotlin constant disagree.
- Assert a full review still records counters, segments, `unreviewedSegmentIds`, commit routing,
  parent analysis, and integration terminal state.

Delete rather than adapt the review-side tests that assert on usage: the usage assertions in
`ReviewTreeAccountingTest`, `ParallelCodeReviewRunnerTest`, `ParallelCodeReviewEndToEndTest`,
`ParallelCodeReviewRegressionTest`, `ReviewAccountingProjectionRedactionTest`, and
`ReviewAccountingDurableRedactionTest`. Keep their counter, segment, and redaction coverage.

Then `(cd runtime-kotlin && ./gradlew check)`.

## Next Path

```bash
skill-bill goal SKILL-194
```
