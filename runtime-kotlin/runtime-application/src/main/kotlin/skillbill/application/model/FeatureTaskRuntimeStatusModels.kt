package skillbill.application.model

/** Request for the read-only status projection of one runtime workflow. */
data class FeatureTaskRuntimeStatusRequest(
  val workflowId: String,
  val dbPathOverride: String? = null,
) {
  init {
    require(workflowId.isNotBlank()) { "FeatureTaskRuntimeStatusRequest.workflowId is required." }
  }
}

/**
 * One ordered phase's read-only status. [resolvedAgentId] is null when no record exists yet for
 * the phase (it has not started).
 */
data class FeatureTaskRuntimePhaseStatus(
  val phaseId: String,
  val status: String,
  val attemptCount: Int,
  val resolvedAgentId: String?,
  val finished: Boolean,
)

/**
 * The read-only status projection for one workflow. [phases] follow the definition's `stepIds`
 * order; the counts and [currentPhaseId] are derived from them.
 */
data class FeatureTaskRuntimeStatusProjection(
  val workflowId: String,
  val featureSize: String?,
  val phases: List<FeatureTaskRuntimePhaseStatus>,
  val completeCount: Int,
  val pendingCount: Int,
  val blockedCount: Int,
  /** First not-yet-complete phase in definition order, or null when all complete. */
  val currentPhaseId: String?,
  /** The run's resolved feature branch, or null when branch setup has not run yet. */
  val resolvedBranch: String? = null,
  /**
   * The ledger-derived finalizing agent (Seam A rollup), computed even for a single-spec run where
   * no goal-continuation outcome is persisted. Null when no terminal agent attribution exists yet.
  */
  val finalizingAgentId: String? = null,
  val decomposeTerminal: FeatureTaskRuntimeDecomposeTerminalStatus? = null,
  val workerLease: FeatureTaskRuntimeWorkerLeaseStatus? = null,
)

data class FeatureTaskRuntimeWorkerLeaseStatus(
  val liveness: String,
  val phaseId: String,
  val phaseAttempt: Int,
  val leaseState: String,
  val heartbeatAt: String,
  val expiresAt: String,
)

data class FeatureTaskRuntimeDecomposeTerminalStatus(
  val reason: String,
  val parentSpecPath: String,
  val decompositionManifestPath: String,
  val subtaskSpecPaths: List<String>,
) {
  val subtaskCount: Int get() = subtaskSpecPaths.size
}
