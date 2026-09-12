package skillbill.engine.featuretask.model

import skillbill.contracts.JsonCodec
import skillbill.contracts.workflow.AuditRepairCycleKeys
import skillbill.error.InvalidAuditRepairCycleSchemaError
import skillbill.workflow.taskruntime.model.AuditRepairAssessment
import skillbill.workflow.taskruntime.model.AuditRepairCheckpoint
import skillbill.workflow.taskruntime.model.AuditRepairCriterion
import skillbill.workflow.taskruntime.model.AuditRepairIdentity
import skillbill.workflow.taskruntime.model.AuditRepairOutcome
import skillbill.workflow.taskruntime.model.AuditRepairRevision
import skillbill.workflow.taskruntime.model.AuditRepairStage
import skillbill.workflow.taskruntime.model.FeatureTaskAuditRepairStageRequest

object FeatureTaskAuditRepairStageRequestCodec {
  fun fromMap(raw: Map<String, Any?>, recordedAt: String? = null): FeatureTaskAuditRepairStageRequest {
    val stage = AuditRepairStage.fromWire(raw.string(AuditRepairCycleKeys.STAGE))
    val suppliedRevision = raw[AuditRepairCycleKeys.REVISION]?.let { raw.int(AuditRepairCycleKeys.REVISION) }
    val expectedRevision = raw.int(AuditRepairCycleKeys.EXPECTED_REVISION)
    return FeatureTaskAuditRepairStageRequest(
      identity = AuditRepairIdentity(
        workflowId = raw.string(AuditRepairCycleKeys.WORKFLOW_ID),
        auditAttempt = raw.int(AuditRepairCycleKeys.AUDIT_ATTEMPT),
        executionId = raw.string(AuditRepairCycleKeys.EXECUTION_ID),
        sessionId = raw.string(AuditRepairCycleKeys.SESSION_ID),
        cycleId = raw.string(AuditRepairCycleKeys.CYCLE_ID),
        ownerToken = raw.string(AuditRepairCycleKeys.OWNER_TOKEN),
        fencingGeneration = raw.long(AuditRepairCycleKeys.FENCING_GENERATION),
      ),
      expectedRevision = expectedRevision,
      revision = AuditRepairRevision(
        revision = if (stage == AuditRepairStage.DIAGNOSIS) {
          require(suppliedRevision == null || suppliedRevision == 0) { "Diagnosis must use revision 0." }
          0
        } else {
          suppliedRevision ?: expectedRevision + 1
        },
        requestId = raw.string(AuditRepairCycleKeys.REQUEST_ID),
        stage = stage,
        recordedAt = raw.optionalString(AuditRepairCycleKeys.RECORDED_AT) ?: requireNotNull(recordedAt),
        assessment = raw.objectValue(AuditRepairCycleKeys.ASSESSMENT)?.let(::assessment),
        repositoryFingerprint = raw.optionalString(AuditRepairCycleKeys.REPOSITORY_FINGERPRINT),
        checkpointIntent = raw.optionalString(AuditRepairCycleKeys.CHECKPOINT_INTENT),
        checkpoint = raw.objectValue(AuditRepairCycleKeys.CHECKPOINT)?.let(::checkpoint),
        repairOutcomes = raw.objectList(AuditRepairCycleKeys.REPAIR_OUTCOMES)?.map(::outcome),
        reason = raw.optionalString(AuditRepairCycleKeys.REASON),
      ),
      criterionRefs = raw.stringList(AuditRepairCycleKeys.CRITERION_REFS),
      legacyEvidence = raw.optionalString(AuditRepairCycleKeys.LEGACY_EVIDENCE),
    )
  }

  private fun assessment(raw: Map<String, Any?>): AuditRepairAssessment = AuditRepairAssessment(
    checkpoint = checkpoint(
      raw.objectValue(
        AuditRepairCycleKeys.CHECKPOINT,
      ) ?: throw InvalidAuditRepairCycleSchemaError("assessment.checkpoint is required."),
    ),
    criteria = raw.objectList(AuditRepairCycleKeys.CRITERIA)?.map(::criterion)
      ?: throw InvalidAuditRepairCycleSchemaError("assessment.criteria is required."),
    value = raw.string(AuditRepairCycleKeys.VALUE),
  )

  private fun criterion(raw: Map<String, Any?>): AuditRepairCriterion = AuditRepairCriterion(
    criterionRef = raw.string(AuditRepairCycleKeys.CRITERION_REF),
    satisfied = raw.boolean(AuditRepairCycleKeys.SATISFIED),
    evidence = raw.string(AuditRepairCycleKeys.EVIDENCE),
    repairId = raw.optionalString(AuditRepairCycleKeys.REPAIR_ID),
    repairGuidance = raw.optionalString(AuditRepairCycleKeys.REPAIR_GUIDANCE),
    pendingValidation = raw.optionalString(AuditRepairCycleKeys.PENDING_VALIDATION),
  )

  private fun checkpoint(raw: Map<String, Any?>): AuditRepairCheckpoint = AuditRepairCheckpoint(
    checkpointId = raw.string(AuditRepairCycleKeys.CHECKPOINT_ID),
    repositoryFingerprint = raw.string(AuditRepairCycleKeys.REPOSITORY_FINGERPRINT),
    scopedPaths = raw.stringList(AuditRepairCycleKeys.CHECKPOINT_PATHS),
  )

  private fun outcome(raw: Map<String, Any?>): AuditRepairOutcome = AuditRepairOutcome(
    repairId = raw.string(AuditRepairCycleKeys.REPAIR_ID),
    value = raw.string(AuditRepairCycleKeys.VALUE),
    changedPaths = raw.stringList(AuditRepairCycleKeys.CHANGED_PATHS),
  )

  private fun Map<String, Any?>.objectValue(key: String): Map<String, Any?>? = JsonCodec.anyToStringAnyMap(this[key])

  private fun Map<String, Any?>.objectList(key: String): List<Map<String, Any?>>? = if (key !in this) {
    null
  } else {
    (this[key] as? List<*>)?.map {
      JsonCodec.anyToStringAnyMap(it) ?: throw InvalidAuditRepairCycleSchemaError("$key must contain objects.")
    } ?: throw InvalidAuditRepairCycleSchemaError("$key must be a list.")
  }

  private fun Map<String, Any?>.stringList(key: String): List<String> = (this[key] as? List<*>)?.map {
    it as? String ?: throw InvalidAuditRepairCycleSchemaError("$key must contain strings.")
  } ?: emptyList()

  private fun Map<String, Any?>.string(key: String): String =
    (this[key] as? String)?.takeIf(String::isNotBlank) ?: throw InvalidAuditRepairCycleSchemaError("$key is required.")

  private fun Map<String, Any?>.optionalString(key: String): String? = if (key in this) string(key) else null

  private fun Map<String, Any?>.int(key: String): Int = when (val value = this[key]) {
    is Int -> value
    is Long -> value.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
      ?: throw InvalidAuditRepairCycleSchemaError("$key is outside the supported integer range.")
    else -> throw InvalidAuditRepairCycleSchemaError("$key must be an integer.")
  }

  private fun Map<String, Any?>.long(key: String): Long = when (val value = this[key]) {
    is Long -> value
    is Int -> value.toLong()
    else -> throw InvalidAuditRepairCycleSchemaError("$key must be an integer.")
  }

  private fun Map<String, Any?>.boolean(key: String): Boolean =
    this[key] as? Boolean ?: throw InvalidAuditRepairCycleSchemaError("$key must be a boolean.")
}
