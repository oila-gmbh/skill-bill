# Review Boundary History

## [2026-09-11] Uncommitted standalone packet
Areas: application/review, runtime-cli/codereview
- `UNCOMMITTED` is a working-tree packet against HEAD, not a commit range and not the durable implement base.
- Feature-task review stays last-commit `BRANCH`; this scope is standalone-only.
Feature flag: N/A
Acceptance criteria: N/A (hotfix)


## [2026-09-11] SKILL-237 subtask 1 — Inline review coverage continuation
Areas: application/review, runtime-infra-fs/{infrastructure/fs,launcher/review}, runtime-ports/review
- Inline review now retains the governed broker and review_run_id across slices, merging findings and accounting until coverage completes or a bounded terminal condition occurs.
- Discovery omits already-delivered selectors while preserving full-list cursor positions; endpoint unbind tears down transport without prematurely finishing delivery.
- Reusable: the continuation seam, delivered-selector catalog filtering, and transport unbind lifecycle preserve coverage truth across worker exits.
- Limitation: delegated single-use launches remain unchanged; continuation stops on zero progress, budget exhaustion, timeout, spawn failure, interrupt, or other non-coverage failure.
Feature flag: N/A
Acceptance criteria: 7/7 implemented

## [2026-09-10] Inline reduced-depth vs full evidence wording
Areas: application/review, skills/bill-code-review-inline, skills/bill-code-review, orchestration/review-orchestrator, docs
- Parent prompt and inline skill now state that reduced depth is judgment-only (one merged checklist, one walk) and does not authorize sampling, a page budget, or early stop.
- Broker delivery stays mandatory: workspace git/shell/Grep/Read do not count; `verdict: approved` is forbidden while required units remain undelivered.
- Docs and playbook drop “bounded budget” phrasing that invited partial evidence work.
Feature flag: N/A
Acceptance criteria: prompt and skill contract clarified; runtime coverage gate unchanged.

## [2026-09-10] SKILL-236 subtask 1: Single-session inline evidence and settlement
Areas: application/review, runtime-infra-fs/launcher/review
- Inline mode launches one parent worker session. Evidence stays broker-paged inside that session; the runtime no longer fans a large diff into sequential chunk agent processes.
- Prelaunch expansions authorize against the parent broker assignment. Delegated launches with null visibleTargetPaths still authorize every prelaunch expansion.
- handleFrame holds deliveryLock only for closing-state checks and pendingDeliveries. Broker I/O runs unlocked so close() can drain pending delivery confirmations.
- Reusable: parentEvidenceBroker is the assignment-identity seam for the single inline parent launch.
- Limitation: AC-007 still needs ./install.sh refresh and installed CLI smoke.
Feature flag: N/A
Acceptance criteria: 6/7 implemented; AC-007 external prerequisites remain unconfirmed.

## [2026-09-09] SKILL-236 subtask 1: Governed review evidence recovery
Areas: application/review, application/featuretask, runtime-cli/codereview, runtime-domain/review, runtime-ports/review, runtime-infra-fs, runtime-mcp/review, orchestration, platform-packs, skills/bill-code-review-inline, docs
- Workers discover assigned targets and authorized expansions through bounded, paginated requests on the existing two governed operations. Parent prompts do not carry complete path inventories.
- Prelaunch expansions bind to final broker assignments and retain source-lane provenance across merged rubrics. Whole-file expansion delivery remains separate from required delta delivery.
- Coverage tracks delivered evidence units. Missing required units block approval; recoverable refusals can settle after complete delivery. Completion settlement preserves failed worker outcomes when incomplete accounting also records a terminal outcome.
- Bridge-local rejections forward only evidence/refused, without rejected tool names or arguments. Endpoint-local rejections record malformed requests; each rejection consumes the existing request budget exactly once, including codec failures.
- Endpoint shutdown rejects new reads and expansions, then allows up to one second for pending delivery acknowledgments before ending the session. Receipt tracking clears only after the acknowledgment response flushes.
- Checkpoint capture retains regular-file, absent, and unavailable identities without following links. Preparation compares snapshots before launch; index identity and SHA-256 checks reject drift. Unrelated symlinks and populated submodules no longer abort capture, and unavailable entries stay unreadable after replacement.
- Expansion admission checks regular-file availability at the bound coordinate before changing authorization, catalogs, or coverage obligations, including trusted records and retries. Unavailable whole-file and exact-selector or path-only delta reads return accounted evidence_unavailable refusals. Empty files remain deliverable; deleted-file deltas remain independent of expansion admission.
- Required rubric companions resolve through pack metadata and governed composition. Missing required guidance fails before worker launch.
- Reusable: governed discovery and delivery accounting across the broker, MCP transport, and review completion gate. Refusals remain bounded and deliver no evidence; corrected requests can recover until budget exhaustion blocks discovery and reads. Receipt replay, bridge initialization, tool listing, and notifications remain uncharged.
- Limitation: AC-007 still requires external artifact refresh and installed CLI smoke verification for commit scope, paired revisions with --diff-file, and explicit expansions, with unavailable providers reported.
Feature flag: N/A
Acceptance criteria: 6/7 implemented; AC-007 regression implementation is present, but its external prerequisites remain unconfirmed.

## [2026-08-28] SKILL-218 subtask 1 — Cursor delegated parent fan-out
Areas: application/review, runtime-ports/review, infra-fs, runtime-core/di, orchestration/review-delegation
- Cursor delegated parent prompt now names every selected native specialist with one `/name` line in a single parallel-launch instruction.
- Before that parent starts, staging copies each selected specialist from the managed native-agent inventory cache into `{reviewLaunchDirectory}/.cursor/agents/`.
- Claude delegated prompts stay free of Cursor `/name` syntax and keep Agent/Task when reviewFanOut is true; Codex launch is unchanged; Cursor inline does not fan out or stage unused-lane agents.
- Missing, dangling, or undeclared selected specialists still fail MissingInstalledNativeAgentError before the parent starts; the run does not fall back to inline.
- PLAYBOOK Cursor section now describes this runtime launch: named `/name` plus project agents in the isolated evidence-endpoint workspace. No live CLI canary.
- Pattern: ReviewLaunchAgentStagingPort plus FileSystemReviewLaunchAgentStaging injected into ParallelCodeReviewRunner; staging and prompt fan-out are Cursor+DELEGATED only. reusable
- Known limit: this does not prove a live `agent --print` parent can spawn the named lanes; sibling AgentRun fan-out is the follow-up if that still fails.
Feature flag: N/A
Acceptance criteria: 6/6 implemented

## [2026-08-24] SKILL-207 subtask 2 — claim verification as phase call and skill alignment
Areas: runtime-application/review, runtime-application/review/model, skills/bill-code-review, skills/bill-code-review-inline
- Claim verification now receives review prose through the `input` + `requestedAction` phase envelope; optional parsed findings enrich per-claim checks instead of gating launch.
- Empty admitted finding lists still launch one prose verification pass when review output is present; blank output remains a recorded skip.
- Verification output is preserved as an `AgentPhaseOutput` for later enrichment, and prompts treat the prose blob as authoritative.
- Pattern: best-effort register shape with runtime-owned launch, evidence, and persistence; reusable for later phase I/O envelope adoption.
- Known limitation: structured verdicts/citations remain optional enrichment, not a typed replacement for verifier prose.
Feature flag: N/A
Acceptance criteria: 5/5 implemented

## [2026-08-19] SKILL-196 subtask 3 — exclude fallback lanes per area
Areas: application/review, domain/review/plan
- `ReviewPerAreaFallbackExclusion.partition` runs after per-root lane assembly and before cross-root reconciliation; a fallback pack contributes a lane for area A only when no native routed pack in the assembled plan declares A.
- Fallback owner resolves through `ReviewFallbackResolver`; with `generic` + `kotlin` + `kmp` on a Kotlin/Android diff the plan carries exactly one lane per area and zero `generic` rows.
- Excluded fallback lanes fold `ownedPaths` and `changedHunkIds` into the winning native lane via `ReviewCrossRootLaneReconciliation`'s `excludedFallbackLanesByArea` input — claim transfer only, no rubric composition (rationale in `runtime-domain/agent/decisions.md`).
- Pattern followed: domain-owned `ReviewFallbackExclusionPartition` consumed by `ParallelCodeReviewRunner`; `ReviewStackRouting` scoring and the per-file fallback branch untouched.
- Regression: the 13-lane cross-stack fixture resolves to one lane per area; coverage ledger and `unreviewedSegmentIds` stay unchanged when a redundant fallback lane drops.
Feature flag: N/A
Acceptance criteria: 11/11 implemented

## [2026-08-19] SKILL-196 subtask 2 — reconcile lanes across routed roots by area
Areas: application/review, domain/review/plan
- Cross-root reconciliation now keys on `lane.area` instead of `skillName`; the delegated launch path no longer uses `groupBy { it.skillName }`, so `kotlin` and `kmp` lanes for the same area collapse to one owner.
- `ReviewCrossRootLaneReconciliation` picks the nearest composition depth across routed roots via `compositionDepthOffsets`, raises `AmbiguousLaneOwnershipError` when two native packs tie at that depth, and sorts inputs deterministically before selection so manifest and root order do not affect owner or `orderIndex`.
- The surviving lane merges reconciled inputs: `required` is the disjunction, `ownedPaths` the sorted distinct union, `changedHunkIds` the distinct union; a dropped duplicate lane's paths stay on the winner so coverage does not hole.
- Pattern followed: domain-owned reconciliation primitive (`ReviewRootLanes`, `ReviewReconciledLane`) consumed by `ParallelCodeReviewRunner`; `flatten` and its in-plan winner selection untouched.
- Known limits: fallback `generic` lanes that survive because no native pack ties at a nearer depth remain until subtask 3.
Feature flag: N/A
Acceptance criteria: 11/11 implemented

## [2026-08-19] SKILL-196 subtask 1 — scope delegated lane plan to composed areas
Areas: application/review, infra-fs/infrastructure/fs, infra-fs/nativeagent/validation, infra-fs/scaffold/platformpack, core/architecture (test)
- Every launch/validation call site that unioned `declaredCodeReviewAreas` across all installed manifests now calls `ReviewLaunchPlanPolicy.composedAreas(slug, manifests)` per routed root, so a routed pack's plan only carries areas its own composition declares.
- `composedAreas` is the single seam for "which areas belong to this pack"; the launch path and `FileSystemReviewAttribution.composedLaunchPlan` are now pinned to the same set (parity test in `ParallelReviewComposedAreaPlanTest`).
- Pattern followed: caller fix only. `flatten`, its per-area winner selection, and `AmbiguousLaneOwnershipError` were left untouched.
- `RuntimeArchitectureTest` gained a guard that fails if the union shape reappears anywhere under `runtime-*/src/main` (reusable: extend it when a new area-set seam lands).
- Known limits: cross-root lane duplicates still survive the `groupBy { it.skillName }` merge (subtask 2) and fallback packs still contribute lanes (subtask 3).
Feature flag: N/A
Acceptance criteria: 6/6 implemented
