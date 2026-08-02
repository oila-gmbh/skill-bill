# SKILL-145 historical failure matrix

Each original item appears exactly once. `resolved` means the current code or deterministic fixture
provides an enforceable guard; `remaining` means the issue is explicitly deferred to provider,
capacity, deadline, or promotion work; `not applicable` means the current SKILL-144/146 contract
owns it; `regression` is reserved for a newly observed break.

| # | Category | Disposition | Evidence |
|---:|---|---|---|
| 1 | installation/native identity | not applicable | SKILL-146 installed inventory preflight contract |
| 2 | installation/native identity | not applicable | SKILL-146 selected-pack inventory contract |
| 3 | installation/native identity | not applicable | SKILL-146 install/runtime generation contract |
| 4 | installation/native identity | not applicable | SKILL-146 CLI/MCP generation contract |
| 5 | installation/native identity | not applicable | SKILL-146 self-contained install contract |
| 6 | routing/scope | not applicable | SKILL-144 path-authoritative routing tests |
| 7 | routing/scope | not applicable | SKILL-144 generic fallback routing tests |
| 8 | routing/scope | resolved | ReviewPacketProjectionTest packet revision digest coverage |
| 9 | routing/scope | resolved | ReviewPreparationService packet authority |
| 10 | routing/scope | resolved | ReviewContextModelsTest baseline-untracked policy coverage |
| 11 | routing/scope | resolved | ReviewPacketConsumerContract forbidden rediscovery guard |
| 12 | routing/scope | not applicable | SKILL-144 flattened composition contract |
| 13 | capacity | remaining | Parent spec capacity/deadline work; no timeout policy selected here |
| 14 | capacity | remaining | Parent spec capacity/deadline work; lane selection remains governed |
| 15 | capacity | remaining | Parent spec capacity/deadline work; coordinator slot policy deferred |
| 16 | capacity | remaining | Parent spec capacity/deadline work; wave scheduling deferred |
| 17 | capacity | remaining | Provider bootstrap measurement is outside this subtask |
| 18 | capacity | remaining | Bounded admission and predicted waves belong to follow-up capacity work |
| 19 | capacity | resolved | ReviewLaunchPlanPolicy flattening and delegation contract |
| 20 | capacity | not applicable | SKILL-144/146 returned-worker tracking contract |
| 21 | liveness | resolved | ReviewLifecycleRecorder and review_lifecycle_events |
| 22 | liveness | resolved | ReviewLifecycleEvidenceFixture durable ledger |
| 23 | liveness | resolved | ReviewLifecycleEvidenceModelsTest heartbeat/progress separation |
| 24 | liveness | remaining | Whole-review deadline policy is deferred by the subtask non-goal |
| 25 | liveness | remaining | Provider output streaming measurement is deferred |
| 26 | liveness | resolved | Durable event sequence is independent of process activity |
| 27 | liveness | resolved | ReviewDurableWorkerProgress has a distinct required type |
| 28 | liveness | remaining | Per-worker deadline policy is deferred |
| 29 | liveness | resolved | Explicit cancelled and coordinator-crashed event classes |
| 30 | completion/aggregation | resolved | Worker terminal events are persisted before aggregation |
| 31 | completion/aggregation | resolved | Missing results cannot satisfy FakeReviewAggregation |
| 32 | completion/aggregation | resolved | classifyReviewOutput rejects non-zero and interrupted repair |
| 33 | completion/aggregation | remaining | Root diagnostic field-path improvement is separate schema work |
| 34 | completion/aggregation | resolved | Process outcome is recorded before terminal admission |
| 35 | completion/aggregation | resolved | ReviewLifecycleLedger requires exact assignment coverage |
| 36 | completion/aggregation | resolved | Existing ParallelReviewMerger plus assignment ownership checks |
| 37 | completion/aggregation | resolved | Worker events bind provider, worker, packet, assignment, and attempt |
| 38 | completion/aggregation | resolved | Coordinator crash event records pre-terminal failure |
| 39 | completion/aggregation | resolved | Event idempotency and duplicate replay fixture |
| 40 | completion/aggregation | remaining | Envelope-only repair without specialist relaunch is follow-up work |
| 41 | telemetry/trust | resolved | Lifecycle package records bounded terminal and worker status |
| 42 | telemetry/trust | remaining | Wave counts belong to deferred capacity instrumentation |
| 43 | telemetry/trust | resolved | ReviewDiagnosticReference provides bounded diagnostic pointers |
| 44 | telemetry/trust | resolved | Liveness observations cannot satisfy durable progress |
| 45 | telemetry/trust | resolved | AgentRunLaunchFacts classification is provider-neutral |
| 46 | telemetry/trust | resolved | Existing ReviewAccountingProjection preserves token dimensions |
| 47 | telemetry/trust | remaining | Provider-specific promotion thresholds remain a later decision gate |

Items 1–7 and 12, plus item 20, are explicitly disposed through existing
SKILL-144/SKILL-146 contracts and do not open duplicate implementation work.
