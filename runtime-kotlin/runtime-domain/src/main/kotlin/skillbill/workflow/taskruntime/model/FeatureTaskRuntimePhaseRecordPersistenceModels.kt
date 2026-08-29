package skillbill.workflow.taskruntime.model

import skillbill.boundary.OpenBoundaryMap
import skillbill.contracts.JsonSupport
import skillbill.contracts.workflow.FEATURE_TASK_RUNTIME_PERSISTENCE_CONTRACT_VERSION
import skillbill.error.InvalidWorkflowStateSchemaError
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import java.math.BigDecimal
import java.math.BigInteger

/**
 * Durable per-phase record: one entry per phase id holding its latest persisted state.
 * `finishedAt`/`durationMillis`/`outputArtifact` are nullable because a phase may be
 * persisted while still running; a finished phase carries all three.
 *
 * `startedAt` is re-minted on every running transition so `durationMillis` measures only
 * the current run, never spanning the resume gap; `firstStartedAt` preserves the original
 * first-started timestamp across resumes. A phase the runtime blocked on persists a
 * terminal `blocked` status with [blockedReason] so blocked-ness survives ledger pruning.
 */
data class FeatureTaskRuntimePhaseRecord(
  val phaseId: String,
  val status: String,
  val attemptCount: Int,
  val startedAt: String,
  val firstStartedAt: String = startedAt,
  val finishedAt: String? = null,
  val durationMillis: Long? = null,
  val resolvedAgentId: String,
  val executionOrigin: FeatureTaskRuntimePhaseExecutionOrigin =
    FeatureTaskRuntimePhaseExecutionOrigin.AGENT_EXECUTED,
  val outputArtifact: String? = null,
  /**
   * Schema-rejected agent output kept as diagnostic evidence. Held apart from [outputArtifact] because it
   * is invalid by construction: storing it as an output makes resume hydration re-validate and reject it.
   */
  val rejectedOutput: String? = null,
  val blockedReason: String? = null,
  val failureDisposition: FeatureTaskRuntimeFailureDisposition? = null,
  val fileManifestBefore: List<String> = emptyList(),
  val fileManifestAfter: List<String> = emptyList(),
  val fileManifestIntroduced: List<String> = emptyList(),
  /** Latest backward-edge context for the resume watermark: the loop and per-edge iteration. */
  val loopId: String? = null,
  val edgeIteration: Int? = null,
  val reviewPassNumber: Int? = null,
  val repairEvidence: FeatureTaskRuntimePhaseOutputRepairEvidence? = null,
  /**
   * The model the phase's child was actually launched with, exactly as handed to the agent CLI —
   * including Cursor's merged `model[effort=…]` form. Null when the phase ran with no model
   * directive, which is also the shape every record written before this field holds.
   */
  val launchedModel: String? = null,
  val launchedEffort: String? = null,
  val reviewRunId: String? = null,
) {
  init {
    require(phaseId.isNotBlank()) { "FeatureTaskRuntimePhaseRecord.phaseId must be non-blank." }
    require(status.isNotBlank()) { "FeatureTaskRuntimePhaseRecord.status must be non-blank." }
    require(attemptCount >= 1) {
      "FeatureTaskRuntimePhaseRecord.attemptCount must be >= 1, was $attemptCount."
    }
    require(startedAt.isNotBlank()) { "FeatureTaskRuntimePhaseRecord.startedAt must be non-blank." }
    require(firstStartedAt.isNotBlank()) { "FeatureTaskRuntimePhaseRecord.firstStartedAt must be non-blank." }
    require(resolvedAgentId.isNotBlank()) { "FeatureTaskRuntimePhaseRecord.resolvedAgentId must be non-blank." }
    durationMillis?.let { duration ->
      require(duration >= 0) { "FeatureTaskRuntimePhaseRecord.durationMillis must be non-negative, was $duration." }
    }
    edgeIteration?.let { iteration ->
      require(iteration >= 1) {
        "FeatureTaskRuntimePhaseRecord.edgeIteration must be >= 1 when present, was $iteration."
      }
    }
    reviewPassNumber?.let { pass ->
      require(phaseId == "review" && pass >= 1) {
        "FeatureTaskRuntimePhaseRecord.reviewPassNumber must be >= 1 and present only for review."
      }
    }
    launchedModel?.let { model ->
      require(model.isNotBlank()) { "FeatureTaskRuntimePhaseRecord.launchedModel must be non-blank when present." }
    }
    launchedEffort?.let { effort ->
      require(effort.isNotBlank()) { "FeatureTaskRuntimePhaseRecord.launchedEffort must be non-blank when present." }
      require(launchedModel != null) {
        "FeatureTaskRuntimePhaseRecord.launchedEffort requires launchedModel; the launch pair moves as a unit."
      }
    }
    reviewRunId?.let { runId ->
      require(phaseId == "review" && runId.isNotBlank()) {
        "FeatureTaskRuntimePhaseRecord.reviewRunId must be non-blank and present only for review."
      }
    }
  }

  @OpenBoundaryMap("Feature-task-runtime per-phase record artifact map at the durable workflow-artifact seam")
  fun toArtifactMap(): Map<String, Any?> = linkedMapOf<String, Any?>(
    "contract_version" to FEATURE_TASK_RUNTIME_PERSISTENCE_CONTRACT_VERSION,
    "record_kind" to "private_phase_record",
    "phase_id" to phaseId,
    "status" to status,
    "attempt_count" to attemptCount,
    "started_at" to startedAt,
    "first_started_at" to firstStartedAt,
    "resolved_agent_id" to resolvedAgentId,
    "execution_origin" to executionOrigin.wireValue,
  ).apply {
    finishedAt?.let { put("finished_at", it) }
    durationMillis?.let { put("duration_millis", it) }
    outputArtifact?.let { put("output_artifact", it) }
    blockedReason?.let { put("blocked_reason", it) }
    failureDisposition?.let { put("failure_disposition", it.wireValue) }
    if (fileManifestBefore.isNotEmpty()) put("file_manifest_before", fileManifestBefore)
    if (fileManifestAfter.isNotEmpty()) put("file_manifest_after", fileManifestAfter)
    if (fileManifestIntroduced.isNotEmpty()) put("file_manifest_introduced", fileManifestIntroduced)
    loopId?.let { put("loop_id", it) }
    edgeIteration?.let { put("edge_iteration", it) }
    reviewPassNumber?.let { put("review_pass_number", it) }
    repairEvidence?.let { put("repair_evidence", it.toArtifactMap()) }
    putLaunchPair()
  }

  private fun MutableMap<String, Any?>.putLaunchPair() {
    launchedModel?.let { put("launched_model", it) }
    launchedEffort?.let { put("launched_effort", it) }
    reviewRunId?.let { put("review_run_id", it) }
  }

  companion object {
    /** Strict decode; loud-fails on any missing or malformed required field. */
    @OpenBoundaryMap("Feature-task-runtime per-phase record decode from the durable workflow-artifact map")
    fun fromArtifactMap(raw: Map<String, Any?>): FeatureTaskRuntimePhaseRecord {
      requireCompatibleShape(raw)
      val phaseId = requireKnownFeatureTaskRuntimePhaseId(raw.requireStringField("phase_id"), "phase_id")
      return try {
        FeatureTaskRuntimePhaseRecord(
          phaseId = phaseId,
          status = raw.requireStringField("status"),
          attemptCount = raw.requireIntField("attempt_count"),
          startedAt = raw.requireStringField("started_at"),
          firstStartedAt = raw.requireStringField("first_started_at"),
          finishedAt = raw.optionalStringField("finished_at"),
          durationMillis = raw.optionalLongField("duration_millis"),
          resolvedAgentId = raw.requireStringField("resolved_agent_id"),
          executionOrigin = FeatureTaskRuntimePhaseExecutionOrigin.fromWireValue(
            raw.requireStringField("execution_origin"),
          ),
          outputArtifact = raw.optionalStringField("output_artifact"),
          rejectedOutput = null,
          blockedReason = raw.optionalStringField("blocked_reason"),
          failureDisposition = raw.optionalStringField("failure_disposition")?.let { value ->
            FeatureTaskRuntimeFailureDisposition.fromWireValue(value) ?: incompatiblePhaseRecord()
          },
          fileManifestBefore = raw.optionalStringListField("file_manifest_before"),
          fileManifestAfter = raw.optionalStringListField("file_manifest_after"),
          fileManifestIntroduced = raw.optionalStringListField("file_manifest_introduced"),
          loopId = raw.optionalStringField("loop_id"),
          edgeIteration = raw.optionalIntField("edge_iteration"),
          reviewPassNumber = raw.optionalIntField("review_pass_number"),
          repairEvidence = raw["repair_evidence"]?.let { value ->
            val evidence = value as? Map<*, *>
              ?: incompatiblePhaseRecord()
            @Suppress("UNCHECKED_CAST")
            FeatureTaskRuntimePhaseOutputRepairEvidence.fromArtifactMap(
              evidence.entries.associate { (key, item) -> key.toString() to item },
            )
          },
          launchedModel = raw.optionalStringField("launched_model"),
          launchedEffort = raw.optionalStringField("launched_effort"),
          reviewRunId = raw.optionalStringField("review_run_id"),
        )
      } catch (_: IllegalArgumentException) {
        incompatiblePhaseRecord()
      }
    }

    /** Key-shape and identity guard: an unknown key is drift, not a field to ignore. */
    private fun requireCompatibleShape(raw: Map<String, Any?>) {
      val required = setOf(
        "contract_version", "record_kind", "phase_id", "status", "attempt_count", "started_at",
        "first_started_at", "resolved_agent_id", "execution_origin",
      )
      val allowed = required + setOf(
        "finished_at", "duration_millis", "output_artifact", "blocked_reason",
        "failure_disposition", "file_manifest_before", "file_manifest_after", "file_manifest_introduced",
        "loop_id", "edge_iteration", "review_pass_number", "rejected_output",
        "repair_evidence", "launched_model", "launched_effort", "review_run_id",
      )
      val missing = required - raw.keys
      val unknown = raw.keys - allowed
      val identityDetail = when {
        raw["record_kind"] != "private_phase_record" -> "record_kind was '${raw["record_kind"]}'"
        raw["contract_version"] != FEATURE_TASK_RUNTIME_PERSISTENCE_CONTRACT_VERSION ->
          "contract_version was '${raw["contract_version"]}'"

        else -> null
      }
      if (missing.isNotEmpty() || unknown.isNotEmpty() || identityDetail != null) {
        // The cause must be named. The contract version is shared across record kinds, so an
        // additive key on this record alone cannot bump it — meaning a newer row read by an older
        // build fails on `unknown` while its version string matches. Reporting only the version
        // would send the operator hunting a migration that does not exist.
        incompatiblePhaseRecord(
          listOfNotNull(
            identityDetail,
            missing.takeIf { it.isNotEmpty() }?.let { "missing required keys ${it.sorted()}" },
            unknown.takeIf { it.isNotEmpty() }?.let {
              "unknown keys ${it.sorted()} (a row written by a newer runtime than this build)"
            },
          ),
        )
      }
    }

    private fun incompatiblePhaseRecord(details: List<String> = emptyList()): Nothing =
      throw InvalidWorkflowStateSchemaError(
        "Private feature-task-runtime phase record is incompatible with persistence contract " +
          "$FEATURE_TASK_RUNTIME_PERSISTENCE_CONTRACT_VERSION" +
          details.takeIf { it.isNotEmpty() }?.joinToString(prefix = " (", postfix = ")").orEmpty() +
          "; $FEATURE_TASK_RUNTIME_INCOMPATIBLE_RECORD_GUIDANCE.",
      )
  }
}
