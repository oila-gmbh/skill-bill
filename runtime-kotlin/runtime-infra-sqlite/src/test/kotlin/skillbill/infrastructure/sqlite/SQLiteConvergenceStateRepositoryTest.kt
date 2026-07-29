package skillbill.infrastructure.sqlite

import skillbill.db.core.DatabaseRuntime
import skillbill.ports.persistence.model.LegacyReconciliation
import skillbill.workflow.taskruntime.model.ConvergenceIdentities
import skillbill.workflow.taskruntime.model.ConvergenceProvenance
import skillbill.workflow.taskruntime.model.ConvergenceRecord
import skillbill.workflow.taskruntime.model.ConvergenceRecordKind
import skillbill.workflow.taskruntime.model.ConvergenceStatus
import skillbill.workflow.taskruntime.model.ReplayResult
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
    val obligation = record(
      "a".repeat(64),
      RecordOptions(kind = ConvergenceRecordKind.IMPLEMENTATION_OBLIGATION, phase = "implement"),
    )
    repository.append(obligation)
    assertEquals(listOf(obligation), repository.unresolved("workflow-1").implementationObligations)
    val emptyLegacy = """{"contract_version":"0.1","records":[]}"""
    assertEquals(
      LegacyReconciliation.Imported(0),
      repository.reconcileLegacy("workflow-1", "c".repeat(64), emptyLegacy),
    )
    assertIs<LegacyReconciliation.AlreadyImported>(
      repository.reconcileLegacy("workflow-1", "c".repeat(64), emptyLegacy),
    )
  }

  @Test
  fun `classification and dispositions drive unresolved review blockers`() = withRepository { repository ->
    val finding = record(
      "a".repeat(64),
      RecordOptions(
        kind = ConvergenceRecordKind.REVIEW_FINDING,
        phase = "review",
        stableKey = "F-001",
        classification = "blocker",
      ),
    )
    repository.append(finding)
    assertEquals(listOf(finding), repository.unresolved("workflow-1").reviewBlockers)
    repository.append(
      record(
        "b".repeat(64),
        RecordOptions(
          kind = ConvergenceRecordKind.REVIEW_DISPOSITION,
          phase = "review",
          stableKey = "F-001-disposition",
          status = ConvergenceStatus.RESOLVED,
          parentLogicalId = finding.logicalId,
        ),
      ),
    )
    assertEquals(emptyList(), repository.unresolved("workflow-1").reviewBlockers)
  }

  @Test
  fun `a later review pass can disposition an earlier blocker by exact parent identity`() =
    withRepository { repository ->
      val finding = record(
        "a".repeat(64),
        RecordOptions(
          kind = ConvergenceRecordKind.REVIEW_FINDING,
          phase = "review",
          stableKey = "F-002",
          classification = "blocker",
          reviewPass = 1,
        ),
      )
      repository.append(finding)
      val disposition = record(
        "b".repeat(64),
        RecordOptions(
          kind = ConvergenceRecordKind.REVIEW_DISPOSITION,
          phase = "review",
          stableKey = "F-002-pass-2-disposition",
          status = ConvergenceStatus.RESOLVED,
          parentLogicalId = finding.logicalId,
          reviewPass = 2,
        ),
      )

      assertIs<ReplayResult.Appended>(repository.append(disposition))
      assertEquals(emptyList(), repository.unresolved("workflow-1").reviewBlockers)
    }

  @Test
  fun `a prior generation disposition does not resolve a reopened blocker`() = withRepository { repository ->
    val first = record(
      "a".repeat(64),
      RecordOptions(
        kind = ConvergenceRecordKind.REVIEW_FINDING,
        phase = "review",
        stableKey = "F-001",
        classification = "blocker",
      ),
    )
    repository.append(first)
    repository.append(
      record(
        "b".repeat(64),
        RecordOptions(
          kind = ConvergenceRecordKind.REVIEW_DISPOSITION,
          phase = "review",
          stableKey = "F-001-disposition",
          status = ConvergenceStatus.RESOLVED,
          parentLogicalId = first.logicalId,
        ),
      ),
    )
    val reopened = record(
      "c".repeat(64),
      RecordOptions(
        kind = ConvergenceRecordKind.REVIEW_FINDING,
        phase = "review",
        stableKey = "F-001",
        classification = "blocker",
        generation = 2,
        reviewPass = 2,
      ),
    )
    repository.append(reopened)

    assertEquals(listOf(reopened), repository.unresolved("workflow-1").reviewBlockers)
  }

  @Test
  fun `workflow deletion cascades convergence evidence for hard reset`() = withRepository { repository ->
    val evidence = record("a".repeat(64))
    repository.append(evidence)
    activeConnection.createStatement().use {
      it.executeUpdate("DELETE FROM feature_task_workflows WHERE workflow_id = 'workflow-1'")
    }
    assertEquals(emptyList(), repository.history("workflow-1"))
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

  @Test
  fun `schema invalid legacy null is quarantined`() = withRepository { repository ->
    val invalid = recordJson(record("a".repeat(64))).replace(
      """"summary":"bounded evidence"""",
      """"summary":null""",
    )
    assertEquals(
      LegacyReconciliation.Quarantined("invalid_contract"),
      repository.reconcileLegacy(
        "workflow-1",
        "9".repeat(64),
        """{"contract_version":"0.1","records":[$invalid]}""",
      ),
    )
  }

  @Test
  fun `legacy relationships use the complete source and quarantine missing parents`() = withRepository { repository ->
    val parent = record("a".repeat(64), RecordOptions(stableKey = "AC-007"))
    val repair = record(
      "b".repeat(64),
      RecordOptions(
        kind = ConvergenceRecordKind.AUDIT_REPAIR,
        stableKey = "AC-007-repair",
        parentLogicalId = parent.logicalId,
        status = ConvergenceStatus.RESOLVED,
      ),
    )
    assertEquals(
      LegacyReconciliation.Imported(2),
      repository.reconcileLegacy("workflow-1", "e".repeat(64), legacySource(repair, parent)),
    )

    val missingParent = record(
      "c".repeat(64),
      RecordOptions(
        kind = ConvergenceRecordKind.AUDIT_REPAIR,
        stableKey = "AC-008-repair",
        parentLogicalId = ConvergenceIdentities.logical(
          "workflow-1",
          ConvergenceRecordKind.AUDIT_GAP,
          "AC-008",
        ),
      ),
    )
    assertEquals(
      LegacyReconciliation.Quarantined("invalid_relationship"),
      repository.reconcileLegacy("workflow-1", "f".repeat(64), legacySource(missingParent)),
    )
  }

  private fun legacySource(vararg records: ConvergenceRecord): String =
    """{"contract_version":"0.1","records":[${records.joinToString(",") { recordJson(it) }}]}"""

  private fun recordJson(record: ConvergenceRecord): String {
    val fields = buildList {
      add(""""contract_version":"0.1"""")
      add(""""record_id":"${record.recordId}"""")
      add(""""workflow_id":"${record.provenance.workflowId}"""")
      add(""""kind":"${record.kind.name.lowercase()}"""")
      add(""""generation":${record.provenance.generation}""")
      add(""""logical_id":"${record.logicalId}"""")
      record.parentLogicalId?.let { add(""""parent_logical_id":"$it"""") }
      add(""""phase_id":"${record.provenance.phaseId}"""")
      record.provenance.attempt?.let { add(""""attempt":$it""") }
      record.provenance.reviewPass?.let { add(""""review_pass":$it""") }
      add(""""status":"${record.status.name.lowercase()}"""")
      add(""""summary":"${record.summary}"""")
      add(""""evidence_digest":"${record.evidenceDigest}"""")
      add(""""created_at":"${record.createdAt}"""")
    }
    return "{${fields.joinToString(",")}}"
  }

  private fun record(digest: String, options: RecordOptions = RecordOptions()): ConvergenceRecord {
    val kind = options.kind
    val phase = options.phase
    val generation = options.generation
    val logical = ConvergenceIdentities.logical("workflow-1", kind, options.stableKey)
    val attempt = if (phase == "implement") 1 else null
    return ConvergenceRecord(
      recordId = ConvergenceIdentities.record("workflow-1", kind, logical, generation),
      logicalId = logical,
      kind = kind,
      provenance = ConvergenceProvenance(
        "workflow-1",
        generation,
        phase,
        attempt = attempt,
        reviewPass = options.reviewPass,
      ),
      evidenceDigest = digest,
      createdAt = "2026-07-28T10:00:00Z",
      status = options.status,
      classification = options.classification,
      summary = "bounded evidence",
      parentLogicalId = options.parentLogicalId,
    )
  }

  private data class RecordOptions(
    val kind: ConvergenceRecordKind = ConvergenceRecordKind.AUDIT_GAP,
    val phase: String = "audit",
    val stableKey: String = "AC-004",
    val classification: String? = null,
    val status: ConvergenceStatus = ConvergenceStatus.OPEN,
    val parentLogicalId: String? = if (kind == ConvergenceRecordKind.AUDIT_REPAIR) {
      "logical:parent"
    } else {
      null
    },
    val generation: Int = 1,
    val reviewPass: Int? = if (phase == "review") 1 else null,
  )

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
