package skillbill.workflow.goal.model

import skillbill.boundary.OpenBoundaryMap
import skillbill.error.InvalidGoalSubtaskReviewStateSchemaError
import skillbill.error.InvalidWorkflowStateSchemaError
import skillbill.review.context.model.CodeReviewExecutionMode
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeGoalContinuationArtifact
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerdict

val GOAL_SUBTASK_REVIEW_PASS_VERDICTS: Set<FeatureTaskRuntimeVerdict> = setOf(
  FeatureTaskRuntimeVerdict.APPROVED,
  FeatureTaskRuntimeVerdict.CHANGES_REQUESTED,
  FeatureTaskRuntimeVerdict.REVIEW_CAP_REACHED,
  FeatureTaskRuntimeVerdict.REVIEW_SKIPPED_BY_USER,
)

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
