package skillbill.ports.persistence.model

enum class FeatureTaskWorkflowMode(val wireValue: String) {
  PROSE("prose"),
  RUNTIME("runtime"),
  ;

  companion object {
    fun fromWireValue(value: String): FeatureTaskWorkflowMode? = entries.firstOrNull { it.wireValue == value }
  }
}

data class WorkflowStateRecord(
  val workflowId: String,
  val sessionId: String,
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
)

data class FeatureImplementSessionSummary(
  val sessionId: String,
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
  val sessionId: String,
  val acceptanceCriteriaCount: Int,
  val rolloutRelevant: Boolean,
  val specSummary: String,
)
