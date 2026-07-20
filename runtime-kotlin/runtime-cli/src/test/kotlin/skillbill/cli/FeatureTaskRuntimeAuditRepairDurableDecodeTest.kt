package skillbill.cli

import skillbill.application.featuretask.FeatureTaskRuntimePhaseRecorder
import skillbill.db.core.DatabaseRuntime
import skillbill.db.workflow.WorkflowStateRow
import skillbill.db.workflow.WorkflowStateStore
import skillbill.error.InvalidWorkflowStateSchemaError
import skillbill.infrastructure.sqlite.SQLiteDatabaseSessionFactory
import skillbill.model.EnvironmentContext
import skillbill.ports.persistence.model.FeatureTaskWorkflowMode
import skillbill.workflow.WorkflowSnapshotValidator
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class FeatureTaskRuntimeAuditRepairDurableDecodeTest {
  @Test
  fun `durable audit repair state decodes its ledger identity and rejects an incompatible contract version`() {
    val dbPath = Files.createTempDirectory("runtime-cli-audit-repair-decode").resolve("metrics.db")

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val store = WorkflowStateStore(connection)
      val row = auditRepairWorkflowRow(auditRepairArtifactsJson())
      store.saveFeatureTaskRuntimeWorkflow(row)

      val recorder = FeatureTaskRuntimePhaseRecorder(
        SQLiteDatabaseSessionFactory(EnvironmentContext(dbPathOverride = dbPath.toString())),
        object : WorkflowSnapshotValidator {
          override fun validate(snapshot: Map<String, Any?>, slug: String) = Unit
        },
      )

      val compatible = assertNotNull(recorder.loadAuditRepairState(row.workflowId, dbPath.toString()))
      val unresolved = compatible.unresolvedGapLedger.unresolvedGaps.single()
      assertEquals("ac-001-gap-1", unresolved.gapId)
      assertEquals("AC-001", unresolved.acceptanceCriterionRef)
      assertEquals(1, unresolved.generation)
      assertEquals(compatible.acceptedPlans.last(), compatible.acceptedPlans.single())

      store.saveFeatureTaskRuntimeWorkflow(row.copy(artifactsJson = auditRepairArtifactsJson("9.9")))
      val error = assertFailsWith<InvalidWorkflowStateSchemaError> {
        recorder.loadAuditRepairState(row.workflowId, dbPath.toString())
      }
      assertContains(error.message.orEmpty(), "contract_version")
    }
  }
}

private fun auditRepairWorkflowRow(artifactsJson: String): WorkflowStateRow = WorkflowStateRow(
  workflowId = "wftr-audit-repair",
  sessionId = "ftr-audit-repair",
  workflowName = "bill-feature-task-runtime",
  contractVersion = "0.1",
  workflowStatus = "running",
  currentStepId = "implement",
  stepsJson = "[]",
  artifactsJson = artifactsJson,
  startedAt = null,
  updatedAt = null,
  finishedAt = null,
  mode = FeatureTaskWorkflowMode.RUNTIME,
)

private fun auditRepairArtifactsJson(contractVersion: String = "0.2"): String = """
  {"feature_task_runtime_audit_repair_state":{
    "contract_version":"$contractVersion",
    "accepted_plans":[{"contract_version":"0.2","gaps":[{
      "gap_id":"ac-001-gap-1","acceptance_criterion_ref":"AC-001",
      "acceptance_criterion_text":"Criterion","failure_evidence":{"observation":"required_behavior_absent","artifact_ref":"runtime-kotlin","check_ref":"AC-001"},
      "diagnosis":"Diagnosis","affected_boundary":"runtime","repair_items":[{
        "repair_item_id":"ac-001-gap-1-item-1","intended_outcome":"Outcome",
        "implementation_actions":["Implement"],"affected_paths_or_symbols":["src/Foo.kt"],
        "required_verification":["Test"],"depends_on":[],"status":"pending"
      }]
    }]}],
    "latest_plan":{"contract_version":"0.2","gaps":[{
      "gap_id":"ac-001-gap-1","acceptance_criterion_ref":"AC-001",
      "acceptance_criterion_text":"Criterion","failure_evidence":{"observation":"required_behavior_absent","artifact_ref":"runtime-kotlin","check_ref":"AC-001"},
      "diagnosis":"Diagnosis","affected_boundary":"runtime","repair_items":[{
        "repair_item_id":"ac-001-gap-1-item-1","intended_outcome":"Outcome",
        "implementation_actions":["Implement"],"affected_paths_or_symbols":["src/Foo.kt"],
        "required_verification":["Test"],"depends_on":[],"status":"pending"
      }]
    }]},
    "execution_history":[],"prior_gap_dispositions":[],
    "unresolved_gap_ledger":{"contract_version":"0.2","gaps":[{
      "gap_id":"ac-001-gap-1","acceptance_criterion_ref":"AC-001","generation":1
    }]},
    "repository_fingerprint":"fingerprint",
    "progress":{"first_pass_convergence":false,"recurring_gap_count":0,"new_gap_count":1,
      "attempted_repair_item_count":0,"resolved_repair_item_count":0,"audit_gap_iteration_count":1}
  }}
""".trimIndent()
