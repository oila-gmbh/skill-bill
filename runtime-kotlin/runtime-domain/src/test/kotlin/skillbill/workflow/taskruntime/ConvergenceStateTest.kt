package skillbill.workflow.taskruntime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

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
}
