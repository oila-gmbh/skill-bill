package skillbill.application

import skillbill.application.workflow.WorkflowService
import skillbill.application.workflow.model.WorkflowContinueResult
import skillbill.application.workflow.model.WorkflowFamilyKind
import skillbill.application.workflow.model.WorkflowGetResult
import skillbill.application.workflow.model.WorkflowOpenResult
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A phase interrupted mid-attempt leaves its step `running`, and continuing then reports
 * `already_running`. That re-entry must still be counted: backoff and retry accounting key off
 * attempt_count, so a frozen counter re-runs the same step forever without ever escalating.
 */
class ApplicationPersistencePortRunningStepReentryTest {
  @Test
  fun `continuing into a step left running counts each re-entry`() {
    val workflowRepository = InMemoryWorkflowStateRepository()
    val database = FakeDatabaseSessionFactory(workflows = workflowRepository)
    val service = testWorkflowService(database)
    val opened = service.openTestFeatureTask(WorkflowFamilyKind.TASK_RUNTIME, sessionId = "ftr-001", dbOverride = null)
      as WorkflowOpenResult.Ok
    val workflowId = opened.workflowId

    assertEquals(1, service.attemptCountFor(workflowId, "preplan"), "a freshly opened step is on attempt 1")

    val first = service.continueWorkflow(WorkflowFamilyKind.TASK_RUNTIME, workflowId, dbOverride = null)
      as WorkflowContinueResult.Standard

    assertEquals("already_running", first.view.continueStatus)
    assertEquals(2, service.attemptCountFor(workflowId, "preplan"), "the first re-entry must be recorded")

    val second = service.continueWorkflow(WorkflowFamilyKind.TASK_RUNTIME, workflowId, dbOverride = null)
      as WorkflowContinueResult.Standard

    assertEquals("already_running", second.view.continueStatus)
    assertEquals(3, service.attemptCountFor(workflowId, "preplan"), "each further re-entry must be recorded")
  }

  private fun WorkflowService.attemptCountFor(workflowId: String, stepId: String): Int {
    val got = get(WorkflowFamilyKind.TASK_RUNTIME, workflowId, dbOverride = null) as WorkflowGetResult.Ok
    return got.snapshot.steps.first { it.stepId == stepId }.attemptCount
  }
}
