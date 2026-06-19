package skillbill.workflow.taskruntime

import skillbill.error.InvalidWorkflowStateSchemaError
import skillbill.workflow.WorkflowEngine
import skillbill.workflow.WorkflowSnapshotValidator
import skillbill.workflow.implement.FeatureImplementWorkflowDefinition
import skillbill.workflow.model.WorkflowDefinition
import skillbill.workflow.model.WorkflowStateSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Domain resume-gate coverage for SKILL-85 subtask 1: the runtime family resolves required-upstream
 * presence from its private per-phase records, while every other family keeps the default top-level
 * key presence rule unchanged.
 */
class FeatureTaskRuntimeResumeGateTest {
  private val engine = WorkflowEngine(NoopWorkflowSnapshotValidator)
  private val runtimeDefinition = FeatureTaskRuntimePhaseWorkflowDefinition.definition

  @Test
  fun `runtime resume gate clears missing artifacts when upstream phase records are completed`() {
    val record = runtimeSnapshot(
      currentStepId = "implement",
      stepsJson = stepsJson(
        "preplan" to "completed",
        "plan" to "completed",
        "implement" to "pending",
      ),
      phaseRecordStatuses = mapOf("preplan" to "completed", "plan" to "completed"),
    )

    val resume = engine.resumeView(runtimeDefinition, record)

    assertEquals(emptyList(), resume.missingArtifacts)
    assertTrue(resume.canResume)
    assertEquals("implement", resume.resumeStepId)
    assertEquals("plan", resume.lastCompletedStepId)
  }

  @Test
  fun `runtime resume gate reports missing upstream when its phase record is not completed`() {
    val record = runtimeSnapshot(
      currentStepId = "plan",
      stepsJson = stepsJson("preplan" to "running", "plan" to "pending"),
      phaseRecordStatuses = mapOf("preplan" to "running"),
    )

    val resume = engine.resumeView(runtimeDefinition, record)

    assertEquals(listOf("preplan"), resume.missingArtifacts)
    assertFalse(resume.canResume)
  }

  @Test
  fun `crashed runtime run with completed preplan and plan resumes at implement not preplan`() {
    // AC8 regression: completed preplan/plan records + dead process (no terminal outcome).
    val record = runtimeSnapshot(
      currentStepId = "plan",
      workflowStatus = "running",
      stepsJson = stepsJson(
        "preplan" to "completed",
        "plan" to "completed",
        "implement" to "pending",
      ),
      phaseRecordStatuses = mapOf("preplan" to "completed", "plan" to "completed"),
    )

    val resume = engine.resumeView(runtimeDefinition, record)

    assertTrue(resume.canResume)
    assertEquals("implement", resume.resumeStepId)
    assertEquals(emptyList(), resume.missingArtifacts)

    val decision = engine.continueDecision(runtimeDefinition, record)
    assertEquals("reopened", decision.view.continueStatus)
    assertEquals("implement", decision.resumeStepId)
  }

  @Test
  fun `completed run done next-action dereferences a terminal-summary artifact present in the snapshot`() {
    // AC6 behavioral: a completed run's "done" next-action must point at an artifact that is actually
    // persisted in the snapshot, not a never-written key. This proves the terminal-summary pointer
    // dereferences real state rather than only equalling a config constant.
    val record = runtimeSnapshot(
      currentStepId = "pr",
      workflowStatus = "completed",
      stepsJson = stepsJson(
        "preplan" to "completed",
        "plan" to "completed",
        "implement" to "completed",
        "pr" to "completed",
      ),
      phaseRecordStatuses = mapOf(
        "preplan" to "completed",
        "plan" to "completed",
        "implement" to "completed",
        "pr" to "completed",
      ),
    )

    val resume = engine.resumeView(runtimeDefinition, record)

    assertEquals("done", resume.resumeMode)
    val terminalSummaryKey = runtimeDefinition.completedTerminalSummaryArtifact
    assertTrue(
      resume.nextAction.contains(terminalSummaryKey),
      "done next-action must reference the terminal-summary artifact key",
    )
    assertTrue(
      resume.availableArtifacts.contains(terminalSummaryKey),
      "terminal-summary artifact key must dereference a present artifact for a completed run",
    )
  }

  @Test
  fun `non-runtime family keeps default top-level key presence rule`() {
    // AC9 family-scoping: the prose implement family still judges presence by top-level keys, so an
    // empty artifacts map blocks resume at a step that requires upstream output.
    val implement = FeatureImplementWorkflowDefinition.definition
    val firstRequiredStep = implement.stepIds.first { stepId ->
      implement.requiredArtifactsByStep[stepId].orEmpty().isNotEmpty()
    }
    val requiredKeys = implement.requiredArtifactsByStep.getValue(firstRequiredStep)

    val record = implementSnapshot(
      definition = implement,
      currentStepId = firstRequiredStep,
      stepsJson = stepsJson(firstRequiredStep to "pending"),
    )

    val resume = engine.resumeView(implement, record)
    assertEquals(requiredKeys, resume.missingArtifacts)
    assertFalse(resume.canResume)
  }

  @Test
  fun `runtime definition binds the family-aware presence resolver`() {
    assertSame(
      FeatureTaskRuntimeRequiredArtifactPresenceResolver,
      runtimeDefinition.requiredArtifactPresenceResolver,
    )
  }

  @Test
  fun `runtime resume gate loud-fails on a corrupt phase record rather than coercing to empty`() {
    val corruptArtifactsJson =
      // A per-phase record missing the required `resolved_agent_id`.
      """{"feature_task_runtime_phase_records":{"preplan":""" +
        """{"phase_id":"preplan","status":"completed","attempt_count":1,""" +
        """"started_at":"2026-06-18T10:00:00Z"}}}"""
    val record = WorkflowStateSnapshot(
      workflowId = "wftr-test",
      sessionId = "ftr-test",
      workflowName = runtimeDefinition.workflowName,
      contractVersion = runtimeDefinition.contractVersion,
      workflowStatus = "running",
      currentStepId = "plan",
      stepsJson = stepsJson("preplan" to "completed", "plan" to "pending"),
      artifactsJson = corruptArtifactsJson,
      startedAt = "2026-06-18T10:00:00Z",
      updatedAt = "2026-06-18T10:05:00Z",
      finishedAt = null,
      mode = runtimeDefinition.workflowMode,
    )

    assertFailsWith<InvalidWorkflowStateSchemaError> {
      engine.resumeView(runtimeDefinition, record)
    }
  }

  private fun runtimeSnapshot(
    currentStepId: String,
    stepsJson: String,
    phaseRecordStatuses: Map<String, String>,
    workflowStatus: String = "running",
  ): WorkflowStateSnapshot = WorkflowStateSnapshot(
    workflowId = "wftr-test",
    sessionId = "ftr-test",
    workflowName = runtimeDefinition.workflowName,
    contractVersion = runtimeDefinition.contractVersion,
    workflowStatus = workflowStatus,
    currentStepId = currentStepId,
    stepsJson = stepsJson,
    artifactsJson = phaseRecordsArtifactsJson(phaseRecordStatuses),
    startedAt = "2026-06-18T10:00:00Z",
    updatedAt = "2026-06-18T10:05:00Z",
    finishedAt = null,
    mode = runtimeDefinition.workflowMode,
  )

  private fun implementSnapshot(
    definition: WorkflowDefinition,
    currentStepId: String,
    stepsJson: String,
  ): WorkflowStateSnapshot = WorkflowStateSnapshot(
    workflowId = "wfi-test",
    sessionId = "impl-test",
    workflowName = definition.workflowName,
    contractVersion = definition.contractVersion,
    workflowStatus = "running",
    currentStepId = currentStepId,
    stepsJson = stepsJson,
    artifactsJson = "{}",
    startedAt = "2026-06-18T10:00:00Z",
    updatedAt = "2026-06-18T10:05:00Z",
    finishedAt = null,
    mode = definition.workflowMode,
  )

  private fun stepsJson(vararg stepStatuses: Pair<String, String>): String =
    stepStatuses.joinToString(prefix = "[", postfix = "]") { (stepId, status) ->
      """{"step_id":"$stepId","status":"$status","attempt_count":1}"""
    }

  private fun phaseRecordsArtifactsJson(phaseRecordStatuses: Map<String, String>): String {
    val records = phaseRecordStatuses.entries.joinToString(",") { (phaseId, status) ->
      val finishedAt = if (status == "completed") ""","finished_at":"2026-06-18T10:04:00Z"""" else ""
      """"$phaseId":{"phase_id":"$phaseId","status":"$status","attempt_count":1,""" +
        """"started_at":"2026-06-18T10:00:00Z","resolved_agent_id":"agent-$phaseId"$finishedAt}"""
    }
    return """{"feature_task_runtime_phase_records":{$records}}"""
  }

  private object NoopWorkflowSnapshotValidator : WorkflowSnapshotValidator {
    override fun validate(snapshot: Map<String, Any?>, slug: String) = Unit
  }
}
