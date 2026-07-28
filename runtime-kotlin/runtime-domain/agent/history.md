# Boundary History — runtime-domain

## [2026-07-27] SKILL-132 subtask 2 — Remove the dormant review pilot
Areas: runtime-domain/review/context, runtime-application/review, runtime-infra-fs tests, docs, orchestration/review-orchestrator
- Re-tracing the review-context slice against main proved it mostly active: ReviewAssignment, ReviewContextPacket, GovernedReviewLaunch, ReviewEvidenceBroker (+ binding and FS implementation), and the review-context schema family are DI-bound on the live parallel-review path and were kept
- Removed the genuinely dormant auto-eligibility residue: ReviewAutoEligibility, the eligibility parameter on ReviewExecutionModePolicy, the constant-returning resolveAutoByEligibility, and ParallelCodeReviewRunner.HIGH_RISK_SIGNAL; resolved review depth is unchanged in every case
- All nine review_context_budget sub-keys reach an active consumer, so no configuration key was removed; strict/tolerant config behavior is now covered in both directions (accepted keys and rejected unsupported keys)
- Pattern followed: prove reachability per candidate from composition roots before deleting, and record the per-candidate verdict in the spec's evidence-ledger.md rather than in code comments. reusable
- Known limitation: runtime-cli CliCodeReviewParallelRuntimeTest has 8 failures reproducible at the pre-branch commit; they come from install-staging add-on omission and are out of this subtask's scope
Feature flag: N/A
Acceptance criteria: 6/6 implemented
