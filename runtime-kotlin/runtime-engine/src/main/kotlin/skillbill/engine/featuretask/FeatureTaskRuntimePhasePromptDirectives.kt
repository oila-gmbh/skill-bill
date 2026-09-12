package skillbill.engine.featuretask

import skillbill.contracts.workflow.FEATURE_TASK_RUNTIME_REPAIR_RECEIPT_CONTRACT_VERSION
import skillbill.engine.featuretask.model.FeatureTaskRuntimeImplementationContinuation
import skillbill.goalrunner.subtaskreview.FeatureTaskRuntimeVerificationSignalKeys
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewInput
import skillbill.review.context.model.CodeReviewExecutionMode
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeCorrectiveRepairContext
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePriorGapMemory
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePriorReviewContext
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairLedger

fun implementationContinuationDirective(
  phaseId: String,
  continuation: FeatureTaskRuntimeImplementationContinuation?,
): String {
  if (continuation == null || continuation.phaseId != phaseId) return ""
  val segments = continuation.priorValueSegments.withIndex().joinToString("\n\n") { (index, value) ->
    "Segment ${index + 1} value:\n$value"
  }
  val prompt = continuation.latestPrompt?.let { "Latest optional prompt: $it" } ?: "No optional prompt recorded."
  val disposition = continuation.failureDisposition ?: "none"
  return """
    ## Continue this implementation — segment ${continuation.segmentNumber}
    A prior segment of this same implementation ran and did real work. It was NOT rejected and its
    output was NOT malformed: continue from where it stopped. Do not restart the implementation and do
    not re-apply changes already present — the mutating-phase idempotency contract still governs.

    Prior stuffed value segments:
    $segments

    $prompt
    Failure disposition from the latest segment: $disposition

    Emit a new non-blank value string carrying your updated implementation_receipt JSON stuffed inside
    value.
  """.trimIndent()
}

class PriorAttemptCorrection private constructor(
  private val reason: String,
  private val kind: Kind,
  val correctiveRepairContext: FeatureTaskRuntimeCorrectiveRepairContext? = null,
) {
  internal enum class Kind { SCHEMA_GATE, RETRYABLE_TERMINAL, FINDING_COVERAGE }

  val schemaGateReason: String? get() = reason.takeIf { kind == Kind.SCHEMA_GATE }
  val retryableTerminalReason: String? get() = reason.takeIf { kind == Kind.RETRYABLE_TERMINAL }
  val findingCoverageReason: String? get() = reason.takeIf { kind == Kind.FINDING_COVERAGE }

  init {
    require(correctiveRepairContext == null || kind == Kind.SCHEMA_GATE) {
      "PriorAttemptCorrection: corrective repair context belongs only to schema-gate retries, " +
        "not retryable-terminal envelopes or finding-coverage continuations."
    }
  }

  companion object {
    fun schemaGate(
      reason: String,
      correctiveRepairContext: FeatureTaskRuntimeCorrectiveRepairContext? = null,
    ): PriorAttemptCorrection =
      PriorAttemptCorrection(reason, Kind.SCHEMA_GATE, correctiveRepairContext = correctiveRepairContext)

    fun retryableTerminal(reason: String): PriorAttemptCorrection =
      PriorAttemptCorrection(reason, Kind.RETRYABLE_TERMINAL, correctiveRepairContext = null)

    fun unaccountedFindings(reason: String): PriorAttemptCorrection =
      PriorAttemptCorrection(reason, Kind.FINDING_COVERAGE, correctiveRepairContext = null)
  }
}

fun findingCoverageDirective(priorFindingCoverage: String?): String {
  if (priorFindingCoverage.isNullOrBlank()) return ""
  return """
    ## Findings still owed — continue this round
    Your previous attempt at this phase emitted a VALID repair receipt. It was NOT rejected and its
    format was NOT wrong. It was incomplete:
    $priorFindingCoverage
    Keep the entries you already wrote and add the missing ones. Do the repair work first, then write
    the entry that describes it. Repeating the same receipt without accounting for the named findings
    blocks the run.
  """.trimIndent()
}

fun terminalRetryDirective(priorTerminalFailure: String?): String {
  if (priorTerminalFailure.isNullOrBlank()) return ""
  return """
    ## Previous attempt reported a retryable block — try again
    Your previous attempt at this phase emitted valid output that reported the phase could not finish.
    It was NOT rejected and its format was NOT wrong. Reported reason:
    $priorTerminalFailure
    Re-attempt the phase against the current repository state. If the same obstacle still stands and you
    cannot clear it, report it again with the disposition that matches it rather than restating it in a
    different shape; a re-emitted block with no new attempt behind it will exhaust this phase's budget.
  """.trimIndent()
}

internal data class ReviewExecutionDirectiveInputs(
  val codeReviewMode: CodeReviewExecutionMode,
  val goalSubtaskReviewInput: GoalSubtaskReviewInput?,
  val reviewPassNumber: Int?,
  val resolvedReviewTier: CodeReviewExecutionMode?,
  val reviewDecidingRule: String?,
  val baselineUntrackedPaths: List<String> = emptyList(),
  val repairLedger: FeatureTaskRuntimeRepairLedger? = null,
  val priorReviewContext: FeatureTaskRuntimePriorReviewContext? = null,
)

fun commitExclusionDirective(phaseId: String, issueKey: String): String {
  if (phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_COMMIT_PUSH) {
    return ""
  }
  return """
    ## Feature-spec commit exclusion
    Feature specs are workflow inputs, not implementation output. Never list any `.feature-specs/`
    path in `commit_push_result.changed_paths` — especially this feature's
    `.feature-specs/$issueKey-*` (or `.feature-specs/$issueKey/`) tree, including the parent spec,
    every subtask spec, and `decomposition-manifest.yaml`. The runtime stages every dirty non-ignored
    implementation path in the worktree and never stages `.feature-specs/`. Leave `.feature-specs/`
    dirty locally if it changed. Never amend, reset, or restage a commit this runtime does not own, including a
    commit a human operator authored: leave those alone.
  """.trimIndent()
}

fun goalContinuationDirective(phaseId: String, suppressDecomposition: Boolean): String {
  if (!suppressDecomposition || phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN) {
    return ""
  }
  return """
    ## Goal-continuation planning constraint
    This run is already executing one governed decomposed subtask. Do not propose or emit a new
    decomposition package in the plan phase. Produce implementable planning value for the current spec
    (executable_plan JSON stuffed inside value); never emit produced_outputs.decomposition_package.
    Never include installer, uninstall, or
    install-sync commands in the plan: do not plan to run
    `./install.sh`, `./uninstall.sh`, `skill-bill install`, `skill-bill install apply`, or any
    equivalent install refresh inside a goal-continuation child. The plan phase defines how future
    acceptance work will be implemented and validated; it does not require that work to have already
    happened. Never block planning merely because a later implementation or validation action is not
    yet complete. A blocked plan requires a genuinely missing input or an irreconcilable constraint
    that prevents an implementable plan from being produced.
  """.trimIndent()
}

const val AUDIT_READONLY_EVIDENCE_SENTENCE: String =
  "All evidence is read-only repository facts: never run a build, a test, or any " +
    "other command as audit evidence; validation owns test execution and failures."

private const val AUDIT_GATE_PROOF_EVIDENCE_SENTENCE: String =
  "Prefer read-only repository facts. When Validation ownership grants a gate-proof exception for " +
    "acceptance criteria that require mechanical proof, run only those allowed commands and inventory " +
    "every remaining finding for that proof in the gap note (count plus rule/location ids — never a " +
    "sample batch). Validation still owns suite test execution and the final lifecycle gate."

private const val IMPLEMENT_READONLY_REPAIR_SENTENCE: String =
  "Repair evidence is read-only repository " +
    "facts: do not run builds or tests here."

private const val IMPLEMENT_GATE_PROOF_REPAIR_SENTENCE: String =
  "When Validation ownership grants a gate-proof exception for this audit-gap remediation, run only " +
    "the allowed gate commands to clear every finding from the audit inventory in this invocation; " +
    "re-run that same gate once at the end to confirm. Otherwise treat repair evidence as read-only " +
    "repository facts and do not run builds or tests here."

val phaseDirectives: Map<String, String> = mapOf(
  FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PREPLAN to
    "Produce the scaled pre-planning digest for the resolved feature size. Do not modify " +
    "repository files during this phase. Emit produced_outputs with a non-blank value string " +
    "carrying the preplanning_digest JSON (same fields as before, stuffed inside value); optional " +
    "prompt may add a short directive when non-blank. Do not forward the complete preplan envelope, " +
    "a generic summary, or progress diagnostics.",
  FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN to
    "Produce an ordered implementation plan that satisfies every acceptance criterion, using the " +
    "upstream preplan value as planning context (structured prose: interpret the stuffed digest " +
    "JSON). Do not modify repository files during this phase. Emit produced_outputs with a non-blank " +
    "value string carrying the executable_plan JSON (same fields as before, stuffed inside value); " +
    "optional prompt may add a short directive when non-blank. Do not forward the complete plan " +
    "envelope, a generic summary, or progress diagnostics.",
  FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT to
    "Reconcile the repository to the intended state the upstream plan value describes: read and " +
    "interpret the stuffed executable_plan JSON, make the changes it specifies, treating any " +
    "already-applied change as a no-op. See the mutating-phase idempotency contract below. Emit " +
    "produced_outputs with a non-blank value string carrying the implementation_receipt JSON (same " +
    "fields as before, stuffed inside value): completed_task_ids, normalized changed_paths, " +
    "tests_added, tests_updated, deviations, unresolved_items, reconciliation_evidence, and " +
    "reconciled_state. repository_checkpoint is runtime-owned: omit it and never invent a " +
    "fingerprint. Every receipt field is a bounded summary, not a transcript. When the briefing " +
    "carries audit prose from the latest audit value, reuse its immutable initial preplan and plan " +
    "outputs and change only what that audit value requires. " +
    IMPLEMENT_READONLY_REPAIR_SENTENCE,
  FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT_FIX to
    "Address every finding verify_findings carried on the CURRENT working tree as " +
    "incremental reconciliation. Every carried finding — Blocker, Major, Minor, and Nit — is in " +
    "scope; specialist narratives and raw review output are not, and a finding verification " +
    "refuted is not carried at all: do not fix it and do not file an entry for it. Do not re-apply " +
    "the plan from scratch or expand scope beyond the carried findings. Treat any fix already present " +
    "as a no-op. See the mutating-phase idempotency contract below. Emit " +
    "produced_outputs.repair_receipt with contract_version " +
    "\"$FEATURE_TASK_RUNTIME_REPAIR_RECEIPT_CONTRACT_VERSION\" and exactly one entry per carried " +
    "finding with finding_id (aliases finding_ref, id, and ref are accepted) and outcome " +
    "(addressed, no_edit_required, or attempted_unresolved). Coverage matches on finding_id and " +
    "outcome alone. Optional decoration — constructs, intent, severity, label, text, " +
    "no_edit_reason, and unresolved_reason — may accompany each entry but does not gate settlement. " +
    "A legitimately unedited finding still needs its no_edit_required entry, and a finding you " +
    "could not close needs its attempted_unresolved entry, which buys it one more attempt before it " +
    "goes to an operator. Leaving a *carried* finding out is never an outcome: the round is sent " +
    "back for it. A refuted finding is the one exception, because it was never carried.",
  FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW to
    "Review the last commit against its first parent in this repository. Fix every Blocker and Major " +
    "finding in this same session before you emit. Emit remaining findings and a verdict of approved or " +
    "changes_requested. Do not run bill-code-review or launch review subagents. Criterion-gap detection " +
    "remains exclusive to the audit phase. Do not run `./gradlew check`, the pack collect-all gate, or " +
    "`bill-code-check`; validate owns those.",
  FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VERIFY_FINDINGS to
    "Verify every finding from the single preceding review pass against the subtask spec intent " +
    "projection and the scoped boundary-memory catalog in the briefing. Each finding receives a " +
    "titles-only heading catalog for boundaries that own its paths; select relevant heading_id " +
    "values in selected_boundary_headings and set boundary_context_unavailable when no eligible " +
    "boundary owns the finding paths. Emit envelope verdict findings_verified or " +
    "no_findings_verified and " +
    "produced_outputs.${FeatureTaskRuntimeVerificationSignalKeys.FINDINGS_VERIFICATION_DISPOSITIONS} " +
    "with exactly one {finding_id, disposition} entry per review finding (verified or rejected). " +
    "Optional decoration — reason, severity, location, message, selected_boundary_headings, and " +
    "boundary_context_unavailable — may support the disposition but does not gate settlement. Do " +
    "not edit the worktree.",
  FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT to
    "Assess every acceptance criterion against the repository at the runtime-owned checkpoint. " +
    "Treat implementation receipts as claims that require repository evidence. Record all gaps " +
    "before repairing. For each gap, identify the failing production behavior, its cause, the " +
    "authorized paths, and the observable evidence that will demonstrate closure. Inspect callers, " +
    "bindings and contracts before choosing the repair. Apply the authorized repairs in this session " +
    "and re-audit every criterion against the retained post-repair checkpoint. " +
    "A changed file or a repair receipt does not prove an acceptance criterion is satisfied. " +
    "Preserve validation obligations for the downstream validation phase. " +
    "When a criterion explicitly requires tests, inspect the required test behavior in source and " +
    "record execution as pending validation when this phase cannot run it. " +
    "Settle with the exact durable final assessment only after its stage is satisfied. " +
    "A paused stage or a rejected stage request ends repair work. Report the blocking reason and " +
    "wait for runtime recovery or a fresh operator decision; do not edit code to bypass the protocol. " +
    AUDIT_READONLY_EVIDENCE_SENTENCE,
  FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE to RUNTIME_OWNED_VALIDATE_PHASE_TASK,
  FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_WRITE_HISTORY to
    "Invoke bill-boundary-history inline and apply its write/skip rules for the implemented " +
    "runtime change. Emit a bounded history_result containing changed_paths and decisions_recorded " +
    "alongside whether history was written or skipped; do not forward implementation or validation reports.",
  FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_COMMIT_PUSH to
    "Run no git command in this phase. The runtime stages, commits, and pushes the subtask on the " +
    "resolved feature branch from what you emit here. Emit commit_push_result with `message` (the " +
    "commit subject describing the implemented, reviewed, audited, validated, and history-updated " +
    "outcome) and optional `changed_paths` (advisory). The runtime stages every dirty non-ignored " +
    "worktree path except `.feature-specs/` — including validate repairs and concurrent operator " +
    "edits — so an incomplete list cannot strand deliverable dirt. A missing or blank `message` " +
    "blocks the subtask rather than publishing a provisional subject. Do not emit commit_sha: the " +
    "runtime captures it after the " +
    "commit. If goal-continuation suppresses PR, this successful phase is the terminal success " +
    "signal for the goal subtask.",
  FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PR to
    "Invoke bill-pr-description, honor any repo-native PR template except its checklist, and " +
    "generate a title in the form `[<issue key>] <descriptive title>` that explains the user-visible " +
    "outcome rather than copying a branch slug; create or reuse the open pull request for the branch " +
    "idempotently, and emit pr_result with the PR URL/number, title, and whether a new PR was created.",
)

fun auditPhaseTaskDirective(
  memory: FeatureTaskRuntimePriorGapMemory?,
  acceptanceCriteria: List<String> = emptyList(),
): String {
  val evidence = if (acceptanceCriteriaRequireGateProof(acceptanceCriteria)) {
    AUDIT_GATE_PROOF_EVIDENCE_SENTENCE
  } else {
    AUDIT_READONLY_EVIDENCE_SENTENCE
  }
  val task = phaseDirectives.getValue(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT)
    .removeSuffix(AUDIT_READONLY_EVIDENCE_SENTENCE)
  val history = if (memory == null) {
    "Read restored_cycle_evidence when present before diagnosing or repairing. "
  } else {
    "Account for prior_audit_values and restored_cycle_evidence. For each recurring gap, name " +
      "the earlier repair, why it failed, and what new evidence supports a different repair. "
  }
  return task + history + evidence
}

fun auditRepairCycleDirective(phaseId: String): String {
  if (phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT) return ""
  return """
    ## Audit-repair cycle
    This audit owns diagnosis, authorized repair, checkpoint capture, and final re-audit in this same
    session. Use the durable feature_task_audit_stage channel described below. First record a diagnosis
    covering every acceptance criterion, including repair_id and repair_guidance for every unmet criterion.
    Do not authorize repair until the repository still matches that diagnosis checkpoint. Apply only the
    authorized repairs, record each outcome, attach the post-repair checkpoint, and submit a complete
    final audit against that checkpoint. Advance only after the durable stage response reports satisfied.
    If repair cannot proceed, record a paused stage with the unresolved criteria and operator reason.
    After a paused acknowledgement or stage rejection, stop mutations and emit a blocked outcome.
    Never retry a failed protocol operation by inventing another cycle, checkpoint, or request identity.
    For every recurring gap, identify the earlier repair, why it failed, and the new evidence for the
    next repair. File changes alone do not show progress. Preserve every pending validation obligation.
    A final response claiming gaps is not a substitute for durable stage evidence.
  """.trimIndent()
}

fun implementPhaseTaskDirective(auditGapImplement: Boolean, acceptanceCriteria: List<String>): String {
  val base = phaseDirectives.getValue(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT)
  if (
    auditGapImplement &&
    acceptanceCriteriaRequireGateProof(acceptanceCriteria)
  ) {
    return base.replace(IMPLEMENT_READONLY_REPAIR_SENTENCE, IMPLEMENT_GATE_PROOF_REPAIR_SENTENCE)
  }
  return base
}
