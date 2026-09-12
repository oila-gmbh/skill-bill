package skillbill.engine.featuretask

import me.tatarka.inject.annotations.Inject
import skillbill.boundary.OpenBoundaryMap
import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.workflow.ValidationEvidencePayloadKeys
import skillbill.engine.featuretask.model.FeatureTaskAuditRepairStageRequestCodec
import skillbill.engine.featuretask.model.FeatureTaskPhaseSettlementAuditRequest
import skillbill.engine.featuretask.model.FeatureTaskPhaseSettlementBlockRequest
import skillbill.engine.featuretask.model.FeatureTaskPhaseSettlementCompleteRequest
import skillbill.error.AuditRepairCycleConflictError
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.featuretask.AuditRepairCycleRepository
import skillbill.ports.featuretask.FeatureTaskPhaseSettlementRepository
import skillbill.ports.featuretask.model.FeatureTaskPhaseSettlement
import skillbill.ports.featuretask.model.FeatureTaskPhaseSettlementKind
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.ProsePhaseOutputSynthesizer
import skillbill.workflow.taskruntime.model.AuditRepairCheckpoint
import skillbill.workflow.taskruntime.model.AuditRepairCycle
import skillbill.workflow.taskruntime.model.AuditRepairIdentity
import skillbill.workflow.taskruntime.model.AuditRepairLaunchBinding
import skillbill.workflow.taskruntime.model.AuditRepairRevision
import skillbill.workflow.taskruntime.model.AuditRepairStage
import skillbill.workflow.taskruntime.model.FeatureTaskAuditRepairStageRequest
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeValidationEvidence
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.model.SettlementEnvelopeRequest
import java.time.Clock

interface FeatureTaskRuntimeAcceptanceCriteriaSource {
  fun cycleRequired(workflowId: String): Boolean = true
  fun criterionRefs(workflowId: String): List<String>?
}

@Inject
class FeatureTaskPhaseSettlementService(
  private val repository: FeatureTaskPhaseSettlementRepository,
  private val clock: Clock,
  private val auditRepairCycles: AuditRepairCycleRepository,
  private val acceptanceCriteriaSource: FeatureTaskRuntimeAcceptanceCriteriaSource,
  private val checkpointCoordinator: AuditRepairCheckpointCoordinator,
  private val diagnostics: RuntimeDiagnostics,
) {
  @OpenBoundaryMap("MCP feature_task_phase_complete acknowledgement wire map")
  fun complete(request: FeatureTaskPhaseSettlementCompleteRequest): Map<String, Any?> {
    require(ProsePhaseOutputSynthesizer.isProsePhase(request.phaseId)) {
      "phase_id must be a prose phase (preplan|plan|implement|audit)."
    }
    require(request.phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT) {
      "Use feature_task_audit_settle for audit completions."
    }
    val envelope = ProsePhaseOutputSynthesizer.envelopeFromSettlement(
      SettlementEnvelopeRequest(
        phaseId = request.phaseId,
        status = "completed",
        value = request.value,
        summary = request.summary?.takeIf { it.any { ch -> !ch.isWhitespace() } } ?: truncateSummary(request.value),
        prompt = request.prompt,
      ),
    )
    return persist(
      PersistRequest(
        workflowId = request.workflowId,
        phaseId = request.phaseId,
        attempt = request.attempt,
        kind = KIND_COMPLETE,
        envelope = envelope,
      ),
    )
  }

  @OpenBoundaryMap("MCP feature_task_phase_block acknowledgement wire map")
  fun block(request: FeatureTaskPhaseSettlementBlockRequest): Map<String, Any?> {
    require(ProsePhaseOutputSynthesizer.isProsePhase(request.phaseId)) {
      "phase_id must be a prose phase (preplan|plan|implement|audit)."
    }
    val envelope = ProsePhaseOutputSynthesizer.envelopeFromSettlement(
      SettlementEnvelopeRequest(
        phaseId = request.phaseId,
        status = "blocked",
        value = request.reason,
        summary = truncateSummary(request.reason),
        failureDisposition = request.failureDisposition,
        verdict = if (request.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT) "gaps_found" else null,
      ),
    )
    return persist(
      PersistRequest(
        workflowId = request.workflowId,
        phaseId = request.phaseId,
        attempt = request.attempt,
        kind = KIND_BLOCK,
        envelope = envelope,
      ),
    )
  }

  @OpenBoundaryMap("MCP feature_task_audit_settle acknowledgement wire map")
  fun auditSettle(request: FeatureTaskPhaseSettlementAuditRequest): Map<String, Any?> {
    require(request.phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT) {
      "feature_task_audit_settle requires phase_id=audit."
    }
    assertCycleSettlement(request.workflowId, request.attempt, request.verdict, request.value)
    val envelope = ProsePhaseOutputSynthesizer.envelopeFromSettlement(
      SettlementEnvelopeRequest(
        phaseId = request.phaseId,
        status = "completed",
        value = request.value,
        summary = request.summary?.takeIf { it.any { ch -> !ch.isWhitespace() } } ?: truncateSummary(request.value),
        verdict = request.verdict,
      ),
    )
    return persist(
      PersistRequest(
        workflowId = request.workflowId,
        phaseId = request.phaseId,
        attempt = request.attempt,
        kind = KIND_AUDIT_SETTLE,
        envelope = envelope,
      ),
    )
  }

  @OpenBoundaryMap("CLI and MCP audit stage request decoded before domain transition validation")
  fun auditStage(raw: Map<String, Any?>): Map<String, Any?> =
    auditStage(FeatureTaskAuditRepairStageRequestCodec.fromMap(raw, clock.instant().toString()))

  @OpenBoundaryMap("MCP feature_task_audit_stage durable acknowledgement wire map")
  fun auditStage(request: FeatureTaskAuditRepairStageRequest): Map<String, Any?> = AuditRepairStageSettlement(
    auditRepairCycles,
    acceptanceCriteriaSource,
    checkpointCoordinator,
    diagnostics,
    clock,
  ).auditStage(request)

  fun prepareAuditCheckpoint(cycleId: String, fingerprint: String, paths: List<String>): AuditRepairCheckpoint =
    checkpointCoordinator.capturePostRepair(
      cycleId,
      fingerprint,
      emptyList(),
      "diagnosis",
      paths,
    ) ?: throw checkpointConflict(cycleId, "Cannot retain the implementation checkpoint before audit.")

  fun requiresAuditRepairCycle(workflowId: String): Boolean = acceptanceCriteriaSource.cycleRequired(workflowId)

  fun auditRepairCycle(workflowId: String, auditAttempt: Int): AuditRepairCycle? =
    auditRepairCycles.findActive(workflowId) ?: auditRepairCycles.findForAttempt(workflowId, auditAttempt)

  fun bindAuditRepairLaunch(
    identity: AuditRepairIdentity,
    checkpoint: AuditRepairCheckpoint? = null,
  ): AuditRepairLaunchBinding = auditRepairCycles.bindLaunch(
    AuditRepairLaunchBinding(
      workflowId = identity.workflowId,
      auditAttempt = identity.auditAttempt,
      cycleId = identity.cycleId,
      executionId = identity.executionId,
      requestedSessionId = identity.sessionId,
      ownerToken = identity.ownerToken,
      fencingGeneration = identity.fencingGeneration,
      boundAt = clock.instant().toString(),
      checkpoint = checkpoint,
    ),
  )

  fun recordAuditRepairProviderSession(
    binding: AuditRepairLaunchBinding,
    ownerToken: String,
    fencingGeneration: Long,
    providerSessionId: String,
  ): AuditRepairLaunchBinding = auditRepairCycles.recordProviderSession(
    AuditRepairIdentity(
      binding.workflowId,
      binding.auditAttempt,
      binding.executionId,
      binding.requestedSessionId,
      binding.cycleId,
      ownerToken,
      fencingGeneration,
    ),
    providerSessionId,
  )

  fun auditRepairLaunchBinding(
    workflowId: String,
    executionId: String,
    requestedSessionId: String,
  ): AuditRepairLaunchBinding? = auditRepairCycles.findLaunchBinding(workflowId, executionId, requestedSessionId)

  fun pauseForUnavailableAuditRepairSession(binding: AuditRepairLaunchBinding, reason: String): AuditRepairCycle? {
    val cycle = auditRepairCycles.find(binding.workflowId, binding.cycleId) ?: return null
    if (cycle.current.stage == AuditRepairStage.SATISFIED || cycle.current.stage == AuditRepairStage.PAUSED) {
      return cycle
    }
    val pause = AuditRepairRevision(
      revision = cycle.current.revision + 1,
      requestId = "runtime-session-pause-${cycle.current.revision + 1}",
      stage = AuditRepairStage.PAUSED,
      recordedAt = clock.instant().toString(),
      reason = reason,
    )
    val currentIdentity = cycle.identity.copy(
      ownerToken = binding.ownerToken,
      fencingGeneration = binding.fencingGeneration,
    )
    return auditRepairCycles.advance(currentIdentity, cycle.current.revision, pause)
  }

  fun auditRepairReady(workflowId: String, auditAttempt: Int, expectedValue: String? = null): Boolean {
    val authoritativeCriterionRefs = acceptanceCriteriaSource.criterionRefs(workflowId)
    val cycle = auditRepairCycles.findForAttempt(workflowId, auditAttempt)
      ?: auditRepairCycles.findActive(workflowId)
      ?: return !acceptanceCriteriaSource.cycleRequired(workflowId)
    return runCatching {
      cycle.validate()
      val expected = authoritativeCriterionRefs ?: cycle.criterionRefs.takeUnless {
        acceptanceCriteriaSource.cycleRequired(workflowId)
      }
      require(expected != null && cycle.criterionRefs.toSet() == expected.toSet())
      require(cycle.current.stage == AuditRepairStage.SATISFIED)
      val finalAssessment = requireNotNull(cycle.finalAssessment)
      require(finalAssessment.unmetCriterionRefs.isEmpty())
      require(expectedValue == null || expectedValue == finalAssessment.value)
      val checkpoint = finalAssessment.checkpoint
      require(checkpointCoordinator.verifyRetained(checkpoint))
      require(checkpointCoordinator.verifyCurrent(checkpoint))
    }.onFailure { error ->
      diagnostics.warning(
        "Audit-repair final eligibility could not be proven for workflow '$workflowId'.",
        error,
      )
    }.isSuccess
  }

  private fun checkpointConflict(workflowId: String, reason: String): AuditRepairCycleConflictError {
    diagnostics.warning("Audit-repair checkpoint verification failed for workflow '$workflowId': $reason")
    return AuditRepairCycleConflictError(reason)
  }

  @OpenBoundaryMap("Durable MCP phase-settlement envelope wire map for gate consumption")
  fun findEnvelope(workflowId: String, phaseId: String, attempt: Int): Map<String, Any?>? {
    val settlement = repository.find(workflowId, phaseId, attempt) ?: return null
    val envelope = JsonCodec.parseObjectOrNull(settlement.envelopeJson)
      ?.let { JsonCodec.anyToStringAnyMap(JsonCodec.jsonElementToValue(it)) }
    if (phaseId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT &&
      envelope?.get(SharedPayloadKeys.STATUS) == "completed"
    ) {
      val produced = envelope[SharedPayloadKeys.PRODUCED_OUTPUTS]?.let(JsonCodec::anyToStringAnyMap)
      auditRepairCycles.findForAttempt(workflowId, attempt)?.let { cycle ->
        assertCycleSettlement(
          cycle,
          envelope[SharedPayloadKeys.VERDICT] as? String,
          produced?.get(SharedPayloadKeys.VALUE) as? String,
          requireCurrent = false,
        )
      }
    }
    val evidence = envelope
      ?.get(SharedPayloadKeys.PRODUCED_OUTPUTS)
      ?.let(JsonCodec::anyToStringAnyMap)
      ?.get(ValidationEvidencePayloadKeys.VALIDATION_RESULT)
      ?.let(JsonCodec::anyToStringAnyMap)
      ?.get(ValidationEvidencePayloadKeys.VALIDATION_EVIDENCE)
      ?.let(JsonCodec::anyToStringAnyMap)
    if (evidence != null) {
      FeatureTaskRuntimeValidationEvidence.fromArtifactMap(evidence, "$phaseId settlement")
    }
    return envelope
  }

  fun clear(workflowId: String, phaseId: String, attempt: Int): Boolean =
    repository.delete(workflowId, phaseId, attempt)

  @OpenBoundaryMap("Validated phase envelope checked against durable audit evidence")
  fun assertAuditCompletion(
    workflowId: String,
    attempt: Int,
    envelope: Map<String, Any?>,
    repositoryFingerprint: String?,
    criterionRefs: List<String>,
  ) {
    if (envelope[SharedPayloadKeys.STATUS] != "completed") return
    val authoritativeCriterionRefs = acceptanceCriteriaSource.criterionRefs(workflowId)
    val cycle = auditRepairCycles.findForAttempt(workflowId, attempt)
      ?: auditRepairCycles.findActive(workflowId)
      ?: if (!acceptanceCriteriaSource.cycleRequired(workflowId)) {
        return
      } else {
        throw AuditRepairCycleConflictError("Audit completion requires a durable audit-repair cycle.")
      }
    cycle.validate()
    val expectedCriterionRefs = authoritativeCriterionRefs ?: cycle.criterionRefs
    assertCompletionCensus(cycle, expectedCriterionRefs, repositoryFingerprint, criterionRefs)
    val produced = envelope[SharedPayloadKeys.PRODUCED_OUTPUTS]?.let(JsonCodec::anyToStringAnyMap)
    assertCycleSettlement(
      cycle,
      envelope[SharedPayloadKeys.VERDICT] as? String,
      produced?.get(SharedPayloadKeys.VALUE) as? String,
    )
    if (envelope[SharedPayloadKeys.VERDICT] == FeatureTaskRuntimeVerdict.SATISFIED.wireValue &&
      !auditRepairReady(workflowId, attempt, produced?.get(SharedPayloadKeys.VALUE) as? String)
    ) {
      throw AuditRepairCycleConflictError("Repository no longer matches the satisfied final audit.")
    }
  }

  private fun assertCompletionCensus(
    cycle: AuditRepairCycle,
    expectedCriterionRefs: List<String>,
    repositoryFingerprint: String?,
    criterionRefs: List<String>,
  ) {
    if (repositoryFingerprint.isNullOrBlank() || criterionRefs.toSet() != expectedCriterionRefs.toSet()) {
      throw AuditRepairCycleConflictError("Audit completion has no current evidence for the criterion census.")
    }
    if (cycle.criterionRefs.toSet() != expectedCriterionRefs.toSet() ||
      expectedCriterionRefs.distinct().size != expectedCriterionRefs.size
    ) {
      throw AuditRepairCycleConflictError("Audit cycle does not cover the workflow acceptance criteria.")
    }
  }

  private fun assertCycleSettlement(workflowId: String, attempt: Int, verdict: String?, value: String?) {
    val cycle = auditRepairCycles.findForAttempt(workflowId, attempt)
      ?: auditRepairCycles.findActive(workflowId)
      ?: if (!acceptanceCriteriaSource.cycleRequired(workflowId)) {
        return
      } else {
        throw AuditRepairCycleConflictError("Audit settlement requires a durable audit-repair cycle.")
      }
    assertCycleSettlement(cycle, verdict, value)
  }

  private fun assertCycleSettlement(
    cycle: AuditRepairCycle,
    verdict: String?,
    value: String?,
    requireCurrent: Boolean = true,
  ) {
    cycle.validate()
    if (verdict == FeatureTaskRuntimeVerdict.SATISFIED.wireValue) {
      assertSatisfiedSettlement(cycle, value, requireCurrent)
      return
    }
    if (verdict == FeatureTaskRuntimeVerdict.GAPS_FOUND.wireValue) {
      val failedAssessment = cycle.current
        .takeIf { it.stage == AuditRepairStage.PAUSED }
        ?.assessment
      if (failedAssessment?.unmetCriterionRefs?.isNotEmpty() == true && value == failedAssessment.value) {
        return
      }
    }
    throw AuditRepairCycleConflictError("Audit settlement must publish durable audit-repair evidence.")
  }

  private fun assertSatisfiedSettlement(cycle: AuditRepairCycle, value: String?, requireCurrent: Boolean) {
    val final = cycle.finalAssessment
    if (final == null || value != final.value || cycle.current.stage != AuditRepairStage.SATISFIED) {
      throw AuditRepairCycleConflictError("Audit settlement must publish the durable satisfied final assessment.")
    }
    if (requireCurrent && !auditRepairReady(cycle.identity.workflowId, cycle.identity.auditAttempt, value)) {
      throw AuditRepairCycleConflictError("Audit cycle is not eligible for final settlement.")
    }
  }

  private fun persist(request: PersistRequest): Map<String, Any?> {
    val envelopeJson = JsonCodec.mapToJsonString(request.envelope)
    repository.upsert(
      FeatureTaskPhaseSettlement(
        workflowId = request.workflowId,
        phaseId = request.phaseId,
        attempt = request.attempt,
        kind = request.kind,
        envelopeJson = envelopeJson,
        recordedAt = clock.instant().toString(),
      ),
    )
    return linkedMapOf(
      SharedPayloadKeys.STATUS to "ok",
      SharedPayloadKeys.WORKFLOW_ID to request.workflowId,
      SharedPayloadKeys.PHASE_ID to request.phaseId,
      "attempt" to request.attempt,
      "kind" to request.kind.wireValue,
      "envelope" to request.envelope,
    )
  }

  private fun truncateSummary(value: String): String {
    val compact = value.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
    return when {
      compact.isBlank() -> "Phase settlement recorded."
      compact.length <= SUMMARY_MAX_CHARS -> compact
      else -> compact.take(SUMMARY_ELLIPSIS_PREFIX) + "..."
    }
  }

  private data class PersistRequest(
    val workflowId: String,
    val phaseId: String,
    val attempt: Int,
    val kind: FeatureTaskPhaseSettlementKind,
    val envelope: Map<String, Any?>,
  )

  companion object {
    val KIND_COMPLETE: FeatureTaskPhaseSettlementKind = FeatureTaskPhaseSettlementKind.Complete
    val KIND_BLOCK: FeatureTaskPhaseSettlementKind = FeatureTaskPhaseSettlementKind.Block
    val KIND_AUDIT_SETTLE: FeatureTaskPhaseSettlementKind = FeatureTaskPhaseSettlementKind.AuditSettle
    private const val SUMMARY_MAX_CHARS: Int = 240
    private const val SUMMARY_ELLIPSIS_PREFIX: Int = 237
  }
}
