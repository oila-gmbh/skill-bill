# Subtask 2 — Producer, goal, sweep, config, and telemetry removal

## Scope

After subtask 1, remove provider-reported token fields and usage-key decoding from all four agent
transport decoders and launch/output models. Delete `GoalSessionAccounting` and its parser, fields,
history, artifact, retention, store, recorder, port, and MCP projection. Remove planning-sweep token
counts and repair its operator summary text. Retire the repo-local `provider_token_thresholds`
config read while accepting the key and emitting one degradation record when it is present. Remove
the review-finished accounting telemetry projection and obsolete telemetry schema definitions, then
bump the telemetry contract with the Kotlin constant, typed error, and parity test. Preserve
`GoalAttemptLedger`, child session identity, terminal outcomes, and local byte-derived lifecycle
estimates.

## Acceptance Criteria

1. The five provider token fields, `tokenOwnership`, `AgentRunTokenOwnership`, and
   `providerUsageEnforceable` are removed from `AgentRunLaunchFacts` and `DecodedAgentRunOutput`,
   and none of the four decoders reads a provider usage key.
2. `GoalSessionAccounting`, its parser, fields, history, artifact key, retention entry, port,
   recorder, store, and MCP projection are gone; `GoalAttemptLedger` and
   `GoalAttemptLedgerEntry` still record child session identity and terminal outcome unchanged.
3. Planning-sweep empty-turn evidence carries no provider token counts and its operator summary is
   well-formed with no dangling clause.
4. A config containing `provider_token_thresholds` parses, ignores the retired block, and emits
   exactly one degradation record; a config without the block emits none, while genuine unknown
   configuration keys still fail loudly.
5. `reviewAccountingUsage`, `boundedReviewAccounting`, and `review_context_accounting` are removed
   from telemetry schema and review-finished payloads, and the telemetry contract version has a
   matching Kotlin constant, typed error, and parity test.
6. Existing `review_accounting` rows containing retired usage keys read without quarantine or
   regeneration, and existing `goal_session_accounting` artifacts read without failing while the
   goal observability projection omits them.
7. `estimated_tokens`, `estimated_input_tokens`, and `estimated_output_tokens` continue to record
   unchanged, and byte/count review accounting surfaces remain behaviorally unchanged.
8. Tests for removed provider-token, goal-session-accounting, planning-sweep, config, legacy-read,
   and telemetry behavior are updated or deleted to assert the retained boundary behavior.
9. Repository-wide search finds no remaining reference to deleted provider token types, fields,
   contract definitions, or goal session accounting surfaces.
10. `skill-bill validate`, `scripts/validate_agent_configs`, and `(cd runtime-kotlin &&
    ./gradlew check)` pass after the complete removal.

## Non-Goals

- Repairing, normalizing, or re-deriving provider token accounting.
- Adding cache-write decoding or any other provider usage key.
- Replacing token-threshold lane termination with another signal.
- Building a general retired-key normalization registry.
- Touching local byte-derived estimates, `ReviewAccountingCounters`, segment accounting,
  `unreviewedSegmentIds`, commit routing, parent analysis, integration terminal state,
  `GoalAttemptLedger`, or `GoalAttemptLedgerEntry`.
- Dropping the `review_accounting` table or migrating historical rows.

## Dependency Notes

Depends on subtask 1 because review consumers and review-context contract surfaces must be removed
before transport producers and their decoded output fields can be deleted.

## Validation Strategy

Run focused transport-decoder, goal-accounting, planning-sweep, config-degradation, legacy-read,
telemetry projection, and contract parity tests. Verify repository-wide absence of retired symbols
and preservation of local estimates and load-bearing accounting. Then run `skill-bill validate`,
`scripts/validate_agent_configs`, and `(cd runtime-kotlin && ./gradlew check)`.

## Next Path

When all criteria pass, the feature is complete and can proceed to the governed review, validation,
and one-commit finalisation for this subtask.
