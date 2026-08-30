package skillbill.application.featuretask

import skillbill.application.featuretask.model.FeatureTaskPhaseSettlementBlockRequest
import skillbill.application.featuretask.model.FeatureTaskPhaseSettlementCompleteRequest
import skillbill.contracts.JsonSupport
import skillbill.ports.featuretask.model.FeatureTaskPhaseSettlement
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FeatureTaskPhaseSettlementServiceTest {
  @Test
  fun `complete then findEnvelope returns stuffed value`() {
    val service = FeatureTaskPhaseSettlementService(InMemoryFeatureTaskPhaseSettlementRepository())
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
    val produced = assertNotNull(JsonSupport.anyToStringAnyMap(envelope["produced_outputs"]))
    assertTrue((produced["value"] as String).contains("implementation_receipt"))
  }

  @Test
  fun `last write wins for the same attempt`() {
    val service = FeatureTaskPhaseSettlementService(InMemoryFeatureTaskPhaseSettlementRepository())
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
    val produced = assertNotNull(JsonSupport.anyToStringAnyMap(envelope["produced_outputs"]))
    assertEquals("second", produced["value"])
  }

  @Test
  fun `block stores blocked status`() {
    val service = FeatureTaskPhaseSettlementService(InMemoryFeatureTaskPhaseSettlementRepository())
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
    val service = FeatureTaskPhaseSettlementService(repo)
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
}
