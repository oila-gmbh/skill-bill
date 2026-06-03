package skillbill.workflow.taskruntime

import skillbill.contracts.workflow.FEATURE_TASK_RUNTIME_CONTRACT_VERSION
import skillbill.workflow.implement.FeatureImplementWorkflowDefinition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FeatureTaskRuntimePhaseWorkflowDefinitionTest {
  private val definition = FeatureTaskRuntimePhaseWorkflowDefinition.definition

  @Test
  fun `definition is independent from the implement workflow definition`() {
    val implement = FeatureImplementWorkflowDefinition.definition
    assertTrue(definition.workflowName != implement.workflowName)
    assertTrue(definition.workflowIdPrefix != implement.workflowIdPrefix)
    assertEquals("feature-task-runtime", definition.skillName)
    assertEquals("wftr", definition.workflowIdPrefix)
  }

  @Test
  fun `contract version is pinned to the runtime constant`() {
    assertEquals(FEATURE_TASK_RUNTIME_CONTRACT_VERSION, definition.contractVersion)
  }

  @Test
  fun `step ids are ordered and every step has a label and a declared dependency set`() {
    val expectedOrder =
      listOf(
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN,
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT,
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW,
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT,
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE,
      )
    assertEquals(expectedOrder, definition.stepIds)
    definition.stepIds.forEach { stepId ->
      assertTrue(definition.stepLabels.containsKey(stepId), "Missing label for $stepId")
      assertTrue(definition.requiredArtifactsByStep.containsKey(stepId), "Missing dependency set for $stepId")
    }
  }

  @Test
  fun `per-phase dependency-set resolution over the DAG matches declarations`() {
    assertEquals(emptyList(), dependenciesOf(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN))
    assertEquals(
      listOf(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN),
      dependenciesOf(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT),
    )
    assertEquals(
      listOf(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT),
      dependenciesOf(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW),
    )
    assertEquals(
      listOf(
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN,
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT,
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW,
      ),
      dependenciesOf(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT),
    )
    assertEquals(
      listOf(
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT,
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT,
      ),
      dependenciesOf(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE),
    )
  }

  @Test
  fun `every declared dependency references an earlier phase forming a valid DAG`() {
    val order = definition.stepIds
    definition.stepIds.forEachIndexed { index, phaseId ->
      dependenciesOf(phaseId).forEach { upstream ->
        val upstreamIndex = order.indexOf(upstream)
        assertTrue(upstreamIndex in 0 until index, "$phaseId depends on $upstream which is not strictly earlier")
      }
    }
  }

  @Test
  fun `phase declarations mirror the dependency set and only review declares derived diff context`() {
    val declarations = FeatureTaskRuntimePhaseWorkflowDefinition.phaseDeclarations
    definition.stepIds.forEach { phaseId ->
      val declaration = declarations.getValue(phaseId)
      assertEquals(dependenciesOf(phaseId), declaration.consumedUpstreamPhaseIds)
    }
    assertEquals(
      listOf("diff"),
      declarations.getValue(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW).derivedContextKeys,
    )
    assertEquals(
      emptyList(),
      declarations.getValue(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN).derivedContextKeys,
    )
  }

  private fun dependenciesOf(phaseId: String): List<String> = definition.requiredArtifactsByStep.getValue(phaseId)
}
