# SKILL-194 Subtask 1 — Review-side removal and review-context contract 2.2

## Scope

Delete the review-side provider token model and every seam that consumes it, and land the
`review-context-schema.yaml` version bump in the same subtask so the tree is never left with a schema
that requires fields the code no longer produces.

The transport fields on `AgentRunLaunchFacts` stay in place through this subtask. Removing the
consumers first leaves them present but unread, which compiles; subtask 2 removes them.

Delete from `runtime-domain/.../review/context/model/ReviewContextModels.kt`:

- `ProviderTokenUsage`, including `freshTokenApproximation` and the private `plus` / `add` folding
  helpers that exist only for it
- `ReviewTreeUsage`
- `ProviderTokenThresholds`, and `providerTokenThresholds` from `ReviewContextBudgetPolicy`
- `TokenOwnership`
- `ReviewBudgetEvaluator.providerUsageOutcome`
- `ReviewBudgetRegression` and `REVIEW_BUDGET_REGRESSION`, whose only producer is
  `providerUsageOutcome`

Delete the usage fields and their folding:

- `usage` from `ReviewAccountingInput` and `ReviewIntegrationAccounting`
- `providerUsage`, `directUsage`, `inclusiveUsage` from `ReviewAccountingNode`
- `aggregateDirectUsage`, `aggregateInclusiveUsage`, `budgetRegression` from
  `ReviewAccountingSummary`
- the usage folding, the `List<ProviderTokenUsage>.sum` helper, and the `budgetRegression` derivation
  in `ReviewTreeAccounting.kt`
- usage emission in `ReviewAccountingProjection.kt`, including the `coerceAtLeast(0)` symptom patch

Delete the consumer seams:

- `providerTokenUsage` in `ParallelCodeReviewRunner.kt` and its call sites, and the `tokenUsage` field
  on `ParallelReviewLaneOutcome` / `ParallelCodeReviewModels` / `ParallelReviewLaneModels`
- `evaluateProviderUsage` from `ReviewEvidenceBroker`, its implementation in
  `FileSystemReviewEvidenceBroker`, and `providerUsage` from `NativeReviewOperationProtocol` and
  `BrokerBackedNativeReviewOperationProtocol`. Neither has a production caller today, so nothing
  changes behaviourally; the enforceable seam is being deleted, not disabled.
- the `provider_token_thresholds` fragment in `ReviewEnvelopeFragments.kt`, so no review specialist is
  handed a token budget in its launch envelope

Version the contract to `2.2` in `orchestration/contracts/review-context-schema.yaml`: drop
`provider_token_thresholds` from the budget object's `required` list and its `$defs`; drop the `usage`
def and every `$ref` to it; drop `aggregate_direct_usage`, `aggregate_inclusive_usage`, and
`budget_regression` from the accounting summary's `required` list and properties. Update every
`contract_version` const in the file. Land the schema, the Kotlin `REVIEW_CONTEXT_CONTRACT_VERSION`,
the contract-version parity test on the `PlatformPackSchemaContractVersionTest` pattern, and the typed
`Invalid<Contract>SchemaError` together.

The version must move to `2.2` rather than being edited in place at `2.1`. Existing durable rows
declare `2.1`; leaving the version unchanged would make them indistinguishable from post-removal rows
while no longer matching the schema shape, and the existing quarantine-on-version-mismatch path in
`loadReviewAccounting` is exactly what should classify them. That path already tolerates them without
regeneration, which is why this program needs no normalization registry.

`CodeReviewDriverCommand` prints the bounded payload to stdout at the end of a CLI review. After this
subtask that output carries counters, segments, routing, parent analysis, and integration, and no
token values. Keep the section and its heading.

## Acceptance Criteria

1. `ProviderTokenUsage`, `ReviewTreeUsage`, `ProviderTokenThresholds`, `TokenOwnership`,
   `freshTokenApproximation`, `providerUsageOutcome`, `ReviewBudgetRegression`, and
   `REVIEW_BUDGET_REGRESSION` no longer exist.
2. No review accounting type carries a provider token field, and `ReviewTreeAccounting` folds only
   counters, segments, and coverage.
3. `ReviewAccountingProjection` emits no token key and applies `coerceAtLeast` to no value.
4. `evaluateProviderUsage` is gone from the broker port, its filesystem implementation, and the native
   review protocol, and no code path can terminate or regress a lane on a token threshold.
5. `ReviewContextBudgetPolicy` has no `providerTokenThresholds`, and the review specialist launch
   envelope contains no `provider_token_thresholds`.
6. A review lane whose provider report has `input_tokens` smaller than `cache_read_input_tokens`
   completes and records its findings.
7. `review-context-schema.yaml` is at contract version `2.2`, defines no provider token fields, and
   does not require `provider_token_thresholds`, `aggregate_direct_usage`,
   `aggregate_inclusive_usage`, or `budget_regression`.
8. A contract-version parity test pins `2.2` against `REVIEW_CONTEXT_CONTRACT_VERSION`, following the
   `PlatformPackSchemaContractVersionTest` pattern, and fails when the two disagree.
9. A pre-existing `review_accounting` row declaring `2.1` and carrying `aggregate_direct_usage` reads
   back without throwing and without being regenerated; the existing version-mismatch quarantine
   classifies it and emits its record.
10. `ReviewAccountingCounters`, segment accounting, `unreviewedSegmentIds`, commit routing, parent
    analysis, and integration terminal state record exactly as before, and the CLI review accounting
    section still prints them.
11. `(cd runtime-kotlin && ./gradlew check)` passes.

## Non-Goals

- Touching the transport token fields on `AgentRunLaunchFacts` or `DecodedAgentRunOutput`, or the four
  decoders. Subtask 2 owns those; this subtask stops at the review domain's consumption of them.
- Touching `telemetry-event-schema.yaml`, goal-runner, planning-sweep, or repo-local config surfaces.
  Subtask 2 owns those.
- Introducing any replacement for the deleted lane-termination threshold.
- Dropping the `review_accounting` table or migrating historical rows.
- Removing the CLI review accounting output section itself.

## Dependency Notes

None. This is the first subtask, and it depends only on the transport fields continuing to exist,
which they do until subtask 2.

## Validation Strategy

- Feed a review lane the recorded incident shape, `input_tokens: 2, cache_read_input_tokens: 17931`,
  and assert the lane completes with its findings attributed.
- Read a stored `review_accounting` payload declaring `2.1` and carrying `aggregate_direct_usage`;
  assert the read does not throw, the row is not regenerated, and the quarantine record is emitted.
- Assert the contract-version parity test fails when schema and Kotlin constant disagree.
- Assert a full review still records counters, segments, `unreviewedSegmentIds`, commit routing,
  parent analysis, and integration terminal state, and that the CLI accounting section renders them.

Delete rather than adapt the review-side tests that assert on usage: the usage and
`freshTokenApproximation` assertions in `ReviewContextModelsTest`, `ReviewTreeAccountingTest`,
`ParallelCodeReviewRunnerTest`, `ParallelCodeReviewEndToEndTest`,
`FileSystemReviewEvidenceBrokerBudgetTest`, and the usage assertions in
`ReviewAccountingProjectionRedactionTest` and `ReviewAccountingDurableRedactionTest`. Keep every
counter, segment, and redaction case in those files intact.

Then `(cd runtime-kotlin && ./gradlew check)`.

## Next Path

```bash
skill-bill goal SKILL-194
```
