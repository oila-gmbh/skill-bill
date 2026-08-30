package skillbill.workflow.taskruntime

import skillbill.contracts.workflow.WORKFLOW_STATE_CONTRACT_VERSION
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeBackwardEdgeCapScope
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeCapExhaustionBehavior
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFeatureSize
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffSourceRef
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseEntryGate
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePlanningProjectionContract
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePriorGapMemory
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeQualityGateSelection
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepositoryCheckpointPolicy
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeSharedReviewEvidenceReference
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeTransitionDeclaration
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerdict
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FeatureTaskRuntimePhaseWorkflowDefinitionTest {
  private val definition = FeatureTaskRuntimePhaseWorkflowDefinition.definition

  @Test
  fun `definition exposes public feature task identity and uses runtime mode`() {
    assertEquals("bill-feature-task", definition.skillName)
    assertEquals("bill-feature-task", definition.workflowName)
    assertEquals("runtime", definition.workflowMode)
    assertEquals("wftr", definition.workflowIdPrefix)
  }

  @Test
  fun `durable workflow-state contract version is independent of the phase-output contract`() {
    assertEquals(WORKFLOW_STATE_CONTRACT_VERSION, definition.contractVersion)
  }

  @Test
  fun `step ids are ordered and every step has a label and a declared dependency set`() {
    val expectedOrder =
      listOf(
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PREPLAN,
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN,
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT,
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT,
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW,
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VERIFY_FINDINGS,
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT_FIX,
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD,
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
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT to "Phase 4: Completeness Audit",
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW to "Phase 5: Code Review",
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VERIFY_FINDINGS to "Phase 5a: Verify Findings",
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT_FIX to "Phase 5b: Implement Fix",
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD to "Phase 5c: Build",
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
    assertPerPhaseDependencySetsMatchDeclarations()
  }

  @Test
  fun `every forward phase's declared dependency references an earlier phase forming a valid DAG`() {
    val order = definition.stepIds
    val loopOnly = FeatureTaskRuntimePhaseWorkflowDefinition.transitions.loopOnlyPhaseIds
    definition.stepIds.forEachIndexed { index, phaseId ->
      if (phaseId in loopOnly) return@forEachIndexed
      phaseWorkflowDependenciesOf(phaseId).forEach { upstream ->
        val upstreamIndex = order.indexOf(upstream)
        assertTrue(upstreamIndex in 0 until index, "$phaseId depends on $upstream which is not strictly earlier")
      }
    }
  }

  @Test
  fun `implement_fix is the sole loop-only mutating phase reached by the bounded review_fix edge`() {
    val def = FeatureTaskRuntimePhaseWorkflowDefinition
    assertTrue(def.isMutatingPhase(def.PHASE_IMPLEMENT_FIX))
    assertTrue(def.isMutatingPhase(def.PHASE_IMPLEMENT))
    val transitions = def.transitions
    assertEquals(setOf(def.PHASE_IMPLEMENT_FIX, def.PHASE_BUILD), transitions.loopOnlyPhaseIds)
    assertEquals(emptyMap(), transitions.loopOnlySuccessors)
    val edge = transitions.backwardEdges.single { it.loopId == def.REVIEW_FIX_LOOP_ID }
    assertEquals(def.PHASE_VERIFY_FINDINGS, edge.fromPhaseId)
    assertEquals(def.PHASE_IMPLEMENT_FIX, edge.destinationPhaseId)
    assertEquals("review_fix", edge.loopId)
    assertEquals(1, edge.perEdgeCap)
    assertEquals(FeatureTaskRuntimeCapExhaustionBehavior.ADVANCE, edge.capExhaustionBehavior)
    assertEquals(FeatureTaskRuntimeVerdict.FINDINGS_VERIFIED, edge.triggeringVerdict)
    assertEquals(
      listOf(def.PHASE_VERIFY_FINDINGS),
      phaseWorkflowDependenciesOf(def.PHASE_IMPLEMENT_FIX),
    )
    val fixProjections = def.phaseDeclarations.getValue(def.PHASE_IMPLEMENT_FIX).projectionDeclarations
    assertEquals(
      FeatureTaskRuntimeRepositoryCheckpointPolicy.MUST_MATCH,
      fixProjections.single { it.projectionName == "review_repair_request" }.checkpointPolicy,
    )
  }

  @Test
  fun `record regeneration loops are empty after implement prose migration`() {
    val def = FeatureTaskRuntimePhaseWorkflowDefinition

    assertEquals(emptySet(), def.REGENERATION_LOOP_IDS)
    assertTrue(def.transitions.backwardEdges.none { def.isRegenerationLoopId(it.loopId) })
  }

  @Test
  fun `the audit_gap backward edge reopens implement-through-audit without planning and without a cap`() {
    val def = FeatureTaskRuntimePhaseWorkflowDefinition
    val transitions = def.transitions
    assertEquals(2, transitions.backwardEdges.size)
    val edge = transitions.backwardEdges.single { it.loopId == def.AUDIT_GAP_LOOP_ID }
    assertEquals(def.PHASE_AUDIT, edge.fromPhaseId)
    assertEquals(def.PHASE_IMPLEMENT, edge.destinationPhaseId)
    assertEquals("audit_gap", edge.loopId)
    assertEquals(null, edge.perEdgeCap)
    assertEquals(FeatureTaskRuntimeVerdict.GAPS_FOUND, edge.triggeringVerdict)
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
  fun `phase declarations mirror the dependency set and pr is split off the review diff key`() {
    val declarations = FeatureTaskRuntimePhaseWorkflowDefinition.phaseDeclarations
    val def = FeatureTaskRuntimePhaseWorkflowDefinition
    definition.stepIds.forEach { phaseId ->
      val declaration = when (phaseId) {
        def.PHASE_WRITE_HISTORY, def.PHASE_COMMIT_PUSH ->
          FeatureTaskRuntimePhaseWorkflowQueries.phaseDeclarationForQualityGate(
            phaseId,
            FeatureTaskRuntimeFeatureSize.MEDIUM,
            FeatureTaskRuntimeQualityGateSelection.VALIDATE,
          )
        else -> declarations.getValue(phaseId)
      }
      assertEquals(phaseWorkflowDependenciesOf(phaseId), declaration.consumedUpstreamPhaseIds, phaseId)
    }
    assertEquals(
      listOf("diff"),
      declarations.getValue(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW).derivedContextKeys,
    )
    assertEquals(
      listOf(FeatureTaskRuntimePhaseWorkflowDefinition.DERIVED_CONTEXT_PR_BRANCH_DIFF),
      declarations.getValue(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PR).derivedContextKeys,
    )
    assertTrue(
      declarations.getValue(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PR).projectionDeclarations.none {
        it.sourceRef == FeatureTaskRuntimeHandoffSourceRef.SharedReviewEvidence
      },
    )
    assertEquals(
      listOf("current_unit_of_work"),
      FeatureTaskRuntimePhaseWorkflowQueries.phaseDeclaration(
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW,
        FeatureTaskRuntimeFeatureSize.SMALL,
      ).derivedContextKeys,
    )
    assertEquals(
      listOf("diff"),
      FeatureTaskRuntimePhaseWorkflowQueries.phaseDeclaration(
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW,
        FeatureTaskRuntimeFeatureSize.MEDIUM,
      ).derivedContextKeys,
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


}

internal fun phaseWorkflowDependenciesOf(phaseId: String): List<String> =
  FeatureTaskRuntimePhaseWorkflowDefinition.definition.requiredArtifactsByStep.getValue(phaseId)
