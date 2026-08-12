# featuretask runtime boundary history

## [2026-08-12] SKILL-187 subtask 2 — Thread rejected response into corrective re-spawn
Areas: runtime-application/featuretask (run loop, observability, prompt composer), runtime-infra-fs (phase-output validator adapter), runtime-domain/workflow/taskruntime/model, runtime-contracts
- gateOutput / settleValidatedOutput reject paths build FeatureTaskRuntimeCorrectiveRepairContext from the same capture metadata and diagnostic identity recorded privately; Exact digests prefer capture-boundary sha/bytes
- settleMalformedOutput and semantic retries carry that context through PriorAttemptCorrection into FeatureTaskRuntimePhasePromptComposer; retryable-terminal and incomplete-work paths stay separate and never render the raw repair section
- Schema rejection after successful delimiter repair retains payload-free structuralRepairEvidence on Rejected and sets acceptedAfterStructuralRepair; the retry prompt names syntax-only repair without claiming phase-schema acceptance
- Event-sink and RunStarted observer failures are isolated so throwing telemetry/status observers cannot abort retry, block, or completion or leak rejected bodies
- Integration coverage: capture↔diagnostic correlation, first-launch/stale-context routing, truncated capture fallback, enum/artifact_ref cases, delimiter-then-schema, exhaustion INVALID_OUTPUT privacy, throwing observers
Feature flag: N/A
Acceptance criteria: 10/10 implemented

## [2026-08-12] SKILL-187 subtask 1 — Corrective-repair context and safe prompt projection
Areas: runtime-domain/workflow/taskruntime/model, runtime-application/featuretask (prompt composer, directives, run loop)
- Added versioned in-flight `FeatureTaskRuntimeCorrectiveRepairContext` (contract 0.1) with phase/attempt/repair-turn, payload-free constraint, diagnostic locator, response digest/byte count, and closed availability + inclusion-reason enums — no Jackson, SQLDelight, or diagnostic-store types cross the seam
- Response states stay exact vs already-truncated vs over-budget vs unavailable; UTF-8 and collection budgets are named constants validated before render so a non-exact body is never labeled exact
- Composer projects the exact response only inside an authorized untrusted repair section (delimiter-safe against fences/braces/YAML/Unicode); required output contract and payload-free guidance stay outside; unavailable/truncated/oversized paths fall back to the opaque diagnostic locator without a misleading excerpt
- Run loop builds the context for schema-invalid corrective retries only; privacy coverage keeps value-bearing validator text, secrets, and raw output out of non-authorized surfaces
- Reusable: typed repair-context + untrusted-section prompt projection for any schema-gate retry that must show prior output without treating it as instructions
- Limitation: context is non-durable and does not add a public raw-output reader or change structural-repair / retry-cap semantics (those remain later subtasks)
Feature flag: N/A
Acceptance criteria: 8/8 implemented

## [2026-08-11] SKILL-177 — Test-value discipline directive
Areas: runtime-application/featuretask (prompt directives + composer)
- Added `testValueDisciplineDirective(phaseId)` beside `minimalismDisciplineDirective`: titled write-time test-value bar for plan, implement, and implement_fix only (dedicated phase set — plan is not mutating)
- Six contracted elements: nameable realistic bug first; critical-path concentration; behavior-at-boundaries / no structure-coupled tests; one strong test per rule; planning `test_obligations` only when the bar passes (empty list valid); never omit real-bug regressions or governed parity / validator-backed rules
- Composer inserts the new section immediately after minimalism; other directive content and order unchanged; prompt-only (no schema / receipt / validate-gate changes)
- Pattern followed: review-time `bill-unit-test-value-check` bar lifted into write-time phase prompts so plan and mutating agents inherit the same discipline
Feature flag: N/A
Acceptance criteria: 7/7 implemented

## [2026-08-10] SKILL-178 subtask 3 — Human-resumable non-convergence pause
Areas: runtime-application/featuretask, runtime-application/goalrunner, runtime-domain/workflow/taskruntime/model, runtime-cli/goal
- Same unresolved advance-blocking set (Blocker or Major) across consecutive remediation passes with an unchanged reviewed-delta digest mints `PAUSED` via `pauseForNonConvergence` instead of re-entering `implement_fix`; an active retry grant suppresses that pause for one transition
- Goal-facing pause reasons carry severity, count, and sanitized labels only — paths, line numbers, and hunks stay behind `skill-bill goal findings`
- `skill-bill goal operator-decision` records `retry_fix` / `accept_and_advance` / `abandon_subtask` onto durable review state without editing `decomposition-manifest.yaml`; resume consumes the decision and reuses `review_base_sha`, baseline untracked inventory, and pass accounting
- `RETRY_FIX` still re-opens the consumed review pass so a hand-applied fix is genuinely re-reviewed; `operatorRetryRounds` stays unbounded — no count converts the pause into auto-advance or terminal failure
- Reusable: `detectReviewRemediationNonProgress` (severity+label+text identities) mirrors audit-repair non-progress detection for the review remediation loop
Feature flag: N/A
Acceptance criteria: 8/8 implemented

## [2026-08-10] SKILL-178 subtask 2 — Remediation-delta finding union
Areas: runtime-application/featuretask, runtime-domain/workflow/taskruntime
- Reserved remediation-pass scope is all findings addressed in that round unioned with `diff(pre-fix -> post-fix)`; pass-one immutable-base and baseline-untracked framing stay suppressed so the two scope statements cannot contradict
- Review-execution-mode text keeps remediation unbounded, now until an unresolved Blocker or Major survives; `context:feature-remediation` stays inline-only
- `implement_fix` briefing carries every preceding-pass finding (Blocker through Nit) with no severity re-filter; handoff projection preserves each finding's severity instead of forcing blocker
- Pattern followed: prompt, composer, and fix-briefing surfaces stay wording-aligned on the widened finding half while the tree-delta half and anti-rediscovery prohibitions stay verbatim
Feature flag: N/A
Acceptance criteria: 7/7 implemented

## [2026-08-10] SKILL-178 subtask 1 — Domain severity gates Blocker + Major
Areas: runtime-domain/workflow/taskruntime/model, runtime-application/featuretask/validation, runtime-domain/config/model, runtime-infra-fs, .skill-bill
- `requiresRemediation` and `blocksAdvance` both gate on Blocker or Major; Minor and Nit stay ledger-only and never reopen `implement_fix` or hard-block advance
- `GoalSubtaskReviewCompactFinding.blocksAdvance` mirrors that rule; a positive unresolved count with an empty itemised list stays blocking; durable artifact keys unchanged
- `REVIEW_CAP_REACHED` / `PAUSED` invariant messages state the Blocker-or-Major rule
- Repo-local `validation_gate.gradle_wrapper` rewrites pack `./gradlew` argv for monorepo layouts without editing pack manifests (reusable)
- BUILD_ONLY terminal `FORCED_FULL` keeps build-only argv and appends pack cache-bypass extras so attestation cannot be a zero-work cache hit
Feature flag: N/A
Acceptance criteria: 8/8 implemented

## [2026-08-10] SKILL-180 — Validate suppression-diff gate
Areas: runtime-application/featuretask/validation, runtime-domain/workflow/taskruntime, runtime-domain/scaffold/policy, runtime-infra-fs (git ops, platformpack), runtime-ports/workflow, orchestration/contracts, platform-packs
- Validate now measures newly introduced suppression markers itself, diffing changed paths against the base ref through the git operations port; the agent's self-report is never an input to the count
- Markers come from platform-pack declarations (`platform.yaml`), not hardcoded Kotlin syntax; a pack declaring none is ungated, and a malformed declaration loud-fails instead of degrading to an empty set
- `validation_result` gained a conditionally required per-suppression justification (path, silenced rule, rationale) — required only when the measured delta is non-zero, so clean runs validate unchanged
- Absent or under-reporting justification blocks validate with the offending paths and unaccounted markers named; a fully accounted delta completes and persists the justification durably
- Gate also applies under `ValidationDepth.BUILD_ONLY`, where suppressions can still be added while fixing compile failures
- Reusable: runtime-measured evidence + conditionally-required agent justification — the pattern for any gate where the agent must account for, but cannot define, the measurement
- Limitation: existing base-ref suppressions are untouched by design; rename/move is neutral, not an audit trail
Feature flag: N/A
Acceptance criteria: 13/13 implemented

## [2026-08-10] SKILL-176 subtask 3 — Orphaned remediation-checkpoint root cause
Areas: runtime-application/featuretask, runtime-ports/workflow, runtime-infra-fs, runtime-kotlin/agent
- Remediation Stage commit and `remediation_base_sha` write are one unit: commit sha is passed into `updateReviewState`; a failed base record soft-resets HEAD to the pre-commit parent so ref and durable row stay paired
- Goal-child resume reconciles committed-but-unrecorded and recorded-but-superseded bases to the branch tip (or latest on-branch review_fix checkpoint) before review prep, with `goal_review_base_recoveries` evidence
- Investigation eliminated in-runtime sibling-orphan producers (failed-checkpoint index restore, crash between commit and record, resume Skip+re-record); stranding needs a post-record history rewrite off the recorded sha
- Reusable: `WorkflowGitOperations.resetSoftToCommit` compensating soft-reset; resume heal closes the crash and post-record rewrite windows without a second healthy-path reconciliation pass
- Limitation: only remediation bases are scope-critical at this seam; any future runtime-owned amend/rebase must call the paired base update
Feature flag: N/A
Acceptance criteria: 7/7 implemented

## [2026-08-10] SKILL-176 — Remediation-base reachability recovery
Areas: runtime-application/featuretask, runtime-domain/workflow/taskruntime, runtime-ports/workflow, runtime-infra-fs
- Unreachable stored review or remediation bases are recovered to the nearest reachable ancestor of the failed sha instead of permanently blocking the goal child
- Recovery gate is disposition-pending only (no longer mutually exclusive with completed review passes / `reservedPassNumber >= 2`)
- Repoint persists to the failed field (`review_base_sha` or `remediation_base_sha`) with durable `goal_review_base_recoveries` evidence (original, replacement, reason)
- Pattern: recover the selected baseline field that materialization actually used; do not always rewrite `reviewBaseSha`
- Reusable: `GoalSubtaskReviewBaselineRecoveryRequest` + field-aware recovery outcome (`Recovered` / `Failed` / `Ineligible`)
- Limitation: still blocks when no reachable ancestor exists; does not prevent orphaning the sha upstream (subtask 3)
Feature flag: N/A
Acceptance criteria: 9/9 implemented

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
