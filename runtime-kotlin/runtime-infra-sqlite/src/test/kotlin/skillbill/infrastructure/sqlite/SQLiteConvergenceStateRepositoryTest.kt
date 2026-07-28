package skillbill.infrastructure.sqlite

import skillbill.db.core.DatabaseRuntime
import skillbill.ports.persistence.LegacyReconciliation
import skillbill.workflow.taskruntime.ConvergenceIdentities
import skillbill.workflow.taskruntime.ConvergenceProvenance
import skillbill.workflow.taskruntime.ConvergenceRecord
import skillbill.workflow.taskruntime.ConvergenceRecordKind
import skillbill.workflow.taskruntime.ConvergenceStatus
import skillbill.workflow.taskruntime.ReplayResult
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SQLiteConvergenceStateRepositoryTest {
  @Test
  fun `append is idempotent and conflicting replay preserves original evidence`() = withRepository { repository ->
    val original = record("a".repeat(64))
    assertIs<ReplayResult.Appended>(repository.append(original))
    assertIs<ReplayResult.Identical>(repository.append(original))
    assertIs<ReplayResult.Conflict>(repository.append(original.copy(evidenceDigest = "b".repeat(64))))
    assertEquals(listOf(original), repository.history("workflow-1"))
  }

  @Test
  fun `unresolved query spans active generations and legacy import is exactly once`() = withRepository { repository ->
    val obligation = record("a".repeat(64), ConvergenceRecordKind.IMPLEMENTATION_OBLIGATION, "implement")
    repository.append(obligation)
    assertEquals(listOf(obligation), repository.unresolved("workflow-1").implementationObligations)
    assertEquals(LegacyReconciliation.Imported(0), repository.reconcileLegacy("workflow-1", "c".repeat(64), emptyList()))
    assertIs<LegacyReconciliation.AlreadyImported>(
      repository.reconcileLegacy("workflow-1", "c".repeat(64), emptyList()),
    )
  }

  private fun record(
    digest: String,
    kind: ConvergenceRecordKind = ConvergenceRecordKind.AUDIT_REPAIR,
    phase: String = "audit",
  ): ConvergenceRecord {
    val logical = ConvergenceIdentities.logical("workflow-1", kind, "AC-004")
    return ConvergenceRecord(
      recordId = ConvergenceIdentities.record(logical, 1),
      logicalId = logical,
      kind = kind,
      provenance = ConvergenceProvenance("workflow-1", 1, phase, attempt = 1),
      evidenceDigest = digest,
      createdAt = "2026-07-28T10:00:00Z",
      status = ConvergenceStatus.OPEN,
      summary = "bounded evidence",
    )
  }

  private fun withRepository(block: (SQLiteConvergenceStateRepository) -> Unit) {
    val directory = Files.createTempDirectory("convergence-state-test")
    DatabaseRuntime.ensureDatabase(directory.resolve("runtime.db")).use { connection ->
      connection.createStatement().use {
        it.execute(
          """
          INSERT INTO feature_task_workflows(
            workflow_id, mode, contract_version, workflow_status, current_step_id, steps_json, artifacts_json
          ) VALUES ('workflow-1', 'runtime', '0.1', 'running', 'implement', '{}', '{}')
          """.trimIndent(),
        )
      }
      block(SQLiteConvergenceStateRepository(connection))
    }
  }
}
