package skillbill.engine.featuretask

import skillbill.application.testHarnessClock
import skillbill.contracts.JsonCodec
import skillbill.contracts.workflow.ValidationEvidencePayloadKeys
import skillbill.engine.featuretask.model.FeatureTaskPhaseSettlementBlockRequest
import skillbill.engine.featuretask.model.FeatureTaskPhaseSettlementCompleteRequest
import skillbill.ports.featuretask.model.FeatureTaskPhaseSettlement
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeValidationCommandResult
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeValidationEvidence
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FeatureTaskPhaseSettlementServiceTest {
  @Test
  fun `complete then findEnvelope returns stuffed value`() {
    val service = FeatureTaskPhaseSettlementService(InMemoryFeatureTaskPhaseSettlementRepository(), testHarnessClock)
    service.complete(
      FeatureTaskPhaseSettlementCompleteRequest(
        workflowId = "wftr-test",
        phaseId = "implement",
        attempt = 1,
        value = """{"projection_kind":"implementation_receipt","completed_task_ids":["task-1"]}""",
      ),
    )
    val envelope = assertNotNull(service.findEnvelope("wftr-test", "implement", 1))
    assertEquals("completed", envelope["status"])
    val produced = assertNotNull(JsonCodec.anyToStringAnyMap(envelope["produced_outputs"]))
    assertTrue((produced["value"] as String).contains("implementation_receipt"))
  }

  @Test
  fun `last write wins for the same attempt`() {
    val service = FeatureTaskPhaseSettlementService(InMemoryFeatureTaskPhaseSettlementRepository(), testHarnessClock)
    service.complete(
      FeatureTaskPhaseSettlementCompleteRequest(
        workflowId = "wftr-test",
        phaseId = "plan",
        attempt = 1,
        value = "first",
      ),
    )
    service.complete(
      FeatureTaskPhaseSettlementCompleteRequest(
        workflowId = "wftr-test",
        phaseId = "plan",
        attempt = 1,
        value = "second",
      ),
    )
    val envelope = assertNotNull(service.findEnvelope("wftr-test", "plan", 1))
    val produced = assertNotNull(JsonCodec.anyToStringAnyMap(envelope["produced_outputs"]))
    assertEquals("second", produced["value"])
  }

  @Test
  fun `block stores blocked status`() {
    val service = FeatureTaskPhaseSettlementService(InMemoryFeatureTaskPhaseSettlementRepository(), testHarnessClock)
    service.block(
      FeatureTaskPhaseSettlementBlockRequest(
        workflowId = "wftr-test",
        phaseId = "preplan",
        attempt = 1,
        reason = "needs human",
      ),
    )
    val envelope = assertNotNull(service.findEnvelope("wftr-test", "preplan", 1))
    assertEquals("blocked", envelope["status"])
  }

  @Test
  fun `clear removes a stored settlement so findEnvelope returns null`() {
    val repo = InMemoryFeatureTaskPhaseSettlementRepository()
    val service = FeatureTaskPhaseSettlementService(repo, testHarnessClock)
    repo.upsert(
      FeatureTaskPhaseSettlement(
        workflowId = "wftr-test",
        phaseId = "plan",
        attempt = 1,
        kind = FeatureTaskPhaseSettlementService.KIND_COMPLETE,
        envelopeJson = """{"status":"completed"}""",
        recordedAt = Instant.now().toString(),
      ),
    )
    assertNotNull(service.findEnvelope("wftr-test", "plan", 1))
    assertTrue(service.clear("wftr-test", "plan", 1))
    assertNull(service.findEnvelope("wftr-test", "plan", 1))
  }

  @Test
  fun `settlement round trip preserves multiple validation command results`() {
    val service = FeatureTaskPhaseSettlementService(InMemoryFeatureTaskPhaseSettlementRepository(), testHarnessClock)
    val evidence = FeatureTaskRuntimeValidationEvidence(
      listOf(
        FeatureTaskRuntimeValidationCommandResult("./gradlew check", 1),
        FeatureTaskRuntimeValidationCommandResult("./gradlew check --offline", 0),
      ),
    )
    service.complete(
      FeatureTaskPhaseSettlementCompleteRequest(
        workflowId = "wftr-test",
        phaseId = "implement",
        attempt = 1,
        value = JsonCodec.mapToJsonString(
          mapOf(ValidationEvidencePayloadKeys.VALIDATION_EVIDENCE to evidence.toArtifactMap()),
        ),
      ),
    )
    val envelope = assertNotNull(service.findEnvelope("wftr-test", "implement", 1))
    val produced = assertNotNull(JsonCodec.anyToStringAnyMap(envelope["produced_outputs"]))
    val value = JsonCodec.parseObjectOrNull(produced["value"] as String)
      ?.let(JsonCodec::jsonElementToValue)
      ?.let(JsonCodec::anyToStringAnyMap)
    val results = JsonCodec.anyToStringAnyMap(value?.get(ValidationEvidencePayloadKeys.VALIDATION_EVIDENCE))
      ?.get(ValidationEvidencePayloadKeys.RESULTS) as? List<*>
    assertEquals(2, results?.size)
  }
}
