package skillbill.engine.featuretask

import skillbill.boundary.OpenBoundaryMap
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.workflow.AuditRepairCycleKeys
import skillbill.error.AuditRepairCycleConflictError
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.featuretask.AuditRepairCycleRepository
import skillbill.workflow.taskruntime.model.AuditRepairCheckpoint
import skillbill.workflow.taskruntime.model.AuditRepairCycle
import skillbill.workflow.taskruntime.model.AuditRepairRevision
import skillbill.workflow.taskruntime.model.AuditRepairStage
import skillbill.workflow.taskruntime.model.FeatureTaskAuditRepairStageRequest
import skillbill.workflow.taskruntime.model.initialCycle
import skillbill.workflow.taskruntime.model.progressSince
import java.time.Clock

internal class AuditRepairStageSettlement(
  private val auditRepairCycles: AuditRepairCycleRepository,
  private val acceptanceCriteriaSource: FeatureTaskRuntimeAcceptanceCriteriaSource,
  private val checkpointCoordinator: AuditRepairCheckpointCoordinator,
  private val diagnostics: RuntimeDiagnostics,
  private val clock: Clock,
) {
  @OpenBoundaryMap("MCP feature_task_audit_stage durable acknowledgement wire map")
  fun auditStage(request: FeatureTaskAuditRepairStageRequest): Map<String, Any?> = try {
    if (request.revision.stage == AuditRepairStage.CHECKPOINT_PENDING) {
      checkpointPending(request)
    } else {
      auditRepairCycles.withStageOwner(request.identity) { scoped -> auditStage(request, scoped) }
    }
  } catch (error: AuditRepairCycleConflictError) {
    if (!error.needsRecovery) throw error
    auditRepairCycles.withStageOwner(request.identity) { scoped -> pauseRejectedStage(request, scoped, error.reason) }
  }

  private sealed interface CheckpointIntentResult {
    data class Persisted(val cycle: AuditRepairCycle, val activityFingerprint: String) : CheckpointIntentResult
    data class Replayed(val acknowledgement: Map<String, Any?>) : CheckpointIntentResult
  }

  private fun checkpointPending(request: FeatureTaskAuditRepairStageRequest): Map<String, Any?> {
    val result = auditRepairCycles.withStageOwner(request.identity) { scoped ->
      validateRequest(request)
      val existing = scoped.find(request.identity.workflowId, request.identity.cycleId)
      val replayed = existing?.revisions?.firstOrNull { it.requestId == request.revision.requestId }
      if (replayed?.stage == AuditRepairStage.CHECKPOINT_PENDING && replayed.checkpoint == null) {
        val acknowledgement = requireNotNull(replay(request, existing))
        if (existing.current.requestId != replayed.requestId) {
          return@withStageOwner CheckpointIntentResult.Replayed(acknowledgement)
        }
        val activity = checkpointCoordinator.activityFingerprint()
          ?: throw checkpointConflict(
            request.identity.workflowId,
            "The repository activity fingerprint is unavailable.",
          )
        if (activity != replayed.repositoryFingerprint) {
          throw checkpointConflict(request.identity.workflowId, "The pending checkpoint intent is no longer current.")
        }
        return@withStageOwner CheckpointIntentResult.Persisted(existing, activity)
      }
      replay(request, existing)?.let { return@withStageOwner CheckpointIntentResult.Replayed(it) }
      verifySource(request, existing)
      val activity = checkpointCoordinator.activityFingerprint()
        ?: throw checkpointConflict(
          request.identity.workflowId,
          "The repository activity fingerprint is unavailable.",
        )
      val intent = request.revision.copy(
        repositoryFingerprint = activity,
        checkpoint = null,
      )
      CheckpointIntentResult.Persisted(
        scoped.advance(request.identity, request.expectedRevision, intent),
        activity,
      )
    }
    if (result is CheckpointIntentResult.Replayed) return result.acknowledgement
    result as CheckpointIntentResult.Persisted
    val checkpoint = capture(request, result.cycle, result.activityFingerprint)
    val attached = auditRepairCycles.withStageOwner(request.identity) { scoped ->
      if (!checkpointCoordinator.verifyCurrent(checkpoint)) {
        throw checkpointConflict(request.identity.workflowId, "Retained content changed before attachment.")
      }
      scoped.attachCheckpoint(
        identity = request.identity,
        expectedRevision = result.cycle.current.revision,
        checkpointIntent = requireNotNull(request.revision.checkpointIntent),
        checkpoint = checkpoint,
      )
    }
    return acknowledgeStage(attached)
  }

  private fun pauseRejectedStage(
    request: FeatureTaskAuditRepairStageRequest,
    scoped: AuditRepairCycleRepository,
    reason: String,
  ): Map<String, Any?> {
    val cycle = scoped.find(request.identity.workflowId, request.identity.cycleId)
      ?: throw AuditRepairCycleConflictError(reason)
    if (cycle.current.stage == AuditRepairStage.PAUSED || cycle.current.stage == AuditRepairStage.SATISFIED) {
      throw AuditRepairCycleConflictError(reason)
    }
    val pause = AuditRepairRevision(
      revision = cycle.current.revision + 1,
      requestId = "runtime-rejection-${cycle.current.revision + 1}",
      stage = AuditRepairStage.PAUSED,
      recordedAt = clock.instant().toString(),
      reason = "Audit protocol rejected ${request.revision.stage.wireValue}: $reason Stop repair and request recovery.",
    )
    return acknowledgeStage(scoped.advance(request.identity, cycle.current.revision, pause))
  }

  private fun auditStage(
    request: FeatureTaskAuditRepairStageRequest,
    auditRepairCycles: AuditRepairCycleRepository,
  ): Map<String, Any?> {
    val authoritativeCriterionRefs = validateRequest(request)
    val existing = auditRepairCycles.find(request.identity.workflowId, request.identity.cycleId)
    replay(request, existing)?.let { return it }
    verifySource(request, existing)
    val cycle = when (request.revision.stage) {
      AuditRepairStage.DIAGNOSIS -> {
        verifyDiagnosisBinding(request, auditRepairCycles)
        auditRepairCycles.start(request.initialCycle(authoritativeCriterionRefs))
      }
      AuditRepairStage.FINAL_AUDIT -> {
        auditRepairCycles.advance(request.identity, request.expectedRevision, attach(request, existing))
      }
      else -> auditRepairCycles.advance(request.identity, request.expectedRevision, request.revision)
    }
    recordProgressFallback(request, existing)
    return acknowledgeStage(cycle)
  }

  private fun verifyDiagnosisBinding(request: FeatureTaskAuditRepairStageRequest, cycles: AuditRepairCycleRepository) {
    val binding = cycles.findLaunchBinding(
      request.identity.workflowId,
      request.identity.executionId,
      request.identity.sessionId,
    )
    if (binding?.checkpoint == null || request.revision.assessment?.checkpoint != binding.checkpoint) {
      throw checkpointConflict(
        request.identity.workflowId,
        "Diagnosis must use the engine-owned implementation scope.",
      )
    }
  }

  private fun attach(request: FeatureTaskAuditRepairStageRequest, existing: AuditRepairCycle?): AuditRepairRevision {
    val checkpoint = existing?.latestCheckpoint
      ?: throw checkpointConflict(request.identity.workflowId, "The pending checkpoint has no retained content.")
    if (!checkpointCoordinator.verifyCurrent(checkpoint) ||
      (request.revision.checkpoint != null && request.revision.checkpoint != checkpoint)
    ) {
      throw checkpointConflict(request.identity.workflowId, "Retained repair content is no longer current.")
    }
    return request.revision.copy(checkpoint = checkpoint)
  }

  private fun validateRequest(request: FeatureTaskAuditRepairStageRequest): List<String> {
    if (request.expectedRevision < 0) {
      throw AuditRepairCycleConflictError("expected_revision must be non-negative.")
    }
    if (request.revision.stage == AuditRepairStage.DIAGNOSIS && request.expectedRevision != 0) {
      throw AuditRepairCycleConflictError("Diagnosis must be the first audit-repair stage.")
    }
    val authoritativeCriterionRefs = acceptanceCriteriaSource.criterionRefs(request.identity.workflowId)
      ?: throw AuditRepairCycleConflictError(
        "Active audit execution has no authoritative acceptance-criteria census.",
      )
    if (request.criterionRefs.toSet() != authoritativeCriterionRefs.toSet() ||
      request.criterionRefs.distinct().size != request.criterionRefs.size
    ) {
      throw AuditRepairCycleConflictError("Audit diagnosis does not cover the authoritative acceptance criteria.")
    }
    return authoritativeCriterionRefs
  }

  private fun replay(request: FeatureTaskAuditRepairStageRequest, existing: AuditRepairCycle?): Map<String, Any?>? {
    val replayed = existing?.revisions?.firstOrNull { it.requestId == request.revision.requestId }
    if (replayed != null) {
      val comparable = if (
        replayed.stage == AuditRepairStage.CHECKPOINT_PENDING && request.revision.checkpoint == null
      ) {
        replayed.copy(checkpoint = null)
      } else if (replayed.stage == AuditRepairStage.FINAL_AUDIT && request.revision.checkpoint == null) {
        replayed.copy(checkpoint = null)
      } else {
        replayed
      }
      val requested = if (
        replayed.stage == AuditRepairStage.CHECKPOINT_PENDING && request.revision.repositoryFingerprint == null
      ) {
        request.revision.copy(repositoryFingerprint = replayed.repositoryFingerprint)
      } else {
        request.revision
      }
      if (comparable.copy(recordedAt = "") != requested.copy(recordedAt = "") ||
        request.expectedRevision != (request.revision.revision - 1).coerceAtLeast(0)
      ) {
        throw AuditRepairCycleConflictError("Request identity was reused with different evidence.")
      }
      return stageAcknowledgement(
        requireNotNull(existing).copy(revisions = existing.revisions.take(replayed.revision + 1)),
      )
    }
    return null
  }

  private fun verifySource(request: FeatureTaskAuditRepairStageRequest, existing: AuditRepairCycle?) {
    val checkpoint = request.revision.assessment?.checkpoint ?: request.revision.checkpoint
    val finalAssessment = request.revision.stage == AuditRepairStage.SATISFIED ||
      (request.revision.stage == AuditRepairStage.PAUSED && request.revision.assessment != null)
    if (request.revision.stage in setOf(
        AuditRepairStage.DIAGNOSIS,
        AuditRepairStage.AUTHORIZED_REPAIR,
      ) || finalAssessment
    ) {
      val expected = if (finalAssessment) {
        checkpoint
      } else {
        existing?.latestAssessment?.checkpoint ?: checkpoint
      }
      if (expected == null || !checkpointCoordinator.verifyCurrent(expected)) {
        throw checkpointConflict(
          request.identity.workflowId,
          "The repository no longer matches the audit checkpoint.",
        )
      }
    }
  }

  private fun recordProgressFallback(request: FeatureTaskAuditRepairStageRequest, existing: AuditRepairCycle?) {
    val assessment = request.revision.assessment ?: return
    val previous = existing?.latestAssessment ?: return
    if (!assessment.progressSince(previous).blocked && checkpointCoordinator.activityFingerprint() == null) {
      diagnostics.warning(
        "Audit progress used fewer unresolved criteria because a repository activity fingerprint was unavailable. " +
          "The assessment still requires verified retained content.",
      )
    }
  }

  private fun capture(
    request: FeatureTaskAuditRepairStageRequest,
    previous: AuditRepairCycle,
    activity: String,
  ): AuditRepairCheckpoint {
    val checkpoint = checkpointCoordinator.capturePostRepair(
      cycleId = request.identity.cycleId,
      expectedFingerprint = activity,
      outcomes = request.revision.repairOutcomes.orEmpty(),
      checkpointIntent = request.revision.checkpointIntent,
      ownedPaths = previous.latestCheckpoint?.scopedPaths.orEmpty(),
    ) ?: throw checkpointConflict(request.identity.workflowId, "Cannot retain the requested repair checkpoint.")
    assertChangedRepair(request, previous, checkpoint)
    return checkpoint
  }

  private fun assertChangedRepair(
    request: FeatureTaskAuditRepairStageRequest,
    previous: AuditRepairCycle,
    checkpoint: AuditRepairCheckpoint,
  ) {
    if (previous.latestAssessment?.unmetCriterionRefs?.isNotEmpty() == true &&
      checkpoint.repositoryFingerprint == previous.latestAssessment?.checkpoint?.repositoryFingerprint
    ) {
      throw checkpointConflict(request.identity.workflowId, "Repair did not change the diagnosed content.")
    }
  }

  private fun acknowledgeStage(cycle: AuditRepairCycle): Map<String, Any?> = stageAcknowledgement(cycle)

  private fun stageAcknowledgement(cycle: AuditRepairCycle): Map<String, Any?> = linkedMapOf<String, Any?>(
    SharedPayloadKeys.STATUS to "ok",
    SharedPayloadKeys.WORKFLOW_ID to cycle.identity.workflowId,
    AuditRepairCycleKeys.CYCLE_ID to cycle.identity.cycleId,
    AuditRepairCycleKeys.REVISION to cycle.current.revision,
    AuditRepairCycleKeys.STAGE to cycle.current.stage.wireValue,
    AuditRepairCycleKeys.RECORDED_AT to cycle.current.recordedAt,
  ).apply {
    cycle.current.reason?.let { put(AuditRepairCycleKeys.REASON, it) }
    cycle.current.checkpoint?.let { checkpoint ->
      put(AuditRepairCycleKeys.CHECKPOINT_PATHS, checkpoint.scopedPaths)
      put(AuditRepairCycleKeys.CHECKPOINT_ID, checkpoint.checkpointId)
      put(AuditRepairCycleKeys.REPOSITORY_FINGERPRINT, checkpoint.repositoryFingerprint)
    }
  }

  private fun checkpointConflict(workflowId: String, reason: String): AuditRepairCycleConflictError {
    diagnostics.warning("Audit checkpoint rejected for workflow '$workflowId': $reason")
    return AuditRepairCycleConflictError(reason, needsRecovery = true)
  }
}
