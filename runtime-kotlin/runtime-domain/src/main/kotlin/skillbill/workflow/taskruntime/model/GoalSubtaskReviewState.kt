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

enum class GoalSubtaskReviewDisposition(val wireValue: String) {
  PENDING("pending"),
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
      if (GOAL_SUBTASK_REVIEW_RESULTS_ARTIFACT_KEY in artifacts) {
        reviewStateError(
          GOAL_SUBTASK_REVIEW_RESULTS_ARTIFACT_KEY,
          "must be absent before the first completed review pass.",
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
  val passResults: List<GoalSubtaskReviewPassResult> = emptyList(),
  val emittedPassCount: Int = 0,
  val contractVersion: String = GOAL_SUBTASK_REVIEW_STATE_CONTRACT_VERSION,
) {
  init {
    require(contractVersion == GOAL_SUBTASK_REVIEW_STATE_CONTRACT_VERSION) {
      "Unsupported goal review state contract '$contractVersion'."
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
  ): GoalSubtaskReviewState {
    val passNumber = reservedPassNumber
      ?: reviewStateError("reserved_pass_number", "must be present before completing a review pass.")
    val capReached = passNumber == GOAL_SUBTASK_REVIEW_MAX_PASSES &&
      blocksAdvance(unresolvedFindingCount, findings)
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
      disposition = if (capReached) GoalSubtaskReviewDisposition.REVIEW_CAP_REACHED else disposition,
      passResults = passResults + result,
    )
  }

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
  ).apply {
    reservedPassNumber?.let { put("reserved_pass_number", it) }
    reviewInputArtifact?.let { put("review_input_artifact", it) }
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
          "completed_pass_count", "disposition", "review_input_artifact", "pass_results", "emitted_pass_count",
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
          passResults = passResults,
          emittedPassCount = raw.requireReviewStateInt("emitted_pass_count", sourceLabel),
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

private fun reviewStateError(fieldPath: String, reason: String, cause: Throwable? = null): Nothing =
  throw InvalidGoalSubtaskReviewStateSchemaError(
    sourceLabel = GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY,
    fieldPath = fieldPath,
    reason = reason,
    cause = cause,
  )
