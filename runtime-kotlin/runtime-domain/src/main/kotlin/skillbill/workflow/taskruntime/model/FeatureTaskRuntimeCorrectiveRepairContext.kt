package skillbill.workflow.taskruntime.model

import java.security.MessageDigest

const val FEATURE_TASK_RUNTIME_CORRECTIVE_REPAIR_CONTEXT_CONTRACT_VERSION: String = "0.1"

data class FeatureTaskRuntimeCorrectiveRepairContext(
  val phaseId: String,
  val attempt: Int,
  val rejectionRule: String,
  val rejectionPath: String,
  val payloadFreeConstraint: String,
  val diagnosticLocator: CorrectiveRepairDiagnosticLocator?,
  val captured: CorrectiveRepairCapturedResponse,
  val repairTurn: Int? = null,
  val budget: FeatureTaskRuntimeCorrectiveRepairBudget = FeatureTaskRuntimeCorrectiveRepairBudget.DEFAULT,
  val acceptedAfterStructuralRepair: Boolean = false,
  /**
   * Payload-free digest/location evidence from a prior successful delimiter-only structural repair on
   * this capture. When present, correlates original/repaired digests and source location with the
   * phase, attempt, and repair turn carried by this context. Never carries response body text.
   */
  val structuralRepairEvidence: FeatureTaskRuntimePhaseOutputRepairEvidence? = null,
  val diagnosticDegradationClass: FeatureTaskRuntimeDiagnosticFailureClass? = null,
  val contractVersion: String = FEATURE_TASK_RUNTIME_CORRECTIVE_REPAIR_CONTEXT_CONTRACT_VERSION,
) {
  init {
    require(contractVersion == FEATURE_TASK_RUNTIME_CORRECTIVE_REPAIR_CONTEXT_CONTRACT_VERSION) {
      "FeatureTaskRuntimeCorrectiveRepairContext.contractVersion must be " +
        "'$FEATURE_TASK_RUNTIME_CORRECTIVE_REPAIR_CONTEXT_CONTRACT_VERSION', was '$contractVersion'."
    }
    require(phaseId.isNotBlank()) { "FeatureTaskRuntimeCorrectiveRepairContext.phaseId must be non-blank." }
    require(attempt >= 0) { "FeatureTaskRuntimeCorrectiveRepairContext.attempt must be >= 0, was $attempt." }
    require(repairTurn == null || repairTurn >= 0) {
      "FeatureTaskRuntimeCorrectiveRepairContext.repairTurn must be >= 0 when present, was $repairTurn."
    }
    require(rejectionRule.isNotBlank()) {
      "FeatureTaskRuntimeCorrectiveRepairContext.rejectionRule must be non-blank."
    }
    require(rejectionPath.isNotBlank()) {
      "FeatureTaskRuntimeCorrectiveRepairContext.rejectionPath must be non-blank."
    }
    require((diagnosticLocator != null) xor (diagnosticDegradationClass != null)) {
      "FeatureTaskRuntimeCorrectiveRepairContext must carry a diagnostic locator xor a typed " +
        "diagnostic degradation class."
    }
    require(structuralRepairEvidence == null || acceptedAfterStructuralRepair) {
      "FeatureTaskRuntimeCorrectiveRepairContext.structuralRepairEvidence requires " +
        "acceptedAfterStructuralRepair=true so syntax-repair correlation cannot disagree with the flag."
    }
    // Constraint may be blank when the validator had no mechanical payload-free restatement; the
    // consumer then falls back to its own payload-free sentence rather than a value-bearing reason.
    val exact = captured as? CorrectiveRepairCapturedResponse.Exact
    require(exact == null || exact.utf8ByteCount <= budget.maxResponseUtf8Bytes) {
      "Exact captured response is ${exact?.utf8ByteCount} UTF-8 bytes against the " +
        "${budget.maxResponseUtf8Bytes}-byte response budget."
    }
  }

  /** Builds the authorized prompt projection, validating budgets before any body is framed. */
  fun promptProjection(): CorrectiveRepairPromptProjection = CorrectiveRepairPromptProjection.from(this)
}

/**
 * Authorized repair prompt projection. Exact body text appears only here; fallbacks are payload-free
 * and name the private diagnostic locator for authorized lookup.
 */
