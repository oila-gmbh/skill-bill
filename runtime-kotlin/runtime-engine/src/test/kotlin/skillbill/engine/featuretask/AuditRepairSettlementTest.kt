package skillbill.engine.featuretask

import skillbill.application.testHarnessClock
import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.engine.featuretask.model.FeatureTaskPhaseSettlementAuditRequest
import skillbill.error.AuditRepairCycleConflictError
import skillbill.ports.featuretask.InMemoryAuditRepairCycleRepository
import skillbill.ports.featuretask.model.FeatureTaskPhaseSettlement
import skillbill.workflow.taskruntime.ProsePhaseOutputSynthesizer
import skillbill.workflow.taskruntime.model.AuditRepairAssessment
import skillbill.workflow.taskruntime.model.AuditRepairCheckpoint
import skillbill.workflow.taskruntime.model.AuditRepairCriterion
import skillbill.workflow.taskruntime.model.AuditRepairCycle
import skillbill.workflow.taskruntime.model.AuditRepairIdentity
import skillbill.workflow.taskruntime.model.AuditRepairOutcome
import skillbill.workflow.taskruntime.model.AuditRepairRevision
import skillbill.workflow.taskruntime.model.AuditRepairStage
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.model.SettlementEnvelopeRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AuditRepairSettlementTest {
  @Test
  fun `initially satisfied diagnosis needs a final audit and retains pending validation without repairs`() {
    val cycles = InMemoryAuditRepairCycleRepository()
    val settlements = InMemoryFeatureTaskPhaseSettlementRepository()
    val service = FeatureTaskPhaseSettlementService(settlements, testHarnessClock, cycles)
    val criterion = pendingCriterion
    val initial = initiallySatisfiedDiagnosis(criterion)
    cycles.start(initial)
    assertFailsWith<AuditRepairCycleConflictError> { service.auditSettle(request) }
    cycles.advance(
      identity,
      0,
      revision(1, AuditRepairStage.CHECKPOINT_PENDING).copy(
        repositoryFingerprint = before.repositoryFingerprint,
        checkpoint = before,
        checkpointIntent = "initially-satisfied-checkpoint",
        repairOutcomes = emptyList(),
      ),
    )
    cycles.advance(
      identity,
      1,
      revision(2, AuditRepairStage.FINAL_AUDIT).copy(checkpoint = before),
    )
    assertFailsWith<AuditRepairCycleConflictError> { service.auditSettle(request) }
    assertNull(settlements.find(identity.workflowId, "audit", 1))
    val finalValue = "Final readiness assessment. Validation remains pending in the validate phase."
    val final = cycles.advance(
      identity,
      2,
      revision(3, AuditRepairStage.SATISFIED).copy(
        assessment = AuditRepairAssessment(before, listOf(criterion), finalValue),
      ),
    )
    service.assertAuditCompletion(
      identity.workflowId,
      1,
      completionEnvelope(finalValue),
      before.repositoryFingerprint,
      initial.criterionRefs,
    )
    service.auditSettle(request.copy(value = finalValue))
    val envelope = assertNotNull(service.findEnvelope(identity.workflowId, "audit", 1))
    val produced = JsonCodec.anyToStringAnyMap(envelope[SharedPayloadKeys.PRODUCED_OUTPUTS])
    assertEquals(finalValue, produced?.get(SharedPayloadKeys.VALUE))
    assertEquals(0, final.repairRoundCount)
    assertEquals(initial.diagnosis, final.diagnosis)
    assertEquals(criterion.pendingValidation, final.finalAssessment?.criteria?.single()?.pendingValidation)
    assertEquals(emptyList(), final.revisions[1].repairOutcomes)
  }

  @Test
  fun `intermediate cycle evidence cannot settle or restore a completed audit`() {
    val cycles = InMemoryAuditRepairCycleRepository()
    val settlements = InMemoryFeatureTaskPhaseSettlementRepository()
    val service = FeatureTaskPhaseSettlementService(settlements, testHarnessClock, cycles)
    cycles.start(diagnosis)
    val stages = listOf(
      authorization,
      checkpointIntent,
      checkpointAttachment,
      revision(4, AuditRepairStage.PAUSED).copy(
        assessment = diagnosis.diagnosis.copy(checkpoint = after),
        reason = "Final audit still has gaps.",
      ),
    )
    for (stage in stages) {
      assertFailsWith<AuditRepairCycleConflictError> { service.auditSettle(request) }
      assertFailsWith<AuditRepairCycleConflictError> {
        service.assertAuditCompletion(
          identity.workflowId,
          1,
          completionEnvelope(),
          after.repositoryFingerprint,
          diagnosis.criterionRefs,
        )
      }
      assertNull(settlements.find(identity.workflowId, "audit", 1))
      settlements.upsert(persistedClaim())
      assertFailsWith<AuditRepairCycleConflictError> { service.findEnvelope(identity.workflowId, "audit", 1) }
      settlements.delete(identity.workflowId, "audit", 1)
      cycles.advance(identity, stage.revision - 1, stage)
    }
    assertFailsWith<AuditRepairCycleConflictError> { service.auditSettle(request) }
    assertFailsWith<AuditRepairCycleConflictError> {
      service.assertAuditCompletion(
        identity.workflowId,
        1,
        completionEnvelope(),
        after.repositoryFingerprint,
        diagnosis.criterionRefs,
      )
    }
    assertNull(settlements.find(identity.workflowId, "audit", 1))
  }

  @Test
  fun `satisfied cycle publishes only its final assessment and legacy attempts remain readable`() {
    val cycles = InMemoryAuditRepairCycleRepository()
    val settlements = InMemoryFeatureTaskPhaseSettlementRepository()
    val service = FeatureTaskPhaseSettlementService(settlements, testHarnessClock, cycles)
    cycles.start(diagnosis)
    listOf(authorization, checkpointIntent, checkpointAttachment).forEach {
      cycles.advance(identity, it.revision - 1, it)
    }
    cycles.advance(
      identity,
      3,
      revision(4, AuditRepairStage.SATISFIED).copy(
        assessment = AuditRepairAssessment(
          after,
          listOf(AuditRepairCriterion("AC-001", true, "Repair is present.")),
          request.value,
        ),
      ),
    )
    assertFailsWith<AuditRepairCycleConflictError> {
      service.auditSettle(request.copy(value = "Repairs applied without a final assessment."))
    }
    assertFailsWith<AuditRepairCycleConflictError> {
      service.auditSettle(request.copy(verdict = FeatureTaskRuntimeVerdict.GAPS_FOUND.wireValue))
    }
    assertRejectedCompletionClaims(service)
    service.assertAuditCompletion(
      identity.workflowId,
      1,
      completionEnvelope(),
      after.repositoryFingerprint,
      diagnosis.criterionRefs,
    )
    assertNull(settlements.find(identity.workflowId, "audit", 1))
    service.auditSettle(request)
    val envelope = assertNotNull(service.findEnvelope(identity.workflowId, "audit", 1))
    val produced = JsonCodec.anyToStringAnyMap(envelope[SharedPayloadKeys.PRODUCED_OUTPUTS])
    assertEquals(request.value, produced?.get(SharedPayloadKeys.VALUE))
    settlements.upsert(persistedClaim("Replaced final audit."))
    assertFailsWith<AuditRepairCycleConflictError> { service.findEnvelope(identity.workflowId, "audit", 1) }
    settlements.upsert(persistedClaim("Legacy audit evidence.").copy(attempt = 2))
    assertNotNull(service.findEnvelope(identity.workflowId, "audit", 2))
  }

  private fun assertRejectedCompletionClaims(service: FeatureTaskPhaseSettlementService) {
    listOf(null, "").forEach { staleFingerprint ->
      assertFailsWith<AuditRepairCycleConflictError> {
        service.assertAuditCompletion(
          identity.workflowId,
          1,
          completionEnvelope(),
          staleFingerprint,
          diagnosis.criterionRefs,
        )
      }
    }
    assertFailsWith<AuditRepairCycleConflictError> {
      service.assertAuditCompletion(
        identity.workflowId,
        1,
        completionEnvelope(),
        after.repositoryFingerprint,
        listOf("AC-001", "AC-002"),
      )
    }
    assertFailsWith<AuditRepairCycleConflictError> {
      service.assertAuditCompletion(
        identity.workflowId,
        1,
        completionEnvelope("Repairs applied without a final assessment."),
        after.repositoryFingerprint,
        diagnosis.criterionRefs,
      )
    }
  }

  private val pendingCriterion get() = AuditRepairCriterion(
    "AC-001",
    true,
    "Repository is ready for the validation phase.",
    pendingValidation = "The validate phase must run the dominant-stack quality gate.",
  )

  private fun initiallySatisfiedDiagnosis(criterion: AuditRepairCriterion): AuditRepairCycle = diagnosis.copy(
    revisions = listOf(
      diagnosis.current.copy(
        assessment = AuditRepairAssessment(before, listOf(criterion), "Initial readiness assessment."),
      ),
    ),
  )

  private fun persistedClaim(value: String = request.value): FeatureTaskPhaseSettlement = FeatureTaskPhaseSettlement(
    workflowId = identity.workflowId,
    phaseId = "audit",
    attempt = 1,
    kind = FeatureTaskPhaseSettlementService.KIND_AUDIT_SETTLE,
    envelopeJson = JsonCodec.mapToJsonString(completionEnvelope(value)),
    recordedAt = testHarnessClock.instant().toString(),
  )

  private fun completionEnvelope(value: String = request.value): Map<String, Any?> =
    ProsePhaseOutputSynthesizer.envelopeFromSettlement(
      SettlementEnvelopeRequest(
        phaseId = "audit",
        status = "completed",
        value = value,
        summary = "Audit claim.",
        verdict = FeatureTaskRuntimeVerdict.SATISFIED.wireValue,
      ),
    )

  private fun revision(number: Int, stage: AuditRepairStage): AuditRepairRevision = AuditRepairRevision(
    revision = number,
    requestId = "request-$number",
    stage = stage,
    recordedAt = testHarnessClock.instant().toString(),
  )

  private val identity = AuditRepairIdentity("workflow", 1, "execution", "session", "cycle", "owner", 1)
  private val before = AuditRepairCheckpoint("before", "before-tree")
  private val after = AuditRepairCheckpoint("after", "after-tree")
  private val diagnosis = AuditRepairCycle(
    identity,
    listOf("AC-001"),
    listOf(
      revision(0, AuditRepairStage.DIAGNOSIS).copy(
        assessment = AuditRepairAssessment(
          before,
          listOf(AuditRepairCriterion("AC-001", false, "Gap.", "repair", "Apply repair.")),
          "Original diagnosis.",
        ),
      ),
    ),
  )
  private val authorization = revision(1, AuditRepairStage.AUTHORIZED_REPAIR).copy(
    repositoryFingerprint = before.repositoryFingerprint,
  )
  private val checkpointIntent = revision(2, AuditRepairStage.CHECKPOINT_PENDING).copy(
    repositoryFingerprint = after.repositoryFingerprint,
    checkpoint = after,
    checkpointIntent = "checkpoint-intent",
    repairOutcomes = listOf(AuditRepairOutcome("repair", "Applied repair.", listOf("src/Repair.kt"))),
  )
  private val checkpointAttachment = revision(3, AuditRepairStage.FINAL_AUDIT).copy(checkpoint = after)
  private val request = FeatureTaskPhaseSettlementAuditRequest(
    workflowId = identity.workflowId,
    phaseId = "audit",
    attempt = 1,
    verdict = FeatureTaskRuntimeVerdict.SATISFIED.wireValue,
    value = "Final repository assessment.",
  )
}
