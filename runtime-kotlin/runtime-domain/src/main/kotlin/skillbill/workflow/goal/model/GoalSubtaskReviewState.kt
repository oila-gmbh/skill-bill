package skillbill.workflow.goal.model

import skillbill.boundary.OpenBoundaryMap
import skillbill.contracts.workflow.GOAL_SUBTASK_REVIEW_STATE_CONTRACT_VERSION
import skillbill.error.InvalidFeatureTaskRuntimeRepairReceiptError
import skillbill.error.InvalidGoalSubtaskReviewStateSchemaError
import skillbill.error.InvalidWorkflowStateSchemaError
import skillbill.workflow.goal.model.CodeReviewExecutionMode

const val GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY: String = "goal_subtask_review_state"
const val GOAL_SUBTASK_REVIEW_INPUT_ARTIFACT_KEY: String = "goal_subtask_review_input"
const val GOAL_SUBTASK_REVIEW_RESULTS_ARTIFACT_KEY: String = "goal_subtask_review_results"
const val GOAL_SUBTASK_REVIEW_RESULT_ARTIFACT_PREFIX: String = "goal_subtask_review_results"
const val GOAL_SUBTASK_REVIEW_BLOCKER_SEVERITY: String = "blocker"

/** Additive evidence log of review/remediation base recoveries; unknown to older runtimes. */
const val GOAL_REVIEW_BASE_RECOVERIES_ARTIFACT_KEY: String = "goal_review_base_recoveries"

enum class GoalSubtaskOperatorDecision(val wireValue: String) {
  RETRY_FIX("retry_fix"),
  ACCEPT_AND_ADVANCE("accept_and_advance"),
  ABANDON_SUBTASK("abandon_subtask"),
  ;

  companion object {
    fun fromWire(value: String): GoalSubtaskOperatorDecision = entries.firstOrNull { it.wireValue == value }
      ?: reviewStateError("operator_decision", "must be one of ${entries.joinToString { it.wireValue }}.")
  }
}

/**
 * How a recorded operator decision releases the pause. Every decision releases it — leaving a decided
 * pause intact would re-pause the subtask identically and strand it on the operator's own choice.
 */
enum class GoalSubtaskPauseRelease {
  /** One fresh, unbudgeted `implement_fix` iteration. */
  RETRY_FIX,

  /** Forward to `validate` with the unresolved Blockers accepted as-is. */
  ADVANCE,

  /** Terminal: the subtask is abandoned rather than repaired. */
  ABANDON,
}

enum class GoalSubtaskBlockerDispositionVerdict(val wireValue: String) {
  RESOLVED("resolved"),
  UNRESOLVED("unresolved"),
  ;

  companion object {
    fun fromWire(value: String): GoalSubtaskBlockerDispositionVerdict = when (value) {
      "superseded" -> reviewStateError(
        "verdict",
        "superseded is removed; records naming it must be regenerated.",
      )
      else -> entries.firstOrNull { it.wireValue == value }
        ?: reviewStateError("verdict", "must be one of ${entries.joinToString { it.wireValue }}.")
    }
  }
}

data class GoalSubtaskBlockerDisposition(
  val findingId: String,
  val verdict: GoalSubtaskBlockerDispositionVerdict,
  val evidence: List<String>,
) {
  init {
    require(findingId.isNotBlank()) { "GoalSubtaskBlockerDisposition.findingId must be non-blank." }
    require(evidence.isNotEmpty()) {
      "GoalSubtaskBlockerDisposition.evidence must contain at least one evidence entry."
    }
    require(evidence.all(String::isNotBlank)) {
      "GoalSubtaskBlockerDisposition.evidence must contain only non-blank strings."
    }
  }

  @OpenBoundaryMap("Blocker disposition at the durable workflow-artifact seam")
  fun toArtifactMap(): Map<String, Any?> = linkedMapOf(
    "finding_id" to findingId,
    "verdict" to verdict.wireValue,
    "evidence" to evidence,
  )

  companion object {
    @OpenBoundaryMap("Blocker disposition decode from the durable workflow-artifact map")
    fun fromArtifactMap(raw: Map<String, Any?>, path: String): GoalSubtaskBlockerDisposition {
      raw.requireOnlyReviewStateKeys(setOf("finding_id", "verdict", "evidence"), path)
      val evidence = raw.requireReviewStateList("evidence", path).mapIndexed { index, value ->
        value as? String ?: reviewStateError(
          "$path.evidence[$index]",
          "must be a string.",
        )
      }
      return GoalSubtaskBlockerDisposition(
        findingId = raw.requireReviewStateString("finding_id", path),
        verdict = GoalSubtaskBlockerDispositionVerdict.fromWire(raw.requireReviewStateString("verdict", path)),
        evidence = evidence,
      )
    }
  }
}

fun unionRefutedBlockerDispositions(
  agentEmitted: List<GoalSubtaskBlockerDisposition>,
  runtimeSuperseded: List<GoalSubtaskBlockerDisposition>,
): List<GoalSubtaskBlockerDisposition> {
  if (runtimeSuperseded.isEmpty()) return agentEmitted
  val byId = agentEmitted.associateBy(GoalSubtaskBlockerDisposition::findingId).toMutableMap()
  runtimeSuperseded.forEach { disposition -> byId[disposition.findingId] = disposition }
  return byId.values.toList()
}

enum class GoalSubtaskReviewDisposition(val wireValue: String) {
  PENDING("pending"),
  PAUSED("paused"),
  REVIEW_CAP_REACHED("review_cap_reached"),
  ;

  companion object {
    fun fromWire(value: String): GoalSubtaskReviewDisposition = entries.firstOrNull { it.wireValue == value }
      ?: reviewStateError("disposition", "must be one of ${entries.joinToString { it.wireValue }}.")
  }
}

data class GoalSubtaskReviewCompactFinding(
  val severity: String,
  val label: String,
  val text: String,
  /**
   * The id the review output itself carried (the `F-XXX` register id). Persisted so the reserved
   * remediation pass dispositions against the ids the agent actually saw rather than positional
   * stand-ins the correspondence could not be verified against. Absent on records written before the
   * id was captured, which fall back to the positional id.
   */
  val findingId: String? = null,
) {
  val isBlocker: Boolean get() = severity == GOAL_SUBTASK_REVIEW_BLOCKER_SEVERITY

  /** Whether this finding severity blocks advancing: Blocker or Major. [isBlocker] stays Blocker-only. */
  val blocksAdvance: Boolean get() = severity == GOAL_SUBTASK_REVIEW_BLOCKER_SEVERITY || severity == "major"

  init {
    require(severity in setOf("blocker", "major", "minor", "nit")) { "Invalid review finding severity '$severity'." }
    require(label.isNotBlank()) { "GoalSubtaskReviewCompactFinding.label must be non-blank." }
    require(text.isNotBlank()) { "GoalSubtaskReviewCompactFinding.text must be non-blank." }
    findingId?.let { require(it.isNotBlank()) { "GoalSubtaskReviewCompactFinding.findingId must be non-blank." } }
  }

  @OpenBoundaryMap("Compact goal-review finding at the durable workflow-artifact seam")
  fun toArtifactMap(): Map<String, Any?> = linkedMapOf<String, Any?>(
    "severity" to severity,
    "label" to label,
    "text" to text,
  ).apply { findingId?.let { put("finding_id", it) } }

  companion object {
    @OpenBoundaryMap("Compact goal-review finding decode from the durable workflow-artifact map")
    fun fromArtifactMap(raw: Map<String, Any?>, path: String): GoalSubtaskReviewCompactFinding {
      raw.requireOnlyReviewStateKeys(setOf("severity", "label", "text", "finding_id"), path)
      return GoalSubtaskReviewCompactFinding(
        severity = raw.requireReviewStateString("severity", path),
        label = raw.requireReviewStateString("label", path),
        text = raw.requireReviewStateString("text", path),
        findingId = raw.optionalReviewStateString("finding_id", path),
      )
    }
  }
}

/**
 * The closed vocabulary a durable goal-review pass result may carry. [FeatureTaskRuntimeVerdict.fromWire]
 * accepts any non-blank value so durable records round-trip, so the producer of a pass result must
 * canonicalize an emitted verdict into this set rather than persisting whatever string the review
 * happened to write.
 */
val GOAL_SUBTASK_REVIEW_PASS_VERDICTS: Set<FeatureTaskRuntimeVerdict> = setOf(
  FeatureTaskRuntimeVerdict.APPROVED,
  FeatureTaskRuntimeVerdict.CHANGES_REQUESTED,
  FeatureTaskRuntimeVerdict.REVIEW_CAP_REACHED,
  FeatureTaskRuntimeVerdict.REVIEW_SKIPPED_BY_USER,
)

data class GoalSubtaskReviewPassResult(
  val passNumber: Int,
  val verdict: FeatureTaskRuntimeVerdict,
  val reviewResultArtifact: String,
  val unresolvedFindingCount: Int,
  val findings: List<GoalSubtaskReviewCompactFinding>,
  val executedMode: CodeReviewExecutionMode? = null,
  /** Present only for a delegated pass over a real commit sequence; never fabricated otherwise. */
  val commitFocusedAccounting: GoalSubtaskCommitFocusedAccounting? = null,
) {
  init {
    require(passNumber >= 1) { "Goal review pass number must be a positive integer." }
    require(verdict in GOAL_SUBTASK_REVIEW_PASS_VERDICTS) {
      "Goal review pass verdict is invalid: '${verdict.wireValue}'."
    }
    require(reviewResultArtifact == "$GOAL_SUBTASK_REVIEW_RESULT_ARTIFACT_PREFIX.$passNumber") {
      "Goal review result artifact must identify its exact review pass."
    }
    require(unresolvedFindingCount >= 0) { "Goal unresolved finding count must be non-negative." }
    require(commitFocusedAccounting == null || executedMode != CodeReviewExecutionMode.INLINE) {
      "An inline review pass has no delegated commit sequence and must omit commit-focused accounting."
    }
  }

  /**
   * Blocker or Major severity blocks advancing. A compact summary may carry a positive unresolved
   * count with no itemised findings, so an empty finding list stays blocking; an itemised list must
   * name a Blocker or Major.
   */
  val blocksAdvance: Boolean get() = blocksAdvance(unresolvedFindingCount, findings)

  @OpenBoundaryMap("Goal-review pass result at the durable workflow-artifact seam")
  fun toArtifactMap(): Map<String, Any?> = linkedMapOf<String, Any?>(
    "pass_number" to passNumber,
    "verdict" to verdict.wireValue,
    "review_result_artifact" to reviewResultArtifact,
    "unresolved_finding_count" to unresolvedFindingCount,
    "findings" to findings.map(GoalSubtaskReviewCompactFinding::toArtifactMap),
  ).apply {
    executedMode?.let { put("executed_mode", it.wireValue) }
    commitFocusedAccounting?.let { put("commit_focused_accounting", it.toArtifactMap()) }
  }

  companion object {
    @OpenBoundaryMap("Goal-review pass result decode from the durable workflow-artifact map")
    fun fromArtifactMap(raw: Map<String, Any?>, path: String): GoalSubtaskReviewPassResult {
      raw.requireOnlyReviewStateKeys(
        setOf(
          "pass_number",
          "verdict",
          "review_result_artifact",
          "unresolved_finding_count",
          "findings",
          "executed_mode",
          "commit_focused_accounting",
        ),
        path,
      )
      val findings = raw.requireReviewStateList("findings", path).mapIndexed { index, value ->
        GoalSubtaskReviewCompactFinding.fromArtifactMap(
          value.asReviewStateMap("$path.findings[$index]"),
          "$path.findings[$index]",
        )
      }
      return GoalSubtaskReviewPassResult(
        passNumber = raw.requireReviewStateInt("pass_number", path),
        verdict = FeatureTaskRuntimeVerdict.fromWire(raw.requireReviewStateString("verdict", path)),
        reviewResultArtifact = raw.requireReviewStateString("review_result_artifact", path),
        unresolvedFindingCount = raw.requireReviewStateInt("unresolved_finding_count", path),
        findings = findings,
        executedMode = raw.optionalReviewStateString("executed_mode", path)?.let(CodeReviewExecutionMode::fromWire),
        commitFocusedAccounting = raw["commit_focused_accounting"]?.let {
          GoalSubtaskCommitFocusedAccounting.fromArtifactMap(
            it.asReviewStateMap("$path.commit_focused_accounting"),
            "$path.commit_focused_accounting",
          )
        },
      )
    }
  }
}

/**
 * The indivisible durable identity of a goal-review child. A regular feature-task runtime
 * workflow has none of these artifacts; a goal child has all of them. Decoding them together
 * prevents a damaged child row from being mistaken for a standalone runtime workflow.
 */
data class GoalSubtaskReviewArtifacts(
  val continuation: FeatureTaskRuntimeGoalContinuationArtifact,
  val state: GoalSubtaskReviewState,
  val rawResults: Map<String, String>,
)

object GoalSubtaskReviewArtifactDecoder {
  @OpenBoundaryMap("Atomic goal-review artifact decode from the durable workflow-artifact map")
  fun decode(artifacts: Map<String, Any?>): GoalSubtaskReviewArtifacts? {
    val hasContinuation = FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_ARTIFACT_KEY in artifacts
    val hasState = GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY in artifacts
    if (!hasContinuation && !hasState) {
      if (GOAL_SUBTASK_REVIEW_RESULTS_ARTIFACT_KEY in artifacts) {
        reviewStateError(
          GOAL_SUBTASK_REVIEW_RESULTS_ARTIFACT_KEY,
          "must be absent when no goal-subtask review child state exists.",
        )
      }
      return null
    }
    if (!hasContinuation) {
      reviewStateError(
        FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_ARTIFACT_KEY,
        "must be present whenever a goal-subtask review state exists.",
      )
    }
    if (!hasState) {
      reviewStateError(
        GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY,
        "must be present whenever a goal-continuation child exists.",
      )
    }
    val continuation = try {
      FeatureTaskRuntimeGoalContinuationArtifact.fromArtifactMap(
        artifacts.getValue(FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_ARTIFACT_KEY).asGoalReviewArtifactMap(
          FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_ARTIFACT_KEY,
        ),
      )
    } catch (error: InvalidWorkflowStateSchemaError) {
      reviewStateError(FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_ARTIFACT_KEY, error.message.orEmpty(), error)
    } catch (error: IllegalArgumentException) {
      reviewStateError(FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_ARTIFACT_KEY, error.message.orEmpty(), error)
    }
    val state = GoalSubtaskReviewState.fromArtifactMap(
      artifacts.getValue(GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY).asGoalReviewArtifactMap(
        GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY,
      ),
    )
    if (state.codeReviewMode != continuation.codeReviewMode) {
      reviewStateError(
        "$GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY.code_review_mode",
        "must match the immutable goal-continuation review policy.",
      )
    }
    return GoalSubtaskReviewArtifacts(
      continuation = continuation,
      state = state,
      rawResults = rawResults(artifacts, state),
    )
  }

  /**
   * Continuation alone, tolerant of a not-yet-captured review state. A goal child whose review
   * state has not been captured yet (or disappeared) is still resumable by its continuation
   * identity; whether that absence blocks progress is for the phase that actually needs review
   * state to decide (see goal-review reservation), not every reader of the continuation.
   */
  @OpenBoundaryMap("Continuation-only decode tolerant of a not-yet-captured review state")
  fun decodeContinuationOnly(artifacts: Map<String, Any?>): FeatureTaskRuntimeGoalContinuationArtifact? {
    if (FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_ARTIFACT_KEY !in artifacts) {
      // A review state (or its raw results) without a continuation is not a legitimate "not
      // captured yet" shape the way a missing state is: continuation is written first on every
      // child-open path, so its absence alongside either one is genuine corruption.
      if (GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY in artifacts) {
        reviewStateError(
          FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_ARTIFACT_KEY,
          "must be present whenever a goal-subtask review state exists.",
        )
      }
      if (GOAL_SUBTASK_REVIEW_RESULTS_ARTIFACT_KEY in artifacts) {
        reviewStateError(
          GOAL_SUBTASK_REVIEW_RESULTS_ARTIFACT_KEY,
          "must be absent when no goal-subtask review child state exists.",
        )
      }
      return null
    }
    return try {
      decode(artifacts)?.continuation
    } catch (error: InvalidGoalSubtaskReviewStateSchemaError) {
      if (GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY !in artifacts) decodeContinuationDirect(artifacts) else throw error
    }
  }

  /**
   * Review state alone, tolerant of it not existing yet: returns null instead of failing when the
   * durable row simply has not captured review state (or it disappeared), deferring to the caller
   * to decide whether that absence blocks it.
   */
  @OpenBoundaryMap("Review-state-only decode tolerant of it not existing yet")
  fun decodeReviewStateOnly(artifacts: Map<String, Any?>): GoalSubtaskReviewState? =
    if (GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY !in artifacts) null else decode(artifacts)?.state

  private fun decodeContinuationDirect(artifacts: Map<String, Any?>): FeatureTaskRuntimeGoalContinuationArtifact = try {
    FeatureTaskRuntimeGoalContinuationArtifact.fromArtifactMap(
      artifacts.getValue(FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_ARTIFACT_KEY).asGoalReviewArtifactMap(
        FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_ARTIFACT_KEY,
      ),
    )
  } catch (error: InvalidWorkflowStateSchemaError) {
    reviewStateError(FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_ARTIFACT_KEY, error.message.orEmpty(), error)
  } catch (error: IllegalArgumentException) {
    reviewStateError(FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_ARTIFACT_KEY, error.message.orEmpty(), error)
  }

  private fun rawResults(artifacts: Map<String, Any?>, state: GoalSubtaskReviewState): Map<String, String> {
    if (state.completedPassCount == 0) {
      // Review invalidation clears results through an empty map because the durable artifact patch
      // merges keys and cannot express removal.
      val cleared = artifacts[GOAL_SUBTASK_REVIEW_RESULTS_ARTIFACT_KEY]
        ?.asGoalReviewArtifactMap(GOAL_SUBTASK_REVIEW_RESULTS_ARTIFACT_KEY)
        .orEmpty()
      if (cleared.isNotEmpty()) {
        reviewStateError(
          GOAL_SUBTASK_REVIEW_RESULTS_ARTIFACT_KEY,
          "must hold no durable raw review result before the first completed review pass.",
        )
      }
      return emptyMap()
    }
    val raw = artifacts[GOAL_SUBTASK_REVIEW_RESULTS_ARTIFACT_KEY]
      ?.asGoalReviewArtifactMap(GOAL_SUBTASK_REVIEW_RESULTS_ARTIFACT_KEY)
      ?: reviewStateError(
        GOAL_SUBTASK_REVIEW_RESULTS_ARTIFACT_KEY,
        "must contain the durable raw review result for every completed pass.",
      )
    val expectedKeys = state.passResults.map { result -> result.passNumber.toString() }.toSet()
    if (raw.keys != expectedKeys) {
      reviewStateError(
        GOAL_SUBTASK_REVIEW_RESULTS_ARTIFACT_KEY,
        "must contain exactly one durable raw review result for every completed pass.",
      )
    }
    return raw.mapValues { (passNumber, value) ->
      (value as? String)?.takeIf(String::isNotBlank)
        ?: reviewStateError(
          "$GOAL_SUBTASK_REVIEW_RESULTS_ARTIFACT_KEY.$passNumber",
          "must be a non-blank durable raw review result.",
        )
    }
  }
}

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

private fun blocksAdvance(unresolvedFindingCount: Int, findings: List<GoalSubtaskReviewCompactFinding>): Boolean =
  unresolvedFindingCount > 0 && (findings.isEmpty() || findings.any(GoalSubtaskReviewCompactFinding::blocksAdvance))

private val GIT_COMMIT_SHA = Regex("^[0-9a-f]{40}(?:[0-9a-f]{24})?$")

fun reviewStateError(fieldPath: String, reason: String, cause: Throwable? = null): Nothing =
  throw InvalidGoalSubtaskReviewStateSchemaError(
    sourceLabel = GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY,
    fieldPath = fieldPath,
    reason = reason,
    cause = cause,
  )
