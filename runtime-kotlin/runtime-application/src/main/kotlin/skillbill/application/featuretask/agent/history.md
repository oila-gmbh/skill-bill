# featuretask runtime boundary history

## [2026-07-29] SKILL-150 — Scoped checkpoint isolation
Areas: runtime-application/{featuretask,goalrunner,workflow}, runtime-domain/taskruntime, runtime-ports/workflow, runtime-infra-{fs,sqlite}, runtime-cli, runtime-core
- Replaced repository-wide checkpoint staging with a workflow-owned path inventory and a private Git index, preserving foreign staged, unstaged, and untracked work
- Added typed policy blocks for paths outside workflow authority, including foreign governed feature specs, before checkpoint commit creation
- Persisted checkpoint identity with branch, phase, loop, generation, parent, owned-path digest, owned paths, and immutable commit SHA
- Bound review generation and semantic delta calculation to the immutable checkpoint and its owned-path inventory so unrelated dirt cannot affect review
- Routed standalone and goal-child checkpoint creation, restoration, crash/resume, audit repair, and review remediation through the same scoped contract
- Pattern: construct the checkpoint tree in a temporary index, create the commit object, then advance the branch with a compare-and-swap ref update. reusable
- Reusable: scoped checkpoint port/models, checkpoint identity artifact mapping, owned-content identities, and Git checkpoint operations
- Breaking changes/limitations: repository-wide `stageAll` checkpoint authority was removed; owned-path violations now stop with a non-retryable policy result
Feature flag: N/A
Acceptance criteria: 10/10 implemented

## [2026-07-28] SKILL-150 — Lossless review generations
Areas: runtime-application/featuretask, runtime-application/goalrunner, runtime-domain/taskruntime, runtime-infra-sqlite, orchestration/contracts
- Replaced destructive review invalidation with immutable, checkpoint-bound generations and durable finding identities/dispositions
- Carried unresolved Blockers across semantic delta changes for explicit evidence-backed settlement; bookkeeping-only changes preserve the active review
- Gated advancement on the durable cross-generation unresolved-Blocker ledger rather than the latest review payload alone
- Preserved generation and finding history idempotently across remediation, pause, cap, operator disposition, abandon, crash, and resume paths
- Exposed bounded generation, pass, carried/new Blocker counts, and terminal disposition summaries without raw review output
- Pattern: classify repository deltas before review settlement and atomically persist generation state before workflow advancement
- Reusable: review delta classifier, generation settlement service, artifact keys, SQLite generation runtime, and status summary reducer
- Breaking changes/limitations: review-state schema adds generation summaries; complete review prose and source diffs remain outside normalized storage
Feature flag: N/A
Acceptance criteria: 11/11 implemented

## [2026-07-28] SKILL-150 — Durable convergence state
Areas: runtime-application/featuretask, runtime-domain/taskruntime, runtime-ports/persistence, runtime-infra-sqlite, runtime-contracts, orchestration/contracts
- Added versioned, bounded convergence records for implementation obligations, audit repairs, review blockers, repository generations, and phase provenance
- Persisted append-only SQLite history with deterministic identities, relationship checks, idempotent replay, conflict detection, and unresolved-state queries
- Made convergence recording participate in injected database sessions so phase outcomes and workflow advancement can commit or roll back atomically
- Added schema-validated, once-only legacy reconciliation with typed quarantine outcomes and preserved rejected-output privacy boundaries
- Pattern: separate immutable convergence evidence from derived current state; resolve children against the parent generation they repair
- Reusable: convergence repository/service ports, bundled schema validator, database migrations/schema helpers, and deterministic identity codec
- Breaking changes/limitations: convergence storage contract is version 1.0; this subtask does not change phase advancement policy
Feature flag: N/A
Acceptance criteria: 10/10 implemented

## [2026-07-28] SKILL-134 — Structured audit deferral
Areas: runtime-application/featuretask, orchestration/contracts, runtime-application tests
- Replaced recursive free-text deferral detection with explicit `deferred_repairs` and exhaustive per-item `repair_results`
- Enforced exact repair identifiers, dependency order, terminal outcomes, changed-path/symbol evidence, executed verification, and blocked-item consistency
- Preserved actionable diagnostic rule IDs and JSON paths while allowing legitimate future-phase language in summaries and evidence
- Pattern: validate remediation authority from structured fields only; keep human-readable text non-authoritative
- Reusable: shared structured repair projection validation across producer retry gates, standalone runtime, and goal-child execution
- Breaking changes/limitations: planning projection contract now requires `deferred_repairs`; completed remediation must leave it empty
Feature flag: N/A
Acceptance criteria: 6/6 implemented

## [2026-07-28] SKILL-134 — Rejected output diagnostics
Areas: runtime-application/featuretask, runtime-cli/featuretask, runtime-contracts, runtime-domain, runtime-ports, runtime-infra-fs, runtime-infra-sqlite, orchestration/contracts
- Persisted one exact rejected phase response per stable workflow/phase/attempt identity before retry or block, with typed metadata, digest, byte size, and deduplication
- Added deterministic size, retention, corruption, cleanup, and owner-only file-permission enforcement behind typed service and persistence boundaries
- Kept raw bodies out of workflow artifacts and ordinary projections; metadata is the default CLI view and exact output requires an explicit raw-output flag
- Extended quarantine metadata with structured diagnostic identity while preserving diagnostic records as non-authoritative for workflow continuation
- Reusable: `RejectedOutputDiagnosticService`, repository port/model, schema validator adapter, and shared privacy assertions
- Pattern: persist privacy-sensitive forensic output separately from workflow authority, expose metadata by default, and require explicit retrieval for raw content
- Breaking changes/limitations: diagnostic and quarantine contract versions changed; storage is local-only and exact responses are not automatically redacted
Feature flag: N/A
Acceptance criteria: 6/6 implemented

## [2026-07-27] SKILL-138-cursor-full-agent-support
Areas: runtime-application/featuretask, runtime-infra-fs/launcher/agentrun, runtime-infra-fs/infrastructure/fs, runtime-cli/tests, skills/bill-code-review*, scripts, docs
- Extended featuretask runtime loop to support Cursor as a full agent provider with strategy-based lifecycle callbacks, stream parsing, and crash reconciliation
- Added Cursor-specific command builder supporting `/<worker>` CLI syntax, tool-denied permissions via isolated `.cursor/cli.json`, and fresh-process review isolation
- Introduced typed error hierarchy for Cursor review streams: malformed, empty, forbidden operation, provider failure, and termination errors
- Updated governed skills `bill-code-review` and `bill-code-review-parallel` with Cursor routing, native subagent instructions, and CLI-delegated parallel review
- Documented exact Cursor paths, commands, generated boundaries, and support tier across README, capabilities, getting-started guides, and internal architecture docs
- Created live parity harness testing 7 scenarios: install, MCP startup, runtime feature task, decomposed goal, delegated/parallel review, workflow resume, and uninstall preservation
- New patterns: Strategy-based agent provider injection (progressProbe, lifecycleCallbacks, idlePolicy, usePtyStdio) without conditional branching in ProcessWaitLoop
- Reusable components: `CursorNativeReviewLifecycleCallbacks` for stream event parsing, `CursorAgentRunCommandBuilder` for review detection and command building, typed `CursorReviewStreamErrors` hierarchy
- Breaking changes: None (provider-agnostic contract preserved, native-agent sources remain provider-neutral)
Feature flag: N/A
Acceptance criteria: 7/7 implemented
