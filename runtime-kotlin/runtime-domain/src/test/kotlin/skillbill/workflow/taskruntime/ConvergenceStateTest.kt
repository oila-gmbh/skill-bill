package skillbill.workflow.taskruntime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import skillbill.error.InvalidFeatureTaskRuntimeConvergenceStateSchemaError

class ConvergenceStateTest {
  @Test
  fun `identities are deterministic and generation specific`() {
    val logical = ConvergenceIdentities.logical("workflow-1", ConvergenceRecordKind.AUDIT_GAP, "AC-004")
    assertEquals(logical, ConvergenceIdentities.logical("workflow-1", ConvergenceRecordKind.AUDIT_GAP, "AC-004"))
    assertNotEquals(ConvergenceIdentities.record(logical, 1), ConvergenceIdentities.record(logical, 2))
  }

  @Test
  fun `current projection retains history and selects newest generation`() {
    val old = record(generation = 1, status = ConvergenceStatus.OPEN)
    val repaired = record(generation = 2, status = ConvergenceStatus.RESOLVED)
    assertEquals(repaired, listOf(old, repaired).currentByLogicalIdentity().getValue(old.logicalId))
    assertEquals(2, listOf(old, repaired).size)
  }

  @Test
  fun `bounded summaries reject unbounded or prohibited payload substitutes`() {
    assertFailsWith<IllegalArgumentException> { record(summary = "x".repeat(513)) }
  }

  @Test
  fun `kind provenance parent and deterministic identity are enforced`() {
    val valid = record()
    assertFailsWith<IllegalArgumentException> {
      valid.copy(recordId = "record:${"b".repeat(64)}")
    }
    assertFailsWith<IllegalArgumentException> {
      valid.copy(provenance = valid.provenance.copy(phaseId = "implement", attempt = 1))
    }
    assertFailsWith<IllegalArgumentException> {
      valid.copy(kind = ConvergenceRecordKind.AUDIT_REPAIR, parentLogicalId = null)
    }
  }

  @Test
  fun `production codec rejects unknown fields unsupported versions and malformed provenance`() {
    val encoded = encodedRecord()
    assertEquals(record(), ConvergenceStateCodec.decodeRecord(encoded, "test"))
    assertFailsWith<InvalidFeatureTaskRuntimeConvergenceStateSchemaError> {
      ConvergenceStateCodec.decodeRecord(encoded.replace("\"contract_version\":\"0.1\"", "\"contract_version\":\"9\""), "test")
    }
    assertFailsWith<InvalidFeatureTaskRuntimeConvergenceStateSchemaError> {
      ConvergenceStateCodec.decodeRecord(encoded.dropLast(1) + ",\"raw_phase_output\":\"private\"}", "test")
    }
    assertFailsWith<InvalidFeatureTaskRuntimeConvergenceStateSchemaError> {
      ConvergenceStateCodec.decodeRecord(encoded.replace("\"phase_id\":\"audit\"", "\"phase_id\":\"review\""), "test")
    }
  }

  private fun record(
    generation: Int = 1,
    status: ConvergenceStatus = ConvergenceStatus.OPEN,
    summary: String = "bounded evidence",
  ): ConvergenceRecord {
    val logical = ConvergenceIdentities.logical("workflow-1", ConvergenceRecordKind.AUDIT_GAP, "AC-004")
    return ConvergenceRecord(
      recordId = ConvergenceIdentities.record(logical, generation),
      logicalId = logical,
      kind = ConvergenceRecordKind.AUDIT_GAP,
      provenance = ConvergenceProvenance("workflow-1", generation, "audit"),
      evidenceDigest = "a".repeat(64),
      createdAt = "2026-07-28T10:00:00Z",
      status = status,
      summary = summary,
    )
  }

  private fun encodedRecord(): String {
    val value = record()
    return """
      {"contract_version":"0.1","record_id":"${value.recordId}","workflow_id":"workflow-1",
      "kind":"audit_gap","generation":1,"logical_id":"${value.logicalId}","phase_id":"audit",
      "status":"open","summary":"bounded evidence","evidence_digest":"${"a".repeat(64)}",
      "created_at":"2026-07-28T10:00:00Z"}
    """.trimIndent()
  }
}
