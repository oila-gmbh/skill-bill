# SKILL-145 historical evidence ledger

This is the authoritative ledger for the 47 historical SKILL-145 items. Each
key appears exactly once. `resolved` means the current code or deterministic
fixture provides an enforceable guard; `remaining` means the issue is explicitly
deferred to provider, capacity, deadline, or promotion work; `not applicable`
means the current SKILL-144/146 contract owns it; `regression` is reserved for a
newly observed break. Every row has an owner, bounded evidence reference,
evidence status, and rationale so an unresolved item cannot be carried without
accountability.

| # | Category | Status | Owner | Evidence reference | Evidence status | Rationale |
|---:|---|---|---|---|---|---|
| 1 | installation/native identity | not applicable | SKILL-146 maintainers | SKILL-146 installed inventory preflight | verified | Installation identity is owned by the existing contract; no duplicate remediation is opened here. |
| 2 | installation/native identity | not applicable | SKILL-146 maintainers | SKILL-146 selected-pack inventory | verified | Selected-pack identity is already governed outside this decision. |
| 3 | installation/native identity | not applicable | SKILL-146 maintainers | SKILL-146 install/runtime generation | verified | Runtime generation remains an existing installation boundary. |
| 4 | installation/native identity | not applicable | SKILL-146 maintainers | SKILL-146 CLI/MCP generation | verified | Provider artifact generation is already covered by the installation contract. |
| 5 | installation/native identity | not applicable | SKILL-146 maintainers | SKILL-146 self-contained install | verified | Self-contained installation remains the authoritative source for native identity. |
| 6 | routing/scope | not applicable | SKILL-144 maintainers | SKILL-144 path-authoritative routing tests | verified | Routing ownership is not reimplemented under SKILL-145. |
| 7 | routing/scope | not applicable | SKILL-144 maintainers | SKILL-144 generic fallback routing tests | verified | Generic fallback behavior remains the existing routing contract. |
| 8 | routing/scope | resolved | Review context owners | ReviewPacketProjectionTest | verified | Packet revisions and packet digest bind the review scope. |
| 9 | routing/scope | resolved | Review application owners | ReviewPreparationService | verified | The prepared packet remains the scope authority. |
| 10 | routing/scope | resolved | Review context owners | ReviewContextModelsTest baseline-untracked policy | verified | Included and excluded baseline-untracked paths are disjoint and explicit. |
| 11 | routing/scope | resolved | Review context owners | ReviewPacketConsumerContract forbidden rediscovery guard | verified | Workers consume the assignment projection instead of rediscovering scope. |
| 12 | routing/scope | not applicable | SKILL-144 maintainers | SKILL-144 flattened composition contract | verified | Flattened composition is already governed and is excluded from duplicate work. |
| 13 | capacity | remaining | Review reliability follow-up owner | spec_followup_domain.md capacity section | deferred | The current contract has a planner, but promotion requires measured admission limits. |
| 14 | capacity | remaining | Review reliability follow-up owner | spec_followup_application.md wave scheduling | deferred | Lane selection is governed; bounded execution admission still needs independent evidence. |
| 15 | capacity | remaining | Review reliability follow-up owner | reliability-contract.md coordinator slot rule | deferred | The coordinator slot is defined, but production capacity policy needs measurement. |
| 16 | capacity | remaining | Review reliability follow-up owner | spec_followup_domain.md wave accounting | deferred | Deterministic wave accounting is specified for follow-up implementation and validation. |
| 17 | capacity | remaining | Provider adapter owners | spec_followup_provider-adapters.md bootstrap measurement | deferred | Provider bootstrap observations are not a promotion claim without authenticated canaries. |
| 18 | capacity | remaining | Review reliability follow-up owner | spec_followup_application.md bounded admission | deferred | Admission and predicted-wave limits require a separate governed implementation. |
| 19 | capacity | resolved | Review planning owners | ReviewLaunchPlanPolicy | verified | Flattening and declared-area selection remain explicit and deterministic. |
| 20 | capacity | not applicable | SKILL-144/146 maintainers | returned-worker tracking contract | verified | Returned-worker identity and context projection are owned by existing contracts. |
| 21 | liveness | resolved | Review application owners | ReviewLifecycleRecorder and review_lifecycle_events | verified | Ownership-boundary events are persisted before the next boundary. |
| 22 | liveness | resolved | Review application owners | ReviewLifecycleEvidenceFixture | verified | Deterministic fixtures reproduce missing aggregation and interruption states. |
| 23 | liveness | resolved | Review ports owners | ReviewLifecycleEvidenceModelsTest | verified | Heartbeats and declared progress are distinct from durable progress. |
| 24 | liveness | remaining | Review reliability follow-up owner | spec_followup_domain.md deadline policy | deferred | Whole-review expiry is defined but requires measured policy validation. |
| 25 | liveness | remaining | Provider adapter owners | spec_followup_provider-adapters.md output observations | deferred | Streaming output remains bounded observational evidence, not progress. |
| 26 | liveness | resolved | Review persistence owners | durable event sequence | verified | Durable event ordering is independent of process activity. |
| 27 | liveness | resolved | Review ports owners | ReviewDurableWorkerProgress | verified | Specialist progress has a distinct required durable type. |
| 28 | liveness | remaining | Review reliability follow-up owner | spec_followup_domain.md per-worker deadline | deferred | Per-worker enforcement is contractually required but not promoted from one run. |
| 29 | liveness | resolved | Review application owners | cancelled and coordinator-crashed event classes | verified | Cancellation and crash outcomes have explicit lifecycle event kinds. |
| 30 | completion/aggregation | resolved | Review application owners | worker terminal events before aggregation | verified | Aggregation reads persisted worker completion rather than in-memory summaries. |
| 31 | completion/aggregation | resolved | Review test owners | FakeReviewAggregation missing-result guard | verified | Missing results cannot satisfy the aggregation gate. |
| 32 | completion/aggregation | resolved | Review application owners | classifyReviewOutput | verified | Non-zero and interrupted outcomes cannot enter schema repair. |
| 33 | completion/aggregation | remaining | Review schema follow-up owner | spec_followup_schema.md diagnostic paths | deferred | Root field-path diagnostics remain a bounded schema follow-up. |
| 34 | completion/aggregation | resolved | Review application owners | process outcome before terminal admission | verified | Process outcome is durable before a terminal result is accepted. |
| 35 | completion/aggregation | resolved | Review domain owners | ReviewLifecycleLedger exact coverage | verified | The ledger requires one selected assignment per completed result. |
| 36 | completion/aggregation | resolved | Review domain owners | ParallelReviewMerger ownership checks | verified | Findings merge only after ownership and envelope checks. |
| 37 | completion/aggregation | resolved | Review ports owners | worker/provider/packet/assignment/attempt event identity | verified | Lifecycle events bind all current-attempt identities. |
| 38 | completion/aggregation | resolved | Review application owners | coordinator crash event | verified | A crash before terminal persistence is explicit and recoverable. |
| 39 | completion/aggregation | resolved | Review persistence owners | duplicate replay fixture | verified | Same event replay is idempotent; conflicting evidence is rejected. |
| 40 | completion/aggregation | remaining | Review application follow-up owner | spec_followup_application.md schema-repair boundary | deferred | Repair remains limited to normal zero-exit invalid envelopes until separately governed. |
| 41 | telemetry/trust | resolved | Review telemetry owners | bounded lifecycle package | verified | Terminal and worker statuses are retained without raw provider output. |
| 42 | telemetry/trust | remaining | Review telemetry follow-up owner | spec_followup_telemetry.md wave metrics | deferred | Wave metrics are specified but need representative measurement fixtures. |
| 43 | telemetry/trust | resolved | Review ports owners | ReviewDiagnosticReference | verified | Diagnostics are bounded references rather than copied provider output. |
| 44 | telemetry/trust | resolved | Review application owners | liveness observation separation | verified | Process, MCP, and file activity cannot satisfy durable progress. |
| 45 | telemetry/trust | resolved | Provider adapter owners | AgentRunLaunchFacts | verified | Provider observations are classified through provider-neutral strategy facts. |
| 46 | telemetry/trust | resolved | Review accounting owners | ReviewAccountingProjection | verified | Token dimensions preserve direct and inclusive ownership. |
| 47 | telemetry/trust | remaining | Review decision owner | decision.md promotion gate | deferred | Provider-specific thresholds remain falsifiable promotion gates, not an implicit promotion. |

Items 1–7 and 12, plus item 20, are explicitly disposed through existing
SKILL-144/SKILL-146 contracts and do not open duplicate implementation work.
