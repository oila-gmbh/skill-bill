package skillbill.application

import skillbill.contracts.JsonSupport
import skillbill.goalrunner.model.GoalAttemptLedgerAction
import skillbill.goalrunner.model.GoalAttemptLedgerEntry
import skillbill.goalrunner.model.GoalRunnerWorkerSubtaskRequestOutcome
import skillbill.goalrunner.model.GoalRunnerWorkerSubtaskRequestRejectionReason
import skillbill.ports.goalrunner.model.GoalRunnerAttemptLedgerRecordRequest
import skillbill.workflow.WorkflowEngine
import skillbill.workflow.model.WorkflowUpdateInput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkflowGoalRunnerOutcomeStoreTaskRuntimeTest {
  @Test
  fun `reads progress from task runtime workflows without probing prose mode`() {
    val workflows = InMemoryWorkflowStates()
    workflows.saveFeatureTaskRuntimeWorkflow(taskRuntimeWorkflowRecord("wfl-task-runtime"))
    val store = WorkflowGoalRunnerOutcomeStore(
      database = FakeDatabaseSessionFactory(workflows),
      workflowSnapshotValidator = testWorkflowSnapshotValidator,
    )

    val progress = requireNotNull(store.progress("wfl-task-runtime"))

    assertEquals("wfl-task-runtime", progress.workflowId)
    assertEquals("running", progress.workflowStatus)
    assertEquals("implement", progress.currentStepId)
  }

  @Test
  fun `appends attempt ledger entries to task runtime workflows without probing prose mode`() {
    val workflows = InMemoryWorkflowStates()
    workflows.saveFeatureTaskRuntimeWorkflow(taskRuntimeWorkflowRecord("wfl-task-runtime"))
    val store = WorkflowGoalRunnerOutcomeStore(
      database = FakeDatabaseSessionFactory(workflows),
      workflowSnapshotValidator = testWorkflowSnapshotValidator,
    )

    val recorded = store.recordAttemptLedgerEntry(
      GoalRunnerAttemptLedgerRecordRequest(
        workflowId = "wfl-task-runtime",
        entry = GoalAttemptLedgerEntry(
          action = GoalAttemptLedgerAction.FINAL_RECONCILED_OUTCOME,
          sequenceNumber = 1,
          timestamp = "2026-06-11T18:28:09Z",
          finalReconciledResult = "blocked",
        ),
      ),
    )

    assertTrue(recorded)
    assertNull(workflows.getFeatureImplementWorkflow("wfl-task-runtime"))
    val saved = requireNotNull(workflows.getFeatureTaskRuntimeWorkflow("wfl-task-runtime")).toSnapshot()
    val artifacts = decodeArtifacts(saved.artifactsJson)
    val ledger = artifacts["goal_attempt_ledger"] as List<*>
    val entry = ledger.single() as Map<*, *>
    assertEquals("final_reconciled_outcome", entry["action"])
    assertEquals("blocked", entry["final_reconciled_result"])
  }

  @Test
  fun `appends worker subtask request outcomes to task runtime workflows`() {
    val workflows = InMemoryWorkflowStates()
    workflows.saveFeatureTaskRuntimeWorkflow(taskRuntimeWorkflowRecord("wfl-task-runtime"))
    val store = WorkflowGoalRunnerOutcomeStore(
      database = FakeDatabaseSessionFactory(workflows),
      workflowSnapshotValidator = testWorkflowSnapshotValidator,
    )

    val recorded = store.recordWorkerSubtaskRequestOutcomes(
      workflowId = "wfl-task-runtime",
      outcomes = listOf(
        GoalRunnerWorkerSubtaskRequestOutcome.Rejected(
          sourceStream = "stdout",
          reason = GoalRunnerWorkerSubtaskRequestRejectionReason.UNSAFE_PATH,
          message = "unsafe path",
        ),
      ),
      dbPathOverride = null,
    )

    assertTrue(recorded)
    assertNull(workflows.getFeatureImplementWorkflow("wfl-task-runtime"))
    val saved = requireNotNull(workflows.getFeatureTaskRuntimeWorkflow("wfl-task-runtime")).toSnapshot()
    val artifacts = decodeArtifacts(saved.artifactsJson)
    val outcomes = artifacts["goal_worker_subtask_request_outcomes"] as List<*>
    val rejected = outcomes.single() as Map<*, *>
    assertEquals("rejected", rejected["status"])
    assertEquals("unsafe_path", rejected["reason"])
  }

  private fun taskRuntimeWorkflowRecord(workflowId: String): skillbill.ports.persistence.model.WorkflowStateRecord {
    val definition = WorkflowFamily.TASK_RUNTIME.definition
    val engine = WorkflowEngine(testWorkflowSnapshotValidator)
    val opened = engine.openRecord(definition, workflowId, "fis-001", "preplan")
    return engine.updateRecord(
      definition,
      opened,
      WorkflowUpdateInput(
        workflowStatus = "running",
        currentStepId = "implement",
        stepUpdates = null,
        artifactsPatch = emptyMap(),
        sessionId = "fis-001",
      ),
    ).toRecord()
  }

  private fun decodeArtifacts(artifactsJson: String): Map<String, Any?> {
    val element = JsonSupport.json.parseToJsonElement(artifactsJson)
    return requireNotNull(JsonSupport.anyToStringAnyMap(JsonSupport.jsonElementToValue(element)))
  }
}
