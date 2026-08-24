# SKILL-206 — Remove provider-reported token accounting

## Context

The runtime decodes provider-reported token counts off four agent transports, folds them into a
review accounting tree, writes them to `review_accounting`, ships thresholds to every review
specialist, and declares them in two contracts. None of it produces a usable number, and almost
none of it is read.

### One column, two incompatible conventions

`ProviderTokenUsage` (`runtime-domain/.../review/context/model/ReviewContextModels.kt`) holds a
single set of token fields. The decoders fill them from two providers whose conventions disagree:

- Codex reports `input_tokens` inclusive of `cached_input_tokens`.
- Anthropic reports `input_tokens` as the uncached remainder, with `cache_read_input_tokens` and
  `cache_creation_input_tokens` additive on top.

`ReviewTreeAccounting` sums those values across lanes, so `aggregate_direct_usage` adds an inclusive
Codex total to an additive Anthropic remainder. The result is not a quantity in any unit.

`freshTokenApproximation` assumes the inclusive convention:

```kotlin
get() = (inputTokens ?: 0) - (cachedInputTokens ?: 0) + (outputTokens ?: 0)
```

Under Anthropic semantics `input_tokens` already excludes cache reads, so this subtracts them a
second time and goes negative. `ReviewAccountingProjection` patches the symptom with
`it.coerceAtLeast(0)`, because `review-context-schema.yaml` declares `minimum: 0` on the field.

`cache_creation_input_tokens` is decoded by no adapter, so cache-write cost has never been counted
for either provider.

### What the recorded data shows

Rows from `~/.skill-bill/review-metrics.db`, `review_accounting.aggregate_direct_usage`:

```
{"input_tokens":8,  "cached_input_tokens":61852,  "output_tokens":7174, "fresh_token_approximation":0}
{"input_tokens":6,  "cached_input_tokens":107339, "output_tokens":8167, "fresh_token_approximation":0}
{"input_tokens":24, "cached_input_tokens":4308091,"output_tokens":20052,"fresh_token_approximation":0}
{"input_tokens":278123,                           "output_tokens":25166,"fresh_token_approximation":303289}
{"input_tokens":18,                               "output_tokens":7599, "fresh_token_approximation":7617}
```

Every cached Anthropic lane records `fresh_token_approximation: 0`, the coercion floor. The third row
sums cache reads to 4,308,091, which is 143 times the configured 30,000 `cachedInputTokens`
threshold. The fourth is a Codex inclusive total in the same column. The fifth claims an 18-token
prompt for a real review lane, because the cache-write dimension is never decoded.

### Nothing enforces it and almost nothing reads it

- `ReviewEvidenceBroker.evaluateProviderUsage` has no production caller.
  `NativeReviewOperationProtocol.providerUsage`, the `enforceable = true` seam that could terminate a
  lane, has no production caller either. `ProviderTokenThresholds` therefore never fires, and
  `ReviewBudgetRegression` has no reachable producer.
- `ReviewEnvelopeFragments` ships `provider_token_thresholds` into every review specialist's launch
  envelope. Each lane is handed a 30,000 cached-token budget it routinely exceeds by a factor of
  three or more, with no consequence, because nothing evaluates it.
- `GoalSessionAccounting` declares `inputTokens`, `cachedInputTokens`, `outputTokens`, and
  `reasoningOutputTokens`. `GoalRunnerLedgerRecorder` constructs `GoalSessionAccountingFields`
  without any of them, always. Across 295 stored `goal_session_accounting` artifacts, not one
  carries a token key. Its `available` flag flips true whenever a child session path exists, so the
  record reports session identity that `GoalAttemptLedgerEntry` already carries, under an accounting
  name.
- `telemetry-event-schema.yaml` defines `boundedReviewAccounting` with
  `contract_version: { const: "0.6" }` and `additionalProperties: false`, while the projection emits
  `2.1` plus `commit_routing_accounting`, `parent_analysis_consumption`, and `integration`. The
  definition no longer describes anything the runtime produces.

The one live consumer is `loadReviewAccounting`, read by
`ReviewFinishedPayloadSupport` into `review_context_accounting` on the review-finished telemetry
payload, and printed by `CodeReviewDriverCommand` as `# Review accounting — {json}` at the end of a
CLI review. Both surface the numbers above verbatim.

### The SKILL-190 crash is already fixed

`ProviderTokenUsage` once required `cachedInputTokens <= inputTokens`, which threw on truthful
Anthropic reports and blocked goal SKILL-190 after its review lane had already produced findings.
Commit `161fef09e` removed that requirement and replaced the test that pinned it with
`additive provider cache reads exceeding input are accepted`. No crash remains, and this program is
not a fix for one. It removes a measurement that cannot be made correct at the seam where it is
taken.

### Why removal rather than repair

Repair means tagging every value with its provider convention, teaching the tree not to add across
conventions, adding cache-write decoding, and re-deriving thresholds against units nobody has
validated. Nothing consumes the output for a decision, so that work buys a correct number no caller
asked for. The provider-independent byte-derived estimates the runtime computes itself are unaffected
and stay.

## Intended Outcome

No provider-reported token value is decoded, computed, stored, projected, or enforced anywhere in the
runtime. The review accounting tree keeps its byte, count, coverage, routing, and integration
surfaces, which are orthogonal and correct. Locally computed byte-derived token estimates stay,
because they are provider-independent arithmetic on bytes the runtime owns and one of them is
load-bearing for the least-context projection budget.

Removal is invisible at every boundary. A pre-existing `review_accounting` row carrying retired usage
keys reads without quarantine or regeneration. A pre-existing `goal_session_accounting` artifact
reads and the goal's observability projection omits it. A `.skill-bill/config.yaml` carrying
`provider_token_thresholds` parses and the key is ignored, with a degradation record. Loud-fail stays
intact for genuine contract drift.

## Acceptance Criteria

1. `ProviderTokenUsage`, `ProviderTokenThresholds`, `TokenOwnership`, `freshTokenApproximation`,
   `ReviewBudgetEvaluator.providerUsageOutcome`, `ReviewBudgetRegression`, and
   `REVIEW_BUDGET_REGRESSION` no longer exist, and no review accounting type carries a provider token
   field.
2. `evaluateProviderUsage` is gone from `ReviewEvidenceBroker`, its filesystem implementation, and
   `NativeReviewOperationProtocol`, and no code path can terminate or regress a lane on a token
   threshold. No replacement enforcement is introduced.
3. `provider_token_thresholds` is gone from the review specialist launch envelope, and no review
   specialist is handed a token budget.
4. The five provider token fields, `tokenOwnership`, `AgentRunTokenOwnership`, and
   `providerUsageEnforceable` are gone from `AgentRunLaunchFacts` and `DecodedAgentRunOutput`, and
   none of the four decoders reads a provider usage key.
5. `GoalSessionAccounting`, its parser, fields, history, artifact key, retention entry, and MCP
   projection are gone. `GoalAttemptLedger` and `GoalAttemptLedgerEntry` are unchanged and still
   record child session identity and terminal outcome.
6. The planning-sweep empty-turn evidence carries no token counts, and its operator summary line
   reads as well-formed text with no dangling clause.
7. `review-context-schema.yaml` is at contract version `2.2`, defines no provider token fields, and
   does not require `provider_token_thresholds`, `aggregate_direct_usage`,
   `aggregate_inclusive_usage`, or `budget_regression`, with a contract-version parity test.
8. `telemetry-event-schema.yaml` no longer defines `reviewAccountingUsage` or
   `boundedReviewAccounting`, `review_context_accounting` is gone from the review-finished event, the
   contract version is bumped, and a parity test pins it.
9. A `.skill-bill/config.yaml` containing `provider_token_thresholds` parses successfully, the key is
   ignored rather than rejected, and one degradation record is emitted per
   `docs/observability-policy.md`. A config without the block emits none.
10. A pre-existing `review_accounting` row carrying `aggregate_direct_usage` reads back without error,
    is not quarantined, and is not regenerated. A pre-existing `goal_session_accounting` artifact
    reads back and the goal observability projection omits it without failing.
11. The byte and count review accounting surface is behaviourally unchanged: `ReviewAccountingCounters`,
    segment accounting, `unreviewedSegmentIds`, commit routing, parent analysis, and integration
    terminal state all record exactly as before.
12. The local byte-derived token estimates are untouched: `estimated_tokens` on the projection
    measurement and `estimated_input_tokens` / `estimated_output_tokens` in lifecycle telemetry still
    record.
13. Tests that encoded provider token behaviour as intended are deleted rather than adapted, including
    the `freshTokenApproximation` and usage-aggregation expectations and the
    `additive provider cache reads exceeding input are accepted` case whose subject no longer exists.
14. Governed boundary records that name provider token accounting are updated to record its removal,
    its cause, and the retained local estimates.
15. A repository-wide search finds no remaining reference to the deleted provider token types, fields,
    or contract definitions.

## Scope

- Delete the review-side token model, thresholds, evaluator path, regression type, tree folding,
  projection emission, broker and protocol methods, and the launch-envelope fragment.
- Delete the transport-level provider token fields and their decoding in all four agent adapters.
- Delete `GoalSessionAccounting` and its durable artifact, retention, port, recorder, store, and MCP
  surfaces.
- Delete the planning-sweep token counters and their operator rendering.
- Retire the repo-local `provider_token_thresholds` config read while keeping the key accepted and
  ignored, with a degradation record.
- Version `review-context-schema.yaml` to `2.2` and bump `telemetry-event-schema.yaml`, each with the
  full contract ceremony: schema, Kotlin `*_CONTRACT_VERSION`, parity test, typed error.

## Constraints

- The removal must never fail a run, a durable read, or a config parse.
- Loud-fail behaviour is retained for genuine contract drift. Only the retired token keys are
  tolerated, and only where a seam would otherwise reject them.
- Existing durable records are neither quarantined nor regenerated.
- Each contract version bump lands schema, Kotlin constant, parity test, and typed error together, so
  no subtask leaves the tree failing.
- Both subtasks must compile and pass `check` on their own. The review domain reads the transport
  fields, so consumers are removed before producers.
- Every tolerated degradation emits a record per `docs/observability-policy.md`.
- No comments are added to any changed file.

## Non-Goals

- Repairing, normalizing, or re-deriving provider token accounting.
- Adding `cache_creation_input_tokens` decoding, or any other usage key.
- Replacing the deleted lane-termination threshold with any other signal.
- Building a general retired-key normalization registry. Two of the three read seams need no
  tolerance at all: the workflow artifacts map is `additionalProperties: true` by contract, and no
  reader survives to parse a legacy `goal_session_accounting` key. Only the repo-local config
  loud-fails unknown budget keys, and that is one allowed-key entry.
- Touching the local byte-derived estimates: `estimatedTokens = (projection.utf8ByteSize + 3) / 4`
  (`FeatureTaskRuntimePhaseRecorder`, `FeatureTaskRuntimeHandoffFoundationModels`), required by
  `feature-task-runtime-projection-measurement-schema.yaml`; and `serializeTokenData` /
  `phaseTokenAccumulator` / `estimatedTotalTokens` (`FeatureTaskRuntimeRunnerPolicies`), which emit
  `estimated_input_tokens` and `estimated_output_tokens`.
- Touching `ReviewAccountingCounters`, segment accounting, `unreviewedSegmentIds`, commit routing,
  parent analysis, or integration terminal state.
- Touching `GoalAttemptLedger` or `GoalAttemptLedgerEntry`.
- Dropping the `review_accounting` table or migrating its historical rows.

## Diagnostic Evidence

Review side:

- `runtime-domain/.../review/context/model/ReviewContextModels.kt` — `ProviderTokenUsage`,
  `freshTokenApproximation`, `ProviderTokenThresholds` with its 30,000 `cachedInputTokens` default,
  `TokenOwnership`, `ReviewBudgetRegression`, `REVIEW_BUDGET_REGRESSION`,
  `ReviewBudgetEvaluator.providerUsageOutcome`.
- `runtime-domain/.../review/context/ReviewTreeAccounting.kt` — usage folding and the
  `budgetRegression` derivation.
- `runtime-domain/.../review/context/model/ReviewAccountingModels.kt` — `usage` on
  `ReviewAccountingInput` and `ReviewIntegrationAccounting`; `providerUsage`, `directUsage`,
  `inclusiveUsage` on `ReviewAccountingNode`; `aggregateDirectUsage`, `aggregateInclusiveUsage`,
  `budgetRegression` on `ReviewAccountingSummary`.
- `runtime-application/.../review/ReviewAccountingProjection.kt` — usage emission and the
  `coerceAtLeast(0)` symptom patch.
- `runtime-application/.../review/ReviewEnvelopeFragments.kt` — the `provider_token_thresholds`
  envelope fragment.
- `runtime-application/.../review/ParallelCodeReviewRunner.kt` — `providerTokenUsage` and its call
  sites.
- `runtime-ports/.../review/ReviewEvidenceBroker.kt`,
  `runtime-ports/.../review/NativeReviewOperationProtocol.kt`,
  `runtime-infra-fs/.../FileSystemReviewEvidenceBroker.kt` — `evaluateProviderUsage` and the
  unreachable `enforceable = true` seam.
- `runtime-cli/.../codereview/CodeReviewDriverCommand.kt` — prints the bounded payload to stdout.
- `runtime-infra-sqlite/.../review/ReviewFinishedPayloadSupport.kt` — reads `review_accounting` into
  the review-finished telemetry payload.

Transport:

- `runtime-ports/.../agentrun/model/AgentRunLauncherModels.kt` — the five fields, `tokenOwnership`,
  `AgentRunTokenOwnership`, `providerUsageEnforceable`, and their non-negativity require.
- `runtime-infra-fs/.../launcher/agentrun/AgentRunAdapters.kt` — `DecodedAgentRunOutput` fields and
  the usage reads in `decodeClaudeJson`, `decodeClaudeStreamJson`, and `decodeCodexJsonl`.
- `runtime-infra-fs/.../launcher/agentrun/CursorAgentRunDecoding.kt` — the `cursorTokens` reads.

Goal, sweep, config:

- `runtime-domain/.../goalrunner/model/GoalRunnerAccountingModels.kt` — `GoalSessionAccounting`, its
  parser, fields, history, artifact key, and limit.
- `runtime-application/.../goalrunner/GoalRunnerLedgerRecorder.kt` — writes the artifact and never
  populates a token field.
- `runtime-application/.../goalrunner/GoalRunnerWorkflowStores.kt`,
  `runtime-domain/.../workflow/model/GoalHistoryArtifactRetention.kt`,
  `runtime-mcp/.../workflow/WorkflowGoalObservabilityMcpMapping.kt` — store, retention, projection.
- `runtime-application/.../model/GoalPlanningSweepModels.kt`,
  `runtime-application/.../goalrunner/GoalPlanningSweep.kt` — `inputTokens` / `outputTokens` on
  `GoalPlanningEmptyTurnEvidence` and its `summary()` rendering.
- `runtime-infra-fs/.../FileSystemRepoLocalConfig.kt` — the `provider_token_thresholds` read and the
  allowed-key set that loud-fails unknown budget keys.

Contracts:

- `orchestration/contracts/review-context-schema.yaml` — `provider_token_thresholds` required on the
  budget object and its `$defs`; the `usage` def including `fresh_token_approximation`;
  `aggregate_direct_usage`, `aggregate_inclusive_usage`, and `budget_regression` required on the
  accounting summary.
- `orchestration/contracts/telemetry-event-schema.yaml` — `reviewAccountingUsage`,
  `boundedReviewAccounting` with its stale `const: "0.6"`, and the `review_context_accounting`
  property on the review-finished event.

Boundary records:

- `runtime-kotlin/agent/decisions.md`
- `runtime-kotlin/.../review/agent/history.md` where it records provider usage accounting

## Subtasks

1. Review-side removal and review-context contract 2.2.
2. Producer, goal, sweep, config, and telemetry removal.

## Validation Strategy

Each subtask validates its own seam, then `(cd runtime-kotlin && ./gradlew check)`. The tree compiles
and passes at both boundaries: subtask 1 removes every consumer of the transport fields, leaving them
present but unread; subtask 2 removes the fields.

The load-bearing behaviour is what survives, not what goes:

- A full review still records byte, count, coverage, routing, parent-analysis, and integration
  accounting exactly as before, and a lane whose provider report has `input_tokens` smaller than
  `cache_read_input_tokens` still completes with findings attributed.
- Each decoder still harvests text identically, the Claude stream decoder still selects the last
  `type: "result"` event and still degrades to a bounded excerpt when none arrives, and session
  identity and stdout digest fields are unchanged.
- A pre-existing `review_accounting` row and a stored `goal_session_accounting` artifact both read
  back without quarantine or regeneration.
- A `.skill-bill/config.yaml` carrying `provider_token_thresholds` parses with the key ignored and one
  degradation record; every other config field resolves as before.
- `estimated_tokens` and the lifecycle `estimated_*_tokens` values still record.

Close with `skill-bill validate`, `(cd runtime-kotlin && ./gradlew check)`, and
`scripts/validate_agent_configs`. The pre-existing `:runtime-infra-fs:sourcesJar` failure on
`./gradlew build` is not introduced by this program; verify with `-x sourcesJar` if that path is used.

## Next Path

```bash
skill-bill goal SKILL-206
```
