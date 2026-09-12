package skillbill.workflow.taskruntime.model

import skillbill.contracts.JsonCodec
import skillbill.error.InvalidAuditRepairCycleSchemaError
import skillbill.contracts.workflow.AuditRepairCycleKeys as Keys

object AuditRepairCycleCodec {
  fun encodeCheckpoint(checkpoint: AuditRepairCheckpoint): String {
    validateAuditRepairCheckpoint(checkpoint)
    return JsonCodec.mapToJsonString(checkpointMap(checkpoint))
  }

  fun decodeCheckpoint(value: String): AuditRepairCheckpoint = checkpoint(
    parseObject(value) ?: throw InvalidAuditRepairCycleSchemaError("Checkpoint must be an object."),
  ).also(::validateAuditRepairCheckpoint)

  fun decode(value: String): AuditRepairCycle {
    val root = parseObject(value)
      ?: throw InvalidAuditRepairCycleSchemaError("Cycle must be a JSON object.")
    root.keysExactly(
      Keys.CONTRACT_VERSION, Keys.WORKFLOW_ID, Keys.AUDIT_ATTEMPT, Keys.EXECUTION_ID, Keys.SESSION_ID,
      Keys.CYCLE_ID, Keys.OWNER_TOKEN, Keys.FENCING_GENERATION, Keys.CRITERION_REFS,
      Keys.LEGACY_EVIDENCE, Keys.REVISIONS,
    )
    return AuditRepairCycle(
      identity = AuditRepairIdentity(
        workflowId = root.text(Keys.WORKFLOW_ID),
        auditAttempt = root.integer(Keys.AUDIT_ATTEMPT),
        executionId = root.text(Keys.EXECUTION_ID),
        sessionId = root.text(Keys.SESSION_ID),
        cycleId = root.text(Keys.CYCLE_ID),
        ownerToken = root.text(Keys.OWNER_TOKEN),
        fencingGeneration = root.number(Keys.FENCING_GENERATION),
      ),
      criterionRefs = root.strings(Keys.CRITERION_REFS),
      revisions = root.objects(Keys.REVISIONS).map(::revision),
      legacyEvidence = root.optionalText(Keys.LEGACY_EVIDENCE),
      contractVersion = root.text(Keys.CONTRACT_VERSION),
    ).also { it.validate() }
  }

  fun encode(cycle: AuditRepairCycle): String {
    cycle.validate()
    return JsonCodec.mapToJsonString(
      buildMap {
        put(Keys.CONTRACT_VERSION, cycle.contractVersion)
        put(Keys.WORKFLOW_ID, cycle.identity.workflowId)
        put(Keys.AUDIT_ATTEMPT, cycle.identity.auditAttempt)
        put(Keys.EXECUTION_ID, cycle.identity.executionId)
        put(Keys.SESSION_ID, cycle.identity.sessionId)
        put(Keys.CYCLE_ID, cycle.identity.cycleId)
        put(Keys.OWNER_TOKEN, cycle.identity.ownerToken)
        put(Keys.FENCING_GENERATION, cycle.identity.fencingGeneration)
        put(Keys.CRITERION_REFS, cycle.criterionRefs)
        cycle.legacyEvidence?.let { put(Keys.LEGACY_EVIDENCE, it) }
        put(Keys.REVISIONS, cycle.revisions.map(::revisionMap))
      },
    )
  }

  private fun revision(root: Map<String, Any?>): AuditRepairRevision {
    root.keysExactly(
      Keys.REVISION, Keys.REQUEST_ID, Keys.STAGE, Keys.RECORDED_AT, Keys.ASSESSMENT,
      Keys.REPOSITORY_FINGERPRINT, Keys.CHECKPOINT_INTENT, Keys.CHECKPOINT, Keys.REPAIR_OUTCOMES, Keys.REASON,
    )
    return AuditRepairRevision(
      revision = root.integer(Keys.REVISION),
      requestId = root.text(Keys.REQUEST_ID),
      stage = AuditRepairStage.fromWire(root.text(Keys.STAGE)),
      recordedAt = root.text(Keys.RECORDED_AT),
      assessment = root.optionalObject(Keys.ASSESSMENT)?.let(::assessment),
      repositoryFingerprint = root.optionalText(Keys.REPOSITORY_FINGERPRINT),
      checkpointIntent = root.optionalText(Keys.CHECKPOINT_INTENT),
      checkpoint = root.optionalObject(Keys.CHECKPOINT)?.let(::checkpoint),
      repairOutcomes = if (Keys.REPAIR_OUTCOMES in root) root.objects(Keys.REPAIR_OUTCOMES).map(::outcome) else null,
      reason = root.optionalText(Keys.REASON),
    )
  }

  private fun checkpoint(root: Map<String, Any?>): AuditRepairCheckpoint {
    root.keysExactly(Keys.CHECKPOINT_ID, Keys.REPOSITORY_FINGERPRINT, Keys.CHECKPOINT_PATHS)
    return AuditRepairCheckpoint(
      checkpointId = root.text(Keys.CHECKPOINT_ID),
      repositoryFingerprint = root.text(Keys.REPOSITORY_FINGERPRINT),
      scopedPaths = root.optionalStrings(Keys.CHECKPOINT_PATHS).orEmpty(),
    )
  }

  private fun assessment(root: Map<String, Any?>): AuditRepairAssessment {
    root.keysExactly(Keys.CHECKPOINT, Keys.CRITERIA, Keys.VALUE)
    return AuditRepairAssessment(
      checkpoint = checkpoint(root.objectValue(Keys.CHECKPOINT)),
      criteria = root.objects(Keys.CRITERIA).map { criterion ->
        criterion.keysExactly(
          Keys.CRITERION_REF,
          Keys.SATISFIED,
          Keys.EVIDENCE,
          Keys.REPAIR_ID,
          Keys.REPAIR_GUIDANCE,
          Keys.PENDING_VALIDATION,
        )
        AuditRepairCriterion(
          criterionRef = criterion.text(Keys.CRITERION_REF),
          satisfied = (criterion[Keys.SATISFIED] as? Boolean) ?: invalidField(Keys.SATISFIED),
          evidence = criterion.text(Keys.EVIDENCE),
          repairId = criterion.optionalText(Keys.REPAIR_ID),
          repairGuidance = criterion.optionalText(Keys.REPAIR_GUIDANCE),
          pendingValidation = criterion.optionalText(Keys.PENDING_VALIDATION),
        )
      },
      value = root.text(Keys.VALUE),
    )
  }

  private fun outcome(root: Map<String, Any?>): AuditRepairOutcome {
    root.keysExactly(Keys.REPAIR_ID, Keys.VALUE, Keys.CHANGED_PATHS)
    return AuditRepairOutcome(root.text(Keys.REPAIR_ID), root.text(Keys.VALUE), root.strings(Keys.CHANGED_PATHS))
  }

  private fun revisionMap(revision: AuditRepairRevision): Map<String, Any?> = buildMap {
    put(Keys.REVISION, revision.revision)
    put(Keys.REQUEST_ID, revision.requestId)
    put(Keys.STAGE, revision.stage.wireValue)
    put(Keys.RECORDED_AT, revision.recordedAt)
    revision.assessment?.let { put(Keys.ASSESSMENT, assessmentMap(it)) }
    revision.repositoryFingerprint?.let { put(Keys.REPOSITORY_FINGERPRINT, it) }
    revision.checkpointIntent?.let { put(Keys.CHECKPOINT_INTENT, it) }
    revision.checkpoint?.let { put(Keys.CHECKPOINT, checkpointMap(it)) }
    revision.repairOutcomes?.let { outcomes ->
      put(
        Keys.REPAIR_OUTCOMES,
        outcomes.map { outcome ->
          mapOf(
            Keys.REPAIR_ID to outcome.repairId,
            Keys.VALUE to outcome.value,
            Keys.CHANGED_PATHS to outcome.changedPaths,
          )
        },
      )
    }
    revision.reason?.let { put(Keys.REASON, it) }
  }

  private fun checkpointMap(checkpoint: AuditRepairCheckpoint): Map<String, Any?> = mapOf(
    Keys.CHECKPOINT_ID to checkpoint.checkpointId,
    Keys.REPOSITORY_FINGERPRINT to checkpoint.repositoryFingerprint,
    Keys.CHECKPOINT_PATHS to checkpoint.scopedPaths,
  )

  private fun assessmentMap(assessment: AuditRepairAssessment): Map<String, Any?> = mapOf(
    Keys.CHECKPOINT to checkpointMap(assessment.checkpoint),
    Keys.VALUE to assessment.value,
    Keys.CRITERIA to assessment.criteria.map { criterion ->
      buildMap {
        put(Keys.CRITERION_REF, criterion.criterionRef)
        put(Keys.SATISFIED, criterion.satisfied)
        put(Keys.EVIDENCE, criterion.evidence)
        criterion.repairId?.let { put(Keys.REPAIR_ID, it) }
        criterion.repairGuidance?.let { put(Keys.REPAIR_GUIDANCE, it) }
        criterion.pendingValidation?.let { put(Keys.PENDING_VALIDATION, it) }
      }
    },
  )

  private fun Map<String, Any?>.keysExactly(vararg allowed: String) {
    auditRepairCheck(keys.all { it in allowed }, "Unknown cycle evidence field.")
  }

  private fun parseObject(value: String): Map<String, Any?>? = JsonCodec.parseObjectOrNull(value)
    ?.let(JsonCodec::jsonElementToValue)?.let(JsonCodec::anyToStringAnyMap)

  private fun Map<String, Any?>.text(key: String): String =
    (get(key) as? String)?.takeIf(String::isNotBlank) ?: invalidField(key)

  private fun Map<String, Any?>.optionalText(key: String): String? = if (key in this) text(key) else null

  private fun Map<String, Any?>.integer(key: String): Int = when (val value = get(key)) {
    is Int -> value
    is Long -> value.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt() ?: invalidField(key)
    else -> invalidField(key)
  }

  private fun Map<String, Any?>.number(key: String): Long = when (val value = get(key)) {
    is Int -> value.toLong()
    is Long -> value
    else -> invalidField(key)
  }

  private fun Map<String, Any?>.objectValue(key: String): Map<String, Any?> =
    JsonCodec.anyToStringAnyMap(get(key)) ?: invalidField(key)

  private fun Map<String, Any?>.optionalObject(key: String): Map<String, Any?>? =
    if (key in this) objectValue(key) else null

  private fun Map<String, Any?>.objects(key: String): List<Map<String, Any?>> = (get(key) as? List<*>)
    ?.map { JsonCodec.anyToStringAnyMap(it) ?: invalidField(key) } ?: invalidField(key)

  private fun Map<String, Any?>.strings(key: String): List<String> = (get(key) as? List<*>)
    ?.map { it as? String ?: invalidField(key) } ?: invalidField(key)

  private fun Map<String, Any?>.optionalStrings(key: String): List<String>? = if (key in this) strings(key) else null

  private fun invalidField(key: String): Nothing = throw InvalidAuditRepairCycleSchemaError("Invalid field '$key'.")
}
