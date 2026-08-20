# Boundary History — runtime-domain

## [2026-08-20] SKILL-201 subtask 3 — Per-lane budget derivation from assignment breadth
Areas: runtime-domain/review/context/model, runtime-application/review
- `ReviewContextBudgetPolicy.deriveLaneEvidenceBytes` scales each lane's cumulative broker `read_evidence` cap from the repo base policy by that lane's share of packet hunk content bytes, floored at `maxEvidenceResultBytes`; lanes owning more diff surface receive a larger allowance than narrow lanes
- `ReviewPreparationService.deriveSpecialistBudget` applies assignment-scaled derivation when composing specialist launches; `ParallelCodeReviewRunner.mergedBudget` sums each specialist's derived cap for the parent broker instead of `base * laneCount`
- `maxLaneEvidenceBytes` now names one thing everywhere: the cumulative broker read_evidence allowance for the bound assignment surface, not a flat per-lane constant independent of ownership breadth
- Pattern: derive enforcement budgets from assignment breadth at preparation; parent fan-out sums specialist-derived caps so parent and lane seams stay aligned. reusable
- Known limits: coverage report and integration-pass prompt changes remain subtask 4
Feature flag: N/A
Acceptance criteria: 6/6 implemented

## [2026-08-20] SKILL-201 subtask 2 — Broker refusal drives lane_evidence_bytes incomplete verdict
Areas: runtime-domain/review/context/model, runtime-infra-fs/infrastructure/fs, runtime-application/review
- `FileSystemReviewEvidenceBroker` records each `lane_evidence_bytes` refusal at the read that triggers it, appending a `commitSha@path` unit to `deniedUnits`; accounting exposes `budgetDimension` and `unreviewedUnits` only when the terminal outcome names that budget kind.
- `ReviewLaneCompletionState.withBrokerEvidenceRefusal` is the completion seam: incomplete disposition, `lane_evidence_bytes` dimension, and the broker's denied-unit list — not a path-sorted greedy tail over the assembled bundle.
- `ParallelCodeReviewRunner` applies broker accounting to bundle completion after a worker run; a successful lane with no evidence refusal stays complete (builds on subtask 1's severed projection).
- Pattern: name unreviewed units only from enforcement (broker reads), never from pre-flight projection or alphabetical bundle order. reusable
- Known limits: per-lane budget derivation remains subtask 3; coverage report and integration-pass prompt changes are subtask 4.
Feature flag: N/A
Acceptance criteria: 6/6 implemented

## [2026-08-20] SKILL-201 subtask 1 — Sever coverage verdict from projected evidence budget
Areas: runtime-domain/review/context/model
- Removed `withLaneEvidenceBudget` from `GovernedReviewLaunch.completionState`; a lane whose worker run succeeded and whose segmentation stayed clean no longer flips to `incomplete` from a pre-flight sum of `entry.hunk.contentBytes` against `maxLaneEvidenceBytes`
- Retired `EVIDENCE_UNREVIEWABLE_SEGMENT_ID` and the projection-only evidence-budget rewrite; `unreviewedUnits` may now come only from withheld or refused reads or a failed run, not from an allowance the broker never attempted to spend
- Segmentation's `unreviewable` path for entries exceeding `maxLaneLaunchBytes` and `asFailedLaneRun` are unchanged; subtask 2 owns wiring broker refusal into completion
- Pattern: keep enforcement (broker reads) and projection (pre-flight size sums) separate at the completion seam so coverage verdicts name only code the worker did not receive. reusable
Feature flag: N/A
Acceptance criteria: 7/7 implemented

## [2026-08-19] Amend-aware checkpoint-identity contract
Areas: runtime-domain/workflow/taskruntime, orchestration/contracts
- `FeatureTaskRuntimeCheckpointIdentity` now requires `checkpointRef` to equal `featureTaskRuntimeCheckpointRefName(issueKey, subtaskId, sequenceNumber)`. The pattern alone only proved shape, so an inline-formatted or stale-subtask ref used to validate and then dedupe under an authority boundary its own record does not own; because the ref is the identity, that drift was invisible on every later read.
- The same rule is written as the `checkpoint_ref_derives_from_its_boundary` coherence check in `feature-task-runtime-checkpoint-identity-schema.yaml`, keeping the YAML contract and the Kotlin `require` in agreement.
- Ledger reads stay fail-whole: one drifted record raises `InvalidWorkflowStateSchemaError` for the entire artifact, never a valid subset.
- Duplicate-ref coverage was rewritten to build the collision from two records sharing a sequence number, since a hand-copied ref can no longer survive construction.
- Contract shape follows the amend model: `sequence_numbers_are_unique` stays (append-only ledger), `commit_shas_are_unique` is gone, ref uniqueness replaces it.
Feature flag: N/A
Acceptance criteria: 8/8 implemented

## [2026-08-18] KMP owns the architecture review area
Areas: runtime-domain/review-context, platform-packs/kmp (code-review), docs
- `kmp` now declares `architecture` in `routing_signals`, `declared_code_review_areas`, `area_metadata`, and `pointers`, with Android-appropriate content at `code-review/bill-kmp-code-review-architecture/`; the area no longer falls through to the backend/desktop-oriented `kotlin` baseline. `kotlin` is byte-unchanged.
- `ReviewOperationPolicy` now threads the assigned-path check into `classifyAbsoluteProhibitions`: a path explicitly assigned to a specialist outranks both the diff-artifact-rediscovery ban and the routing-path prohibitions. Unassigned paths keep the old bans. This unblocks specialists whose own rubric or pack file sits under a routing-shaped path.
- Ownership is pinned by regression tests on both routes (`KmpPlatformPackTest`, `ComposedReviewLaunchPlanTest`): an Android/KMP diff plans `architecture` owned by `kmp`, a backend Kotlin diff still plans it owned by `kotlin`, and the planned area set is unchanged.
- Native-agent bundle and snapshot updated for all six declared `kmp` areas.
- Docs/README wording refreshed alongside; SKILL-198 spec artifacts landed in the same branch.
Feature flag: N/A
Acceptance criteria: 11/11 implemented

## [2026-08-17] Cursor sessions resolve as invoking agent
Areas: runtime-domain/install, runtime-cli/codereview
- Flagless `skill-bill` from a Cursor agent used to miss detection and fall through to the last-resort `codex` lane, so review/goal/task workers launched Codex instead of Cursor.
- `INVOKING_AGENT_CONTEXT_SIGNALS` now maps session markers `CURSOR_AGENT` and `CURSOR_INVOKED_AS` to `cursor`, after Claude and Codex so existing dual-marker precedence is unchanged. `CURSOR_API_KEY` stays a credential, not a session identity.
- `--agent1` / `SKILL_BILL_AGENT` still win; empty environments still default to Codex.
Feature flag: N/A
Acceptance criteria: N/A (defect fix)

## [2026-08-06] SKILL-158 subtask 3 — Single-pass bundled lane review
Areas: runtime-domain/review, runtime-domain/review/context/model, runtime-application/review, runtime-infra-sqlite/db/core, runtime-infra-sqlite/review, runtime-ports/review/model, orchestration/contracts, orchestration/review-orchestrator
- `ReviewLaneBundleAssembly` assembles one bundle per selected lane from the sparse commit-to-lane assignments: assignment-limited hunk bodies only, ordered by commit order then path, with readable commit identity and a stable composition digest. Workers never see the raw complete diff
- Worker launch count now equals selected lane count and is invariant to commit count (1..20 commits, same launches); a multi-segment lane is still one launch
- Oversized bundles split into the fewest size-driven segments that fit — mechanical, never on commit boundaries — each carrying commit identity/order and its own byte accounting; an entry larger than the budget is recorded unreviewable rather than dropped
- Lanes that cannot finish terminate with an explicit incomplete disposition naming unreviewed segments; incomplete stays distinguishable from clean across launch, progress, result, and resume, and prior findings survive. Resume re-runs only incomplete lanes
- Persistence: new lane disposition, bundle composition digest, and per-segment accounting columns with self-healing column ensures for already-migrated databases
- `FORBIDDEN_REDISCOVERY` names the anti-patterns this replaced (per-commit stepping, worker-side relevance re-decision, aggregate-diff restart) so future work does not reintroduce them. reusable
- Finding parsing/merging extended with commit attribution while staying backward compatible with unattributed findings
Feature flag: N/A
Acceptance criteria: 10/10 implemented

## [2026-08-05] SKILL-136 subtask 4 — Controlled vocabularies for review-run attribution
Areas: runtime-domain/review, runtime-domain/review/model, runtime-domain/workflow/taskruntime/model, runtime-ports/review, runtime-application/review, runtime-application/featuretask, runtime-infra-sqlite/db/core, runtime-infra-sqlite/review, runtime-mcp, platform-packs/kmp native-agents, orchestration/contracts
- `ReviewAttributionCanonicalization` resolves routed skill, detected stack, and detected scope at ingestion against known pack skill names, platform slugs, and the closed scope vocabulary; raw prose is preserved beside each canonical id
- Unresolvable values are marked unresolved explicitly instead of bucketed into a default, and resolution failures surface as typed errors via `ReviewAttributionPort` with CLI/MCP parity coverage
- Free-form scope detail moved to its own field so canonical scope stays enumerable; `execution_mode` is now recorded for runs that previously omitted it
- Migration 24 plus `ReviewAttributionBackfillMigration` heals legacy columns and backfills unambiguous rows: canonical routed-skill grouping collapses 24 variants to one row per pack, canonical stack 57 to one per stack, with the unresolved bucket asserted separately
- Pattern followed for vocabulary work: canonicalize once at the ingestion seam in domain, keep raw text durable, and prove the backfill against a copy of the real store behind an env-gated harness (verified by a negative-path probe that the env actually reaches the test). reusable
- `severity`, `confidence`, `disposition`, and `event_type` deliberately untouched — verified by a zero-matching-line branch-diff check
Feature flag: N/A
Acceptance criteria: 9/9 implemented

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
