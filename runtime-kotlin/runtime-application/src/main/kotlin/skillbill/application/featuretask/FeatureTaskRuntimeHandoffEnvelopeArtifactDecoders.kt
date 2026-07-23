package skillbill.application.featuretask

import skillbill.application.model.FeatureTaskRuntimePhaseLaunchBriefing
import skillbill.contracts.JsonSupport
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_DELIVERED_PROJECTIONS_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PHASE_BRIEFINGS_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeDeliveredProjectionRecord

/**
 * Strict decode of the per-phase briefings. [validateEnvelope] receives the raw `handoff_envelope`
 * wire map, not the decoded envelope, because the typed decoder tolerates undeclared wire keys and
 * any `contract_version` string: schema violations are only observable before decode discards them.
 */
internal fun phaseBriefingsFrom(
  artifacts: Map<String, Any?>,
  validateEnvelope: (Map<String, Any?>) -> Unit = {},
): Map<String, FeatureTaskRuntimePhaseLaunchBriefing> =
  decodeStrictKeyedArtifactMap(artifacts, FEATURE_TASK_RUNTIME_PHASE_BRIEFINGS_ARTIFACT_KEY) { _, briefingMap ->
    val briefing = FeatureTaskRuntimePhaseLaunchBriefing.fromArtifactMap(briefingMap)
    validateEnvelope(handoffEnvelopeWireMap(briefingMap))
    briefing
  }

private fun handoffEnvelopeWireMap(briefingMap: Map<String, Any?>): Map<String, Any?> =
  JsonSupport.anyToStringAnyMap(briefingMap["handoff_envelope"])
    ?: schemaError(
      "Feature-task-runtime artifact '$FEATURE_TASK_RUNTIME_PHASE_BRIEFINGS_ARTIFACT_KEY' entry must carry a " +
        "'handoff_envelope' object.",
    )

internal fun deliveredProjectionsFrom(
  artifacts: Map<String, Any?>,
  validateEnvelope: (Map<String, Any?>) -> Unit = {},
): Map<String, FeatureTaskRuntimeDeliveredProjectionRecord> =
  decodeStrictKeyedArtifactMap(artifacts, FEATURE_TASK_RUNTIME_DELIVERED_PROJECTIONS_ARTIFACT_KEY) { _, recordMap ->
    val delivered = FeatureTaskRuntimeDeliveredProjectionRecord.fromArtifactMap(recordMap)
    validateEnvelope(
      JsonSupport.anyToStringAnyMap(recordMap["handoff_envelope"])
        ?: schemaError(
          "Feature-task-runtime artifact '$FEATURE_TASK_RUNTIME_DELIVERED_PROJECTIONS_ARTIFACT_KEY' entry must " +
            "carry a 'handoff_envelope' object.",
        ),
    )
    delivered
  }
