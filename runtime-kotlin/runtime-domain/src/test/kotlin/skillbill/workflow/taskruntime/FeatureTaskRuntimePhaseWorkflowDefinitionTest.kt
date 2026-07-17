package skillbill.workflow.taskruntime

import skillbill.contracts.workflow.FEATURE_TASK_RUNTIME_CONTRACT_VERSION
import skillbill.workflow.implement.FeatureImplementWorkflowDefinition
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeCapExhaustionBehavior
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerdict
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
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT_FIX,
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
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT_FIX to "Phase 3b: Implement Fix",
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
  fun `every forward phase's declared dependency references an earlier phase forming a valid DAG`() {
    val order = definition.stepIds
    val loopOnly = FeatureTaskRuntimePhaseWorkflowDefinition.transitions.loopOnlyPhaseIds
    definition.stepIds.forEachIndexed { index, phaseId ->
      // Loop-only phases (e.g. implement_fix) are backward-edge destinations: they legitimately
      // consume their backward source (review), which is forward-later, so the strict-earlier
      // invariant applies only to the forward pipeline.
      if (phaseId in loopOnly) return@forEachIndexed
      dependenciesOf(phaseId).forEach { upstream ->
        val upstreamIndex = order.indexOf(upstream)
        assertTrue(upstreamIndex in 0 until index, "$phaseId depends on $upstream which is not strictly earlier")
      }
    }
  }

  @Test
  fun `implement_fix is a loop-only mutating phase reached only by the declared review_fix backward edge`() {
    val def = FeatureTaskRuntimePhaseWorkflowDefinition
    assertTrue(def.isMutatingPhase(def.PHASE_IMPLEMENT_FIX))
    assertTrue(def.isMutatingPhase(def.PHASE_IMPLEMENT))
    val transitions = def.transitions
    assertEquals(setOf(def.PHASE_IMPLEMENT_FIX), transitions.loopOnlyPhaseIds)
    val edge = transitions.backwardEdges.single { it.loopId == def.REVIEW_FIX_LOOP_ID }
    assertEquals(def.PHASE_REVIEW, edge.fromPhaseId)
    assertEquals(def.PHASE_IMPLEMENT_FIX, edge.destinationPhaseId)
    assertEquals("review_fix", edge.loopId)
    assertEquals(1, edge.perEdgeCap)
    assertEquals(FeatureTaskRuntimeCapExhaustionBehavior.ADVANCE, edge.capExhaustionBehavior)
    assertEquals(FeatureTaskRuntimeVerdict.CHANGES_REQUESTED, edge.triggeringVerdict)
    // The backward destination precedes its source so the reopened span includes review (re-review leg).
    val ids = transitions.forwardPhaseIds
    assertTrue(ids.indexOf(edge.destinationPhaseId) < ids.indexOf(edge.fromPhaseId))
    // The fix phase consumes the plan, the latest implement output, and the review findings.
    assertEquals(
      listOf(def.PHASE_PLAN, def.PHASE_IMPLEMENT, def.PHASE_REVIEW),
      dependenciesOf(def.PHASE_IMPLEMENT_FIX),
    )
  }

  @Test
  fun `the audit_gap backward edge reopens implement-through-audit without planning and blocks after two loops`() {
    val def = FeatureTaskRuntimePhaseWorkflowDefinition
    val transitions = def.transitions
    assertEquals(2, transitions.backwardEdges.size)
    val edge = transitions.backwardEdges.single { it.loopId == def.AUDIT_GAP_LOOP_ID }
    assertEquals(def.PHASE_AUDIT, edge.fromPhaseId)
    assertEquals(def.PHASE_IMPLEMENT, edge.destinationPhaseId)
    assertEquals("audit_gap", edge.loopId)
    assertEquals(2, edge.perEdgeCap)
    assertEquals(FeatureTaskRuntimeCapExhaustionBehavior.BLOCK, edge.capExhaustionBehavior)
    assertEquals(FeatureTaskRuntimeVerdict.GAPS_FOUND, edge.triggeringVerdict)
    // The reopened [implement, audit] span contains remediation but excludes immutable planning.
    val ids = transitions.forwardPhaseIds
    assertTrue(ids.indexOf(edge.destinationPhaseId) < ids.indexOf(edge.fromPhaseId))
    assertTrue(
      ids.subList(ids.indexOf(edge.destinationPhaseId), ids.indexOf(edge.fromPhaseId) + 1)
        .any(def::isMutatingPhase),
    )
    val reopenedPhaseIds = ids.subList(ids.indexOf(edge.destinationPhaseId), ids.indexOf(edge.fromPhaseId) + 1)
    assertTrue(def.PHASE_PREPLAN !in reopenedPhaseIds)
    assertTrue(def.PHASE_PLAN !in reopenedPhaseIds)
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
  fun `terminal summary artifact points at the always-persisted per-phase records store`() {
    assertEquals(
      FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY,
      definition.completedTerminalSummaryArtifact,
    )
  }

  private fun dependenciesOf(phaseId: String): List<String> = definition.requiredArtifactsByStep.getValue(phaseId)
}
