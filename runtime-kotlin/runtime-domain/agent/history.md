# Boundary History — runtime-domain

## [2026-08-03] SKILL-159 subtask 2 — Review mode rename and single-prompt inline
Areas: runtime-domain/review, runtime-domain/review/context, runtime-domain/workflow/model, runtime-domain/workflow/taskruntime/model, runtime-application/review, runtime-application/featuretask, runtime-cli, runtime-contracts, orchestration/contracts, orchestration/review-*, skills/bill-code-review, skills/bill-feature*
- `CodeReviewExecutionMode` now means `delegated` = specialist subagent fan-out (and is the default when no `code-review:` token is given), `inline` = exactly one review prompt in the caller's own context; the old external-process meaning of `delegated` is gone
- `ReviewExecutionModePolicy` resolves `auto` to `delegated` on pass one and `inline` on every follow-up/remediation pass; rules renamed to `auto_mode_by_pass_number` / `auto_mode_default` so the resolved mode always reports a deciding rule
- Resolution is one-way: `ResolvedReviewExecutionMode` and the persisted `executed_mode` enums no longer accept `auto`, so a request token can never be mistaken for a resolution
- `goal-subtask-review-state-schema.yaml` bumped 0.2 -> 0.3 with its Kotlin constant and parity test in lockstep; pre-bump records loud-fail at the read seam and are quarantined/regenerated in-band rather than reinterpreted under new semantics
- Pattern followed for semantics-changing renames that reuse an existing wire token: bump the schema so old records fail loudly instead of silently acquiring the new meaning. reusable
- `parallel-review:<agent>` composes with both primary lanes; the parallel lane inherits the primary resolved mode instead of resolving independently
Feature flag: N/A
Acceptance criteria: 7/7 implemented

## [2026-07-27] SKILL-132 subtask 2 — Remove the dormant review pilot
Areas: runtime-domain/review/context, runtime-application/review, runtime-infra-fs tests, docs, orchestration/review-orchestrator
- Re-tracing the review-context slice against main proved it mostly active: ReviewAssignment, ReviewContextPacket, GovernedReviewLaunch, ReviewEvidenceBroker (+ binding and FS implementation), and the review-context schema family are DI-bound on the live parallel-review path and were kept
- Removed the genuinely dormant auto-eligibility residue: ReviewAutoEligibility, the eligibility parameter on ReviewExecutionModePolicy, the constant-returning resolveAutoByEligibility, and ParallelCodeReviewRunner.HIGH_RISK_SIGNAL; resolved review depth is unchanged in every case
- All nine review_context_budget sub-keys reach an active consumer, so no configuration key was removed; strict/tolerant config behavior is now covered in both directions (accepted keys and rejected unsupported keys)
- Pattern followed: prove reachability per candidate from composition roots before deleting, and record the per-candidate verdict in the spec's evidence-ledger.md rather than in code comments. reusable
- Known limitation: runtime-cli CliCodeReviewParallelRuntimeTest has 8 failures reproducible at the pre-branch commit; they come from install-staging add-on omission and are out of this subtask's scope
Feature flag: N/A
Acceptance criteria: 6/6 implemented
