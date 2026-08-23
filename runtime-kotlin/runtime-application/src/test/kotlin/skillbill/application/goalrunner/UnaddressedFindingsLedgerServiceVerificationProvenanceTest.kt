package skillbill.application.goalrunner

import skillbill.application.InMemoryRuntimeWorkflowRepository
import skillbill.application.RuntimeFakeDatabaseSessionFactory
import skillbill.application.decomposition.decodeArtifacts
import skillbill.application.testWorkflowSnapshotValidator
import skillbill.application.workflow.WorkflowFamily
import skillbill.application.workflow.toRecord
import skillbill.goalrunner.model.UnaddressedFinding
import skillbill.workflow.WorkflowEngine
import skillbill.workflow.model.WorkflowUpdateInput
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_FINDING_VERIFICATION_DISPOSITIONS_ARTIFACT_KEY
import kotlin.test.Test
import kotlin.test.assertEquals

class UnaddressedFindingsLedgerServiceVerificationProvenanceTest {
  @Test
  fun `verification dispositions expose selected heading ids and source paths for the issue key`() {
    val repository = InMemoryRuntimeWorkflowRepository()
    val workflowId = "wftr-provenance"
    seedWorkflow(
      repository,
      workflowId,
      """
      {
        "$FEATURE_TASK_RUNTIME_FINDING_VERIFICATION_DISPOSITIONS_ARTIFACT_KEY": [{
          "finding_id": "F-001",
          "disposition": "verified",
          "reason": "Matches intent",
          "severity": "major",
          "location": "Foo.kt",
          "message": "example",
          "selected_boundary_headings": [{
            "heading_id": "runtime-kotlin/agent/history.md#abc",
            "source_path": "runtime-kotlin/agent/history.md"
          }]
        }]
      }
      """.trimIndent(),
    )
    val database = RuntimeFakeDatabaseSessionFactory(repository)
    database.ledgerRows.add(
      UnaddressedFinding(
        issueKey = "SKILL-202",
        subtaskId = 3,
        workflowId = workflowId,
        reviewPassNumber = 1,
        findingOrdinal = 1,
        severity = "major",
        issueCategory = "behavior_correctness",
        location = "Foo.kt",
        summary = "example",
      ),
    )
    val service = UnaddressedFindingsLedgerService(database)

    val dispositions = service.verificationDispositions("SKILL-202")

    assertEquals(1, dispositions.size)
    assertEquals("F-001", dispositions.single().findingId)
    val selectedHeading = dispositions.single().selectedBoundaryHeadings.single()
    assertEquals("runtime-kotlin/agent/history.md#abc", selectedHeading.headingId)
    assertEquals("runtime-kotlin/agent/history.md", selectedHeading.sourcePath)
  }
}

private fun seedWorkflow(repository: InMemoryRuntimeWorkflowRepository, workflowId: String, artifactsJson: String) {
  val engine = WorkflowEngine(testWorkflowSnapshotValidator)
  val definition = WorkflowFamily.TASK_RUNTIME.definition
  val opened = engine.openRecord(definition, workflowId, "ftr-provenance", "verify_findings")
  val seeded = engine.updateRecord(
    definition,
    opened,
    WorkflowUpdateInput(
      workflowStatus = "running",
      currentStepId = "verify_findings",
      stepUpdates = null,
      artifactsPatch = decodeArtifacts(artifactsJson),
      sessionId = "ftr-provenance",
    ),
  ).toRecord()
  repository.saveFeatureTaskRuntimeWorkflow(seeded)
}
