package skillbill.application

import skillbill.application.featuretask.FeatureTaskRuntimeRunState
import skillbill.application.featuretask.parsedOutput
import skillbill.application.goalrunner.GoalRunnerChildProgressRead
import skillbill.application.goalrunner.GoalRunnerProgressReader
import skillbill.error.InvalidFeatureTaskRuntimePhaseOutputSchemaError
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeTransitionDeclaration
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs

class AmbientInputsAndLoudFailSeamsTest {
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

    assertIs<GoalRunnerChildProgressRead.Failed>(reader.read("wfl-child"))
  }
}
