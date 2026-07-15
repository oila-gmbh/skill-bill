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
  val mode: String? = null,
)

data class WorkflowContinueDecision(
  val view: WorkflowContinueView,
  val shouldReopen: Boolean,
  val resumeStepId: String,
  val nextAttemptCount: Int,
)

/**
 * Resolves which of a resume step's required-upstream artifacts are absent for a given
 * workflow snapshot. The presence rule is family-specific: most families judge presence by
 * a top-level artifact key existing in the durable artifacts map ([DEFAULT]), but a family
 * whose upstream outputs live in a private per-phase store supplies its own resolver so the
 * generic resume gate reads its authoritative state rather than the top-level key. Pure
 * domain function: no JDBC/HTTP/Files and no clock/random.
 */
data class ResolvedRequiredArtifact(
  val present: Boolean,
  val value: Any?,
)

interface RequiredArtifactPresenceResolver {
  /**
   * Returns the subset of [requiredArtifacts] (upstream phase/artifact ids the [resumeStepId]
   * consumes) that are NOT present for [snapshot], preserving [requiredArtifacts] order.
   */
  fun missingRequiredArtifacts(
    snapshot: WorkflowSnapshotView,
    resumeStepId: String,
    requiredArtifacts: List<String>,
  ): List<String>

  fun resolveRequiredArtifact(snapshot: WorkflowSnapshotView, artifactKey: String): ResolvedRequiredArtifact =
    ResolvedRequiredArtifact(
      present = snapshot.artifacts.containsKey(artifactKey),
      value = snapshot.artifacts[artifactKey],
    )

  companion object {
    /**
     * Top-level-key presence: an upstream id is present iff the durable artifacts map carries
     * a matching key. Preserves the historical `snapshot.artifacts.containsKey` behavior for
     * every family that does not override it.
     */
    val DEFAULT: RequiredArtifactPresenceResolver =
      object : RequiredArtifactPresenceResolver {
        override fun missingRequiredArtifacts(
          snapshot: WorkflowSnapshotView,
          resumeStepId: String,
          requiredArtifacts: List<String>,
        ): List<String> =
          requiredArtifacts.filterNot(snapshot.artifacts::containsKey)
      }
  }
}

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
  val workflowMode: String? = null,
  val requiredArtifactPresenceResolver: RequiredArtifactPresenceResolver =
    RequiredArtifactPresenceResolver.DEFAULT,
)
