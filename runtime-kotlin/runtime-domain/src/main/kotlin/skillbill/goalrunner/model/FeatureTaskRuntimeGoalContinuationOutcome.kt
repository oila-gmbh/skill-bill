package skillbill.goalrunner.model

import skillbill.boundary.OpenBoundaryMap
import skillbill.workflow.taskruntime.model.optionalStringField
import skillbill.workflow.taskruntime.model.optionalStringListField
import skillbill.workflow.taskruntime.model.requireIntField
import skillbill.workflow.taskruntime.model.requireStringField

data class FeatureTaskRuntimeGoalContinuationOutcome(
  val issueKey: String,
  val subtaskId: Int,
  val status: GoalRunnerTerminalStatus,
  val workflowId: String,
  val commitSha: String? = null,
  val blockedReason: String? = null,
  val lastResumableStep: String,
  val finalizingAgentId: String? = null,
  val participatingAgentIds: List<String> = emptyList(),
) {
  constructor(
    issueKey: String,
    subtaskId: Int,
    status: String,
    workflowId: String,
    commitSha: String? = null,
    blockedReason: String? = null,
    lastResumableStep: String,
    finalizingAgentId: String? = null,
    participatingAgentIds: List<String> = emptyList(),
  ) : this(
    issueKey = issueKey,
    subtaskId = subtaskId,
    status = requireNotNull(GoalRunnerTerminalStatus.fromWire(status)) {
      "Unknown goal-continuation outcome status '$status'."
    },
    workflowId = workflowId,
    commitSha = commitSha,
    blockedReason = blockedReason,
    lastResumableStep = lastResumableStep,
    finalizingAgentId = finalizingAgentId,
    participatingAgentIds = participatingAgentIds,
  )

  init {
    require(issueKey.isNotBlank()) { "FeatureTaskRuntimeGoalContinuationOutcome.issueKey must be non-blank." }
    require(subtaskId > 0) { "FeatureTaskRuntimeGoalContinuationOutcome.subtaskId must be positive." }
    require(workflowId.isNotBlank()) { "FeatureTaskRuntimeGoalContinuationOutcome.workflowId must be non-blank." }
    require(lastResumableStep.isNotBlank()) {
      "FeatureTaskRuntimeGoalContinuationOutcome.lastResumableStep must be non-blank."
    }
  }

  @OpenBoundaryMap("Feature-task-runtime goal-continuation outcome artifact map at the durable workflow-artifact seam")
  fun toArtifactMap(): Map<String, Any?> = linkedMapOf<String, Any?>(
    "issue_key" to issueKey,
    "subtask_id" to subtaskId,
    "status" to status.wireValue,
    "workflow_id" to workflowId,
    "last_resumable_step" to lastResumableStep,
    "participating_agent_ids" to participatingAgentIds,
  ).apply {
    commitSha?.let { put("commit_sha", it) }
    blockedReason?.let { put("blocked_reason", it) }
    finalizingAgentId?.let { put("finalizing_agent_id", it) }
  }

  companion object {
    /** Strict decode; loud-fails on a missing or malformed required field. New agent fields are additive-optional. */
    @OpenBoundaryMap("Feature-task-runtime goal-continuation outcome decode from the durable workflow-artifact map")
    fun fromArtifactMap(raw: Map<String, Any?>): FeatureTaskRuntimeGoalContinuationOutcome =
      FeatureTaskRuntimeGoalContinuationOutcome(
        issueKey = raw.requireStringField("issue_key"),
        subtaskId = raw.requireIntField("subtask_id"),
        status = requireNotNull(GoalRunnerTerminalStatus.fromWire(raw.requireStringField("status"))) {
          "Unknown goal-continuation outcome status '${raw["status"]}'."
        },
        workflowId = raw.requireStringField("workflow_id"),
        commitSha = raw.optionalStringField("commit_sha"),
        blockedReason = raw.optionalStringField("blocked_reason"),
        lastResumableStep = raw.requireStringField("last_resumable_step"),
        finalizingAgentId = raw.optionalStringField("finalizing_agent_id"),
        participatingAgentIds = raw.optionalStringListField("participating_agent_ids"),
      )
  }
}
