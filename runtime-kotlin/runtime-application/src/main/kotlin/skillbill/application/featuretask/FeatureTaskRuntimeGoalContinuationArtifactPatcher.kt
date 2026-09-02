package skillbill.application.featuretask

import skillbill.agentaddon.model.AgentAddonSelection
import skillbill.application.workflow.model.WorkflowFamily
import skillbill.error.InvalidGoalSubtaskReviewStateSchemaError
import skillbill.ports.workflow.WorkflowStateRepository
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaseline
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.engine.model.WorkflowUpdateInput
import skillbill.workflow.goal.model.GOAL_SUBTASK_REVIEW_RESULTS_ARTIFACT_KEY
import skillbill.workflow.goal.model.GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY
import skillbill.workflow.goal.model.GoalSubtaskReviewArtifactDecoder
import skillbill.workflow.goal.model.GoalSubtaskReviewDisposition
import skillbill.workflow.goal.model.GoalSubtaskReviewState
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeGoalContinuationArtifact
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeQualityGateSelection

class FeatureTaskRuntimeGoalContinuationArtifactPatcher(
  private val engine: WorkflowEngine,
) {
  fun save(record: WorkflowStateSnapshot, workflowStates: WorkflowStateRepository, patch: Map<String, Any?>) {
    val updated = engine.updateRecord(
      WorkflowFamily.TASK_RUNTIME.definition,
      record,
      WorkflowUpdateInput(
        workflowStatus = record.workflowStatus,
        currentStepId = record.currentStepId,
        stepUpdates = null,
        artifactsPatch = patch,
        sessionId = record.sessionId.orEmpty(),
      ),
    )
    WorkflowFamily.TASK_RUNTIME.save(workflowStates, updated)
  }
}

fun continuationFromArtifacts(artifacts: Map<String, Any?>): FeatureTaskRuntimeGoalContinuationArtifact? =
  GoalSubtaskReviewArtifactDecoder.decodeContinuationOnly(artifacts)

fun reviewStateFromArtifacts(artifacts: Map<String, Any?>): GoalSubtaskReviewState? =
  GoalSubtaskReviewArtifactDecoder.decodeReviewStateOnly(artifacts)

fun GoalSubtaskReviewState.canRecoverReviewBase(): Boolean = disposition == GoalSubtaskReviewDisposition.PENDING

fun GoalSubtaskReviewState.matches(
  baseline: GoalSubtaskReviewBaseline,
  continuation: FeatureTaskRuntimeGoalContinuationArtifact,
): Boolean = reviewBaseSha == baseline.reviewBaseSha &&
  baselineUntrackedPaths == baseline.baselineUntrackedPaths.distinct().sorted() &&
  codeReviewMode == continuation.codeReviewMode

fun rawReviewResultsFromArtifacts(artifacts: Map<String, Any?>, state: GoalSubtaskReviewState): Map<String, String> {
  val decoded = GoalSubtaskReviewArtifactDecoder.decode(artifacts)
    ?: rawReviewResultError(
      GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY,
      "must be present whenever raw goal-subtask review results are read.",
    )
  if (decoded.state != state) {
    rawReviewResultError(
      GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY,
      "changed while reading its durable raw review results.",
    )
  }
  return decoded.rawResults
}

fun rawReviewResultError(fieldPath: String, reason: String): Nothing = throw InvalidGoalSubtaskReviewStateSchemaError(
  sourceLabel = GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY,
  fieldPath = fieldPath,
  reason = reason,
)

fun continuationPatch(
  continuation: FeatureTaskRuntimeGoalContinuationArtifact?,
  existing: FeatureTaskRuntimeGoalContinuationArtifact?,
): Map<String, Any?> = when {
  continuation == null || continuation == existing -> emptyMap()
  existing == null -> mapOf(
    FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_ARTIFACT_KEY to continuation.toArtifactMap(),
    "install_sync_result" to mapOf(
      "status" to "deferred",
      "reason" to
        "goal-continuation defers installer, uninstall, and install-sync flows until the parent goal exits; " +
        "deferred install sync must not block subtask completion",
    ),
  )
  else -> mapOf(FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_ARTIFACT_KEY to continuation.toArtifactMap())
}

fun FeatureTaskRuntimeGoalContinuationArtifact?.compatibleWith(
  supplied: FeatureTaskRuntimeGoalContinuationArtifact?,
): Boolean {
  if (this == null || supplied == null) return true
  val healed = copy(
    agentAddonSelection = AgentAddonSelection(),
    validationDepth = validationDepth ?: supplied.validationDepth,
    qualityGateSelection = qualityGateSelection ?: FeatureTaskRuntimeQualityGateSelection.VALIDATE,
    parallelReviewAgent = null,
  )
  return healed == supplied.copy(
    agentAddonSelection = AgentAddonSelection(),
    parallelReviewAgent = null,
  )
}

internal enum class GoalReviewBaseField(val wireValue: String) {
  REVIEW_BASE("review_base_sha"),
  REMEDIATION_BASE("remediation_base_sha"),
}

internal fun reviewStatePatch(
  request: GoalContinuationStateRecordRequest,
  artifacts: Map<String, Any?>,
  existingContinuation: FeatureTaskRuntimeGoalContinuationArtifact?,
): Map<String, Any?> {
  val continuation = request.continuation ?: return emptyMap()
  val state = reviewStateFromArtifacts(artifacts)
  val baseline = request.reviewBaseline
  if (state != null) {
    check(baseline == null || state.matches(baseline, continuation)) {
      "Goal-subtask review baseline and execution mode are immutable on resume."
    }
    return emptyMap()
  }
  check(existingContinuation == null) {
    "Goal-subtask review state is missing for an existing child workflow; " +
      "refusing to capture a replacement baseline."
  }
  requireNotNull(baseline) {
    "Goal-subtask review baseline is required when opening a child workflow; " +
      "refusing to create an unpinned review scope."
  }
  if (GOAL_SUBTASK_REVIEW_RESULTS_ARTIFACT_KEY in artifacts) {
    rawReviewResultError(
      GOAL_SUBTASK_REVIEW_RESULTS_ARTIFACT_KEY,
      "must be absent before the goal-subtask review state exists.",
    )
  }
  return mapOf(
    GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY to GoalSubtaskReviewState.initial(
      reviewBaseSha = baseline.reviewBaseSha,
      baselineUntrackedPaths = baseline.baselineUntrackedPaths,
      codeReviewMode = continuation.codeReviewMode,
    ).toArtifactMap(),
  )
}
