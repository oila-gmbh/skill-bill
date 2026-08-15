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

data class FullValidateRepairPlanItem(
  val identities: List<String>,
) {
  init {
    require(identities.isNotEmpty()) {
      "FullValidateRepairPlanItem.identities must not be empty."
    }
    require(identities.all { it.isNotBlank() }) {
      "FullValidateRepairPlanItem.identities must not contain blank identities."
    }
  }

  @OpenBoundaryMap("FULL validate repair-plan item at the durable workflow-artifact seam")
  fun toArtifactMap(): Map<String, Any?> = linkedMapOf("identities" to identities)
}

data class FullValidateSubstantiationReceipt(
  val identity: String,
  val rootCause: String,
  val changedPathsOrSymbols: List<String>,
  val rationale: String,
) {
  fun covers(requiredIdentity: String): Boolean =
    identity == requiredIdentity &&
      rootCause.isNotBlank() &&
      changedPathsOrSymbols.any { it.isNotBlank() }

  @OpenBoundaryMap("FULL validate substantiation receipt at the durable workflow-artifact seam")
  fun toArtifactMap(): Map<String, Any?> = linkedMapOf(
    "identity" to identity,
    "root_cause" to rootCause,
    "changed_paths_or_symbols" to changedPathsOrSymbols,
    "rationale" to rationale,
  )
}

data class FeatureTaskRuntimeValidationGateProgress(
  val gateRunCount: Int,
  val gateRuns: List<FeatureTaskRuntimeValidationGateRunRecord>,
  val remainingFindings: List<Map<String, String?>> = emptyList(),
  val remainingFindingsDroppedCount: Int = 0,
  val completeFindings: List<Map<String, String?>> = emptyList(),
  val findingsPageOffset: Int = 0,
  val confirmationRetriesUsed: Int = 0,
  val discoveryIdentities: List<String> = emptyList(),
  val validationRepairPlan: List<FullValidateRepairPlanItem> = emptyList(),
  val substantiationReceipts: List<FullValidateSubstantiationReceipt> = emptyList(),
) {
  init {
    require(gateRunCount >= 0) {
      "FeatureTaskRuntimeValidationGateProgress.gateRunCount must be >= 0, was $gateRunCount."
    }
    require(gateRuns.size <= gateRunCount) {
      "FeatureTaskRuntimeValidationGateProgress.gateRuns size ${gateRuns.size} exceeds gateRunCount $gateRunCount."
    }
    require(remainingFindingsDroppedCount >= 0) {
      "FeatureTaskRuntimeValidationGateProgress.remainingFindingsDroppedCount must be >= 0, " +
        "was $remainingFindingsDroppedCount."
    }
    require(findingsPageOffset >= 0) {
      "FeatureTaskRuntimeValidationGateProgress.findingsPageOffset must be >= 0, was $findingsPageOffset."
    }
    require(confirmationRetriesUsed >= 0) {
      "FeatureTaskRuntimeValidationGateProgress.confirmationRetriesUsed must be >= 0, " +
        "was $confirmationRetriesUsed."
    }
  }

  @OpenBoundaryMap("Runtime-owned validation gate progress at the durable workflow-artifact seam")
  fun toArtifactMap(): Map<String, Any?> = linkedMapOf(
    "contract_version" to FEATURE_TASK_RUNTIME_PERSISTENCE_CONTRACT_VERSION,
    "gate_run_count" to gateRunCount,
    "gate_runs" to gateRuns.map { it.toArtifactMap() },
    "remaining_findings" to remainingFindings,
    "remaining_findings_dropped_count" to remainingFindingsDroppedCount,
    "complete_findings" to completeFindings,
    "findings_page_offset" to findingsPageOffset,
    "confirmation_retries_used" to confirmationRetriesUsed,
    "discovery_identities" to discoveryIdentities,
    "validation_repair_plan" to validationRepairPlan.map { it.toArtifactMap() },
    "substantiation_receipts" to substantiationReceipts.map { it.toArtifactMap() },
  )

  companion object {
    @OpenBoundaryMap("Runtime-owned validation gate progress decode from durable workflow artifacts")
    fun fromArtifactMap(raw: Map<String, Any?>): FeatureTaskRuntimeValidationGateProgress =
      FeatureTaskRuntimeValidationGateProgress(
        gateRunCount = raw.asStarMap().gateProgressInt("gate_run_count"),
        gateRuns = decodeGateRuns(raw["gate_runs"]),
        remainingFindings = decodeRemainingFindings(raw["remaining_findings"]),
        remainingFindingsDroppedCount = decodeOptionalNonNegativeInt(
          raw["remaining_findings_dropped_count"],
          "remaining_findings_dropped_count",
        ),
        completeFindings = decodeRemainingFindings(raw["complete_findings"]),
        findingsPageOffset = decodeOptionalNonNegativeInt(raw["findings_page_offset"], "findings_page_offset"),
        confirmationRetriesUsed = decodeOptionalNonNegativeInt(
          raw["confirmation_retries_used"],
          "confirmation_retries_used",
        ),
        discoveryIdentities = decodeOptionalStringList(raw["discovery_identities"], "discovery_identities"),
        validationRepairPlan = decodeOptionalRepairPlan(raw["validation_repair_plan"]),
        substantiationReceipts = decodeOptionalReceipts(raw["substantiation_receipts"]),
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

    private fun decodeOptionalNonNegativeInt(raw: Any?, field: String): Int = when (raw) {
      null -> 0
      is Int -> raw
      is Long -> raw.toInt()
      is Number -> raw.toInt()
      else -> throw InvalidWorkflowStateSchemaError(
        "FeatureTaskRuntimeValidationGateProgress.$field must be an int.",
      )
    }

    private fun decodeOptionalStringList(raw: Any?, field: String): List<String> {
      if (raw == null) return emptyList()
      val list = raw as? List<*>
        ?: throw InvalidWorkflowStateSchemaError(
          "FeatureTaskRuntimeValidationGateProgress.$field must be a list.",
        )
      return list.mapIndexed { index, entry ->
        entry as? String
          ?: throw InvalidWorkflowStateSchemaError(
            "FeatureTaskRuntimeValidationGateProgress.$field[$index] must be a string.",
          )
      }
    }

    private fun decodeOptionalRepairPlan(raw: Any?): List<FullValidateRepairPlanItem> {
      if (raw == null) return emptyList()
      val list = raw as? List<*>
        ?: throw InvalidWorkflowStateSchemaError(
          "FeatureTaskRuntimeValidationGateProgress.validation_repair_plan must be a list.",
        )
      return list.mapIndexed { index, entry ->
        val map = entry as? Map<*, *>
          ?: throw InvalidWorkflowStateSchemaError(
            "FeatureTaskRuntimeValidationGateProgress.validation_repair_plan[$index] must be a mapping.",
          )
        val identities = decodeOptionalStringList(map["identities"], "validation_repair_plan[$index].identities")
        if (identities.isEmpty()) {
          throw InvalidWorkflowStateSchemaError(
            "FeatureTaskRuntimeValidationGateProgress.validation_repair_plan[$index].identities must not be empty.",
          )
        }
        FullValidateRepairPlanItem(identities = identities)
      }
    }

    private fun decodeOptionalReceipts(raw: Any?): List<FullValidateSubstantiationReceipt> {
      if (raw == null) return emptyList()
      val list = raw as? List<*>
        ?: throw InvalidWorkflowStateSchemaError(
          "FeatureTaskRuntimeValidationGateProgress.substantiation_receipts must be a list.",
        )
      return list.mapIndexed { index, entry ->
        val map = entry as? Map<*, *>
          ?: throw InvalidWorkflowStateSchemaError(
            "FeatureTaskRuntimeValidationGateProgress.substantiation_receipts[$index] must be a mapping.",
          )
        val pathsRaw = map["changed_paths_or_symbols"]
        val paths = when (pathsRaw) {
          null -> emptyList()
          is List<*> -> decodeOptionalStringList(pathsRaw, "substantiation_receipts[$index].changed_paths_or_symbols")
          else -> throw InvalidWorkflowStateSchemaError(
            "FeatureTaskRuntimeValidationGateProgress.substantiation_receipts[$index]." +
              "changed_paths_or_symbols must be a list.",
          )
        }
        FullValidateSubstantiationReceipt(
          identity = (map["identity"] as? String).orEmpty(),
          rootCause = (map["root_cause"] as? String).orEmpty(),
          changedPathsOrSymbols = paths,
          rationale = (map["rationale"] as? String).orEmpty(),
        )
      }
    }

    private fun decodeRemainingFindings(raw: Any?): List<Map<String, String?>> {
      if (raw == null) return emptyList()
      val list = raw as? List<*>
        ?: throw InvalidWorkflowStateSchemaError(
          "FeatureTaskRuntimeValidationGateProgress.remaining_findings must be a list.",
        )
      return list.mapIndexed { index, entry ->
        val map = entry as? Map<*, *>
          ?: throw InvalidWorkflowStateSchemaError(
            "FeatureTaskRuntimeValidationGateProgress.remaining_findings[$index] must be a mapping.",
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
