package skillbill.workflow.model

import skillbill.boundary.OpenBoundaryMap

data class WorkflowStepState(
  val stepId: String,
  val status: String,
  val attemptCount: Int,
)

data class WorkflowUpdateInput(
  val workflowStatus: String,
  val currentStepId: String,
  /**
   * SKILL-52.1 open boundary: caller-supplied step-update patches.
   * Each entry carries an arbitrary JSON object validated downstream
   * by `WorkflowEngine.validateUpdate`; typing it would prematurely
   * lock the workflow-state schema before its discriminator family
   * is extracted.
   */
  @OpenBoundaryMap("Caller-supplied JSON patch for workflow step updates")
  val stepUpdates: List<Map<String, Any?>>?,
  /**
   * SKILL-52.1 open boundary: caller-supplied artifacts patch
   * merged verbatim into the durable workflow artifacts JSON. Free-form
   * by contract because artifact values are workflow-family-specific
   * payloads with no shared schema.
   */
  @OpenBoundaryMap("Caller-supplied JSON patch for durable workflow artifacts")
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
  val view: WorkflowContinueView,
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
