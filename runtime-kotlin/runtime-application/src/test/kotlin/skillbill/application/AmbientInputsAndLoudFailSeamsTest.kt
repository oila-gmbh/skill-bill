package skillbill.application

import skillbill.application.featuretask.FeatureTaskRuntimeRunState
import skillbill.application.goalrunner.GoalRunnerChildProgressRead
import skillbill.application.goalrunner.GoalRunnerProgressReader
import skillbill.application.goalrunner.model.GoalRunnerRunRequest
import skillbill.error.InvalidFeatureTaskRuntimePhaseOutputSchemaError
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeTransitionDeclaration
import skillbill.workflow.model.WorkflowStepStatus
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AmbientInputsAndLoudFailSeamsTest {
  @Test
  fun `explicit resume reopens the requested phase and downstream phases`() {
    val completed = listOf(
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PREPLAN,
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN,
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT,
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW,
    ).associateWith { phaseId ->
      FeatureTaskRuntimePhaseRecord(
        phaseId = phaseId,
        status = WorkflowStepStatus.COMPLETED,
        attemptCount = 1,
        startedAt = "2026-09-08T00:00:00Z",
        resolvedAgentId = "codex",
      )
    }
    val state = FeatureTaskRuntimeRunState(
      initialRecords = completed,
      transitions = FeatureTaskRuntimePhaseWorkflowDefinition.transitions,
      outputValidator = AlwaysValidValidator,
    )

    state.reopenFromExplicitResume(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT)

    assertTrue(state.isComplete(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PREPLAN))
    assertTrue(state.isComplete(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN))
    assertFalse(state.isComplete(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT))
    assertFalse(state.isComplete(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW))
  }

  @Test
  fun `corrupt durable phase payload does not collapse to emptyMap`() {
    val state = FeatureTaskRuntimeRunState(
      initialRecords = emptyMap(),
      transitions = FeatureTaskRuntimeTransitionDeclaration(
        listOf(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT),
      ),
      outputValidator = ThrowingValidator(setOf(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT)),
    )
    val output = FeatureTaskRuntimePhaseOutput(
      phaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT,
      iteration = 1,
      payload = """{"contract_version":"not-a-version","phase_id":"implement","status":"completed"}""",
    )

    assertFailsWith<InvalidFeatureTaskRuntimePhaseOutputSchemaError> {
      state.parsedOutput(output)
    }
  }

  @Test
  fun `throwing progress store is distinguishable from absent progress`() {
    val outcomes = RecordingOutcomeStore().apply { throwOnProgress = true }
    val reader = GoalRunnerProgressReader(outcomes)
    val request = GoalRunnerRunRequest(
      issueKey = "SKILL-227",
      repoRoot = Path.of("/tmp/skillbill-ambient"),
      invokedAgentId = "claude",
    )

    assertIs<GoalRunnerChildProgressRead.Failed>(reader.read("wfl-child", request))
  }
}
