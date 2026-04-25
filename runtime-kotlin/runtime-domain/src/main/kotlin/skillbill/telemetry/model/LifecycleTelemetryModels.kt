package skillbill.telemetry.model

data class FeatureImplementStartedRecord(
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

data class FeatureImplementFinishedRecord(
  val sessionId: String,
  val completionStatus: String,
  val planCorrectionCount: Int,
  val planTaskCount: Int,
  val planPhaseCount: Int,
  val featureFlagUsed: Boolean,
  val featureFlagPattern: String,
  val filesCreated: Int,
  val filesModified: Int,
  val tasksCompleted: Int,
  val reviewIterations: Int,
  val auditResult: String,
  val auditIterations: Int,
  val validationResult: String,
  val boundaryHistoryWritten: Boolean,
  val boundaryHistoryValue: String,
  val prCreated: Boolean,
  val planDeviationNotes: String,
  val childSteps: List<Map<String, Any?>>,
)

data class QualityCheckStartedRecord(
  val sessionId: String,
  val routedSkill: String,
  val detectedStack: String,
  val scopeType: String,
  val initialFailureCount: Int,
)

data class QualityCheckFinishedRecord(
  val sessionId: String,
  val finalFailureCount: Int,
  val iterations: Int,
  val result: String,
  val failingCheckNames: List<String>,
  val unsupportedReason: String,
)

data class FeatureVerifyStartedRecord(
  val sessionId: String,
  val acceptanceCriteriaCount: Int,
  val rolloutRelevant: Boolean,
  val specSummary: String,
)

data class FeatureVerifyFinishedRecord(
  val sessionId: String,
  val featureFlagAuditPerformed: Boolean,
  val reviewIterations: Int,
  val auditResult: String,
  val completionStatus: String,
  val historyRelevance: String,
  val historyHelpfulness: String,
  val gapsFound: List<String>,
)

data class PrDescriptionGeneratedRecord(
  val sessionId: String,
  val commitCount: Int,
  val filesChangedCount: Int,
  val wasEditedByUser: Boolean,
  val prCreated: Boolean,
  val prTitle: String,
)
