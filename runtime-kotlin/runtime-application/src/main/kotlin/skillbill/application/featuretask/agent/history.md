# featuretask runtime boundary history

Revisit when: prune eligibility or the checkpoint namespace layout changes.

## [2026-08-24] SKILL-207 subtask 1 — Phase I/O envelope and prose review handoff
Areas: runtime-application/{featuretask,model,review}, runtime-domain/{agent,review}, runtime-cli/codereview
- Added shared `AgentPhaseInput` and `AgentPhaseOutput` string envelopes for phase handoffs.
- Review driver, runner, and CLI preserve worker-returned prose as authoritative output; register parsing remains optional diagnostics, so near-miss lines survive.
- Typed launch dependencies remain on launch plumbing outside the phase string fields.
- Pattern: preserve full worker prose at the phase boundary while keeping structured register parsing non-authoritative. reusable
- Limitation: claim verification and governed skill-content rewrites remain subtask 2; plan, implement, and validate do not use the envelope.
Feature flag: N/A
Acceptance criteria: 5/5 implemented

## [2026-08-23] SKILL-202 subtask 3 — scoped boundary memory for verification
Areas: runtime-application/{featuretask,goalrunner,review}, runtime-domain/{review,taskruntime}, runtime-infra-fs/goalplanning, runtime-ports/goalrunner, runtime contracts, runtime-cli/goal, governed feature-task skills
- Verification now discovers boundary memory by title first and scopes it to boundaries owning each finding's paths; excluded roots contribute nothing and unmatched paths stay intent-only.
- Verification-specific discovery and body caps are declared below planning caps; the shared maximum boundary-file size remains unchanged, and over-budget resolution loud-fails without truncation.
- Dispositions persist selected heading ids with source paths and expose that provenance through goal findings; unselected history and decision bodies never enter prompts.
- Pattern: reuse GoalPlanningContextDiscovery and GoalPlanningBoundaryBodyResolver with a path-scoped, titles-first projection. reusable
- Limitation: findings with no eligible owning boundary retain intent-only verification and record unavailable boundary context.
Feature flag: N/A
Acceptance criteria: 10/10 implemented

## [2026-08-22] SKILL-205 subtask 2 — Prior-gap memory for continuing audit_gap rounds
Areas: runtime-application/featuretask, runtime-application/goalrunner, runtime-domain/workflow/taskruntime/model, orchestration/contracts, platform-packs/{kotlin,kmp}/quality-check
- Added bounded durable `prior_gap_memory` to audit-gap handoffs, retaining unmet criterion refs/notes and subsequent implement claims while degrading legacy workflows to empty memory.
- Implement re-entry prioritizes sticky unmet criteria while still closing every current gap; follow-up audit requires explicit re-justification for repeated sticky ids and rereads the repository as authority.
- Schema-first handoff projection and envelope contracts are validated at producer and consumer seams; Kotlin and KMP quality-check guidance stays aligned. reusable
- Pattern: carry only bounded criterion memory across remediation edges, deriving it from durable audit and implement receipts rather than treating implement claims as proof.
- Limitation: memory improves audit_gap retries only; subtask 1's no-progress comparison and pause policy remain authoritative.
Feature flag: N/A
Acceptance criteria: 5/5 implemented

## [2026-08-22] SKILL-205 subtask 1 — No-progress and warn-threshold pause for audit_gap
Areas: runtime-application/featuretask, runtime-application/goalrunner, runtime-domain/workflow/taskruntime/model, runtime-domain/config/model
- `audit_gap` re-entry now compares the new unmet criterion set against the prior round's: any cleared prior criterion ref is progress; a non-shrinking set with an unchanged (or unprovable) repository mints a durable no-progress pause instead of another implement launch.
- Crossing `warnAfterIterations` (iteration 4 at threshold 3) is control flow now: it pauses for an operator decision; the advisory side channel still emits.
- `FeatureTaskRuntimeAuditGapPause` durable artifact carries `pause_kind` (no_progress | warn_threshold), `edge_iteration`, `operator_decision`, `grant_consumed`; the recorder gains load/persist seams for the progress and pause artifacts and canonical criterion-ref extraction moves into output verification.
- Goal operator decision handles an audit-gap pause without review state: `retry_fix` persists the decision, resume settles the paused audit from carried-forward output and consumes the grant for exactly one further attempt; `abandon_subtask` consumes the grant; re-pausing clears `operator_decision` so a second no-progress or threshold condition pauses again.
- Status and blocked reasons source from the pause artifact, so no-progress and warn-threshold pauses read distinct from output-gate/schema failures; a consumed grant is no longer an active pause.
- Progress detection fails closed when the previous repository fingerprint is unproven (`UNPROVEN_REPOSITORY_FINGERPRINT`), so an unchanged audit cannot pass as progress when change cannot be proven.
- Reusable: durable pause artifact + single-grant operator-decision loop for any unbounded remediation edge; fails-closed shrink-or-fingerprint progress comparison.
Feature flag: N/A
Acceptance criteria: 5/5 implemented

## [2026-08-22] SKILL-204 subtask 1 — First-class build phase and pack build command
Areas: runtime-application/featuretask, runtime-domain/workflow/taskruntime, runtime-infra-fs/contracts/workflow, orchestration/contracts, platform-packs/{kotlin,kmp}, AGENTS.md
- Added a real `build` phase between clean review and write-history, with runtime-owned discover → repair → confirm gate execution.
- Platform validation gates now declare a build command; Kotlin and KMP use compile/buildability argv distinct from their collect-all full gate.
- Settled build output projects a schema-validated `build_receipt`, while downstream receipt acceptance remains deferred to subtask 2.
- Build prompts and repository guidance prohibit suite tests, full checks, substitute agent-run gates, and delegated subagents; targeted repair commands remain allowed.
- Pattern: keep pack command ownership in manifests and share typed gate/projection validation across launch and settlement. reusable
- Limitation: goal-child routing still defaults to `review → validate`; selecting `build` for intermediate children is subtask 2.
Feature flag: N/A
Acceptance criteria: 6/6 implemented

## [2026-08-21] SKILL-202 subtask 2 — verify_findings phase
Areas: runtime-application/{featuretask,goalrunner}, runtime-domain/taskruntime, runtime-infra-sqlite, runtime-cli/goal, runtime-infra-fs/install, runtime contracts, governed feature-task skills
- Added the declared `verify_findings` phase between review and implement_fix; each review finding receives one bounded `verified` or `rejected` disposition against the spec intent projection.
- Verified findings of every severity drive the single capped implement_fix round; rejected findings retain their reason and severity in the unaddressed-findings ledger and are never fixed.
- Pattern: keep phase topology, entry gates, durable records, status, telemetry, CLI, and governed prompts aligned around one review pass and one fix round. reusable
- Durable verification records and retired values loud-fail rather than being coerced; resume reuses in-flight dispositions without rerunning review.
- Limitation: verified findings that remain after the one fix round proceed to validate under the accepted bounded-remediation trade-off.
Feature flag: N/A
Acceptance criteria: 15/15 implemented

## [2026-08-21] SKILL-202 — single-pass review remediation
Areas: runtime-application/featuretask, runtime-application/goalrunner, runtime-domain/taskruntime, runtime contracts, governed feature-task skills
- Review remediation is one `implement_fix` round: `review --changes_requested` uses a per-subtask cap of one and advances to validate after that round.
- Removed `plan_fix`, second-round escalation, churn, and unresolved-finding pause vocabulary from runtime projections, durable review state, transition wiring, and prompts.
- Pattern: declare bounded remediation edges in workflow topology and keep prompts, persistence, schemas, and projections aligned. reusable
- Durable records naming retired phase, verdict, or ledger values loud-fail rather than being coerced; audit-gap and rejected-output loops retain their independent behavior.
Feature flag: N/A
Acceptance criteria: 11/11 implemented

## [2026-08-20] Validate runs only the pack collect-all command
Areas: runtime-application/featuretask (validate directives, prompt composer, run loop), AGENTS.md, skills/bill-feature-task-runtime
- Validate briefing names the pack `collect_all_full_gate_command` and forbids `skill-bill validate`, `npx agnix`, `scripts/validate_agent_configs`, and `bill-code-check`.
- The launch seam interpolates the declared argv into the Task line so the session sees the exact command.
- Pattern: platform pack owns the validate command set; repo-root checklists stay maintainer-only. reusable
Feature flag: N/A
Acceptance criteria: 1/1 implemented

## [2026-08-20] Validate agent runs collect-all, fixes, then confirms
Areas: runtime-application/featuretask (validate directives, prompt composer, projection shapes), AGENTS.md, skills/bill-feature-task-runtime, skills/bill-code-check, platform-packs/kotlin and kmp quality-check
- Validate briefing tells the single session to run the pack collect-all gate, read that output, fix every finding, then run one confirmation check. Parsed runtime findings are a hint, not a substitute for the log.
- Repair windows no longer forbid Gradle during repair; they forbid rerunning the full gate after each individual finding. Targeted compile/test stays allowed.
- Pattern: collect-all once, repair the set, confirm once, in the same process. reusable
Feature flag: N/A
Acceptance criteria: 1/1 implemented

## [2026-08-20] SKILL-190 subtask 6 — Checkpoint-ref prune lifecycle and docs
Areas: runtime-application/featuretask, runtime-application/goalrunner, runtime-ports/workflow, AGENTS.md, orchestration/workflow-contract/PLAYBOOK.md, skills/bill-feature-task-runtime
- `FeatureTaskRuntimeCheckpointRefPrune` deletes refs under `refs/skill-bill/checkpoints/<issue>/<subtask>/` only after push plus a recorded manifest `commit_sha`; hard reset bypasses the gate; pruning is idempotent.
- Goal runner prunes on subtask completion and on status reconcile for complete subtasks; hard reset prunes every reset subtask namespace.
- `commit_push` finalisation defers prune until the manifest records `commit_sha`; agents emit message and paths only.
- Pattern: gated ref lifecycle paired with runtime-owned finalisation; reset-driven namespace bounding. reusable
Feature flag: N/A
Acceptance criteria: 9/9 implemented

## [2026-08-20] SKILL-190 — Completed-upstream-missing-output goal repair wedge
Areas: runtime-application/featuretask, runtime-application/goalrunner, runtime-application/model, runtime-cli/goal
- Added `COMPLETED_UPSTREAM_MISSING_OUTPUT` goal repair wedge: blocked consumers missing upstream projections diagnose `completed` phase rows with no settled output
- `FeatureTaskRuntimeCompletedUpstreamRepair` reopens the earliest unsettled upstream and dependent blocked phases via operator-resume repair input; `asPendingForOperatorResume` lives in `FeatureTaskRuntimePhaseRecordRepair`
- `GoalRunnerChildRepairApplyResult` carries manifest projection artifacts; outcome store writes decomposition projection after `updateGoalParentForBlockedPhaseRetry`
- `RemediationBaseReconciler` quarantines superseded checkpoint-identity stores to sibling evidence and clears the live key so children regenerate identities instead of dying on version errors
- Pattern: operator `goal repair` wedge for durable phase-record inconsistencies that otherwise strand children with nonzero exit and no recovery path. reusable
- Limitation: runtime-owned `commit_push` finalisation from subtask 5 spec remains outstanding on this branch checkpoint
Feature flag: N/A
Acceptance criteria: 6/6 implemented

## [2026-08-19] SKILL-190 subtask 4 — Ref-based remediation reconciliation and rollback under amend
Areas: runtime-application/featuretask (goal continuation recorder, run loop), runtime-kotlin/agent, runtime-ports/workflow
- `reconcileRemediationBaseCoherence` resolves the latest `review_fix` base through checkpoint refs instead of branch ancestry; unresolvable bases block with operator guidance rather than rewriting to HEAD.
- `rollbackRemediationCheckpointCommit` restores the prior checkpoint ref (or removes the first subtask commit) and no-ops when HEAD already moved; compensating rollback no longer soft-resets to `parentSha` alone.
- Durable `goal_review_base_recoveries` evidence carries seam, value used, value expected, and cause on blocked reconciliation and stored-base misses.
- Reusable: ref-resolved remediation base + typed blocked outcome at goal-child resume; ref-based compensating rollback paired with subtask 3 amend ceremony.
- Limitation: none for this bundle; pair with integrated verification before treating production-safe.
Feature flag: N/A
Acceptance criteria: 10/10 implemented

## [2026-08-19] SKILL-190 subtask 3 — Runtime-owned subtask commit identity and amend ceremony
Areas: runtime-application/featuretask (run loop, commit resolver, checkpoint scope), runtime-domain/workflow/taskruntime/model, runtime-infra-fs (GitCheckpointHistoryOperations), runtime-ports/workflow
- Forward and remediation checkpoints now create-or-amend exactly one subtask commit instead of appending branch commits; Skip and Block verdicts still write nothing.
- `FeatureTaskRuntimeSubtaskCommitResolver` decides create vs amend from durable identity first, falling back to the `Skill-Bill-Subtask: <issue>/<subtask-id>` trailer on HEAD with an observability record when state is unavailable. reusable
- Provisional subject comes from the manifest subtask `name`; `phase`, `loop`, and `generation` move to the commit body alongside the trailer, retiring the old single-line checkpoint subject.
- Before each amend, the pre-amend commit is written to `refs/skill-bill/checkpoints/<issue>/<subtask>/<sequence>` and must resolve before amend runs; ref failure blocks the checkpoint loudly.
- Pattern: extend subtask 1 amend/ref primitives through the checkpoint write path without changing ceremony dispatch or scope verdicts; checkpoint-identity idempotency from subtask 2 is preserved in the same transaction.
- Limitation: none for this bundle; pair with integrated verification before treating production-safe.
Feature flag: N/A
Acceptance criteria: 12/12 implemented

## [2026-08-18] SKILL-198 subtask 2 — runtime repair window owns check execution
Areas: runtime-application/featuretask (validation coordinator, policy, cycle models), runtime-domain/workflow/taskruntime/model, AGENTS.md
- Durable `repair_window_phase` (`none` | `findings_open`) on validation gate progress; while `findings_open` the coordinator runs zero pack argv and resume hands back the persisted complete finding set without a discovery rerun.
- Gate cycle phases are `INITIAL_DISCOVERY` then `POST_REPAIR_VERIFY` only; `full_gate_command` is never an intermediate repair argv; leaving `findings_open` requires the agent repaired signal, then exactly one cache-bypassing collect-all or build-only verify.
- `validationGateArgv` maps discovery to collect-all or build-only and verify to cache-bypassing counterparts; a failing verify replaces the finding set and re-enters `findings_open` without targeted module commands.
- Pattern: runtime-owned repair window aligned with subtask 1 briefing — agent edits only during `findings_open`, runtime is the sole declared-gate executor. reusable
- Limitation: agent-run fallback without `validation_gate` stays prompt-only; arbitrary shell Gradle is not intercepted
Feature flag: N/A
Acceptance criteria: 8/8 implemented

## [2026-08-18] SKILL-198 subtask 1 — validate briefing repair-window contract
Areas: runtime-application/featuretask (validate directives), AGENTS.md
- Runtime-owned FULL, BUILD_ONLY, and agent-run fallback validate briefings declare an open finding set as a repair window: no gate, `bill-code-check`, targeted Gradle tasks, pack checkers, or subagent checks until every finding is repaired; verification is one cache-bypassing gate after the batch.
- `AGENTS.md` validate paragraph aligned: dropped "no cache-bypassing confirmation pass", named `detekt`/`ktlintCheck`/`test`/`compileKotlin`, and states BUILD_ONLY runs outside an open repair window only.
- `FeatureTaskRuntimePhasePromptComposerTest` pins forbidden-command phrases in composed validate prompts; `MaintainerPolicyRepairWindowParityTest` locks `AGENTS.md` and `CLAUDE.md` to the same vocabulary.
Feature flag: N/A
Acceptance criteria: 6/6 implemented

## [2026-08-17] SKILL-191 subtask 9 — Feature-task review phase delegation
Areas: runtime-application/featuretask (run loop, review driver, prompt directives, briefing assembler), runtime-core/di, runtime-application tests
- `PHASE_REVIEW` stopped being an agent phase: `runDeclaredReviewDriverCycle` prepares the pass, mints or reuses the `review_run_id`, pins the repository checkpoint, and invokes `FeatureTaskRuntimeReviewDriver` — bound in `RuntimeComponent` to `ParallelCodeReviewRunner::run`, the same driver the standalone entry uses
- A reused `review_run_id` is now claimed only when the durable record belongs to the same review pass, so a second pass mints its own id instead of inheriting pass one's
- `FeatureTaskRuntimeReviewEnvelope` assembles `produced_outputs.findings` and `review_run_id` from the driver result only, strips criterion-gap keys (`unmet_criteria`, `gaps`, `failing_criteria`), and adds `blocker_dispositions` from pass two onward
- The review directive now states the runtime owns the review; the briefing allowlist keeps `ACCEPTANCE_CRITERIA` out of the review phase, so criterion-gap detection stays exclusive to audit
- The mapper pins `ParallelReviewScope.UNSTAGED` with the caller-supplied diff, base and head revisions from `GoalSubtaskReviewInput`, and the governed spec path, so no stage rediscovers a baseline and adjudication always runs
- Reusable: the validate-phase precedent of runtime-run gate plus bounded agent projection, now applied to review
- Limitation: `code_review_mode` resolution and continuation conflict rejection are unchanged; pass two still forces `INLINE` via `FeatureTaskRuntimeReviewPassSequence.resolveForPass`
Feature flag: N/A
Acceptance criteria: 9/9 implemented

## [2026-08-15] SKILL-192 subtask 3 — Repair plan, substantiation receipts, confirmation closure
Areas: runtime-application/featuretask/validation, runtime-domain/workflow/taskruntime/model, runtime-ports/validation, AGENTS.md, runtime-kotlin/agent
- FULL collect-all no longer treats a completed repair payload as proof: persist a covering repair plan, require a substantiation receipt per discovery identity, then run one confirmation collect-all
- Green confirmation is identity closure (every discovery identity absent from the confirmation finding set), not measured PASSED; leftover identities fail substantiation and stay in the next set; new confirmation identities join that set without fail-fast rediscovery
- Incomplete plan or receipts reject the same repair pass without a new gate run (producer-side gate, same validator as launch); grouped plan items still close every identity
- Pattern: suite proof stays one collect-all confirmation; never substantiate via pack full gate or per-test Gradle. BUILD_ONLY stays compile/build-only without receipts or closure. reusable
- Limitation: identity key remains exact module|ruleOrTestId|message|location; additive plan/receipt keys decode empty without a persistence version bump
Feature flag: N/A
Acceptance criteria: 12/12 implemented

## [2026-08-15] SKILL-192 subtask 2 — FULL validate discover, plan, repair-all, confirm
Areas: runtime-application/featuretask (coordinator, policy, projector, prompts), runtime-domain/workflow/taskruntime/model
- FULL validate replaced unbounded fail-fast `full_gate_command` looping with collect-all discovery, persist-complete-set, one repair pass over the entire set (or pages without a gate rerun), then cache-bypassing collect-all confirmation
- A cache-eligible green discovery is never terminal; confirmation with zero executed work never satisfies; a failed confirmation's complete set is the next repair input with no extra discovery; retry-cap exhaustion blocks with remaining findings
- BUILD_ONLY stays on `build_only_command` cache-eligible then forced-full attestation; goal last-child FULL vs intermediate BUILD_ONLY is unchanged
- Pattern: pack collect-all argv owns continue-on-failure and cache-bypass; coordinator never falls back to fail-fast FULL when collect-all exists. reusable
- Limitation: repair-plan receipts and confirmation identity closure are subtask 3; this cycle treats a completed repair launch as pass-attempted
Feature flag: N/A
Acceptance criteria: 10/10 implemented

## [2026-08-13] SKILL-186 subtask 2 — Quarantine identity integrity
Areas: runtime-application/featuretask (recorder, run loop), runtime-application/model, runtime-domain/workflow/taskruntime/model, orchestration/contracts, runtime-contracts, runtime-infra-fs (schema validator)
- `recordRejectedOutput` returns `Written(identity)` only after the evidence transaction commits, or `Degraded(failureClass)` when it rolled back — never a `rod_` token for a row that does not exist
- Quarantine append records that outcome as exclusive wire fields: identity when the write landed, `diagnostic_degraded: true` (identity omitted) when it degraded; regeneration still fires; the store stays append-only
- Contract 0.3 stays: additive `diagnostic_degraded` const-true mutually exclusive with identity so pre-change identity-only entries still decode; `false` and undeclared envelope/entry fields loud-fail instead of being dropped
- Reusable: typed write-outcome at the persistence seam so a caller cannot persist an identity the store never wrote; exclusive optional fields over a version bump when old records must remain readable
- Limitation: already-persisted dangling identities are not repaired (spec non-goal)
Feature flag: N/A
Acceptance criteria: 10/10 implemented

## [2026-08-12] SKILL-186 subtask 1 — Degraded diagnostic-persistence operator seam
Areas: runtime-application/featuretask (recorder, run loop, status), runtime-application/model, runtime-domain/workflow/taskruntime/model, runtime-ports/persistence, runtime-infra-sqlite/db/telemetry, runtime-cli/featuretask, docs
- `degradeDiagnosticFailure` now emits a content-free `FeatureTaskRuntimeDiagnosticDegradationMeasurement` through `LifecycleTelemetryRepository` beside the durable `FeatureTaskRuntimeDiagnosticSignal`; a throwing sink still appends the signal, returns null, and lets the run proceed
- Measurement pins contract 0.1, `toTelemetryMap()` keys are parity-tested, and the map is on the `@OpenBoundaryMap` allow-list; `repair_turn` is omitted when the failure was not scoped to one turn
- `producerOutput` returns `Found` / `Absent` / `Unreadable(failureClass)` instead of collapsing store refusal into null; the producer-evidence block names "no retained evidence" vs "store refused it" with the typed class, keeping `NEEDS_USER_ACTION` and `childNeverLaunched`
- Status projection reports degraded-signal count plus latest failure class/phase/attempt, or null when none exist; a malformed durable list still loud-fails; CLI `status` surfaces the same object
- Reusable: typed read-result over a nullable that hid two causes; separate-transaction telemetry so a throwing sink cannot roll back the operator signal
- Limitation: `persistDiagnosticSignal`'s best-effort catch stays silent; quarantine `diagnosticIdentity` dangling-pointer is subtask 2
Feature flag: N/A
Acceptance criteria: 10/10 implemented

## [2026-08-12] SKILL-187 subtask 3 — Regression and conformance coverage
Areas: runtime-application/featuretask (integration + privacy tests, RealPhaseOutputValidator fixture), runtime-infra-fs (structural-repair + schema-validator tests), runtime-domain/workflow/taskruntime/model, runtime-contracts
- Synthetic SKILL-16 audit sentinels (nested root verdict, unauthorized observation enum, compound/oversized artifact_ref, missing-delimiter-then-schema) assert exact capture in the authorized repair section and payload-free cues; corrected envelopes advance
- Privacy helpers split surfaces: raw body allowed only inside the untrusted repair section; blocked reasons, durable phase rows, status, telemetry, and normal logs stay payload-free while private diagnostics keep sentinel bytes and value-bearing reasons
- First/schema-valid/incomplete/phase-mismatched launches omit the repair section; truncated/oversized/YAML-unsupported/degraded-observer paths keep Exact vs fallback classification without silent truncation or outcome flips
- Reusable: shared privacy assertions + RealPhaseOutputValidator so gate and consumer tests share one validator without copying real rejected payloads
- Limitation: suite uses synthetic sentinels only; does not widen durable storage or public raw-output readers
Feature flag: N/A
Acceptance criteria: 13/13 implemented

## [2026-08-12] SKILL-187 subtask 2 — Thread rejected response into corrective re-spawn
Areas: runtime-application/featuretask (run loop, observability, prompt composer, validation gate), runtime-infra-fs (phase-output validator adapter), runtime-domain/workflow/taskruntime/model, runtime-contracts
- gateOutput / settleValidatedOutput reject paths build FeatureTaskRuntimeCorrectiveRepairContext from the same capture metadata and diagnostic identity recorded privately; Exact digests prefer capture-boundary sha/bytes
- settleMalformedOutput and semantic retries carry that context through PriorAttemptCorrection into FeatureTaskRuntimePhasePromptComposer; retryable-terminal and incomplete-work paths stay separate and never render the raw repair section
- Schema rejection after successful delimiter repair retains payload-free structuralRepairEvidence on Rejected and sets acceptedAfterStructuralRepair; the retry prompt names syntax-only repair without claiming phase-schema acceptance
- Shared emitFeatureTaskRuntimeEventSafely isolates event-sink / ValidationGateProgress / RunStarted observer faults (CancellationException still propagates) so throws cannot abort retry, block, or completion or leak rejected bodies
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
- Reusable: `WorkflowGitOperations.resetSoftToCommit` for ref-targeted compensating rollback; resume reconciliation resolves checkpoint refs before review prep
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
