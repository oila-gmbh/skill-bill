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
    val emptyLegacy = """{"contract_version":"0.1","records":[]}"""
    assertEquals(LegacyReconciliation.Imported(0), repository.reconcileLegacy("workflow-1", "c".repeat(64), emptyLegacy))
    assertIs<LegacyReconciliation.AlreadyImported>(
      repository.reconcileLegacy("workflow-1", "c".repeat(64), emptyLegacy),
    )
  }

  @Test
  fun `classification and dispositions drive unresolved review blockers`() = withRepository { repository ->
    val finding = record(
      "a".repeat(64),
      kind = ConvergenceRecordKind.REVIEW_FINDING,
      phase = "review",
      stableKey = "F-001",
      classification = "blocker",
    )
    repository.append(finding)
    assertEquals(listOf(finding), repository.unresolved("workflow-1").reviewBlockers)
    repository.append(
      record(
        "b".repeat(64),
        kind = ConvergenceRecordKind.REVIEW_DISPOSITION,
        phase = "review",
        stableKey = "F-001-disposition",
        status = ConvergenceStatus.RESOLVED,
        parentLogicalId = finding.logicalId,
      ),
    )
    assertEquals(emptyList(), repository.unresolved("workflow-1").reviewBlockers)
  }

  @Test
  fun `convergence evidence survives workflow deletion`() = withRepository { repository ->
    val evidence = record("a".repeat(64))
    repository.append(evidence)
    activeConnection.createStatement().use {
      it.executeUpdate("DELETE FROM feature_task_workflows WHERE workflow_id = 'workflow-1'")
    }
    assertEquals(listOf(evidence), repository.history("workflow-1"))
  }

  @Test
  fun `legacy contract incompatibility is quarantined exactly once`() = withRepository { repository ->
    val digest = "d".repeat(64)
    assertEquals(
      LegacyReconciliation.Quarantined("invalid_contract"),
      repository.reconcileLegacy("workflow-1", digest, """{"contract_version":"9","records":[]}"""),
    )
    assertIs<LegacyReconciliation.AlreadyImported>(
      repository.reconcileLegacy("workflow-1", digest, """{"contract_version":"0.1","records":[]}"""),
    )
  }

  private fun record(
    digest: String,
    kind: ConvergenceRecordKind = ConvergenceRecordKind.AUDIT_GAP,
    phase: String = "audit",
    stableKey: String = "AC-004",
    classification: String? = null,
    status: ConvergenceStatus = ConvergenceStatus.OPEN,
    parentLogicalId: String? = if (kind == ConvergenceRecordKind.AUDIT_REPAIR) "logical:parent" else null,
  ): ConvergenceRecord {
    val logical = ConvergenceIdentities.logical("workflow-1", kind, stableKey)
    val reviewPass = if (phase == "review") 1 else null
    val attempt = if (phase == "implement") 1 else null
    return ConvergenceRecord(
      recordId = ConvergenceIdentities.record(logical, 1),
      logicalId = logical,
      kind = kind,
      provenance = ConvergenceProvenance("workflow-1", 1, phase, attempt = attempt, reviewPass = reviewPass),
      evidenceDigest = digest,
      createdAt = "2026-07-28T10:00:00Z",
      status = status,
      classification = classification,
      summary = "bounded evidence",
      parentLogicalId = parentLogicalId,
    )
  }

  private lateinit var activeConnection: java.sql.Connection

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
      activeConnection = connection
      block(SQLiteConvergenceStateRepository(connection))
    }
  }
}
