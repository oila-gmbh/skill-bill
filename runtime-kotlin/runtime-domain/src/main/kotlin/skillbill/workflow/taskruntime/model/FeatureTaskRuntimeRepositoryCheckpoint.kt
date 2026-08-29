package skillbill.workflow.taskruntime.model

import skillbill.boundary.OpenBoundaryMap
import skillbill.contracts.JsonSupport
import skillbill.contracts.workflow.FEATURE_TASK_RUNTIME_HANDOFF_ENVELOPE_CONTRACT_VERSION
import skillbill.contracts.workflow.FEATURE_TASK_RUNTIME_PHASE_HANDOFF_CONTRACT_VERSION
import skillbill.error.InvalidFeatureTaskRuntimePhaseHandoffSchemaError
import skillbill.review.model.ReviewFindingVerdict
import skillbill.workflow.taskruntime.FeatureTaskRuntimeHandoffFoundationValidator
import skillbill.workflow.taskruntime.FeatureTaskRuntimePlanningProjectionValidator
import skillbill.workflow.taskruntime.NoopFeatureTaskRuntimePlanningProjectionValidator
import skillbill.workflow.goal.model.ValidationDepth

enum class FeatureTaskRuntimeRepositoryCheckpointPolicy(val wireValue: String) {
  NOT_REQUIRED("not_required"),
  MUST_MATCH("must_match"),
  REFRESH_FROM_REPOSITORY("refresh_from_repository"),
  ;

  companion object {
    fun fromWire(value: String): FeatureTaskRuntimeRepositoryCheckpointPolicy =
      entries.firstOrNull { it.wireValue == value }
        ?: unrecognizedHandoffWireValue("repository checkpoint policy", value)
  }
}

/**
 * Deterministic identification of the repository state a projection was derived against. [baseRef]
 * and [headRef] carry the base/head pair when the caller knows it; [fingerprint] is the equivalent
 * digest every policy compares on.
 */
data class FeatureTaskRuntimeRepositoryCheckpoint(
  val fingerprint: String,
  val baseRef: String? = null,
  val headRef: String? = null,
  val workingTreeOwnedPaths: List<String> = emptyList(),
) {
  init {
    require(fingerprint.isNotBlank()) {
      "FeatureTaskRuntimeRepositoryCheckpoint.fingerprint must be non-blank; an unidentified checkpoint " +
        "cannot satisfy must_match or refresh_from_repository."
    }
    require(fingerprint.length <= MAX_REPOSITORY_FINGERPRINT_LENGTH) {
      "FeatureTaskRuntimeRepositoryCheckpoint.fingerprint allows at most " +
        "$MAX_REPOSITORY_FINGERPRINT_LENGTH characters, had ${fingerprint.length}."
    }
    require(workingTreeOwnedPaths.none(String::isBlank)) {
      "FeatureTaskRuntimeRepositoryCheckpoint.workingTreeOwnedPaths must not contain blank entries."
    }
  }

  @OpenBoundaryMap("Feature-task-runtime repository checkpoint at the handoff-envelope wire seam")
  fun toEnvelopeMap(): Map<String, Any?> = linkedMapOf<String, Any?>("fingerprint" to fingerprint).apply {
    baseRef?.let { put("base_ref", it) }
    headRef?.let { put("head_ref", it) }
    if (workingTreeOwnedPaths.isNotEmpty()) put("working_tree_owned_paths", workingTreeOwnedPaths)
  }
}
