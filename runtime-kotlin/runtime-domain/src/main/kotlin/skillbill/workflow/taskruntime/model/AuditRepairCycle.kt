package skillbill.workflow.taskruntime.model

import skillbill.contracts.workflow.FEATURE_TASK_RUNTIME_AUDIT_REPAIR_CYCLE_CONTRACT_VERSION
import skillbill.error.AuditRepairCycleConflictError
import skillbill.error.InvalidAuditRepairCycleSchemaError
import java.time.Instant
import java.time.format.DateTimeParseException

enum class AuditRepairStage(val wireValue: String) {
  DIAGNOSIS("diagnosis"),
  AUTHORIZED_REPAIR("authorized_repair"),
  CHECKPOINT_PENDING("checkpoint_pending"),
  FINAL_AUDIT("final_audit"),
  SATISFIED("satisfied"),
  PAUSED("paused"),
  ;

  companion object {
    fun fromWire(value: String): AuditRepairStage = entries.firstOrNull { it.wireValue == value }
      ?: throw InvalidAuditRepairCycleSchemaError("Unknown stage.")
  }
}

data class AuditRepairIdentity(
  val workflowId: String,
  val auditAttempt: Int,
  val executionId: String,
  val sessionId: String,
  val cycleId: String,
  val ownerToken: String,
  val fencingGeneration: Long,
)

data class AuditRepairLaunchBinding(
  val workflowId: String,
  val auditAttempt: Int,
  val cycleId: String,
  val executionId: String,
  val requestedSessionId: String,
  val ownerToken: String,
  val fencingGeneration: Long,
  val providerSessionId: String? = null,
  val boundAt: String,
  val checkpoint: AuditRepairCheckpoint? = null,
) {
  val identity: AuditRepairIdentity get() = AuditRepairIdentity(
    workflowId,
    auditAttempt,
    executionId,
    requestedSessionId,
    cycleId,
    ownerToken,
    fencingGeneration,
  )

  init {
    auditRepairCheck(
      listOf(workflowId, cycleId, executionId, requestedSessionId, ownerToken, boundAt).all(String::isNotBlank) &&
        auditAttempt > 0 && fencingGeneration > 0,
      "Audit-repair launch binding is incomplete.",
    )
    providerSessionId?.let { auditRepairCheck(it.isNotBlank(), "Provider session identity must be nonblank.") }
    try {
      Instant.parse(boundAt)
    } catch (_: DateTimeParseException) {
      throw InvalidAuditRepairCycleSchemaError("Invalid launch binding timestamp.")
    }
  }
}

data class AuditRepairCheckpoint(
  val checkpointId: String,
  val repositoryFingerprint: String,
  val scopedPaths: List<String> = emptyList(),
) {
  init {
    auditRepairCheck(
      scopedPaths == scopedPaths.distinct().sorted() && scopedPaths.all(::isAuditRepairPath),
      "Checkpoint scope must be sorted, unique, and nonblank.",
    )
  }
}

data class AuditRepairCriterion(
  val criterionRef: String,
  val satisfied: Boolean,
  val evidence: String,
  val repairId: String? = null,
  val repairGuidance: String? = null,
  val pendingValidation: String? = null,
)

data class AuditRepairAssessment(
  val checkpoint: AuditRepairCheckpoint,
  val criteria: List<AuditRepairCriterion>,
  val value: String,
) {
  val unmetCriterionRefs: Set<String>
    get() = criteria.filterNot { it.satisfied }.map { it.criterionRef }.toSet()
}

data class AuditRepairOutcome(val repairId: String, val value: String, val changedPaths: List<String>)

data class AuditRepairRevision(
  val revision: Int,
  val requestId: String,
  val stage: AuditRepairStage,
  val recordedAt: String,
  val assessment: AuditRepairAssessment? = null,
  val repositoryFingerprint: String? = null,
  val checkpointIntent: String? = null,
  val checkpoint: AuditRepairCheckpoint? = null,
  val repairOutcomes: List<AuditRepairOutcome>? = null,
  val reason: String? = null,
)

data class AuditRepairCycle(
  val identity: AuditRepairIdentity,
  val criterionRefs: List<String>,
  val revisions: List<AuditRepairRevision>,
  val legacyEvidence: String? = null,
  val contractVersion: String = FEATURE_TASK_RUNTIME_AUDIT_REPAIR_CYCLE_CONTRACT_VERSION,
) {
  val current: AuditRepairRevision get() = revisions.last()
  val diagnosis: AuditRepairAssessment get() = requireNotNull(revisions.first().assessment)
  val repairRoundCount: Int get() = revisions.count { it.stage == AuditRepairStage.AUTHORIZED_REPAIR }
  val finalAssessment: AuditRepairAssessment?
    get() = current.takeIf { it.stage == AuditRepairStage.SATISFIED }?.assessment
  val latestAssessment: AuditRepairAssessment?
    get() = revisions.asReversed().firstOrNull { it.assessment != null }?.assessment
  val latestCheckpoint: AuditRepairCheckpoint?
    get() = revisions.asReversed().flatMap { listOfNotNull(it.checkpoint, it.assessment?.checkpoint) }.firstOrNull()

  fun append(expectedRevision: Int, next: AuditRepairRevision): AuditRepairCycle {
    validate()
    revisions.firstOrNull { it.requestId == next.requestId }?.let { recorded ->
      if (!sameReplayEvidence(recorded, next) || expectedRevision != next.revision - 1) {
        throw AuditRepairCycleConflictError("Request identity was reused with different evidence.")
      }
      return this
    }
    if (current.revision != expectedRevision || next.revision != expectedRevision + 1) {
      throw AuditRepairCycleConflictError("Expected revision does not match durable state.")
    }
    return copy(revisions = revisions + next).also { it.validate() }
  }

  fun attachCheckpoint(
    expectedRevision: Int,
    checkpointIntent: String,
    checkpoint: AuditRepairCheckpoint,
  ): AuditRepairCycle {
    validate()
    val recorded = revisions.getOrNull(expectedRevision)
    if (current.revision != expectedRevision || current.stage != AuditRepairStage.CHECKPOINT_PENDING) {
      if (recorded?.stage == AuditRepairStage.CHECKPOINT_PENDING &&
        recorded.checkpointIntent == checkpointIntent && recorded.checkpoint == checkpoint
      ) {
        return this
      }
      throw AuditRepairCycleConflictError("Checkpoint attachment does not match durable state.")
    }
    if (current.checkpointIntent != checkpointIntent) {
      throw AuditRepairCycleConflictError("Checkpoint attachment does not match its durable intent.")
    }
    current.checkpoint?.let { attached ->
      if (attached == checkpoint) return this
      throw AuditRepairCycleConflictError("Checkpoint attachment conflicts with retained content.")
    }
    return copy(
      revisions = revisions.dropLast(1) + current.copy(checkpoint = checkpoint),
    ).also { it.validate() }
  }

  private fun sameReplayEvidence(left: AuditRepairRevision, right: AuditRepairRevision): Boolean =
    left.copy(recordedAt = "") == right.copy(recordedAt = "")

  fun validate() {
    auditRepairCheck(
      contractVersion == FEATURE_TASK_RUNTIME_AUDIT_REPAIR_CYCLE_CONTRACT_VERSION,
      "Unsupported version.",
    )
    with(identity) {
      auditRepairCheck(
        listOf(workflowId, executionId, sessionId, cycleId, ownerToken).all(String::isNotBlank) &&
          auditAttempt > 0 && fencingGeneration > 0,
        "Missing or invalid execution attribution.",
      )
    }
    auditRepairCheck(
      criterionRefs.isNotEmpty() && criterionRefs.all(String::isNotBlank) &&
        criterionRefs.distinct().size == criterionRefs.size,
      "Criterion census must be nonempty and unique.",
    )
    auditRepairCheck(legacyEvidence == null || legacyEvidence.isNotBlank(), "Legacy evidence must be nonblank.")
    auditRepairCheck(revisions.isNotEmpty(), "Diagnosis is required.")
    auditRepairCheck(revisions.map { it.requestId }.distinct().size == revisions.size, "Repeated request identity.")
    val checkpoints = revisions.flatMap { listOfNotNull(it.assessment?.checkpoint, it.checkpoint) }
    auditRepairCheck(
      checkpoints.groupBy { it.checkpointId }.values.all { references ->
        references.map { it.repositoryFingerprint }.distinct().size == 1
      },
      "A checkpoint identity cannot reference different repository fingerprints.",
    )
    revisions.forEachIndexed { index, entry ->
      auditRepairCheck(entry.revision == index && entry.requestId.isNotBlank(), "Invalid revision identity.")
      try {
        Instant.parse(entry.recordedAt)
      } catch (_: DateTimeParseException) {
        throw InvalidAuditRepairCycleSchemaError("Invalid revision timestamp.")
      }
      entry.assessment?.let { validateAuditRepairAssessment(it, criterionRefs) }
      entry.checkpoint?.let(::validateAuditRepairCheckpoint)
      validateAuditRepairTransition(revisions.take(index), entry)
    }
  }
}

internal fun auditRepairCheck(valid: Boolean, reason: String) {
  if (!valid) throw InvalidAuditRepairCycleSchemaError(reason)
}

internal fun validateAuditRepairCheckpoint(checkpoint: AuditRepairCheckpoint) {
  auditRepairCheck(
    checkpoint.checkpointId.isNotBlank() && checkpoint.repositoryFingerprint.isNotBlank() &&
      checkpoint.repositoryFingerprint != UNPROVEN_REPOSITORY_FINGERPRINT,
    "Checkpoint identity must be proven.",
  )
}

internal fun validateAuditRepairAssessment(assessment: AuditRepairAssessment, census: List<String>) {
  validateAuditRepairCheckpoint(assessment.checkpoint)
  auditRepairCheck(assessment.value.isNotBlank(), "Assessment prose is required.")
  auditRepairCheck(
    assessment.criteria.size == census.size && assessment.criteria.map { it.criterionRef }.toSet() == census.toSet(),
    "Assessment must cover exactly the criterion census.",
  )
  assessment.criteria.forEach { criterion ->
    auditRepairCheck(criterion.evidence.isNotBlank(), "Criterion evidence is required.")
    auditRepairCheck(
      if (criterion.satisfied) {
        criterion.repairId == null && criterion.repairGuidance == null
      } else {
        !criterion.repairId.isNullOrBlank() && !criterion.repairGuidance.isNullOrBlank()
      },
      "Each gap requires a repair identity and guidance; satisfied criteria cannot authorize repairs.",
    )
    auditRepairCheck(
      criterion.pendingValidation == null || criterion.pendingValidation.isNotBlank(),
      "Pending validation obligation must be nonblank.",
    )
  }
  val repairIds = assessment.criteria.mapNotNull { it.repairId }
  auditRepairCheck(repairIds.distinct().size == repairIds.size, "Repair identities must be unique.")
}

internal fun isAuditRepairPath(path: String): Boolean = path.isNotBlank() && !path.startsWith('/') && '\\' !in path &&
  path.split('/').all { it.isNotEmpty() && it != "." && it != ".." && it != ".git" } &&
  !Regex("^[A-Za-z]:").containsMatchIn(path)

fun validateAuditRepairPaths(paths: List<String>) {
  auditRepairCheck(paths.all(::isAuditRepairPath), "Audit paths must be normalized repository-relative paths.")
}
