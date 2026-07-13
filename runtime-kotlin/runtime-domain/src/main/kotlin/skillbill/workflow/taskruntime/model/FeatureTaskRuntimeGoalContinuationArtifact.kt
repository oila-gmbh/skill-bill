package skillbill.workflow.taskruntime.model

import skillbill.boundary.OpenBoundaryMap
import skillbill.error.InvalidWorkflowStateSchemaError
import skillbill.workflow.model.CodeReviewExecutionMode

data class FeatureTaskRuntimeGoalContinuationArtifact(
  val issueKey: String,
  val subtaskId: Int,
  val suppressPr: Boolean,
  val goalBranch: String,
  val parentWorkflowId: String? = null,
  val codeReviewMode: CodeReviewExecutionMode,
  val parallelReviewAgent: String? = null,
) {
  init {
    require(issueKey.isNotBlank()) { "FeatureTaskRuntimeGoalContinuationArtifact.issueKey must be non-blank." }
    require(subtaskId > 0) { "FeatureTaskRuntimeGoalContinuationArtifact.subtaskId must be positive." }
    require(goalBranch.isNotBlank()) { "FeatureTaskRuntimeGoalContinuationArtifact.goalBranch must be non-blank." }
    parallelReviewAgent?.let {
      require(it.isNotBlank()) { "FeatureTaskRuntimeGoalContinuationArtifact.parallelReviewAgent must be non-blank." }
    }
  }

  @OpenBoundaryMap("Feature-task-runtime goal-continuation artifact map at the durable workflow-artifact seam")
  fun toArtifactMap(): Map<String, Any?> = linkedMapOf<String, Any?>(
    "issue_key" to issueKey,
    "subtask_id" to subtaskId,
    "suppress_pr" to suppressPr,
    "goal_branch" to goalBranch,
    "code_review_mode" to codeReviewMode.wireValue,
  ).apply {
    parentWorkflowId?.let { put("parent_workflow_id", it) }
    parallelReviewAgent?.let { put("parallel_review_agent", it) }
  }

  companion object {
    @OpenBoundaryMap("Feature-task-runtime goal-continuation decode from the durable workflow-artifact map")
    fun fromArtifactMap(raw: Map<String, Any?>): FeatureTaskRuntimeGoalContinuationArtifact {
      rejectUnknownGoalContinuationKeys(raw)
      return FeatureTaskRuntimeGoalContinuationArtifact(
        issueKey = raw.requireStringField("issue_key"),
        subtaskId = raw.requireIntField("subtask_id"),
        suppressPr = raw.requireGoalContinuationSuppressPr(),
        goalBranch = raw.requireStringField("goal_branch"),
        parentWorkflowId = raw.optionalStringField("parent_workflow_id"),
        codeReviewMode = raw.requireGoalContinuationCodeReviewMode(),
        parallelReviewAgent = raw.optionalStringField("parallel_review_agent"),
      )
    }
  }
}

private val goalContinuationKeys: Set<String> = setOf(
  "issue_key",
  "subtask_id",
  "suppress_pr",
  "goal_branch",
  "parent_workflow_id",
  "code_review_mode",
  "parallel_review_agent",
)

private fun rejectUnknownGoalContinuationKeys(raw: Map<String, Any?>) {
  raw.keys.firstOrNull { it !in goalContinuationKeys }?.let { key ->
    throw InvalidWorkflowStateSchemaError("Goal-continuation artifact field '$key' is not supported.")
  }
}

private fun Map<String, Any?>.requireGoalContinuationSuppressPr(): Boolean = optionalBooleanField("suppress_pr")
  ?: throw InvalidWorkflowStateSchemaError(
    "Goal-continuation artifact field 'suppress_pr' must be a boolean.",
  )

private fun Map<String, Any?>.requireGoalContinuationCodeReviewMode(): CodeReviewExecutionMode = try {
  CodeReviewExecutionMode.fromWire(requireStringField("code_review_mode"))
} catch (error: IllegalArgumentException) {
  throw InvalidWorkflowStateSchemaError("Goal-continuation artifact code_review_mode is invalid.", error)
}
