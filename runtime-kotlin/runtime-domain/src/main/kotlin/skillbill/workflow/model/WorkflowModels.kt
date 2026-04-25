package skillbill.workflow.model

data class WorkflowStepState(
  val stepId: String,
  val status: String,
  val attemptCount: Int,
)

data class WorkflowUpdateInput(
  val workflowStatus: String,
  val currentStepId: String,
  val stepUpdates: List<Map<String, Any?>>?,
  val artifactsPatch: Map<String, Any?>?,
  val sessionId: String,
)

data class WorkflowStateSnapshot(
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

data class WorkflowContinueDecision(
  val payload: Map<String, Any?>,
  val shouldReopen: Boolean,
  val resumeStepId: String,
  val nextAttemptCount: Int,
)

data class WorkflowDefinition(
  val skillName: String,
  val workflowName: String,
  val workflowIdPrefix: String,
  val defaultSessionPrefix: String,
  val contractVersion: String,
  val workflowStatuses: Set<String>,
  val stepStatuses: Set<String>,
  val terminalStatuses: Set<String>,
  val defaultInitialStepId: String,
  val stepIds: List<String>,
  val stepLabels: Map<String, String>,
  val requiredArtifactsByStep: Map<String, List<String>>,
  val resumeActions: Map<String, String>,
  val continuationReferenceSections: Map<String, List<String>>,
  val continuationDirectives: Map<String, String>,
  val continuationArtifactOrder: List<String>,
  val openPriorStepsCompleted: Boolean,
  val completedTerminalSummaryArtifact: String,
)
