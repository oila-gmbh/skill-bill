# SKILL-194 Subtask 2 — Producer, goal, sweep, config, and telemetry removal

## Scope

Every consumer is gone by this point. Remove the fields that carried provider token values, the
remaining surfaces that declared them, and the contract definitions that described them.

**Transport.** Delete from `runtime-ports/.../agentrun/model/AgentRunLauncherModels.kt`:

- `inputTokens`, `cachedInputTokens`, `outputTokens`, `reasoningTokens`, `totalTokens` on
  `AgentRunLaunchFacts`
- `tokenOwnership` and the `AgentRunTokenOwnership` enum
- `providerUsageEnforceable`
- the non-negativity require covering those five fields

Delete the same fields from `DecodedAgentRunOutput` in
`runtime-infra-fs/.../launcher/agentrun/AgentRunAdapters.kt` and their mapping onto launch facts, then
delete the usage decoding from all four decoders:

- `decodeClaudeJson` — `input_tokens`, `cache_read_input_tokens`, `output_tokens`, `reasoning_tokens`,
  `total_tokens`
- `decodeClaudeStreamJson` — the same keys off the terminal `result` event
- `decodeCodexJsonl` — `input_tokens`, `cached_input_tokens`, `output_tokens`, `reasoning_tokens`,
  `total_tokens`
- `decodeCursorStreamJson` in `CursorAgentRunDecoding.kt` — the `cursorTokens` reads

Each decoder keeps everything else exactly as it is: text harvesting, terminal-event selection,
`rawOutputPreview` degradation, `assistantEventCount`, session identity, `stdoutTruncated`,
`stdoutByteSize`, `stdoutSha256`, and malformed-stream handling. The Claude stream decoder must still
select the last `type: "result"` event and still degrade to an empty harvest with a bounded excerpt
when no terminal event arrives.

**Goal session accounting.** Delete `GoalSessionAccounting` wholesale rather than stripping its token
fields. Its `available` / `unavailableReason` pair exists only to report whether token data was found,
and the session identity it also carries, `childSessionPath` and `childSessionId`, is already recorded
on `GoalAttemptLedgerEntry`. Delete from
`runtime-domain/.../goalrunner/model/GoalRunnerAccountingModels.kt`: `GoalSessionAccounting` and its
`toArtifactMap`, `GoalSessionAccountingParser`, `GoalSessionAccountingFields`,
`GoalSessionAccountingHistory`, `GOAL_SESSION_ACCOUNTING_ARTIFACT_KEY`, and
`GOAL_SESSION_ACCOUNTING_LIMIT`. Delete the wiring: the artifact write in `GoalRunnerLedgerRecorder`,
the store handling and sequence watermark in `GoalRunnerWorkflowStores`, the retention entry in
`GoalHistoryArtifactRetention`, the projection in `WorkflowGoalObservabilityMcpMapping`, the port
surface in `GoalRunnerPortModels`, and its allowance in `RuntimeArchitectureTest`.

`GoalAttemptLedger`, `GoalAttemptLedgerEntry`, `GoalAttemptLedgerAction`,
`GOAL_ATTEMPT_LEDGER_ARTIFACT_KEY`, and `GOAL_ATTEMPT_LEDGER_LIMIT` are untouched.

The 295 stored `goal_session_accounting` artifacts need no tolerance seam. `workflow-state-schema.yaml`
declares the artifacts map open, `additionalProperties: true`, deliberately, and once the readers are
gone nothing parses the key. Those entries become inert data. Do not add a normalization step for
them, and do not migrate or delete them.

**Planning sweep.** Delete `inputTokens` and `outputTokens` from `GoalPlanningEmptyTurnEvidence` in
`GoalPlanningSweepModels.kt`, the two clauses that render them in `summary()`, and the assignment from
the launch outcome in `GoalPlanningSweep.kt`. The summary keeps every other field and must read as
well-formed text with no dangling separator or empty clause.

**Repo-local config.** Delete the `provider_token_thresholds` read in `FileSystemRepoLocalConfig.kt`
and the `ProviderTokenThresholds` construction it fed. This file is the one seam in the program that
genuinely needs tolerance: it loud-fails any budget key outside its allowed set, so
`provider_token_thresholds` must stay in that set, be ignored, and emit one degradation record per
`docs/observability-policy.md`. A repository that upgrades with the block in place must see no error
and no warning demanding action.

**Telemetry contract.** Delete `reviewAccountingUsage` and `boundedReviewAccounting` from
`orchestration/contracts/telemetry-event-schema.yaml`, along with the `review_context_accounting`
property on the review-finished event and every `$ref` to the deleted defs. `boundedReviewAccounting`
already pins `contract_version: { const: "0.6" }` with `additionalProperties: false` while the
projection emits a different version and three properties the def does not declare, so it describes
nothing the runtime produces. Delete the `reviewContextAccounting` field on `ReviewStatsModels`, its
population from `loadReviewAccounting` in `ReviewFinishedPayloadSupport`, and its emission in
`ReviewFinishedTelemetryPayload`, which leaves `loadReviewAccounting` with the CLI driver as its
consumer. Bump `TELEMETRY_EVENT_CONTRACT_VERSION` from `1.10.0` to `1.11.0`, landing schema, Kotlin
constant, parity test, and typed `InvalidTelemetryEventSchemaError` together. Update
`docs/telemetry-privacy.md` where it lists `review_context_accounting`.

**Boundary records.** Update `runtime-kotlin/agent/decisions.md` and the review area's
`agent/history.md` to record that provider-reported token accounting was removed, that the two
incompatible provider conventions are why, and that the local byte-derived estimates are deliberately
retained. Correct any entry that describes provider usage accounting as a live capability rather than
leaving it to mislead planning.

## Acceptance Criteria

1. `AgentRunLaunchFacts` and `DecodedAgentRunOutput` carry no `inputTokens`, `cachedInputTokens`,
   `outputTokens`, `reasoningTokens`, `totalTokens`, `tokenOwnership`, or `providerUsageEnforceable`,
   and `AgentRunTokenOwnership` no longer exists.
2. None of the four decoders reads a provider usage key, and no `usage` object is consulted for token
   values anywhere in the transport layer.
3. Each decoder still harvests text identically: the buffered Claude decoder, the Claude stream decoder
   selecting the last `type: "result"` event, the Codex JSONL decoder, and the Cursor stream decoder.
   The Claude stream decoder still degrades to an empty harvest with a bounded `rawOutputPreview` when
   no terminal event is present, and `assistantEventCount`, session identity, `stdoutTruncated`,
   `stdoutByteSize`, and `stdoutSha256` are unchanged.
4. `GoalSessionAccounting`, `GoalSessionAccountingParser`, `GoalSessionAccountingFields`,
   `GoalSessionAccountingHistory`, `GOAL_SESSION_ACCOUNTING_ARTIFACT_KEY`, and
   `GOAL_SESSION_ACCOUNTING_LIMIT` no longer exist, and no recorder, store, retention policy, port
   model, or MCP mapping references goal session accounting.
5. `GoalAttemptLedger`, `GoalAttemptLedgerEntry`, `GoalAttemptLedgerAction`, and the attempt ledger
   artifact key and limit are unchanged, and still record child activation, resume, retry, timeout,
   interruption, and final reconciled outcome.
6. A goal whose durable state already contains a `goal_session_accounting` artifact reads back
   successfully, the MCP goal observability projection omits the section without failing, and no
   normalization or migration step was added for the legacy key.
7. `GoalPlanningEmptyTurnEvidence` carries no token counts, and its `summary()` renders every remaining
   field as well-formed text with no dangling separator or empty clause.
8. A `.skill-bill/config.yaml` containing a `provider_token_thresholds` block parses successfully, the
   key is ignored, and one degradation record is emitted per `docs/observability-policy.md`. A config
   without the block parses with no degradation record. Every other repo-local config field, including
   `review_context_budget.max_parent_packet_bytes` and `validation_gate`, resolves exactly as before.
9. An unknown budget key that is not `provider_token_thresholds` still loud-fails as it does today.
10. `telemetry-event-schema.yaml` defines neither `reviewAccountingUsage` nor
    `boundedReviewAccounting`, no `$ref` to either remains, and the review-finished event has no
    `review_context_accounting` property.
11. `TELEMETRY_EVENT_CONTRACT_VERSION` is `1.11.0`, matches the schema, and a parity test on the
    `PlatformPackSchemaContractVersionTest` pattern fails when the two disagree.
12. No type in the runtime carries a provider-reported token value, and a repository-wide search finds
    no remaining reference to the deleted types, fields, or contract definitions.
13. `estimated_tokens` on the projection measurement and `estimated_input_tokens` /
    `estimated_output_tokens` in lifecycle telemetry still record.
14. `runtime-kotlin/agent/decisions.md` and the review area's `agent/history.md` record the removal,
    its cause, and the retained local estimates, with no entry left describing provider usage
    accounting as live.
15. `skill-bill validate`, `(cd runtime-kotlin && ./gradlew check)`, and `scripts/validate_agent_configs`
    all pass.

## Non-Goals

- Changing text harvesting, terminal-event selection, degradation behaviour, or session identity
  capture in any decoder.
- Adding `cache_creation_input_tokens` decoding, or any other usage key.
- Touching the local byte-derived estimates; they do not come from a provider report.
- Touching the attempt ledger, or preserving `childSessionPath`, `childSessionId`, `model`, or
  `finalStatus` under a new record.
- Migrating, deleting, or normalizing historical `goal_session_accounting` artifacts,
  `review_accounting` rows, or telemetry records.
- Editing any checked-in or operator `.skill-bill/config.yaml`, or emitting a warning that asks the
  operator to remove the block. The degradation record is diagnostic, not a call to action.
- Relaxing loud-fail for any config key other than `provider_token_thresholds`.

## Dependency Notes

Depends on subtask 1, which removes every consumer of the transport fields. Landing this subtask first
would break compilation at the review accounting model, the broker, and the runner.

## Validation Strategy

- Assert the buffered and streamed Claude decoders still produce identical text for the same terminal
  payload, including `{"input_tokens":2,"cache_read_input_tokens":17931,"output_tokens":6,"total_tokens":120}`,
  which must now decode to its text with no token fields and no error. Keep that payload as a decode
  fixture precisely because it must no longer be special.
- Assert a Claude stream with no terminal `result` event still yields an empty harvest with a bounded
  excerpt, and that a malformed or blank stream behaves as before.
- Assert the Codex JSONL and Cursor stream decoders still harvest their text and session identity.
- Read a stored goal workflow whose artifact map contains `goal_session_accounting`; assert the read
  succeeds and the MCP goal observability projection returns successfully with the section omitted.
- Assert a goal run completes with no goal session accounting artifact written, and that the attempt
  ledger still records its full action set across a run with a resume and a retry.
- Parse a `.skill-bill/config.yaml` carrying a full `provider_token_thresholds` block plus
  `review_context_budget.max_parent_packet_bytes`; assert success, one degradation record, and that the
  byte budget resolves to its declared value. Parse a config with an unknown budget key that is not
  `provider_token_thresholds` and assert it still loud-fails.
- Assert the planning sweep summary for an outcome that previously reported token counts renders
  correctly and mentions no tokens.
- Assert `estimated_tokens` and the lifecycle `estimated_*_tokens` values still record.

Rewrite `AgentRunCommandBuildersTest` and `CursorAgentRunTransportTest` to drop their token assertions
while keeping the text-parity, terminal-selection, and degradation cases. Delete the
`GoalRunnerAccountingModelsTest` goal session accounting coverage and the corresponding assertions in
`WorkflowServiceTest`, keeping their attempt ledger coverage. Delete the token-threshold assertion in
`FileSystemRepoLocalConfigTest` and add the legacy-block tolerance case plus the unknown-key loud-fail
case. Update `GoalPlanningSweepTest`'s summary expectation and the `TelemetryReliabilityContractTest`
review-finished expectation.

Close with `skill-bill validate`, `(cd runtime-kotlin && ./gradlew check)`, and
`scripts/validate_agent_configs`. The pre-existing `:runtime-infra-fs:sourcesJar` failure on
`./gradlew build` is not introduced here; verify with `-x sourcesJar` if that path is used.

## Next Path

```bash
skill-bill goal SKILL-194
```
