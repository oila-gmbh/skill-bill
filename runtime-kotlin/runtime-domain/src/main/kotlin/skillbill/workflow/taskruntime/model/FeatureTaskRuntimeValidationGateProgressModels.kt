package skillbill.workflow.taskruntime.model

import skillbill.boundary.OpenBoundaryMap
import skillbill.contracts.workflow.FEATURE_TASK_RUNTIME_PERSISTENCE_CONTRACT_VERSION
import skillbill.error.InvalidWorkflowStateSchemaError

const val FEATURE_TASK_RUNTIME_VALIDATION_GATE_PROGRESS_ARTIFACT_KEY: String =
  "feature_task_runtime_validation_gate_progress"

data class FeatureTaskRuntimeValidationGateRunRecord(
  val durationMs: Long,
  val outcome: String,
  val cacheMode: String,
  val executedWorkUnits: Int,
) {
  @OpenBoundaryMap("Runtime-owned validation gate run measurement at the durable workflow-artifact seam")
  fun toArtifactMap(): Map<String, Any?> = linkedMapOf(
    "duration_ms" to durationMs,
    "outcome" to outcome,
    "cache_mode" to cacheMode,
    "executed_work_units" to executedWorkUnits,
  )
}

data class FeatureTaskRuntimeValidationGateProgress(
  val gateRunCount: Int,
  val gateRuns: List<FeatureTaskRuntimeValidationGateRunRecord>,
) {
  init {
    require(gateRunCount >= 0) {
      "FeatureTaskRuntimeValidationGateProgress.gateRunCount must be >= 0, was $gateRunCount."
    }
    require(gateRuns.size <= gateRunCount) {
      "FeatureTaskRuntimeValidationGateProgress.gateRuns size ${gateRuns.size} exceeds gateRunCount $gateRunCount."
    }
  }

  @OpenBoundaryMap("Runtime-owned validation gate progress at the durable workflow-artifact seam")
  fun toArtifactMap(): Map<String, Any?> = linkedMapOf(
    "contract_version" to FEATURE_TASK_RUNTIME_PERSISTENCE_CONTRACT_VERSION,
    "gate_run_count" to gateRunCount,
    "gate_runs" to gateRuns.map { it.toArtifactMap() },
  )

  companion object {
    @OpenBoundaryMap("Runtime-owned validation gate progress decode from durable workflow artifacts")
    fun fromArtifactMap(raw: Map<String, Any?>): FeatureTaskRuntimeValidationGateProgress {
      val gateRunCount = raw.asStarMap().gateProgressInt("gate_run_count")
      val runsRaw = raw["gate_runs"] as? List<*>
        ?: throw InvalidWorkflowStateSchemaError(
          "FeatureTaskRuntimeValidationGateProgress is missing gate_runs.",
        )
      val gateRuns = runsRaw.mapIndexed { index, entry ->
        val map = entry as? Map<*, *>
          ?: throw InvalidWorkflowStateSchemaError(
            "FeatureTaskRuntimeValidationGateProgress.gate_runs[$index] must be a mapping.",
          )
        FeatureTaskRuntimeValidationGateRunRecord(
          durationMs = map.gateProgressLong("duration_ms"),
          outcome = map.gateProgressString("outcome"),
          cacheMode = map.gateProgressString("cache_mode"),
          executedWorkUnits = map.gateProgressInt("executed_work_units"),
        )
      }
      return FeatureTaskRuntimeValidationGateProgress(gateRunCount = gateRunCount, gateRuns = gateRuns)
    }
  }
}

private fun Map<String, Any?>.asStarMap(): Map<*, *> = this

private fun Map<*, *>.gateProgressString(key: String): String =
  this[key] as? String ?: throw InvalidWorkflowStateSchemaError("Missing required string field '$key'.")

private fun Map<*, *>.gateProgressInt(key: String): Int = when (val value = this[key]) {
  is Int -> value
  is Long -> value.toInt()
  is Number -> value.toInt()
  else -> throw InvalidWorkflowStateSchemaError("Missing required int field '$key'.")
}

private fun Map<*, *>.gateProgressLong(key: String): Long = when (val value = this[key]) {
  is Long -> value
  is Int -> value.toLong()
  is Number -> value.toLong()
  else -> throw InvalidWorkflowStateSchemaError("Missing required long field '$key'.")
}
