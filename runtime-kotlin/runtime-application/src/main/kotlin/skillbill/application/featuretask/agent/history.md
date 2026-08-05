# featuretask runtime boundary history

## [2026-08-05] Operator resume reopens blocked goal children
Areas: runtime-application/goalrunner, runtime-ports/goalrunner
- `skill-bill goal <key>` resume of a blocked subtask now reopens the child's durable blocked phase before launch (same child-side reopen as `feature-task retry-blocked`)
- Clears `needs_user_action` / blocked phase records so operator resume continues instead of immediately re-surfacing the prior block
- Records an `operator_block_retry` artifact with reason `Operator resumed the goal after a blocked stop…`
- `non_retryable_policy_conflict` remains a distinct disposition; reopen still applies because the operator explicitly resumed the goal
- Reusable: treat an explicit parent relaunch as the operator decision that unlocks a blocked child
Feature flag: N/A
Acceptance criteria: N/A

## [2026-08-05] Uncapped implementation continuation
Areas: runtime-application/featuretask
- Removed the hard 5-segment implementation-continuation budget; honest incomplete receipts keep continuing until obligations close, the agent reports a terminal outcome, or an operator stops the run
- Malformed-output and semantic fix-loop budgets are unchanged and still independent of continuation
- Resume no longer re-blocks solely because a prior continuation segment count reached the old cap
- Durable blocks that name the removed continuation budget are treated as stale and relaunch implement on resume (same pattern as other removed terminal gates)
- Reusable: keep partial-work continuation on its own axis; do not charge honest progress to structural or semantic repair budgets
Feature flag: N/A
Acceptance criteria: N/A

## [2026-08-04] SKILL-150 — Audit repair convergence
Areas: runtime-application/featuretask, runtime-application/review, runtime-domain/workflow/taskruntime, runtime-ports/persistence, runtime-infra-sqlite/db/workflow, runtime-infra-fs, orchestration/contracts, runtime-cli, skills/bill-code-review*
- Completeness audits now persist append-only generations (repository checkpoint, satisfied criteria, gaps, closure-complete repair batch, bounded evidence) instead of living only in phase output
- Gap and repair-item identities are stable across generations with explicit `new`/`recurring`/`resolved`/`superseded`/still-open transitions; a later snapshot replacing the audit plan can no longer silently mark a gap resolved
- Repair re-entry is fed from the durable authority: every still-open item exactly once, in dependency order, with prior result evidence and non-regression constraints
- Follow-up audit must reverify every carried gap and inspect the repair batch's production blast radius before emitting `satisfied`
- New `feature-task-runtime-audit-generation-schema.yaml` plus store/migration followed the contract recipe and the append-only, name-keyed migration rule
- Reusable: identity-keyed recurrence counters over append-only generations — the pattern for any loop that must prove convergence rather than assert it
- Pattern: audit gaps stay production-only; test adequacy and failures belong to validate, and repair evidence stays read-only repository facts
- Limitation: makes `audit_gap` converge on evidence but does not cap it (SKILL-157 keeps it unbounded with an advisory)
Feature flag: N/A
Acceptance criteria: 10/10 implemented

## [2026-08-04] SKILL-150 — Truthful implementation completion
Areas: runtime-application/featuretask, runtime-application/model, runtime-domain/workflow/taskruntime, runtime-infra-fs, runtime-contracts, runtime-core/di, orchestration/contracts, runtime tests
- An implementation receipt claiming `completed` advances only when the completion gate finds every authoritative-plan task ID closed with no unresolved item or actionable deviation; the gate names the exact missing task or field
- Incomplete work and malformed output are now separate loops: retryable `blocked`/`failed` stay schema-valid continuation outcomes instead of being coerced to `schemaInvalid` to reach the SKILL-153 structural-repair cap
- Semantic continuation retries get a distinct continue-the-implementation prompt carrying the complete bounded prior receipt (completed task IDs, changed paths, deviations, unresolved items, reconciliation evidence, checkpoint, disposition)
- Continuation projection is rebuilt from durable append-only attempt records, so retry and crash resume reconstruct identical context without depending on in-memory prompts or a replaceable phase-record snapshot
- New `feature-task-runtime-implementation-attempt-schema.yaml` contract with version constant, parity test, typed `Invalid...SchemaError`, and classpath bundling followed the standard runtime-contract recipe
- Status and telemetry now distinguish semantic continuation, schema correction, process retry, crash resume, and audit/review re-entry as separate continuation kinds
- Reusable: producer-side completion gate pattern — validate a phase's own claim against its declared plan before any consumer sees it, since consumers cannot repair it
- Pattern: durable bounded attempt history as the single source for continuation prompts; prompt text is derived, never authoritative
- Limitation: the gate trusts the receipt's changed-path evidence for existence, not repository behavior; behavioral proof stays with audit, review, and validate
Feature flag: N/A
Acceptance criteria: 10/10 implemented

## [2026-08-03] SKILL-157 — Semantic loop warning threshold
Areas: runtime-application/featuretask, runtime-domain/workflow/taskruntime, runtime-application tests, runtime-domain tests
- Semantic remediation loops (`review_fix`, `audit_gap`) stay unbounded; crossing iteration 3 emits one user-visible advisory naming the loop, threshold, iteration, and current work
- Backward-edge declarations carry `warnAfterIterations`; non-semantic bounded edges keep their caps and leave the field null
- Warning acknowledgement is persisted per loop and per subtask, so crash or parent resume yields at-most-once emission and the two loops warn independently
- Emission is a pure side channel: destination, edge iteration, verdict, phase status, unresolved items, and completion outcome are byte-equivalent under Noop, recording, and throwing diagnostics sinks
- Pattern: express advisory-only loop policy as a declared edge field consumed by the run loop, not as control flow in the transition decision
- Reusable: acknowledgement-keyed at-most-once diagnostics for any durable-state loop
- Limitation: warns once at crossing only; no periodic reminder and no hidden hard ceiling
Feature flag: N/A
Acceptance criteria: 7/7 implemented

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
