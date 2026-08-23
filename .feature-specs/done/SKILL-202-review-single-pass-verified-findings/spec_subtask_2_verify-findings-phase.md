# SKILL-202 Subtask 2 — The verify_findings phase

## Intended Outcome

A new `verify_findings` forward phase sits between `review` and `implement_fix`.
It verifies each finding from the single review pass against the subtask's spec
intent projection, settling `findings_verified` when at least one finding
survived and `no_findings_verified` otherwise. The capped fix round moves off
`review` and onto this phase, and a declared entry gate makes `implement_fix`
unreachable unless verification settled `findings_verified`. Verified findings of
any severity, Minor and Nit included, are fixed in that one round; rejected
findings land in the unaddressed-findings ledger with their reason and are never
fixed. Severity stops being control flow and keeps only its reporting role. The
ledger, CLI, status, and telemetry describe dispositions in this same commit.

## Scope

Phase wiring, against the parent spec's wiring inventory:

- `PHASE_VERIFY_FINDINGS` with `stepIds` becoming `preplan, plan, implement,
  audit, review, verify_findings, implement_fix, validate, write_history,
  commit_push, pr`; `stepLabels`; `resumeActions`; `requiredArtifactsByStep`
  (`verify_findings to listOf(PHASE_REVIEW)`, and `implement_fix` gaining
  `PHASE_VERIFY_FINDINGS`); a `PHASE_PROJECTION_MATRIX` entry; a
  `DEFAULT_PHASE_TIERS` entry; a `phaseDirectives` entry; and both step-id enums
  in `workflow-state-schema.yaml` with the contract-version bump decision.
- `OUTPUT_RETRY_PHASES` gains `verify_findings`: it is a producer whose durable
  output must be re-emitted rather than blocked on first invalid output. It is
  not a mutating phase and not generation-scoped.
- Kotlin identifiers avoid `verifyFindings`, which
  `FeatureTaskRuntimeValidationGateCoordinator` already uses for the post-repair
  confirmation gate run inside `validate`.

Topology:

- The capped edge moves: `fromPhaseId = verify_findings`,
  `triggeringVerdict = findings_verified`, `destinationPhaseId = implement_fix`,
  `loopId = review_fix`, `perEdgeCap = 1`, `capExhaustionBehavior = ADVANCE`,
  `capScope = PER_SUBTASK`. `review` is left with no edge of its own; its verdict
  is recorded and never routes.
- A `FeatureTaskRuntimePhaseEntryGate` declares `implement_fix` requires
  `verify_findings` to have settled `findings_verified`. Enforcement is the
  existing shared `entryGateViolation` predicate, which
  `FeatureTaskRuntimeTransitionFunction` applies to the computed target, so the
  gate covers forward advance and cap-exhaustion advance alike and throws
  `FeatureTaskRuntimePhaseOrderViolationError`. No branch in the run loop.
- New verdicts `findings_verified` and `no_findings_verified`, with the
  `verify_findings` branch in `FeatureTaskRuntimeOutputVerification.verdictFor`.

Verification:

- Intent comes from `SpecIntentProjectionResolver`, which already owns
  `SpecIntentProjectionExtractor` and its degradation seams. No second spec
  reader, and no direct extractor call around the resolver.
- Every finding from the pass gets one disposition, `verified` or `rejected`,
  plus a bounded reason. The disposition set is the durable output of the phase.
- The phase never loops, never edits the worktree, and settles once per subtask.
- Rejected findings go to `UnaddressedFindingsLedgerService` with their reason
  and their severity.
- Verified findings are the fix round's input regardless of severity. A pass whose
  only surviving findings are Minor or Nit still settles `findings_verified` and
  still runs the round.
- Resume inside `verify_findings` reuses the in-flight dispositions and never
  re-runs `review`.

Surfaces, in this commit because they describe what this phase persists:

- `skill-bill goal findings --issue-key <KEY>` shows disposition, reason, and
  severity per entry.
- Goal status and watch name `verify_findings` when active and describe
  verify-then-fix-once. `IdeStatusProjector`'s progress denominator absorbs the
  added phase.
- Lifecycle telemetry for the phase and the round carries disposition counts and
  the cap-exhaustion outcome, with no path-bearing detail in compact surfaces.
  `phase_id` is a free string in the telemetry schema, so no schema change is
  needed for the phase itself.
- `../../../skills/bill-feature-task-runtime/content.md` and
  `../../../skills/bill-feature-goal/content.md` describe the phase, the gate, the
  severity-independent round, and the accepted trade-off that an unfixed verified
  finding reaches `validate`. Regenerate the governed `SKILL.md` outputs.

## Acceptance Criteria

1. `verify_findings` is a declared forward phase between `review` and `implement_fix`, fully wired: projection-matrix entry, tier default, prompt directive, labels, resume action, dependency set, and both workflow-state step-id enums.
2. Every finding from the single review pass gets one disposition, `verified` or `rejected`, plus a bounded reason, derived against the subtask's spec intent projection.
3. Intent is resolved through `SpecIntentProjectionResolver`. No second spec reader is introduced.
4. `verify_findings` settles `findings_verified` when at least one finding survived and `no_findings_verified` otherwise, settles once per subtask, never loops, and never edits the worktree.
5. The capped round is driven from `verify_findings`, not from `review`. `review` has no declared edge and its verdict never routes.
6. `implement_fix` is unreachable unless `verify_findings` settled `findings_verified`, enforced by a declared phase entry gate that loud-fails through the shared entry-gate predicate rather than by a branch in the run loop.
7. A verified finding is fixed regardless of severity. A pass whose surviving findings are only Minor or Nit still runs the round.
8. A rejected finding is never fixed and appears in the unaddressed-findings ledger with its rejection reason and severity, retrievable through `skill-bill goal findings --issue-key <KEY>`.
9. The run advances to `validate` after the round regardless of whether the round resolved every verified finding.
10. Goal status and watch name `verify_findings` when that phase is active and never describe a second review pass.
11. Telemetry for `verify_findings` and the round carries disposition counts and the cap-exhaustion outcome without path-bearing detail in compact surfaces.
12. Resume lands correctly when a run is interrupted inside `verify_findings` or inside the round, without minting a second verification pass or a second round.
13. Durable verification records that this change invalidates loud-fail with a named error rather than being coerced.
14. `../../../skills/bill-feature-task-runtime/content.md` and `../../../skills/bill-feature-goal/content.md` describe one review pass, per-finding verification, at most one fix round, then validate, including the accepted trade-off.
15. `(cd runtime-kotlin && ./gradlew check --continue)` passes.

## Non-Goals

- Path-scoped boundary memory for verification (subtask 3). Verification is
  intent-only here, which stays its permanent fallback.
- Reintroducing any pause, re-review, or second round.
- Changing review lane assembly, evidence brokering, or the review packet contract.
- Consulting git history, PR comments, or other subtasks' ledgers during
  verification.
- Changing planning's boundary discovery or caps.

## Dependency Notes

Depends on subtask 1 for the collapsed topology: the single capped round this
phase takes ownership of, the absence of `plan_fix`, and the removal of the pause
and churn paths a gated one-shot round cannot coexist with.

## Validation Strategy

Add a per-disposition test pair: a verified finding enqueues the round, a
rejected finding appears in the ledger with its reason and does not. Add an
entry-gate test that entering `implement_fix` after `no_findings_verified` throws
`FeatureTaskRuntimePhaseOrderViolationError`. Add a test that a Minor-only
surviving set still runs the round, which is the rule most likely to regress
silently. Add a resume test interrupted mid-verification asserting the
dispositions are reused and `review` does not re-run. Assert
`SpecIntentProjectionResolver` is the only intent source. Update the pinned phase
tests and the MCP golden. Add CLI output coverage for `goal findings`
dispositions. Run the repository validation gate.

## Next Path

Proceed to subtask 3 to give verification path-scoped boundary memory.
