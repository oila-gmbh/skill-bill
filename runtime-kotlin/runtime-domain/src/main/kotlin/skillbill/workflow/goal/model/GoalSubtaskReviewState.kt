package skillbill.workflow.goal.model

import skillbill.boundary.OpenBoundaryMap
import skillbill.contracts.workflow.GOAL_SUBTASK_REVIEW_STATE_CONTRACT_VERSION
import skillbill.error.InvalidFeatureTaskRuntimeRepairReceiptError
import skillbill.error.InvalidGoalSubtaskReviewStateSchemaError
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePriorReviewContext
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairLedger
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairReceipt
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeReviewPassSequence
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.model.featureTaskRuntimeFoldRepairLedger

data class GoalSubtaskReviewState(
  val reviewBaseSha: String,
  val baselineUntrackedPaths: List<String>,
  val codeReviewMode: CodeReviewExecutionMode,
  val reservedPassNumber: Int? = null,
  val completedPassCount: Int = 0,
  val disposition: GoalSubtaskReviewDisposition = GoalSubtaskReviewDisposition.PENDING,
  val reviewInputArtifact: String? = null,
  val reviewedDeltaDigest: String? = null,
  val passResults: List<GoalSubtaskReviewPassResult> = emptyList(),
  val emittedPassCount: Int = 0,
  val blockerDispositions: List<GoalSubtaskBlockerDisposition> = emptyList(),
  val operatorDecision: GoalSubtaskOperatorDecision? = null,
  val operatorRetryRounds: Int = 0,
  val resolvedTier: CodeReviewExecutionMode? = null,
  val decidingRule: String? = null,
  val remediationBaseSha: String? = null,
  val repairReceipts: List<FeatureTaskRuntimeRepairReceipt> = emptyList(),
  val contractVersion: String = GOAL_SUBTASK_REVIEW_STATE_CONTRACT_VERSION,
) {
  init {
    require(contractVersion == GOAL_SUBTASK_REVIEW_STATE_CONTRACT_VERSION) {
      "Unsupported goal review state contract '$contractVersion'. " +
        "Records written before $GOAL_SUBTASK_REVIEW_STATE_CONTRACT_VERSION carry a two-pass " +
        "remediation ceiling, are rejected, and must be regenerated."
    }
    resolvedTier?.let { tier ->
      require(tier != CodeReviewExecutionMode.AUTO) {
        "Goal review resolved tier must be a concrete mode, never 'auto'."
      }
    }
    require(GIT_COMMIT_SHA.matches(reviewBaseSha)) {
      "Goal review base SHA must be a 40- or 64-character lowercase commit SHA."
    }
    remediationBaseSha?.let { sha ->
      require(GIT_COMMIT_SHA.matches(sha)) {
        "Goal remediation base SHA must be a 40- or 64-character lowercase commit SHA."
      }
    }
    require(baselineUntrackedPaths.all(String::isNotBlank)) { "Baseline untracked paths must be non-blank." }
    require(baselineUntrackedPaths == baselineUntrackedPaths.distinct().sorted()) {
      "Baseline untracked paths must be sorted and unique."
    }
    require(completedPassCount >= 0) { "Completed review passes must be non-negative." }
    require(passResults.size == completedPassCount) { "Pass result count must equal completed pass count." }
    require(passResults.map(GoalSubtaskReviewPassResult::passNumber) == (1..completedPassCount).toList()) {
      "Pass results must be ordered and contiguous."
    }
    passResults.forEach { result ->
      result.executedMode?.let { executedMode ->
        require(executedMode == FeatureTaskRuntimeReviewPassSequence.modeForPass(codeReviewMode, result.passNumber)) {
          "Pass ${result.passNumber} executed mode must match the immutable review pass sequence."
        }
      }
    }
    reservedPassNumber?.let { reserved ->
      require(reserved == completedPassCount + 1) {
        "Reserved pass must be the next permitted review pass."
      }
    }
    require(emittedPassCount in 0..completedPassCount) { "Emitted pass count cannot exceed completed pass count." }
    require(
      disposition != GoalSubtaskReviewDisposition.REVIEW_CAP_REACHED ||
        (
          completedPassCount >= 1 &&
            passResults.lastOrNull()?.blocksAdvance == true
          ),
    ) { "review_cap_reached requires unresolved Blocker or Major findings on a completed pass." }
    require(
      blockerDispositions.map(GoalSubtaskBlockerDisposition::findingId).distinct().size == blockerDispositions.size,
    ) {
      "Each prior Blocker may carry exactly one disposition."
    }
    require(
      disposition != GoalSubtaskReviewDisposition.PAUSED ||
        blockerDispositions.any { it.verdict == GoalSubtaskBlockerDispositionVerdict.UNRESOLVED } ||
        passResults.lastOrNull()?.blocksAdvance == true,
    ) {
      "paused requires an unresolved Blocker disposition or a Blocker or Major the remediation pass itself introduced."
    }
    require(operatorDecision == null || disposition == GoalSubtaskReviewDisposition.PAUSED) {
      "An operator decision is only recorded against a paused subtask."
    }
    require(repairReceipts.map(FeatureTaskRuntimeRepairReceipt::roundNumber).distinct().size == repairReceipts.size) {
      "Each remediation round may carry exactly one repair receipt."
    }
  }

  val repairLedger: FeatureTaskRuntimeRepairLedger
    get() = featureTaskRuntimeFoldRepairLedger(repairReceipts, passResults)

  val priorReviewContext: FeatureTaskRuntimePriorReviewContext?
    get() = passResults.lastOrNull()?.let { previous ->
      FeatureTaskRuntimePriorReviewContext(
        passNumber = previous.passNumber,
        findings = previous.findings,
        dispositions = blockerDispositions,
      ).takeUnless(FeatureTaskRuntimePriorReviewContext::isEmpty)
    }

  val reviewCapReached: Boolean get() = disposition == GoalSubtaskReviewDisposition.REVIEW_CAP_REACHED

  val reviewSkippedByUser: Boolean get() =
    passResults.lastOrNull()?.verdict == FeatureTaskRuntimeVerdict.REVIEW_SKIPPED_BY_USER

  fun reserveNextPass(): GoalSubtaskReviewState = when {
    reviewCapReached -> this
    reviewSkippedByUser -> this
    reservedPassNumber != null -> this
    completedPassCount >= 1 -> this
    else -> copy(reservedPassNumber = 1)
  }

  fun completeReservedPass(
    verdict: FeatureTaskRuntimeVerdict,
    unresolvedFindingCount: Int,
    findings: List<GoalSubtaskReviewCompactFinding>,
    blockerDispositions: List<GoalSubtaskBlockerDisposition> = emptyList(),
    /** Supplied only by a delegated pass over a real commit sequence; inline passes omit it. */
    commitFocusedAccounting: GoalSubtaskCommitFocusedAccounting? = null,
  ): GoalSubtaskReviewState {
    val passNumber = reservedPassNumber
      ?: reviewStateError("reserved_pass_number", "must be present before completing a review pass.")
    require(
      blockerDispositions.map(GoalSubtaskBlockerDisposition::findingId).distinct().size == blockerDispositions.size,
    ) {
      "Each prior Blocker may carry exactly one disposition."
    }
    val disposedPass = blockerDispositions.isNotEmpty()
    val executedMode = FeatureTaskRuntimeReviewPassSequence.modeForPass(codeReviewMode, passNumber)
    val result = GoalSubtaskReviewPassResult(
      passNumber = passNumber,
      verdict = verdict,
      reviewResultArtifact = "$GOAL_SUBTASK_REVIEW_RESULT_ARTIFACT_PREFIX.$passNumber",
      unresolvedFindingCount = unresolvedFindingCount,
      findings = findings,
      executedMode = executedMode,
      // An inline pass carries no delegated commit sequence, so accounting a caller offers anyway is
      // dropped rather than fabricated into durable state.
      commitFocusedAccounting = commitFocusedAccounting
        ?.takeIf { executedMode != CodeReviewExecutionMode.INLINE },
    )
    return copy(
      reservedPassNumber = null,
      completedPassCount = passNumber,
      disposition = GoalSubtaskReviewDisposition.PENDING,
      passResults = passResults + result,
      blockerDispositions = if (disposedPass) blockerDispositions else this.blockerDispositions,
      operatorDecision = null,
      operatorRetryRounds = 0,
    )
  }

  val pausedForOperatorDecision: Boolean get() = disposition == GoalSubtaskReviewDisposition.PAUSED

  val unresolvedBlockerDispositions: List<GoalSubtaskBlockerDisposition>
    get() = blockerDispositions.filter { it.verdict == GoalSubtaskBlockerDispositionVerdict.UNRESOLVED }

  /**
   * The only disposition projection any goal-facing surface may read: pass number, per-finding
   * verdict, and counts. Location-bearing evidence stays in the durable artifact and is reachable
   * only through `skill-bill goal findings --issue-key <KEY>`.
   */
  @OpenBoundaryMap("Bounded goal-facing disposition projection; carries no location-bearing evidence")
  fun boundedDispositionSummary(): Map<String, Any?> = linkedMapOf(
    "pass" to completedPassCount,
    "disposition_counts" to GoalSubtaskBlockerDispositionVerdict.entries.associate { verdict ->
      verdict.wireValue to blockerDispositions.count { it.verdict == verdict }
    },
    "verdicts" to blockerDispositions.map { it.verdict.wireValue },
  )

  fun acknowledgeSummariesThrough(passNumber: Int): GoalSubtaskReviewState =
    copy(emittedPassCount = passNumber.coerceIn(emittedPassCount, completedPassCount))

  @OpenBoundaryMap("Goal-review state at the durable workflow-artifact seam")
  fun toArtifactMap(): Map<String, Any?> = linkedMapOf<String, Any?>(
    "contract_version" to contractVersion,
    "review_base_sha" to reviewBaseSha,
    "baseline_untracked_paths" to baselineUntrackedPaths,
    "code_review_mode" to codeReviewMode.wireValue,
    "completed_pass_count" to completedPassCount,
    "disposition" to disposition.wireValue,
    "pass_results" to passResults.map(GoalSubtaskReviewPassResult::toArtifactMap),
    "emitted_pass_count" to emittedPassCount,
    "blocker_dispositions" to blockerDispositions.map(GoalSubtaskBlockerDisposition::toArtifactMap),
  ).apply {
    reservedPassNumber?.let { put("reserved_pass_number", it) }
    reviewInputArtifact?.let { put("review_input_artifact", it) }
    reviewedDeltaDigest?.let { put("reviewed_delta_digest", it) }
    operatorDecision?.let { put("operator_decision", it.wireValue) }
    if (operatorRetryRounds > 0) put("operator_retry_rounds", operatorRetryRounds)
    resolvedTier?.let { put("resolved_tier", it.wireValue) }
    decidingRule?.let { put("deciding_rule", it) }
    remediationBaseSha?.let { put("remediation_base_sha", it) }
    if (repairReceipts.isNotEmpty()) {
      put("repair_receipts", repairReceipts.map(FeatureTaskRuntimeRepairReceipt::toArtifactMap))
    }
  }

  companion object {
    fun initial(
      reviewBaseSha: String,
      baselineUntrackedPaths: Collection<String>,
      codeReviewMode: CodeReviewExecutionMode,
    ): GoalSubtaskReviewState = GoalSubtaskReviewState(
      reviewBaseSha = reviewBaseSha,
      baselineUntrackedPaths = baselineUntrackedPaths.map(String::trim).filter(String::isNotBlank).distinct().sorted(),
      codeReviewMode = codeReviewMode,
    )

    @OpenBoundaryMap("Goal-review state decode from the durable workflow-artifact map")
    fun fromArtifactMap(
      raw: Map<String, Any?>,
      sourceLabel: String = GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY,
    ): GoalSubtaskReviewState {
      raw.requireOnlyReviewStateKeys(
        setOf(
          "contract_version", "review_base_sha", "baseline_untracked_paths", "code_review_mode", "reserved_pass_number",
          "completed_pass_count", "disposition", "review_input_artifact", "reviewed_delta_digest", "pass_results",
          "emitted_pass_count", "blocker_dispositions", "operator_decision", "operator_retry_rounds",
          "resolved_tier", "deciding_rule",
          "remediation_base_sha",
          "repair_receipts",
        ),
        sourceLabel,
      )
      return try {
        GoalSubtaskReviewState(
          contractVersion = raw.requireReviewStateString("contract_version", sourceLabel),
          reviewBaseSha = raw.requireReviewStateString("review_base_sha", sourceLabel),
          baselineUntrackedPaths = raw.requireReviewStateList("baseline_untracked_paths", sourceLabel)
            .mapIndexed { index, value ->
              value as? String ?: reviewStateError(
                "$sourceLabel.baseline_untracked_paths[$index]",
                "must be a string.",
              )
            },
          codeReviewMode = CodeReviewExecutionMode.fromWire(
            raw.requireReviewStateString("code_review_mode", sourceLabel),
          ),
          reservedPassNumber = raw.optionalReviewStateInt("reserved_pass_number", sourceLabel),
          completedPassCount = raw.requireReviewStateInt("completed_pass_count", sourceLabel),
          disposition = GoalSubtaskReviewDisposition.fromWire(raw.requireReviewStateString("disposition", sourceLabel)),
          reviewInputArtifact = raw.optionalReviewStateString("review_input_artifact", sourceLabel),
          reviewedDeltaDigest = raw.optionalReviewStateString("reviewed_delta_digest", sourceLabel),
          passResults = decodePassResults(raw, sourceLabel),
          emittedPassCount = raw.requireReviewStateInt("emitted_pass_count", sourceLabel),
          blockerDispositions = decodeBlockerDispositions(raw, sourceLabel),
          operatorDecision = raw.optionalReviewStateString("operator_decision", sourceLabel)
            ?.let(GoalSubtaskOperatorDecision::fromWire),
          operatorRetryRounds = raw.optionalReviewStateInt("operator_retry_rounds", sourceLabel) ?: 0,
          resolvedTier = raw.optionalReviewStateString("resolved_tier", sourceLabel)
            ?.let(CodeReviewExecutionMode::fromWire),
          decidingRule = raw.optionalReviewStateString("deciding_rule", sourceLabel),
          remediationBaseSha = raw.optionalReviewStateString("remediation_base_sha", sourceLabel),
          repairReceipts = decodeRepairReceipts(raw, sourceLabel),
        )
      } catch (error: InvalidGoalSubtaskReviewStateSchemaError) {
        throw error
      } catch (error: IllegalArgumentException) {
        reviewStateError(sourceLabel, error.message.orEmpty(), error)
      }
    }

    private fun decodePassResults(raw: Map<String, Any?>, sourceLabel: String): List<GoalSubtaskReviewPassResult> =
      raw.requireReviewStateList("pass_results", sourceLabel).mapIndexed { index, value ->
        GoalSubtaskReviewPassResult.fromArtifactMap(
          value.asReviewStateMap("$sourceLabel.pass_results[$index]"),
          "$sourceLabel.pass_results[$index]",
        )
      }

    private fun decodeBlockerDispositions(
      raw: Map<String, Any?>,
      sourceLabel: String,
    ): List<GoalSubtaskBlockerDisposition> = raw.optionalReviewStateList("blocker_dispositions", sourceLabel)
      ?.mapIndexed { index, value ->
        GoalSubtaskBlockerDisposition.fromArtifactMap(
          value.asReviewStateMap("$sourceLabel.blocker_dispositions[$index]"),
          "$sourceLabel.blocker_dispositions[$index]",
        )
      }.orEmpty()

    private fun decodeRepairReceipts(
      raw: Map<String, Any?>,
      sourceLabel: String,
    ): List<FeatureTaskRuntimeRepairReceipt> = raw.optionalReviewStateList("repair_receipts", sourceLabel)
      ?.mapIndexed { index, value ->
        try {
          FeatureTaskRuntimeRepairReceipt.fromArtifactMap(
            value.asReviewStateMap("$sourceLabel.repair_receipts[$index]"),
            "$sourceLabel.repair_receipts[$index]",
          )
        } catch (error: InvalidFeatureTaskRuntimeRepairReceiptError) {
          reviewStateError("$sourceLabel.repair_receipts[$index]", error.payloadFreeReason, error)
        }
      }.orEmpty()
  }
}

internal fun blocksAdvance(unresolvedFindingCount: Int, findings: List<GoalSubtaskReviewCompactFinding>): Boolean =
  unresolvedFindingCount > 0 && (findings.isEmpty() || findings.any(GoalSubtaskReviewCompactFinding::blocksAdvance))

private val GIT_COMMIT_SHA = Regex("^[0-9a-f]{40}(?:[0-9a-f]{24})?$")

fun reviewStateError(fieldPath: String, reason: String, cause: Throwable? = null): Nothing =
  throw InvalidGoalSubtaskReviewStateSchemaError(
    sourceLabel = GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY,
    fieldPath = fieldPath,
    reason = reason,
    cause = cause,
  )
