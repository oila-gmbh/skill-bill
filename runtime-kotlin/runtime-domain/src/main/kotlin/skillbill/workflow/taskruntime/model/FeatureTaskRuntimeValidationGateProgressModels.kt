package skillbill.workflow.taskruntime.model

import skillbill.boundary.OpenBoundaryMap
import skillbill.contracts.workflow.FEATURE_TASK_RUNTIME_PERSISTENCE_CONTRACT_VERSION
import skillbill.error.InvalidWorkflowStateSchemaError

const val FEATURE_TASK_RUNTIME_VALIDATION_GATE_PROGRESS_ARTIFACT_KEY: String =
  "feature_task_runtime_validation_gate_progress"

const val FEATURE_TASK_RUNTIME_BUILD_GATE_PROGRESS_ARTIFACT_KEY: String =
  "feature_task_runtime_build_gate_progress"

enum class FeatureTaskRuntimeValidationGateRepairWindowPhase(val wireValue: String) {
  NONE("none"),
  FINDINGS_OPEN("findings_open"),
  ;

  companion object {
    fun fromWire(value: String?): FeatureTaskRuntimeValidationGateRepairWindowPhase = when (value) {
      null, NONE.wireValue -> NONE
      FINDINGS_OPEN.wireValue -> FINDINGS_OPEN
      else -> throw InvalidWorkflowStateSchemaError(
        "FeatureTaskRuntimeValidationGateProgress.repair_window_phase must be 'none' or 'findings_open'.",
      )
    }
  }
}

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
  val remainingFindings: List<Map<String, String?>> = emptyList(),
  val completeFindings: List<Map<String, String?>> = emptyList(),
  val repairWindowPhase: FeatureTaskRuntimeValidationGateRepairWindowPhase =
    FeatureTaskRuntimeValidationGateRepairWindowPhase.NONE,
  val repairsUsed: Int = 0,
) {
  init {
    require(gateRunCount >= 0) {
      "FeatureTaskRuntimeValidationGateProgress.gateRunCount must be >= 0, was $gateRunCount."
    }
    require(gateRuns.size <= gateRunCount) {
      "FeatureTaskRuntimeValidationGateProgress.gateRuns size ${gateRuns.size} exceeds gateRunCount $gateRunCount."
    }
    require(repairsUsed >= 0) {
      "FeatureTaskRuntimeValidationGateProgress.repairsUsed must be >= 0, was $repairsUsed."
    }
  }

  @OpenBoundaryMap("Runtime-owned validation gate progress at the durable workflow-artifact seam")
  fun toArtifactMap(): Map<String, Any?> = linkedMapOf(
    "contract_version" to FEATURE_TASK_RUNTIME_PERSISTENCE_CONTRACT_VERSION,
    "gate_run_count" to gateRunCount,
    "gate_runs" to gateRuns.map { it.toArtifactMap() },
    "remaining_findings" to remainingFindings,
    "complete_findings" to completeFindings,
    "repair_window_phase" to repairWindowPhase.wireValue,
    "repairs_used" to repairsUsed,
  )

  companion object {
    @OpenBoundaryMap("Runtime-owned validation gate progress decode from durable workflow artifacts")
    fun fromArtifactMap(raw: Map<String, Any?>): FeatureTaskRuntimeValidationGateProgress =
      FeatureTaskRuntimeValidationGateProgress(
        gateRunCount = raw.asStarMap().gateProgressInt("gate_run_count"),
        gateRuns = decodeGateRuns(raw["gate_runs"]),
        remainingFindings = decodeFindings(raw["remaining_findings"], "remaining_findings"),
        completeFindings = decodeFindings(raw["complete_findings"], "complete_findings"),
        repairWindowPhase = FeatureTaskRuntimeValidationGateRepairWindowPhase.fromWire(
          raw["repair_window_phase"] as? String,
        ),
        repairsUsed = raw.asStarMap().gateProgressOptionalInt("repairs_used") ?: 0,
      )

    private fun decodeGateRuns(raw: Any?): List<FeatureTaskRuntimeValidationGateRunRecord> {
      val runsRaw = raw as? List<*>
        ?: throw InvalidWorkflowStateSchemaError(
          "FeatureTaskRuntimeValidationGateProgress is missing gate_runs.",
        )
      return runsRaw.mapIndexed { index, entry ->
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
    }

    private fun decodeFindings(raw: Any?, field: String): List<Map<String, String?>> {
      if (raw == null) return emptyList()
      val list = raw as? List<*>
        ?: throw InvalidWorkflowStateSchemaError(
          "FeatureTaskRuntimeValidationGateProgress.$field must be a list.",
        )
      return list.mapIndexed { index, entry ->
        val map = entry as? Map<*, *>
          ?: throw InvalidWorkflowStateSchemaError(
            "FeatureTaskRuntimeValidationGateProgress.$field[$index] must be a mapping.",
          )
        linkedMapOf(
          "module" to (map["module"] as? String),
          "rule_or_test_id" to (map["rule_or_test_id"] as? String),
          "message" to (map["message"] as? String),
          "location" to (map["location"] as? String),
        )
      }
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

private fun Map<*, *>.gateProgressOptionalInt(key: String): Int? {
  if (!containsKey(key) || this[key] == null) {
    return null
  }
  return gateProgressInt(key)
}
