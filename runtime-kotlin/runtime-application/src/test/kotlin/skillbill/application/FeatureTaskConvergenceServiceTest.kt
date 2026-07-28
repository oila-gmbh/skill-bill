package skillbill.application

import skillbill.application.featuretask.FeatureTaskConvergenceService
import skillbill.ports.persistence.ConvergenceReplayConflictException
import skillbill.ports.persistence.ConvergenceStateRepository
import skillbill.ports.persistence.model.LegacyReconciliation
import skillbill.workflow.taskruntime.model.ConvergenceIdentities
import skillbill.workflow.taskruntime.model.ConvergenceProvenance
import skillbill.workflow.taskruntime.model.ConvergenceRecord
import skillbill.workflow.taskruntime.model.ConvergenceRecordKind
import skillbill.workflow.taskruntime.model.ConvergenceStatus
import skillbill.workflow.taskruntime.model.ReplayResult
import skillbill.workflow.taskruntime.model.UnresolvedConvergence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class FeatureTaskConvergenceServiceTest {
  @Test
  fun `identical replay verifies authoritative state without advancing twice`() {
    val convergence = InMemoryConvergenceRepository()
    val service = FeatureTaskConvergenceService(
      RuntimeFakeDatabaseSessionFactory(InMemoryRuntimeWorkflowRepository(), convergence = convergence),
    )
    val record = implementationOutcome()
    var advances = 0
    var advanced = false

    assertIs<ReplayResult.Appended>(
      service.recordAndAdvance(record, isAlreadyAdvanced = { advanced }) {
        advances += 1
        advanced = true
      },
    )
    assertIs<ReplayResult.Identical>(
      service.recordAndAdvance(record, isAlreadyAdvanced = { advanced }) {
        advances += 1
      },
    )
    assertEquals(1, advances)
  }

  @Test
  fun `identical replay with mismatched authoritative state is a conflict`() {
    val convergence = InMemoryConvergenceRepository()
    val service = FeatureTaskConvergenceService(
      RuntimeFakeDatabaseSessionFactory(InMemoryRuntimeWorkflowRepository(), convergence = convergence),
    )
    val record = implementationOutcome()
    service.recordAndAdvance(record, isAlreadyAdvanced = { false }) { }

    assertFailsWith<ConvergenceReplayConflictException> {
      service.recordAndAdvance(record, isAlreadyAdvanced = { false }) { }
    }
  }

  private fun implementationOutcome(): ConvergenceRecord {
    val logical = ConvergenceIdentities.logical(
      "workflow-1",
      ConvergenceRecordKind.IMPLEMENTATION_OUTCOME,
      "implement-attempt-1",
    )
    return ConvergenceRecord(
      recordId = ConvergenceIdentities.record(
        "workflow-1",
        ConvergenceRecordKind.IMPLEMENTATION_OUTCOME,
        logical,
        1,
      ),
      logicalId = logical,
      kind = ConvergenceRecordKind.IMPLEMENTATION_OUTCOME,
      provenance = ConvergenceProvenance("workflow-1", 1, "implement", attempt = 1),
      evidenceDigest = "a".repeat(64),
      createdAt = "2026-07-28T10:00:00Z",
      status = ConvergenceStatus.COMPLETED,
    )
  }
}

private class InMemoryConvergenceRepository : ConvergenceStateRepository {
  private val records = linkedMapOf<String, ConvergenceRecord>()

  override fun append(record: ConvergenceRecord): ReplayResult {
    val existing = records[record.recordId]
    return when {
      existing == null -> {
        records[record.recordId] = record
        ReplayResult.Appended(record)
      }
      existing == record -> ReplayResult.Identical(record)
      else -> ReplayResult.Conflict(existing, record)
    }
  }

  override fun history(workflowId: String): List<ConvergenceRecord> =
    records.values.filter { it.provenance.workflowId == workflowId }

  override fun current(workflowId: String): Map<String, ConvergenceRecord> =
    history(workflowId).associateBy(ConvergenceRecord::logicalId)

  override fun unresolved(workflowId: String): UnresolvedConvergence =
    UnresolvedConvergence(emptyList(), emptyList(), emptyList())

  override fun reconcileLegacy(workflowId: String, sourceDigest: String, encodedSource: String): LegacyReconciliation =
    LegacyReconciliation.Imported(0)
}
