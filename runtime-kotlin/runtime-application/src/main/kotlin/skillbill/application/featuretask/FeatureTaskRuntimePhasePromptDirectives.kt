package skillbill.application.featuretask

import skillbill.application.featuretask.model.FeatureTaskRuntimeImplementationContinuation
import skillbill.contracts.workflow.FEATURE_TASK_RUNTIME_REPAIR_RECEIPT_CONTRACT_VERSION
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewInput
import skillbill.workflow.goal.model.CodeReviewExecutionMode
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeCorrectiveRepairContext
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePriorGapMemory
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePriorReviewContext
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairLedger

// Phase-scoped prompt directives and the per-phase task directive table, split out of
// FeatureTaskRuntimePhasePromptComposer so the composer object stays within its size budget.
// Validate Task-line specialization lives in FeatureTaskRuntimePhasePromptValidateDirectives.

internal fun implementationContinuationDirective(
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

/**
 * Why the previous attempt at a phase must be corrected, kept typed rather than as a bare string.
 *
 * A schema-gate rejection and a retryable `blocked`/`failed` envelope both re-enter the same bounded
 * semantic budget, but they are different events and must not be prompted, reported or dispositioned
 * alike: only the first is a rejection. Threading one nullable string made them indistinguishable at
 * the composer seam, which is how a schema-valid terminal envelope came to be told it was rejected.
 *
 * [correctiveRepairContext] is schema-gate only: the authorized bounded repair projection of the
 * rejected response. Retryable-terminal and incomplete-work paths must not carry it, so they never
 * receive a raw-output repair section.
 */
internal class PriorAttemptCorrection private constructor(
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

/**
 * Emitted when the prior attempt's repair receipt left carried review findings out.
 *
 * Deliberately not the schema-correction directive: the receipt validated. Telling its author the
 * output was rejected invites a re-serialization of the same two entries, which is exactly what has
 * to stop happening — what is missing is repair work on the named findings, not a better document.
 */
internal fun findingCoverageDirective(priorFindingCoverage: String?): String {
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

/**
 * Emitted when the prior attempt ended in a retryable `blocked` or `failed` envelope.
 *
 * Deliberately not the schema-correction directive: that envelope validated. Telling its author the
 * output was rejected and must be re-emitted describes an event that did not happen and invites a
 * cosmetic re-serialization of the same blocked state instead of an attempt at the blocker itself.
 */
internal fun terminalRetryDirective(priorTerminalFailure: String?): String {
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

/**
 * Everything the review-execution directive needs to state the run's review depth and scope. These
 * travel together from [FeatureTaskRuntimePhasePromptComposer.compose] and are only ever read as a set.
 */
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

// Emits for every commit phase: the runtime and agent never stage feature specs. A human operator
// may already have committed them; leave those HEAD files alone and leave remaining spec dirt local.
internal fun commitExclusionDirective(phaseId: String, issueKey: String): String {
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

internal fun goalContinuationDirective(phaseId: String, suppressDecomposition: Boolean): String {
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

// One imperative task directive per phase; the briefing carries the spec-specific scope.
// Validate Task-line specialization lives in FeatureTaskRuntimePhasePromptValidateDirectives.
internal val phaseDirectives: Map<String, String> = mapOf(
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
    "outputs and change only what that audit value requires. Repair evidence is read-only repository " +
    "facts: do not run builds or tests here.",
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
    "The runtime owns this review. Do not run bill-code-review, do not emit findings, and do not " +
    "report unsatisfied acceptance criteria. Criterion-gap detection remains exclusive to the audit phase. " +
    "Do not run `./gradlew check`, the pack collect-all gate, or `bill-code-check`; validate owns those.",
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
    "Answer one question: is every acceptance criterion in the briefing implemented in the repository? " +
    "Read the tree itself at the resolved checkpoint — the diff over its base_ref/head_ref plus its " +
    "scoped_owned_paths. The upstream implement value is structured prose (former implementation_receipt " +
    "JSON stuffed inside value): read and interpret it as a producer CLAIM, not evidence. Never mark a " +
    "criterion satisfied because that string lists a completed task id, a changed path, or " +
    "reconciliation_evidence claiming reconciled. A claim the tree contradicts is itself unmet. " +
    "Report the answer as envelope verdict plus produced_outputs.value: verdict satisfied when every " +
    "criterion is implemented, or verdict gaps_found when one or more remain unmet. Stuff the gap " +
    "report inside value as structured prose (for example a JSON object with gaps and " +
    "non_blocking_findings arrays); the runtime does not cross-check that inner shape against the " +
    "verdict. Every unmet gap must name its criterion ref and one dense note that both diagnoses what " +
    "is missing and hands implement a complete fix plan. Before you emit a gap, plan the repair " +
    "carefully: name the minimal production change that closes the criterion; inspect blast radius " +
    "across callers, DI/bindings, sibling phases, contracts, and fixtures that share the touched " +
    "surface; confirm the plan does not regress neighboring criteria or break other functionality; " +
    "and confirm the plan is complete enough that one implement round can close the gap without " +
    "inventing follow-up work or opening a new gap. Prefer a slightly broader correct plan over a " +
    "narrow patch that leaves a sibling hole for the next audit. Do not emit a separate repair-plan " +
    "object, per-item identifiers, or verification bookkeeping — the note inside value is the plan. " +
    "A later audit re-checks every criterion from scratch, so you never need to account for what an " +
    "earlier audit said unless this briefing carries prior_gap_memory. Judge production behavior and " +
    "production implementation only: test adequacy, coverage, fixtures, and assertions are never " +
    "unmet criteria. All evidence is read-only repository facts: never run a build, a test, or any " +
    "other command as audit evidence; validation owns test execution and failures.",
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
    "Invoke bill-pr-description, honor any repo-native PR template, create or reuse the open " +
    "pull request for the branch idempotently, and emit pr_result with the PR URL/number, " +
    "title, and whether a new PR was created.",
)

// The blank-slate sentence in the shared PHASE_AUDIT directive. It is subordinated for a
// memory-carrying audit (which must account for the earlier audit's claims) while staying byte-identical
// for a first or forward audit.
private const val AUDIT_NO_EARLIER_AUDIT_SENTENCE: String =
  "A later audit re-checks every criterion from scratch, so you never need to account for what an " +
    "earlier audit said."

private const val AUDIT_STICKY_REJUSTIFICATION_SENTENCE: String =
  "A later audit re-checks every criterion from scratch. When this briefing carries prior-gap memory, " +
    "treat prior_audit_values as authoritative context: repeating a criterion already named in an " +
    "earlier audit value string requires explicit re-justification — name what the prior implement " +
    "claimed and why the tree still fails it."

/**
 * The audit phase task directive, memory-aware. A first or forward audit (no memory) returns the
 * shared static wording byte-for-byte; a memory-carrying audit swaps the blank-slate sentence for the
 * sticky re-justification requirement (AC-003).
 */
internal fun auditPhaseTaskDirective(memory: FeatureTaskRuntimePriorGapMemory?): String {
  val base = phaseDirectives.getValue(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT)
  if (memory == null) return base
  return base.replace(AUDIT_NO_EARLIER_AUDIT_SENTENCE, AUDIT_STICKY_REJUSTIFICATION_SENTENCE)
}
