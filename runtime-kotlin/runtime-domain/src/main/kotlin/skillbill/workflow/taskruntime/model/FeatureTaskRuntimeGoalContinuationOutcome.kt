package skillbill.workflow.taskruntime.model

import skillbill.boundary.OpenBoundaryMap

data class FeatureTaskRuntimeGoalContinuationOutcome(
  val issueKey: String,
  val subtaskId: Int,
  val status: String,
  val workflowId: String,
  val commitSha: String? = null,
  val blockedReason: String? = null,
  val lastResumableStep: String,
  val finalizingAgentId: String? = null,
  val participatingAgentIds: List<String> = emptyList(),
) {
  init {
    require(issueKey.isNotBlank()) { "FeatureTaskRuntimeGoalContinuationOutcome.issueKey must be non-blank." }
    require(subtaskId > 0) { "FeatureTaskRuntimeGoalContinuationOutcome.subtaskId must be positive." }
    require(status.isNotBlank()) { "FeatureTaskRuntimeGoalContinuationOutcome.status must be non-blank." }
    require(workflowId.isNotBlank()) { "FeatureTaskRuntimeGoalContinuationOutcome.workflowId must be non-blank." }
    require(lastResumableStep.isNotBlank()) {
      "FeatureTaskRuntimeGoalContinuationOutcome.lastResumableStep must be non-blank."
    }
  }

  @OpenBoundaryMap("Feature-task-runtime goal-continuation outcome artifact map at the durable workflow-artifact seam")
  fun toArtifactMap(): Map<String, Any?> = linkedMapOf<String, Any?>(
    "issue_key" to issueKey,
    "subtask_id" to subtaskId,
    "status" to status,
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
        status = raw.requireStringField("status"),
        workflowId = raw.requireStringField("workflow_id"),
        commitSha = raw.optionalStringField("commit_sha"),
        blockedReason = raw.optionalStringField("blocked_reason"),
        lastResumableStep = raw.requireStringField("last_resumable_step"),
        finalizingAgentId = raw.optionalStringField("finalizing_agent_id"),
        participatingAgentIds = raw.optionalStringListField("participating_agent_ids"),
      )
  }
}

/**
 * Durable per-phase launch briefing store. The assembled briefing is persisted, keyed
 * by phase id, before the phase agent is launched, so it is a durable handoff a consumer
 * reads rather than dead computation. Each entry is the latest briefing for that phase.
 */
const val FEATURE_TASK_RUNTIME_PHASE_BRIEFINGS_ARTIFACT_KEY: String = "feature_task_runtime_phase_briefings"
