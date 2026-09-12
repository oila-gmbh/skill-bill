package skillbill.workflow.taskruntime.model

internal fun validateAuditRepairTransition(history: List<AuditRepairRevision>, next: AuditRepairRevision) {
  when (next.stage) {
    AuditRepairStage.DIAGNOSIS -> {
      auditRepairCheck(history.isEmpty() && next.assessment != null, "Diagnosis must be the first revision.")
      auditRepairCheck(next.onlyAssessment(), "Diagnosis contains unrelated stage evidence.")
    }
    AuditRepairStage.AUTHORIZED_REPAIR -> validateAuthorization(history, next)
    AuditRepairStage.CHECKPOINT_PENDING -> validateCheckpointIntent(history, next)
    AuditRepairStage.FINAL_AUDIT -> validateAttachment(history, next)
    AuditRepairStage.SATISFIED -> validateFinal(history, next)
    AuditRepairStage.PAUSED -> validatePause(history, next)
  }
}

private fun interruptedStage(history: List<AuditRepairRevision>): AuditRepairRevision? =
  if (history.lastOrNull()?.let { it.stage == AuditRepairStage.PAUSED && it.assessment == null } == true) {
    history.dropLast(1).lastOrNull()
  } else {
    null
  }

private fun validateAuthorization(history: List<AuditRepairRevision>, next: AuditRepairRevision) {
  val previous = history.lastOrNull()
  val assessment = latestAssessment(history)
  val diagnosed = previous?.stage == AuditRepairStage.DIAGNOSIS ||
    interruptedStage(history)?.stage == AuditRepairStage.DIAGNOSIS
  val failedFinal = previous?.let {
    it.stage == AuditRepairStage.PAUSED && it.repairOutcomes == null && it.assessment != null
  } == true
  auditRepairCheck(
    (diagnosed || failedFinal) && !assessment?.unmetCriterionRefs.isNullOrEmpty(),
    "Repair authorization requires a diagnosis with gaps.",
  )
  auditRepairCheck(
    next.repositoryFingerprint == assessment?.checkpoint?.repositoryFingerprint,
    "Repository changed after diagnosis.",
  )
  auditRepairCheck(
    next.assessment == null && next.checkpoint == null && next.checkpointIntent == null &&
      next.repairOutcomes == null && next.reason == null,
    "Repair authorization contains unrelated stage evidence.",
  )
}

private fun validateAttachment(history: List<AuditRepairRevision>, next: AuditRepairRevision) {
  val previous = interruptedStage(history) ?: history.lastOrNull()
  val attachable = previous?.stage == AuditRepairStage.CHECKPOINT_PENDING ||
    interruptedStage(history)?.stage == AuditRepairStage.FINAL_AUDIT
  auditRepairCheck(
    attachable && next.checkpoint != null && next.checkpoint == previous?.checkpoint,
    "Final audit requires attachment of the intended repository checkpoint.",
  )
  auditRepairCheck(
    next.assessment == null && next.repositoryFingerprint == null && next.checkpointIntent == null &&
      next.repairOutcomes == null && next.reason == null,
    "Checkpoint attachment contains unrelated stage evidence.",
  )
}

private fun validateFinal(history: List<AuditRepairRevision>, next: AuditRepairRevision) {
  val previous = history.lastOrNull()
  auditRepairCheck(
    previous?.stage == AuditRepairStage.FINAL_AUDIT && next.assessment != null &&
      next.assessment.checkpoint == previous.checkpoint && next.assessment.unmetCriterionRefs.isEmpty(),
    "Satisfied requires a complete final audit against the attached checkpoint with no gaps.",
  )
  auditRepairCheck(next.onlyAssessment(), "Satisfied revision contains unrelated stage evidence.")
  preservePendingValidation(latestAssessment(history), next.assessment)
}

private fun validatePause(history: List<AuditRepairRevision>, next: AuditRepairRevision) {
  val previous = history.lastOrNull()
  auditRepairCheck(
    previous != null && previous.stage !in setOf(AuditRepairStage.SATISFIED, AuditRepairStage.PAUSED) &&
      !next.reason.isNullOrBlank(),
    "Pause requires an active cycle and an operator reason.",
  )
  next.assessment?.let {
    auditRepairCheck(
      previous?.stage == AuditRepairStage.FINAL_AUDIT && it.checkpoint == previous.checkpoint &&
        it.unmetCriterionRefs.isNotEmpty(),
      "Failed final audit must reference the attached checkpoint and retain its gaps.",
    )
    preservePendingValidation(latestAssessment(history), it)
    preserveRepairIdentities(latestAssessment(history), it)
  }
  auditRepairCheck(
    next.repositoryFingerprint == null && next.checkpointIntent == null && next.checkpoint == null,
    "Pause contains unrelated stage evidence.",
  )
  next.repairOutcomes?.let { outcomes ->
    auditRepairCheck(
      previous?.stage == AuditRepairStage.AUTHORIZED_REPAIR,
      "Paused repair outcomes require the immediately preceding repair authorization.",
    )
    validateRepairOutcomes(latestAssessment(history), outcomes, complete = false)
  }
}

private fun preserveRepairIdentities(diagnosis: AuditRepairAssessment?, final: AuditRepairAssessment) {
  val originalCriteria = diagnosis?.criteria.orEmpty().associateBy { it.criterionRef }
  val originalRepairs = diagnosis?.criteria.orEmpty().mapNotNull { it.repairId }.toSet()
  auditRepairCheck(
    final.criteria.filterNot { it.satisfied }.all { criterion ->
      val originalRepair = originalCriteria[criterion.criterionRef]?.repairId
      if (originalRepair != null) criterion.repairId == originalRepair else criterion.repairId !in originalRepairs
    },
    "Recurring gaps must retain their repair identities; new gaps cannot reuse an earlier repair identity.",
  )
}

private fun preservePendingValidation(diagnosis: AuditRepairAssessment?, final: AuditRepairAssessment?) {
  val finalCriteria = final?.criteria.orEmpty().associateBy { it.criterionRef }
  auditRepairCheck(
    diagnosis?.criteria.orEmpty().all { criterion ->
      criterion.pendingValidation == null ||
        finalCriteria[criterion.criterionRef]?.pendingValidation == criterion.pendingValidation
    },
    "Final audit cannot discard or replace pending validation obligations.",
  )
}

private fun AuditRepairRevision.onlyAssessment(): Boolean = repositoryFingerprint == null &&
  checkpointIntent == null && checkpoint == null && repairOutcomes == null && reason == null

private fun validateCheckpointIntent(history: List<AuditRepairRevision>, next: AuditRepairRevision) {
  val previous = history.lastOrNull()
  val diagnosis = latestAssessment(history)
  val interrupted = interruptedStage(history)
  val initiallySatisfied = diagnosis?.unmetCriterionRefs?.isEmpty() == true
  val diagnosedWithoutGaps = (interrupted ?: previous)?.stage == AuditRepairStage.DIAGNOSIS && initiallySatisfied
  val interruptedPendingWithoutAttachment = interrupted?.let {
    it.stage == AuditRepairStage.CHECKPOINT_PENDING && it.checkpoint == null
  } == true
  auditRepairCheck(
    previous?.stage == AuditRepairStage.AUTHORIZED_REPAIR || diagnosedWithoutGaps ||
      interrupted?.stage == AuditRepairStage.AUTHORIZED_REPAIR || interruptedPendingWithoutAttachment,
    "Checkpoint intent requires authorized repair or an initially satisfied diagnosis.",
  )
  auditRepairCheck(
    !next.checkpointIntent.isNullOrBlank() && !next.repositoryFingerprint.isNullOrBlank() &&
      next.repositoryFingerprint != UNPROVEN_REPOSITORY_FINGERPRINT && next.repairOutcomes != null,
    "Checkpoint intent requires repair outcomes and a proven repository fingerprint.",
  )
  auditRepairCheck(
    next.assessment == null && next.reason == null,
    "Checkpoint intent contains unrelated stage evidence.",
  )
  validateRepairOutcomes(diagnosis, next.repairOutcomes.orEmpty(), complete = true)
  val completed = previous?.takeIf { it.stage == AuditRepairStage.PAUSED }?.repairOutcomes.orEmpty()
  auditRepairCheck(
    completed.all { it in next.repairOutcomes.orEmpty() },
    "Recovery must retain completed repair receipts without repeating their mutations.",
  )
  if (interruptedPendingWithoutAttachment) {
    auditRepairCheck(
      next.checkpointIntent == interrupted?.checkpointIntent &&
        next.repositoryFingerprint == interrupted?.repositoryFingerprint &&
        next.repairOutcomes == interrupted?.repairOutcomes,
      "Checkpoint recovery must preserve the interrupted intent, expected content and repair outcomes.",
    )
  }
  next.checkpoint?.let { retained ->
    if (initiallySatisfied) {
      auditRepairCheck(
        retained.repositoryFingerprint == diagnosis?.checkpoint?.repositoryFingerprint,
        "Initially satisfied repository changed before checkpoint intent.",
      )
    } else {
      auditRepairCheck(
        retained.repositoryFingerprint != diagnosis?.checkpoint?.repositoryFingerprint,
        "Unchanged repair content requires an operator pause.",
      )
    }
  }
}

private fun latestAssessment(history: List<AuditRepairRevision>): AuditRepairAssessment? =
  history.asReversed().firstOrNull { it.assessment != null }?.assessment

private fun validateRepairOutcomes(
  diagnosis: AuditRepairAssessment?,
  outcomes: List<AuditRepairOutcome>,
  complete: Boolean,
) {
  val authorizedIds = diagnosis?.criteria.orEmpty().mapNotNull { it.repairId }.toSet()
  val outcomeIds = outcomes.map { it.repairId }.toSet()
  auditRepairCheck(
    outcomes.size == outcomeIds.size && authorizedIds.containsAll(outcomeIds) &&
      (!complete || outcomeIds == authorizedIds),
    "Repair outcomes must name unique authorized identities and cover all repairs before checkpoint intent.",
  )
  outcomes.forEach { outcome ->
    auditRepairCheck(outcome.value.isNotBlank(), "Repair outcome prose is required.")
    auditRepairCheck(
      outcome.changedPaths.distinct().size == outcome.changedPaths.size && outcome.changedPaths.all(
        ::isAuditRepairPath,
      ),
      "Repair paths must be normalized repository-relative paths.",
    )
  }
}
