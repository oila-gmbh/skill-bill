package skillbill.application.workflow

import skillbill.contracts.JsonSupport
import skillbill.error.InvalidWorkflowStateSchemaError
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PHASE_LEDGER_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerEntry

internal fun decodeWorkflowArtifacts(artifactsJson: String): Map<String, Any?> =
  JsonSupport.parseObjectOrNull(artifactsJson)
    ?.let(JsonSupport::jsonElementToValue)
    ?.let(JsonSupport::anyToStringAnyMap)
    .orEmpty()

internal fun decodeFeatureTaskRuntimePhaseRecords(
  artifacts: Map<String, Any?>,
): Map<String, FeatureTaskRuntimePhaseRecord> {
  val raw = JsonSupport.anyToStringAnyMap(artifacts[FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY])
    ?: return emptyMap()
  return raw.mapValues { (_, value) ->
    FeatureTaskRuntimePhaseRecord.fromArtifactMap(
      JsonSupport.anyToStringAnyMap(value)
        ?: throw IllegalArgumentException("Feature-task-runtime phase record entry is malformed."),
    )
  }
}

internal object FeatureTaskRuntimePhaseLedgerDecoder {
  fun decode(artifacts: Map<String, Any?>): List<FeatureTaskRuntimePhaseLedgerEntry> {
    if (FEATURE_TASK_RUNTIME_PHASE_LEDGER_ARTIFACT_KEY !in artifacts) return emptyList()
    val raw = artifacts[FEATURE_TASK_RUNTIME_PHASE_LEDGER_ARTIFACT_KEY] as? List<*>
      ?: invalid("must decode to a JSON array")
    return raw.map { value ->
      val entry = JsonSupport.anyToStringAnyMap(value) ?: invalid("contains a malformed entry")
      try {
        FeatureTaskRuntimePhaseLedgerEntry.fromArtifactMap(entry)
      } catch (error: InvalidWorkflowStateSchemaError) {
        rethrow(error)
      } catch (error: IllegalArgumentException) {
        invalid("contains a malformed entry", error)
      }
    }
  }

  private fun invalid(reason: String, cause: Throwable? = null): Nothing = throw InvalidWorkflowStateSchemaError(
    "Workflow artifact '$FEATURE_TASK_RUNTIME_PHASE_LEDGER_ARTIFACT_KEY' $reason.",
    cause,
  )

  private fun rethrow(error: InvalidWorkflowStateSchemaError): Nothing = throw error
}
