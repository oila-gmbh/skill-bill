package skillbill.workflow.taskruntime.model

import skillbill.boundary.OpenBoundaryMap
import skillbill.contracts.workflow.FEATURE_TASK_RUNTIME_PERSISTENCE_CONTRACT_VERSION
import skillbill.error.InvalidWorkflowStateSchemaError

sealed interface FeatureTaskRuntimeDerivationResult<out T> {
  data class Decided<T>(val value: T) : FeatureTaskRuntimeDerivationResult<T>
  data object Indecisive : FeatureTaskRuntimeDerivationResult<Nothing>
}

data class FeatureTaskRuntimeDerivedSettlement(
  val status: String,
  val failureDisposition: FeatureTaskRuntimeFailureDisposition?,
)

const val FEATURE_TASK_RUNTIME_DERIVATION_REASK_ARTIFACT_KEY: String =
  "feature_task_runtime_derivation_reask"

data class FeatureTaskRuntimeDerivationReaskState(
  val phaseId: String,
  val reaskCount: Int,
  val firstOutputArtifact: String,
  val secondOutputArtifact: String? = null,
  val authoritativeAttempt: Int,
) {
  init {
    require(phaseId.isNotBlank()) { "FeatureTaskRuntimeDerivationReaskState.phaseId must be non-blank." }
    require(reaskCount >= 1) {
      "FeatureTaskRuntimeDerivationReaskState.reaskCount must be >= 1, was $reaskCount."
    }
    require(firstOutputArtifact.isNotBlank()) {
      "FeatureTaskRuntimeDerivationReaskState.firstOutputArtifact must be non-blank."
    }
    require(authoritativeAttempt in setOf(1, 2)) {
      "FeatureTaskRuntimeDerivationReaskState.authoritativeAttempt must be 1 or 2, was $authoritativeAttempt."
    }
    if (authoritativeAttempt == 2) {
      require(!secondOutputArtifact.isNullOrBlank()) {
        "FeatureTaskRuntimeDerivationReaskState.secondOutputArtifact is required when authoritativeAttempt is 2."
      }
    }
  }

  @OpenBoundaryMap("Feature-task-runtime derivation re-ask state at the durable workflow-artifact seam")
  fun toArtifactMap(): Map<String, Any?> = linkedMapOf(
    "contract_version" to FEATURE_TASK_RUNTIME_PERSISTENCE_CONTRACT_VERSION,
    "record_kind" to "derivation_reask",
    "phase_id" to phaseId,
    "reask_count" to reaskCount,
    "first_output_artifact" to firstOutputArtifact,
    "authoritative_attempt" to authoritativeAttempt,
  ).apply {
    secondOutputArtifact?.let { put("second_output_artifact", it) }
  }

  companion object {
    @OpenBoundaryMap("Feature-task-runtime derivation re-ask decode from the durable workflow-artifact map")
    fun fromArtifactMap(raw: Map<String, Any?>): FeatureTaskRuntimeDerivationReaskState {
      if (raw["contract_version"] != FEATURE_TASK_RUNTIME_PERSISTENCE_CONTRACT_VERSION) {
        throw InvalidWorkflowStateSchemaError(
          "Feature-task-runtime derivation re-ask artifact uses unsupported persistence contract " +
            "version '${raw["contract_version"]}'.",
        )
      }
      if (raw["record_kind"] != "derivation_reask") {
        throw InvalidWorkflowStateSchemaError(
          "Feature-task-runtime derivation re-ask artifact record_kind was '${raw["record_kind"]}'.",
        )
      }
      return FeatureTaskRuntimeDerivationReaskState(
        phaseId = raw.requireStringField("phase_id"),
        reaskCount = raw.requireIntField("reask_count"),
        firstOutputArtifact = raw.requireStringField("first_output_artifact"),
        secondOutputArtifact = raw.optionalStringField("second_output_artifact"),
        authoritativeAttempt = raw.requireIntField("authoritative_attempt"),
      )
    }
  }
}
