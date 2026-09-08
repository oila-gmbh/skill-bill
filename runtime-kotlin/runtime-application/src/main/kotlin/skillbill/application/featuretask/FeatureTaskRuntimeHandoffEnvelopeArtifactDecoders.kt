package skillbill.application.featuretask

import skillbill.application.featuretask.model.FeatureTaskRuntimePhaseLaunchBriefing
import skillbill.contracts.JsonCodec
import skillbill.error.InvalidFeatureTaskRuntimePersistenceSchemaError
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_DELIVERED_PROJECTIONS_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_INCOMPATIBLE_RECORD_GUIDANCE
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PHASE_BRIEFINGS_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeDeliveredProjectionRecord

/**
 * Strict decode of the per-phase briefings. [validateEnvelope] receives the raw `handoff_envelope`
 * wire map, not the decoded envelope, because the typed decoder tolerates undeclared wire keys and
 * any `contract_version` string: schema violations are only observable before decode discards them.
 */
fun phaseBriefingsFrom(
  artifacts: Map<String, Any?>,
  validateEnvelope: (Map<String, Any?>) -> Unit = {},
): Map<String, FeatureTaskRuntimePhaseLaunchBriefing> =
  decodeStrictKeyedArtifactMap(artifacts, FEATURE_TASK_RUNTIME_PHASE_BRIEFINGS_ARTIFACT_KEY) { _, briefingMap ->
    val briefing = FeatureTaskRuntimePhaseLaunchBriefing.fromArtifactMap(briefingMap)
    validateEnvelope(handoffEnvelopeWireMap(briefingMap))
    briefing
  }

private fun handoffEnvelopeWireMap(briefingMap: Map<String, Any?>): Map<String, Any?> =
  JsonCodec.anyToStringAnyMap(briefingMap["handoff_envelope"])
    ?: schemaError(
      "Feature-task-runtime artifact '$FEATURE_TASK_RUNTIME_PHASE_BRIEFINGS_ARTIFACT_KEY' entry must carry a " +
        "'handoff_envelope' object.",
    )

fun deliveredProjectionsFrom(
  artifacts: Map<String, Any?>,
  validateEnvelope: (Map<String, Any?>) -> Unit = {},
  validatePersistenceRecord: (Map<String, Any?>) -> Unit = {},
): Map<String, FeatureTaskRuntimeDeliveredProjectionRecord> =
  deliveredProjectionHistoryFrom(artifacts, validateEnvelope, validatePersistenceRecord)
    .values
    .groupBy(FeatureTaskRuntimeDeliveredProjectionRecord::consumerPhaseId)
    .mapValues { (_, records) -> records.maxBy(FeatureTaskRuntimeDeliveredProjectionRecord::iteration) }

fun deliveredProjectionHistoryFrom(
  artifacts: Map<String, Any?>,
  validateEnvelope: (Map<String, Any?>) -> Unit = {},
  validatePersistenceRecord: (Map<String, Any?>) -> Unit = {},
): Map<String, FeatureTaskRuntimeDeliveredProjectionRecord> =
  decodeStrictKeyedArtifactMap(artifacts, FEATURE_TASK_RUNTIME_DELIVERED_PROJECTIONS_ARTIFACT_KEY) { key, recordMap ->
    try {
      validatePersistenceRecord(recordMap)
    } catch (error: InvalidFeatureTaskRuntimePersistenceSchemaError) {
      val consumerPhaseId = recordMap["consumer_phase_id"] as? String ?: "<unknown>"
      throw InvalidFeatureTaskRuntimePersistenceSchemaError(
        sourceLabel = "consumer-phase:$consumerPhaseId/delivered-projection:$key",
        reason = "${error.reason}; $FEATURE_TASK_RUNTIME_INCOMPATIBLE_RECORD_GUIDANCE.",
        cause = error,
      )
    }
    val delivered = FeatureTaskRuntimeDeliveredProjectionRecord.fromArtifactMap(recordMap)
    validateEnvelope(
      JsonCodec.anyToStringAnyMap(recordMap["handoff_envelope"])
        ?: schemaError(
          "Feature-task-runtime artifact '$FEATURE_TASK_RUNTIME_DELIVERED_PROJECTIONS_ARTIFACT_KEY' entry must " +
            "carry a 'handoff_envelope' object.",
        ),
    )
    delivered
  }
