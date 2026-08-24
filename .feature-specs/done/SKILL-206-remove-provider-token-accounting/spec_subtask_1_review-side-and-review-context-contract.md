# Subtask 1 — Review-side removal and review-context contract 2.2

## Scope

Remove provider-reported token accounting from the review domain and application consumers while
leaving transport output fields present for the producer-removal subtask. Delete the provider usage
model, ownership and regression types, evaluator path, tree folding, projection output, specialist
envelope threshold fragment, CLI accounting print, and review-finished accounting payload. Update
`review-context-schema.yaml` to contract version 2.2 with its Kotlin version constant, typed error,
and parity test. Preserve byte, count, coverage, routing, parent-analysis, integration, and local
byte-derived estimate surfaces.

## Acceptance Criteria

1. `ProviderTokenUsage`, `ProviderTokenThresholds`, `TokenOwnership`, `freshTokenApproximation`,
   `ReviewBudgetEvaluator.providerUsageOutcome`, `ReviewBudgetRegression`, and
   `REVIEW_BUDGET_REGRESSION` no longer exist, and review accounting types carry no provider token
   fields.
2. `evaluateProviderUsage` is removed from `ReviewEvidenceBroker`, its filesystem implementation,
   and `NativeReviewOperationProtocol`, with no replacement token-threshold enforcement.
3. `provider_token_thresholds` is absent from review specialist launch envelopes and no review
   specialist receives a provider token budget.
4. Review accounting projection and CLI output no longer emit provider-reported token values or
   retired usage aggregates, while byte, count, coverage, routing, parent-analysis, integration,
   and local byte-derived estimates remain unchanged.
5. `review-context-schema.yaml` is version 2.2, defines no provider token fields, and does not
   require `provider_token_thresholds`, `aggregate_direct_usage`, `aggregate_inclusive_usage`, or
   `budget_regression`.
6. The review-context contract version has a matching Kotlin constant, typed schema error, and
   parity test, and existing non-token review accounting records still parse.
7. Tests that only encode removed provider token behavior are deleted, including fresh-token and
   usage-aggregation expectations.
8. Review-side validation and `(cd runtime-kotlin && ./gradlew check)` pass with transport token
   fields still present but unused.

## Non-Goals

- Removing provider token fields from transport models or decoders.
- Removing `GoalSessionAccounting`, planning-sweep token fields, repo-local config tolerance, or
  telemetry schema declarations handled by subtask 2.
- Repairing or replacing provider-reported token accounting.
- Changing `ReviewAccountingCounters`, segment accounting, `unreviewedSegmentIds`, commit routing,
  parent analysis, integration terminal state, or local byte-derived token estimates.

## Dependency Notes

None. This subtask removes review consumers first so the producer-removal subtask can delete the
transport fields without leaving unresolved review references.

## Validation Strategy

Run focused review-domain and review-context contract tests covering the removed model and evaluator
surfaces, schema version parity, legacy review-accounting reads, and preservation of byte/count
accounting. Then run `skill-bill validate` and `(cd runtime-kotlin && ./gradlew check)`.

## Next Path

Subtask 2 removes the now-unused transport fields and decoders, goal accounting artifact, planning
sweep counters, config read while preserving the retired config key with degradation, and telemetry
schema and projection surfaces.
