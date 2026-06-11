package skillbill.workflow.taskruntime

import skillbill.contracts.workflow.FEATURE_TASK_RUNTIME_CONTRACT_VERSION
import skillbill.workflow.implement.FeatureImplementWorkflowDefinition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FeatureTaskRuntimePhaseWorkflowDefinitionTest {
  private val definition = FeatureTaskRuntimePhaseWorkflowDefinition.definition

  @Test
  fun `definition shares public feature task identity and uses runtime mode`() {
    val implement = FeatureImplementWorkflowDefinition.definition
    assertEquals(implement.workflowName, definition.workflowName)
    assertEquals("runtime", definition.workflowMode)
    assertEquals("prose", implement.workflowMode)
    assertTrue(definition.workflowIdPrefix != implement.workflowIdPrefix)
    assertEquals("bill-feature-task", definition.skillName)
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
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PREPLAN,
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN,
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT,
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW,
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT,
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE,
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_WRITE_HISTORY,
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_COMMIT_PUSH,
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PR,
      )
    assertEquals(expectedOrder, definition.stepIds)
    assertEquals(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PREPLAN, definition.defaultInitialStepId)
    assertEquals(
      mapOf(
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PREPLAN to "Phase 1: Pre-plan",
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN to "Phase 2: Plan",
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT to "Phase 3: Implement",
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW to "Phase 4: Code Review",
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT to "Phase 5: Completeness Audit",
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE to "Phase 6: Quality Validation",
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_WRITE_HISTORY to "Phase 7: Boundary History",
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_COMMIT_PUSH to "Phase 8: Commit and Push",
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PR to "Phase 9: Pull Request",
      ),
      definition.stepLabels,
    )
    definition.stepIds.forEach { stepId ->
      assertTrue(definition.stepLabels.containsKey(stepId), "Missing label for $stepId")
      assertTrue(definition.requiredArtifactsByStep.containsKey(stepId), "Missing dependency set for $stepId")
      assertTrue(definition.resumeActions.containsKey(stepId), "Missing resume action for $stepId")
    }
  }

  @Test
  fun `per-phase dependency-set resolution over the DAG matches declarations`() {
    assertEquals(emptyList(), dependenciesOf(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PREPLAN))
    assertEquals(
      listOf(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PREPLAN),
      dependenciesOf(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN),
    )
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
    assertEquals(
      listOf(
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT,
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE,
      ),
      dependenciesOf(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_WRITE_HISTORY),
    )
    assertEquals(
      listOf(
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT,
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE,
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_WRITE_HISTORY,
      ),
      dependenciesOf(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_COMMIT_PUSH),
    )
    assertEquals(
      listOf(
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT,
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_COMMIT_PUSH,
      ),
      dependenciesOf(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PR),
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
  fun `phase declarations mirror the dependency set and review plus pr declare derived diff context`() {
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
      listOf("diff"),
      declarations.getValue(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PR).derivedContextKeys,
    )
    assertEquals(
      emptyList(),
      declarations.getValue(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PREPLAN).derivedContextKeys,
    )
    assertEquals(
      emptyList(),
      declarations.getValue(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN).derivedContextKeys,
    )
  }

  @Test
  fun `terminal summary artifact is pr`() {
    assertEquals(
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PR,
      definition.completedTerminalSummaryArtifact,
    )
  }

  private fun dependenciesOf(phaseId: String): List<String> = definition.requiredArtifactsByStep.getValue(phaseId)
}
