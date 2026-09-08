package skillbill.workflow.taskruntime.model

import skillbill.boundary.OpenBoundaryMap
import skillbill.contracts.workflow.FEATURE_TASK_RUNTIME_PERSISTENCE_CONTRACT_VERSION
import skillbill.error.InvalidWorkflowStateSchemaError

data class FeatureTaskRuntimeAuditGapPause(
  val pauseKind: String,
  val reason: String,
  val edgeIteration: Int,
  val operatorDecision: String? = null,
  val grantConsumed: Boolean = false,
) {
  init {
    require(pauseKind in setOf(AUDIT_GAP_PAUSE_KIND_NO_PROGRESS, AUDIT_GAP_PAUSE_KIND_WARN_THRESHOLD)) {
      "FeatureTaskRuntimeAuditGapPause.pauseKind must be no_progress or warn_threshold, was '$pauseKind'."
    }
    require(reason.isNotBlank()) { "FeatureTaskRuntimeAuditGapPause.reason must be non-blank." }
    require(edgeIteration >= 1) {
      "FeatureTaskRuntimeAuditGapPause.edgeIteration must be >= 1, was $edgeIteration."
    }
    require(
      operatorDecision == null ||
        operatorDecision in setOf(AUDIT_GAP_PAUSE_DECISION_RETRY_FIX, AUDIT_GAP_PAUSE_DECISION_ABANDON_SUBTASK),
    ) {
      "FeatureTaskRuntimeAuditGapPause.operatorDecision must be retry_fix or abandon_subtask, " +
        "was '$operatorDecision'."
    }
  }

  @OpenBoundaryMap("Feature-task-runtime audit-gap pause artifact map at the durable workflow-artifact seam")
  fun toArtifactMap(): Map<String, Any?> = linkedMapOf<String, Any?>(
    "contract_version" to FEATURE_TASK_RUNTIME_PERSISTENCE_CONTRACT_VERSION,
    "record_kind" to "audit_gap_pause",
    "pause_kind" to pauseKind,
    "reason" to reason,
    "edge_iteration" to edgeIteration,
    "grant_consumed" to grantConsumed,
  ).apply {
    operatorDecision?.let { put("operator_decision", it) }
  }

  companion object {
    /** Strict decode; loud-fails on a missing or malformed required field. */
    @OpenBoundaryMap("Feature-task-runtime audit-gap pause decode from the durable workflow-artifact map")
    fun fromArtifactMap(raw: Map<String, Any?>): FeatureTaskRuntimeAuditGapPause {
      requireExactAuditGapPauseFields(raw)
      if (raw["contract_version"] != FEATURE_TASK_RUNTIME_PERSISTENCE_CONTRACT_VERSION) {
        throw InvalidWorkflowStateSchemaError(
          "Feature-task-runtime audit-gap pause artifact uses unsupported persistence contract " +
            "version '${raw["contract_version"]}'; $FEATURE_TASK_RUNTIME_INCOMPATIBLE_RECORD_GUIDANCE.",
        )
      }
      if (raw["record_kind"] != "audit_gap_pause") {
        throw InvalidWorkflowStateSchemaError(
          "Feature-task-runtime audit-gap pause artifact must have kind 'audit_gap_pause'.",
        )
      }
      return FeatureTaskRuntimeAuditGapPause(
        pauseKind = raw.requireStringField("pause_kind"),
        reason = raw.requireStringField("reason"),
        edgeIteration = raw.requireIntField("edge_iteration"),
        operatorDecision = raw.optionalStringField("operator_decision"),
        grantConsumed = raw.optionalBooleanField("grant_consumed") ?: false,
      )
    }

    private fun requireExactAuditGapPauseFields(raw: Map<String, Any?>) {
      val expected = setOf(
        "contract_version",
        "record_kind",
        "pause_kind",
        "reason",
        "edge_iteration",
        "operator_decision",
        "grant_consumed",
      )
      val unexpected = raw.keys - expected
      if (unexpected.isNotEmpty()) {
        throw InvalidWorkflowStateSchemaError(
          "Feature-task-runtime audit-gap pause artifact contains unsupported fields; " +
            "$FEATURE_TASK_RUNTIME_INCOMPATIBLE_RECORD_GUIDANCE.",
        )
      }
    }
  }
}

data class FeatureTaskRuntimeAuditGapProgress(
  val criterionRefs: Set<String>,
  val repositoryFingerprint: String? = null,
) {
  init {
    require(criterionRefs.all(String::isNotBlank)) {
      "FeatureTaskRuntimeAuditGapProgress.criterionRefs must not contain blank refs."
    }
  }

  @OpenBoundaryMap("Feature-task-runtime audit-gap progress artifact map at the durable workflow-artifact seam")
  fun toArtifactMap(): Map<String, Any?> = linkedMapOf<String, Any?>(
    "contract_version" to FEATURE_TASK_RUNTIME_PERSISTENCE_CONTRACT_VERSION,
    "record_kind" to "audit_gap_progress",
    "previous_criterion_refs" to criterionRefs.sorted(),
  ).apply {
    repositoryFingerprint?.let { put("previous_repository_fingerprint", it) }
  }

  companion object {
    const val HAD_GAPS_MARKER: String = "gaps_found"

    /** Strict decode; loud-fails on a missing or malformed required field. */
    @OpenBoundaryMap("Feature-task-runtime audit-gap progress decode from the durable workflow-artifact map")
    fun fromArtifactMap(raw: Map<String, Any?>): FeatureTaskRuntimeAuditGapProgress {
      requireExactAuditGapProgressFields(raw)
      if (raw["contract_version"] != FEATURE_TASK_RUNTIME_PERSISTENCE_CONTRACT_VERSION) {
        throw InvalidWorkflowStateSchemaError(
          "Feature-task-runtime audit-gap progress artifact uses unsupported persistence contract " +
            "version '${raw["contract_version"]}'; $FEATURE_TASK_RUNTIME_INCOMPATIBLE_RECORD_GUIDANCE.",
        )
      }
      if (raw["record_kind"] != "audit_gap_progress") {
        throw InvalidWorkflowStateSchemaError(
          "Feature-task-runtime audit-gap progress artifact must have kind 'audit_gap_progress'.",
        )
      }
      return FeatureTaskRuntimeAuditGapProgress(
        criterionRefs = raw.optionalStringListField("previous_criterion_refs").toSet(),
        repositoryFingerprint = raw.optionalStringField("previous_repository_fingerprint"),
      )
    }

    private fun requireExactAuditGapProgressFields(raw: Map<String, Any?>) {
      val expected = setOf(
        "contract_version",
        "record_kind",
        "previous_criterion_refs",
        "previous_repository_fingerprint",
      )
      val unexpected = raw.keys - expected
      if (unexpected.isNotEmpty()) {
        throw InvalidWorkflowStateSchemaError(
          "Feature-task-runtime audit-gap progress artifact contains unsupported fields; " +
            "$FEATURE_TASK_RUNTIME_INCOMPATIBLE_RECORD_GUIDANCE.",
        )
      }
    }
  }
}
