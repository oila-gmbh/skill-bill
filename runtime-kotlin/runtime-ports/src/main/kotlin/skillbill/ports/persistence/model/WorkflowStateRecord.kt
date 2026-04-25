package skillbill.ports.persistence.model

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
