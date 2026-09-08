package skillbill.workflow.taskruntime.model

import skillbill.boundary.OpenBoundaryMap
import skillbill.contracts.JsonSupport
import skillbill.error.InvalidWorkflowStateSchemaError

/**
 * Durable, bounded record of diagnostic-evidence writes that failed and were degraded rather than
 * thrown. A diagnostic is private evidence about a run; losing one is a diagnosability regression, and
 * killing the run over it turns a bookkeeping fault into a lost workflow. The runtime therefore keeps
 * the failure here and proceeds.
 *
 * Every field is payload-free by construction: the key, the phase, the ordinals, and a typed failure
 * class. No agent output, prompt text, database path, or process output is ever recorded.
 */
const val FEATURE_TASK_RUNTIME_DIAGNOSTIC_SIGNALS_ARTIFACT_KEY: String =
  "feature_task_runtime_diagnostic_signals"

/** Oldest entries are pruned first, so a pathological run cannot grow the workflow row without bound. */
const val FEATURE_TASK_RUNTIME_DIAGNOSTIC_SIGNALS_LIMIT: Int = 32

/** Typed classes of degraded diagnostic-persistence failure; the class is what an operator triages on. */
enum class FeatureTaskRuntimeDiagnosticFailureClass(val wireValue: String) {
  /** Divergent evidence already committed under the same key. */
  CONFLICT("conflict"),
  PERMISSION("permission"),
  CORRUPT("corrupt"),
  SCHEMA("schema"),
  PERSISTENCE("persistence"),
  ;

  companion object {
    fun fromWire(raw: String): FeatureTaskRuntimeDiagnosticFailureClass = entries.firstOrNull { it.wireValue == raw }
      ?: throw InvalidWorkflowStateSchemaError(
        "Feature-task-runtime diagnostic failure class '$raw' is not a declared class.",
      )
  }
}

data class FeatureTaskRuntimeDiagnosticSignal(
  val operation: String,
  val failureClass: FeatureTaskRuntimeDiagnosticFailureClass,
  val conflictingKey: String,
  val phaseId: String,
  val attempt: Int,
  /** Null when the failure was not scoped to a single repair turn, as on a newest-turn read. */
  val repairTurn: Int?,
  val generation: Int,
  val recordedAt: String,
) {
  init {
    require(operation.isNotBlank()) { "FeatureTaskRuntimeDiagnosticSignal.operation must be non-blank." }
    require(conflictingKey.isNotBlank()) { "FeatureTaskRuntimeDiagnosticSignal.conflictingKey must be non-blank." }
    require(phaseId.isNotBlank()) { "FeatureTaskRuntimeDiagnosticSignal.phaseId must be non-blank." }
    require(attempt >= 0) { "FeatureTaskRuntimeDiagnosticSignal.attempt must be >= 0." }
    require(repairTurn == null || repairTurn >= 0) {
      "FeatureTaskRuntimeDiagnosticSignal.repairTurn must be >= 0 when present."
    }
    require(generation >= 0) { "FeatureTaskRuntimeDiagnosticSignal.generation must be >= 0." }
    require(recordedAt.isNotBlank()) { "FeatureTaskRuntimeDiagnosticSignal.recordedAt must be non-blank." }
  }

  /** The operator-facing sentence. Names the key, the phase and the attempt, and nothing else. */
  fun operatorSummary(): String =
    "Diagnostic evidence write '$operation' failed as '${failureClass.wireValue}' for key " +
      "'$conflictingKey' (phase '$phaseId', attempt $attempt, repair turn ${repairTurn ?: "any"}, " +
      "generation $generation). The evidence was not retained; the run continued."

  @OpenBoundaryMap("Feature-task-runtime diagnostic signal artifact map at the durable workflow-artifact seam")
  fun toArtifactMap(): Map<String, Any?> = linkedMapOf(
    "operation" to operation,
    "failure_class" to failureClass.wireValue,
    "conflicting_key" to conflictingKey,
    "phase_id" to phaseId,
    "attempt" to attempt,
    "repair_turn" to repairTurn,
    "generation" to generation,
    "recorded_at" to recordedAt,
  )

  companion object {
    @OpenBoundaryMap("Feature-task-runtime diagnostic signal decode from the durable workflow-artifact map")
    fun fromArtifactMap(raw: Map<String, Any?>): FeatureTaskRuntimeDiagnosticSignal =
      FeatureTaskRuntimeDiagnosticSignal(
        operation = raw.requireStringField("operation"),
        failureClass = FeatureTaskRuntimeDiagnosticFailureClass.fromWire(raw.requireStringField("failure_class")),
        conflictingKey = raw.requireStringField("conflicting_key"),
        phaseId = raw.requireStringField("phase_id"),
        attempt = raw.requireIntField("attempt"),
        repairTurn = raw["repair_turn"]?.let { raw.requireIntField("repair_turn") },
        generation = raw.requireIntField("generation"),
        recordedAt = raw.requireStringField("recorded_at"),
      )
  }
}

/** Strict decode of the durable signal list; an absent key decodes to no signals. */
@OpenBoundaryMap("Feature-task-runtime diagnostic signal list decode from the durable workflow-artifact map")
fun featureTaskRuntimeDiagnosticSignalsFromWire(raw: Any?): List<FeatureTaskRuntimeDiagnosticSignal> {
  if (raw == null) return emptyList()
  val entries = raw as? List<*>
    ?: throw InvalidWorkflowStateSchemaError("Feature-task-runtime diagnostic signals must be an array.")
  return entries.map { entry ->
    FeatureTaskRuntimeDiagnosticSignal.fromArtifactMap(
      JsonSupport.anyToStringAnyMap(entry)
        ?: throw InvalidWorkflowStateSchemaError("Feature-task-runtime diagnostic signal must be an object."),
    )
  }
}

/** Appends one signal, pruning the oldest beyond [FEATURE_TASK_RUNTIME_DIAGNOSTIC_SIGNALS_LIMIT]. */
fun featureTaskRuntimeAppendDiagnosticSignal(
  existing: List<FeatureTaskRuntimeDiagnosticSignal>,
  signal: FeatureTaskRuntimeDiagnosticSignal,
): List<FeatureTaskRuntimeDiagnosticSignal> =
  (existing + signal).takeLast(FEATURE_TASK_RUNTIME_DIAGNOSTIC_SIGNALS_LIMIT)
