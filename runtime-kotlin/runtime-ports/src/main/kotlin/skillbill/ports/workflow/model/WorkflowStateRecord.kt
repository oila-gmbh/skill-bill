package skillbill.ports.workflow.model

import skillbill.workflow.decomposition.model.IssueKey
import skillbill.workflow.engine.model.SessionId
import skillbill.workflow.engine.model.WorkflowId

enum class FeatureTaskWorkflowMode(val wireValue: String) {
  PROSE("prose"),
  RUNTIME("runtime"),
  ;

  companion object {
    fun fromWireValue(value: String): FeatureTaskWorkflowMode? = entries.firstOrNull { it.wireValue == value }
  }
}

data class WorkflowStateRecord(
  val workflowId: WorkflowId,
  val sessionId: SessionId,
  val workflowName: String,
  val contractVersion: String,
  val workflowStatus: String,
  val currentStepId: String,
  val stepsJson: String,
  val artifactsJson: String,
  val startedAt: String?,
  val updatedAt: String?,
  val finishedAt: String?,
  val mode: FeatureTaskWorkflowMode? = null,
  val implementationSkill: String? = null,
  val issueKey: IssueKey? = null,
  val stateEnteredAt: String? = null,
  val stateEnteredAtEstimated: Boolean = false,
)

data class FeatureImplementSessionSummary(
  val sessionId: SessionId,
  val issueKeyProvided: Boolean,
  val issueKeyType: String,
  val specInputTypes: List<String>,
  val specWordCount: Int,
  val featureSize: String,
  val featureName: String,
  val rolloutNeeded: Boolean,
  val acceptanceCriteriaCount: Int,
  val openQuestionsCount: Int,
  val specSummary: String,
)

data class FeatureVerifySessionSummary(
  val sessionId: SessionId,
  val acceptanceCriteriaCount: Int,
  val rolloutRelevant: Boolean,
  val specSummary: String,
)
