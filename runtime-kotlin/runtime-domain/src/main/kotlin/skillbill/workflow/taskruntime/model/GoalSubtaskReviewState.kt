package skillbill.workflow.taskruntime.model

import skillbill.boundary.OpenBoundaryMap
import skillbill.contracts.workflow.GOAL_SUBTASK_REVIEW_STATE_CONTRACT_VERSION
import skillbill.error.InvalidGoalSubtaskReviewStateSchemaError
import skillbill.error.InvalidWorkflowStateSchemaError
import skillbill.workflow.model.CodeReviewExecutionMode

const val GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY: String = "goal_subtask_review_state"
const val GOAL_SUBTASK_REVIEW_INPUT_ARTIFACT_KEY: String = "goal_subtask_review_input"
const val GOAL_SUBTASK_REVIEW_RESULTS_ARTIFACT_KEY: String = "goal_subtask_review_results"
const val GOAL_SUBTASK_REVIEW_RESULT_ARTIFACT_PREFIX: String = "goal_subtask_review_results"
const val GOAL_SUBTASK_REVIEW_MAX_PASSES: Int = 2
const val GOAL_SUBTASK_REVIEW_BLOCKER_SEVERITY: String = "blocker"

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

enum class GoalSubtaskBlockerDispositionVerdict(val wireValue: String) {
  RESOLVED("resolved"),
  UNRESOLVED("unresolved"),
  SUPERSEDED("superseded"),
  ;

  companion object {
    fun fromWire(value: String): GoalSubtaskBlockerDispositionVerdict = entries.firstOrNull { it.wireValue == value }
      ?: reviewStateError("verdict", "must be one of ${entries.joinToString { it.wireValue }}.")
  }
}

data class GoalSubtaskBlockerDisposition(
  val findingId: String,
  val verdict: GoalSubtaskBlockerDispositionVerdict,
  val evidence: List<String>,
) {
  init {
    require(findingId.isNotBlank()) { "GoalSubtaskBlockerDisposition.findingId must be non-blank." }
    require(evidence.isNotEmpty()) { "GoalSubtaskBlockerDisposition.evidence must contain at least one evidence entry." }
    require(evidence.all(String::isNotBlank)) { "GoalSubtaskBlockerDisposition.evidence must contain only non-blank strings." }
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
) {
  val isBlocker: Boolean get() = severity == GOAL_SUBTASK_REVIEW_BLOCKER_SEVERITY

  init {
    require(severity in setOf("blocker", "major", "minor", "nit")) { "Invalid review finding severity '$severity'." }
    require(label.isNotBlank()) { "GoalSubtaskReviewCompactFinding.label must be non-blank." }
    require(text.isNotBlank()) { "GoalSubtaskReviewCompactFinding.text must be non-blank." }
  }

  @OpenBoundaryMap("Compact goal-review finding at the durable workflow-artifact seam")
  fun toArtifactMap(): Map<String, Any?> = linkedMapOf(
    "severity" to severity,
    "label" to label,
    "text" to text,
  )

  companion object {
    @OpenBoundaryMap("Compact goal-review finding decode from the durable workflow-artifact map")
    fun fromArtifactMap(raw: Map<String, Any?>, path: String): GoalSubtaskReviewCompactFinding {
      raw.requireOnlyReviewStateKeys(setOf("severity", "label", "text"), path)
      return GoalSubtaskReviewCompactFinding(
        severity = raw.requireReviewStateString("severity", path),
        label = raw.requireReviewStateString("label", path),
        text = raw.requireReviewStateString("text", path),
      )
    }
  }
}

data class GoalSubtaskReviewPassResult(
  val passNumber: Int,
  val verdict: FeatureTaskRuntimeVerdict,
  val reviewResultArtifact: String,
  val unresolvedFindingCount: Int,
  val findings: List<GoalSubtaskReviewCompactFinding>,
  val executedMode: CodeReviewExecutionMode? = null,
) {
  init {
    require(passNumber in 1..GOAL_SUBTASK_REVIEW_MAX_PASSES) { "Goal review pass number must be 1 or 2." }
    require(
      verdict in setOf(
        FeatureTaskRuntimeVerdict.APPROVED,
        FeatureTaskRuntimeVerdict.CHANGES_REQUESTED,
        FeatureTaskRuntimeVerdict.REVIEW_CAP_REACHED,
        FeatureTaskRuntimeVerdict.REVIEW_SKIPPED_BY_USER,
      ),
    ) { "Goal review pass verdict is invalid: '${verdict.wireValue}'." }
    require(reviewResultArtifact == "$GOAL_SUBTASK_REVIEW_RESULT_ARTIFACT_PREFIX.$passNumber") {
      "Goal review result artifact must identify its exact review pass."
    }
    require(unresolvedFindingCount >= 0) { "Goal unresolved finding count must be non-negative." }
  }

  /**
   * Only Blocker severity blocks advancing. A compact summary may carry a positive unresolved count
   * with no itemised findings, so an empty finding list stays blocking; an itemised list must name a
   * Blocker.
   */
  val blocksAdvance: Boolean get() = blocksAdvance(unresolvedFindingCount, findings)

  @OpenBoundaryMap("Goal-review pass result at the durable workflow-artifact seam")
  fun toArtifactMap(): Map<String, Any?> = linkedMapOf<String, Any?>(
    "pass_number" to passNumber,
    "verdict" to verdict.wireValue,
    "review_result_artifact" to reviewResultArtifact,
    "unresolved_finding_count" to unresolvedFindingCount,
    "findings" to findings.map(GoalSubtaskReviewCompactFinding::toArtifactMap),
  ).apply { executedMode?.let { put("executed_mode", it.wireValue) } }

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
  val resolvedTier: CodeReviewExecutionMode? = null,
  val decidingRule: String? = null,
  val contractVersion: String = GOAL_SUBTASK_REVIEW_STATE_CONTRACT_VERSION,
) {
  init {
    require(contractVersion == GOAL_SUBTASK_REVIEW_STATE_CONTRACT_VERSION) {
      "Unsupported goal review state contract '$contractVersion'. Legacy 0.1 records are rejected and must be regenerated at 0.2."
    }
    require(GIT_COMMIT_SHA.matches(reviewBaseSha)) {
      "Goal review base SHA must be a 40- or 64-character lowercase commit SHA."
    }
    require(baselineUntrackedPaths.all(String::isNotBlank)) { "Baseline untracked paths must be non-blank." }
    require(baselineUntrackedPaths == baselineUntrackedPaths.distinct().sorted()) {
      "Baseline untracked paths must be sorted and unique."
    }
    require(completedPassCount in 0..GOAL_SUBTASK_REVIEW_MAX_PASSES) { "Completed review passes must be 0..2." }
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
      require(reserved == completedPassCount + 1 && reserved <= GOAL_SUBTASK_REVIEW_MAX_PASSES) {
        "Reserved pass must be the next permitted review pass."
      }
    }
    require(emittedPassCount in 0..completedPassCount) { "Emitted pass count cannot exceed completed pass count." }
    require(
      disposition != GoalSubtaskReviewDisposition.REVIEW_CAP_REACHED ||
        (
          completedPassCount == GOAL_SUBTASK_REVIEW_MAX_PASSES &&
            passResults.lastOrNull()?.blocksAdvance == true
          ),
    ) { "review_cap_reached requires unresolved Blocker findings on pass two." }
    require(blockerDispositions.map(GoalSubtaskBlockerDisposition::findingId).distinct().size == blockerDispositions.size) {
      "Each prior Blocker may carry exactly one disposition."
    }
    require(
      disposition != GoalSubtaskReviewDisposition.PAUSED ||
        blockerDispositions.any { it.verdict == GoalSubtaskBlockerDispositionVerdict.UNRESOLVED },
    ) { "paused requires at least one unresolved Blocker disposition." }
    require(operatorDecision == null || disposition == GoalSubtaskReviewDisposition.PAUSED) {
      "An operator decision is only recorded against a paused subtask."
    }
  }

  val reviewCapReached: Boolean get() = disposition == GoalSubtaskReviewDisposition.REVIEW_CAP_REACHED

  val reviewSkippedByUser: Boolean get() =
    passResults.lastOrNull()?.verdict == FeatureTaskRuntimeVerdict.REVIEW_SKIPPED_BY_USER

  fun reserveNextPass(): GoalSubtaskReviewState = when {
    reviewCapReached -> this
    reviewSkippedByUser -> this
    reservedPassNumber != null -> this
    completedPassCount >= GOAL_SUBTASK_REVIEW_MAX_PASSES -> this
    else -> copy(reservedPassNumber = completedPassCount + 1)
  }

  fun completeReservedPass(
    verdict: FeatureTaskRuntimeVerdict,
    unresolvedFindingCount: Int,
    findings: List<GoalSubtaskReviewCompactFinding>,
    blockerDispositions: List<GoalSubtaskBlockerDisposition> = emptyList(),
  ): GoalSubtaskReviewState {
    val passNumber = reservedPassNumber
      ?: reviewStateError("reserved_pass_number", "must be present before completing a review pass.")
    require(blockerDispositions.map(GoalSubtaskBlockerDisposition::findingId).distinct().size == blockerDispositions.size) {
      "Each prior Blocker may carry exactly one disposition."
    }
    val disposedPass = blockerDispositions.isNotEmpty()
    val unresolvedBlocker = if (disposedPass) {
      blockerDispositions.any { it.verdict == GoalSubtaskBlockerDispositionVerdict.UNRESOLVED }
    } else {
      blocksAdvance(unresolvedFindingCount, findings)
    }
    // The Blocker disposition, not cap exhaustion, terminates the remediation loop: a disposed pass
    // with every Blocker resolved or superseded advances, and one with a survivor pauses resumably.
    // Only an undisposed pass still falls back to the cap-reached block.
    val capReached = passNumber == GOAL_SUBTASK_REVIEW_MAX_PASSES && unresolvedBlocker && !disposedPass
    val paused = passNumber == GOAL_SUBTASK_REVIEW_MAX_PASSES && unresolvedBlocker && disposedPass
    val result = GoalSubtaskReviewPassResult(
      passNumber = passNumber,
      verdict = if (capReached) FeatureTaskRuntimeVerdict.REVIEW_CAP_REACHED else verdict,
      reviewResultArtifact = "$GOAL_SUBTASK_REVIEW_RESULT_ARTIFACT_PREFIX.$passNumber",
      unresolvedFindingCount = unresolvedFindingCount,
      findings = findings,
      executedMode = FeatureTaskRuntimeReviewPassSequence.modeForPass(codeReviewMode, passNumber),
    )
    return copy(
      reservedPassNumber = null,
      completedPassCount = passNumber,
      disposition = when {
        capReached -> GoalSubtaskReviewDisposition.REVIEW_CAP_REACHED
        paused -> GoalSubtaskReviewDisposition.PAUSED
        else -> disposition
      },
      passResults = passResults + result,
      blockerDispositions = if (disposedPass) blockerDispositions else this.blockerDispositions,
      operatorDecision = if (paused) null else operatorDecision,
    )
  }

  /** Non-terminal: the subtask waits on a bounded operator decision, and resume reuses this state. */
  val pausedForOperatorDecision: Boolean get() = disposition == GoalSubtaskReviewDisposition.PAUSED

  val unresolvedBlockerDispositions: List<GoalSubtaskBlockerDisposition>
    get() = blockerDispositions.filter { it.verdict == GoalSubtaskBlockerDispositionVerdict.UNRESOLVED }

  /**
   * `retry_fix` grants one fresh `implement_fix` iteration per operator choice and is unbudgeted; it
   * is recorded as a disposition round inside the already-consumed reserved pass and never
   * re-reserves one, so `completedPassCount` and `reservedPassNumber` are left untouched.
   */
  fun applyOperatorDecision(decision: GoalSubtaskOperatorDecision): GoalSubtaskReviewState {
    if (!pausedForOperatorDecision) {
      reviewStateError("operator_decision", "is only accepted while the subtask is paused.")
    }
    return copy(operatorDecision = decision)
  }

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
    resolvedTier?.let { put("resolved_tier", it.wireValue) }
    decidingRule?.let { put("deciding_rule", it) }
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
          "emitted_pass_count", "blocker_dispositions", "operator_decision", "resolved_tier", "deciding_rule",
        ),
        sourceLabel,
      )
      return try {
        val passResults = raw.requireReviewStateList("pass_results", sourceLabel).mapIndexed { index, value ->
          GoalSubtaskReviewPassResult.fromArtifactMap(
            value.asReviewStateMap("$sourceLabel.pass_results[$index]"),
            "$sourceLabel.pass_results[$index]",
          )
        }
        val blockerDispositions = raw.optionalReviewStateList("blocker_dispositions", sourceLabel)?.mapIndexed { index, value ->
          GoalSubtaskBlockerDisposition.fromArtifactMap(
            value.asReviewStateMap("$sourceLabel.blocker_dispositions[$index]"),
            "$sourceLabel.blocker_dispositions[$index]",
          )
        } ?: emptyList()
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
          passResults = passResults,
          emittedPassCount = raw.requireReviewStateInt("emitted_pass_count", sourceLabel),
          blockerDispositions = blockerDispositions,
          operatorDecision = raw.optionalReviewStateString("operator_decision", sourceLabel)?.let(GoalSubtaskOperatorDecision::fromWire),
          resolvedTier = raw.optionalReviewStateString("resolved_tier", sourceLabel)?.let(CodeReviewExecutionMode::fromWire),
          decidingRule = raw.optionalReviewStateString("deciding_rule", sourceLabel),
        )
      } catch (error: InvalidGoalSubtaskReviewStateSchemaError) {
        throw error
      } catch (error: IllegalArgumentException) {
        reviewStateError(sourceLabel, error.message.orEmpty(), error)
      }
    }
  }
}

private fun Map<String, Any?>.requireOnlyReviewStateKeys(allowed: Set<String>, sourceLabel: String) {
  keys.forEach { key -> if (key !in allowed) reviewStateError("$sourceLabel.$key", "unknown field is not allowed.") }
}

private fun Map<String, Any?>.requireReviewStateString(key: String, sourceLabel: String): String =
  (this[key] as? String)?.takeIf(String::isNotBlank)
    ?: reviewStateError("$sourceLabel.$key", "must be a non-blank string.")

private fun Map<String, Any?>.optionalReviewStateString(key: String, sourceLabel: String): String? =
  when (val value = this[key]) {
    null -> null
    is String -> value.takeIf(String::isNotBlank)
      ?: reviewStateError("$sourceLabel.$key", "must be a non-blank string.")
    else -> reviewStateError("$sourceLabel.$key", "must be a string.")
  }

private fun Map<String, Any?>.requireReviewStateInt(key: String, sourceLabel: String): Int =
  (this[key] as? Number)?.toInt()?.takeIf { value -> value.toDouble() == (this[key] as Number).toDouble() }
    ?: reviewStateError("$sourceLabel.$key", "must be an integer.")

private fun Map<String, Any?>.optionalReviewStateInt(key: String, sourceLabel: String): Int? =
  if (key in this) requireReviewStateInt(key, sourceLabel) else null

private fun Map<String, Any?>.requireReviewStateList(key: String, sourceLabel: String): List<*> =
  this[key] as? List<*> ?: reviewStateError("$sourceLabel.$key", "must be a list.")

private fun Map<String, Any?>.optionalReviewStateList(key: String, sourceLabel: String): List<*>? =
  when (val value = this[key]) {
    null -> null
    is List<*> -> value
    else -> reviewStateError("$sourceLabel.$key", "must be a list.")
  }

private fun Any?.asReviewStateMap(sourceLabel: String): Map<String, Any?> =
  (this as? Map<*, *>)?.entries?.associate { (key, value) ->
    val stringKey = key as? String ?: reviewStateError(sourceLabel, "map keys must be strings.")
    stringKey to value
  } ?: reviewStateError(sourceLabel, "must be an object.")

private fun Any?.asGoalReviewArtifactMap(sourceLabel: String): Map<String, Any?> =
  (this as? Map<*, *>)?.entries?.associate { (key, value) ->
    val stringKey = key as? String ?: reviewStateError(sourceLabel, "map keys must be strings.")
    stringKey to value
  } ?: reviewStateError(sourceLabel, "must be an object.")

private fun blocksAdvance(unresolvedFindingCount: Int, findings: List<GoalSubtaskReviewCompactFinding>): Boolean =
  unresolvedFindingCount > 0 && (findings.isEmpty() || findings.any(GoalSubtaskReviewCompactFinding::isBlocker))

private val GIT_COMMIT_SHA = Regex("^[0-9a-f]{40}(?:[0-9a-f]{24})?$")

fun reviewStateError(fieldPath: String, reason: String, cause: Throwable? = null): Nothing =
  throw InvalidGoalSubtaskReviewStateSchemaError(
    sourceLabel = GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY,
    fieldPath = fieldPath,
    reason = reason,
    cause = cause,
  )
