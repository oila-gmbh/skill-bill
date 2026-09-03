package skillbill.workflow.taskruntime

import skillbill.contracts.JsonSupport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProsePhaseOutputSynthesizerTest {
  @Test
  fun `implementation_receipt sibling becomes stuffed value`() {
    val raw =
      """
      {
        "contract_version": "0.6",
        "phase_id": "implement",
        "status": "completed",
        "summary": "Did the work.",
        "produced_outputs": {
          "implementation_receipt": {
            "projection_kind": "implementation_receipt",
            "completed_task_ids": ["task-1"]
          }
        }
      }
      """.trimIndent()

    val envelope = assertNotNull(ProsePhaseOutputSynthesizer.trySynthesize(raw, "implement"))
    val produced = assertNotNull(JsonSupport.anyToStringAnyMap(envelope["produced_outputs"]))
    val value = assertNotNull(produced["value"] as? String)
    assertTrue(value.contains("implementation_receipt") || value.contains("completed_task_ids"))
    assertEquals("completed", envelope["status"])
    assertEquals("implement", envelope["phase_id"])
    assertNull(produced["implementation_receipt"])
  }

  @Test
  fun `audit without recoverable verdict rejects`() {
    val raw =
      """
      {
        "contract_version": "0.6",
        "phase_id": "audit",
        "status": "completed",
        "summary": "Checked criteria.",
        "produced_outputs": { "value": "gaps remain on AC-1" }
      }
      """.trimIndent()

    assertNull(ProsePhaseOutputSynthesizer.trySynthesize(raw, "audit"))
  }

  @Test
  fun `blank value rejects`() {
    val raw =
      """
      {
        "contract_version": "0.6",
        "phase_id": "plan",
        "status": "completed",
        "summary": "Empty.",
        "produced_outputs": { "value": "   " }
      }
      """.trimIndent()

    assertNull(ProsePhaseOutputSynthesizer.trySynthesize(raw, "plan"))
  }

  @Test
  fun `bare non-json prose rejects`() {
    assertNull(ProsePhaseOutputSynthesizer.trySynthesize("not a json object", "implement"))
  }

  @Test
  fun `wrong phase_id rejects instead of rewriting identity`() {
    val raw =
      """
      {
        "contract_version": "0.6",
        "phase_id": "plan",
        "status": "completed",
        "summary": "Wrong slot.",
        "produced_outputs": { "value": "plan prose" }
      }
      """.trimIndent()

    assertNull(ProsePhaseOutputSynthesizer.trySynthesize(raw, "preplan"))
  }

  @Test
  fun `unsupported status rejects instead of defaulting`() {
    val raw =
      """
      {
        "contract_version": "0.6",
        "phase_id": "plan",
        "status": "queued",
        "summary": "Bad status.",
        "produced_outputs": { "value": "plan prose" }
      }
      """.trimIndent()

    assertNull(ProsePhaseOutputSynthesizer.trySynthesize(raw, "plan"))
  }

  @Test
  fun `produced_outputs as a name-value list under a near-miss status recovers instead of blocking`() {
    val raw =
      """
      {
        "contract_version": "0.6",
        "phase_id": "implement",
        "status": "complete",
        "summary": "Applied every plan task.",
        "tests_executed": [],
        "produced_outputs": [ { "name": "implementation_receipt", "value": "implementation prose" } ]
      }
      """.trimIndent()

    val envelope = assertNotNull(ProsePhaseOutputSynthesizer.trySynthesize(raw, "implement"))
    val produced = assertNotNull(JsonSupport.anyToStringAnyMap(envelope["produced_outputs"]))
    assertEquals("implementation prose", produced["value"])
    assertEquals("completed", envelope["status"])
    assertNull(envelope["tests_executed"])
  }

  @Test
  fun `audit with verdict field synthesizes`() {
    val raw =
      """
      {
        "contract_version": "0.6",
        "phase_id": "audit",
        "status": "completed",
        "summary": "All good.",
        "verdict": "satisfied",
        "produced_outputs": { "value": "all criteria met" }
      }
      """.trimIndent()

    val envelope = assertNotNull(ProsePhaseOutputSynthesizer.trySynthesize(raw, "audit"))
    assertEquals("satisfied", envelope["verdict"])
  }
}
