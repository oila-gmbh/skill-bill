package skillbill.application.featuretask.model

import skillbill.application.idestatus.model.IdeStatusCurrentPhaseExecution

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
  val executionOrigin: String? = null,
  /**
   * Why this phase last re-entered, when it did. Null when the phase never re-ran. Reported instead
   * of leaving an operator to guess from a bare attempt count whether the runtime was correcting
   * malformed output or carrying partial implementation work forward.
   */
  val continuationKind: String? = null,
  /** The model/effort the phase's child was launched with; null when the phase ran with no directive. */
  val launchedModel: String? = null,
  val launchedEffort: String? = null,
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
  val auditRepair: FeatureTaskRuntimeAuditRepairStatus? = null,
  /** Runtime-measured validation gate runs while validate is active; null when not yet started. */
  val gateRunCount: Int? = null,
  /**
   * Authoritative current-phase execution measure for [currentPhaseId] only. Null when there is no
   * current phase or no reliable durable counter for it. Never carries a completed neighbouring
   * phase's historical loop or pass.
   */
  val currentPhaseExecution: IdeStatusCurrentPhaseExecution? = null,
  /**
   * Degraded diagnostic-persistence signals for this workflow. Null when none exist; never a
   * count-zero object. [count] is the durable list size and the remaining fields are the most
   * recently appended signal.
   */
  val degradedDiagnostic: FeatureTaskRuntimeDegradedDiagnosticStatus? = null,
  val operatorDecisionPause: FeatureTaskRuntimeOperatorDecisionPause? = null,
)

data class FeatureTaskRuntimeOperatorDecisionPause(
  val phaseId: String,
  val reason: String? = null,
) {
  init {
    require(phaseId.isNotBlank()) { "FeatureTaskRuntimeOperatorDecisionPause.phaseId is required." }
    require(reason == null || reason.isNotBlank()) {
      "FeatureTaskRuntimeOperatorDecisionPause.reason must be absent or non-blank."
    }
  }
}

data class FeatureTaskRuntimeDegradedDiagnosticStatus(
  val count: Int,
  val failureClass: String,
  val phaseId: String,
  val attempt: Int,
) {
  init {
    require(count >= 1) { "FeatureTaskRuntimeDegradedDiagnosticStatus.count must be >= 1 when present." }
    require(failureClass.isNotBlank()) {
      "FeatureTaskRuntimeDegradedDiagnosticStatus.failureClass must be non-blank."
    }
    require(phaseId.isNotBlank()) { "FeatureTaskRuntimeDegradedDiagnosticStatus.phaseId must be non-blank." }
    require(attempt >= 0) { "FeatureTaskRuntimeDegradedDiagnosticStatus.attempt must be >= 0." }
  }
}

data class FeatureTaskRuntimeAuditRepairStatus(
  val firstPassConvergence: Boolean,
  val auditGapIterationCount: Int,
)

data class FeatureTaskRuntimeDecomposeTerminalStatus(
  val reason: String,
  val parentSpecPath: String,
  val decompositionManifestPath: String,
  val subtaskSpecPaths: List<String>,
) {
  val subtaskCount: Int get() = subtaskSpecPaths.size
}
