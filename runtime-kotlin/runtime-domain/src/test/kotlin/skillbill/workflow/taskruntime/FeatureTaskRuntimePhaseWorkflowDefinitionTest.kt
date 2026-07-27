package skillbill.workflow.taskruntime

import skillbill.contracts.workflow.WORKFLOW_STATE_CONTRACT_VERSION
import skillbill.workflow.implement.FeatureImplementWorkflowDefinition
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeBackwardEdgeCapScope
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeCapExhaustionBehavior
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffSourceRef
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseEntryGate
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepositoryCheckpointPolicy
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeTransitionDeclaration
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerdict
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT_FIX,
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW,
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
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT_FIX to "Phase 4b: Implement Fix",
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW to "Phase 5: Code Review",
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
      listOf(
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN,
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT,
      ),
      dependenciesOf(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT),
    )
    assertEquals(
      listOf(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT),
      dependenciesOf(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW),
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
    assertEquals(
      FeatureTaskRuntimeCapExhaustionBehavior.ADVANCE_UNLESS_UNRESOLVED_BLOCKER,
      edge.capExhaustionBehavior,
    )
    assertEquals(FeatureTaskRuntimeVerdict.CHANGES_REQUESTED, edge.triggeringVerdict)
    // The backward destination precedes its source so the reopened span includes review (re-review leg).
    val ids = transitions.forwardPhaseIds
    assertTrue(ids.indexOf(edge.destinationPhaseId) < ids.indexOf(edge.fromPhaseId))
    // The fix phase consumes only the bounded review repair request; settled plan and implementation
    // evidence stay private to their producing phases.
    assertEquals(
      listOf(def.PHASE_REVIEW),
      dependenciesOf(def.PHASE_IMPLEMENT_FIX),
    )
    assertEquals(
      FeatureTaskRuntimeRepositoryCheckpointPolicy.MUST_MATCH,
      def.phaseDeclarations.getValue(def.PHASE_IMPLEMENT_FIX).projectionDeclarations.single().checkpointPolicy,
    )
  }

  @Test
  fun `the three record-regeneration edges keep their caps and cap-exhaustion behavior`() {
    // AC-006: the producer-side projection gate reduces how often these edges fire; it must not remove,
    // re-cap, or reroute any of them. They stay the recovery path for genuine drift.
    val def = FeatureTaskRuntimePhaseWorkflowDefinition
    val expected = mapOf(
      def.PREPLAN_REGENERATION_LOOP_ID to (def.PHASE_PLAN to def.PHASE_PREPLAN),
      def.PLAN_REGENERATION_LOOP_ID to (def.PHASE_IMPLEMENT to def.PHASE_PLAN),
      def.IMPLEMENT_REGENERATION_LOOP_ID to (def.PHASE_AUDIT to def.PHASE_IMPLEMENT),
    )

    assertEquals(setOf("regenerate_preplan", "regenerate_plan", "regenerate_implement"), def.REGENERATION_LOOP_IDS)
    assertEquals(2, def.MAX_RECORD_REGENERATION_ATTEMPTS)
    expected.forEach { (loopId, endpoints) ->
      val edge = def.transitions.backwardEdges.single { it.loopId == loopId }
      assertEquals(endpoints.first, edge.fromPhaseId)
      assertEquals(endpoints.second, edge.destinationPhaseId)
      assertEquals(def.MAX_RECORD_REGENERATION_ATTEMPTS, edge.perEdgeCap)
      assertEquals(FeatureTaskRuntimeBackwardEdgeCapScope.PER_SUBTASK, edge.capScope)
      assertEquals(FeatureTaskRuntimeCapExhaustionBehavior.BLOCK, edge.capExhaustionBehavior)
      assertEquals(FeatureTaskRuntimeVerdict.RECORD_REJECTED, edge.triggeringVerdict)
    }
  }

  @Test
  fun `the audit_gap backward edge reopens implement-through-audit without planning and without a cap`() {
    val def = FeatureTaskRuntimePhaseWorkflowDefinition
    val transitions = def.transitions
    // Two remediation edges (review_fix, audit_gap) plus the three SKILL-140 regeneration edges.
    assertEquals(5, transitions.backwardEdges.size)
    val edge = transitions.backwardEdges.single { it.loopId == def.AUDIT_GAP_LOOP_ID }
    assertEquals(def.PHASE_AUDIT, edge.fromPhaseId)
    assertEquals(def.PHASE_IMPLEMENT, edge.destinationPhaseId)
    assertEquals("audit_gap", edge.loopId)
    assertEquals(null, edge.perEdgeCap)
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
  @Suppress("LongMethod")
  fun `consumer projection matrix is exact and downstream edges never receive whole phase receipts`() {
    val def = FeatureTaskRuntimePhaseWorkflowDefinition
    val expected = mapOf(
      def.PHASE_PLAN to setOf(def.PHASE_PREPLAN to "feature_task_runtime.preplanning_digest"),
      def.PHASE_IMPLEMENT to setOf(def.PHASE_PLAN to "feature_task_runtime.executable_plan"),
      def.PHASE_AUDIT to setOf(
        def.PHASE_PLAN to "feature_task_runtime.plan_commitment",
        def.PHASE_IMPLEMENT to "feature_task_runtime.implementation_receipt",
      ),
      def.PHASE_IMPLEMENT_FIX to setOf(
        def.PHASE_REVIEW to FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.REVIEW_REPAIR_REQUEST,
      ),
      def.PHASE_REVIEW to setOf(
        def.PHASE_AUDIT to FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.AUDIT_CLEARANCE,
      ),
      def.PHASE_VALIDATE to setOf(
        def.PHASE_IMPLEMENT to FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.VALIDATION_REQUEST,
        def.PHASE_AUDIT to FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.AUDIT_CLEARANCE,
      ),
      def.PHASE_WRITE_HISTORY to setOf(
        def.PHASE_IMPLEMENT to FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.BOUNDARY_CANDIDATES,
        def.PHASE_VALIDATE to FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.VALIDATION_RECEIPT,
      ),
      def.PHASE_COMMIT_PUSH to setOf(
        def.PHASE_IMPLEMENT to FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.COMMIT_REQUEST,
        def.PHASE_VALIDATE to FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.VALIDATION_RECEIPT,
        def.PHASE_WRITE_HISTORY to FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.HISTORY_RECEIPT,
      ),
      def.PHASE_PR to setOf(
        def.PHASE_IMPLEMENT to FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.PR_REQUEST,
        def.PHASE_COMMIT_PUSH to FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.COMMIT_RECEIPT,
      ),
    )
    expected.forEach { (consumer, expectedEdges) ->
      val actual = def.phaseDeclarations.getValue(consumer).projectionDeclarations.map { declaration ->
        val source = declaration.sourceRef as FeatureTaskRuntimeHandoffSourceRef.UpstreamPhaseOutput
        source.producingPhaseId to declaration.projectionContractId
      }.toSet()
      assertEquals(expectedEdges, actual, consumer)
      assertTrue(
        def.phaseDeclarations.getValue(consumer).projectionDeclarations.none {
          it.projectionContractId == def.UPSTREAM_PHASE_RECEIPT_CONTRACT_ID
        },
        "$consumer must not receive a complete upstream phase receipt",
      )
      def.phaseDeclarations.getValue(consumer).projectionDeclarations.forEach { declaration ->
        assertTrue(declaration.required, "${declaration.projectionName} must reject missing required fields")
        assertTrue("phase_output_receipt" !in declaration.declaredFieldNames)
        assertTrue(
          declaration.declaredFieldNames.none {
            it in setOf(
              "summary", "raw_payload", "payload", "raw_prompt", "prompt", "transcript",
              "tool_output", "logs", "source_body", "diff_body", "telemetry", "prior_reports",
              "repair_history",
            )
          },
          "${declaration.projectionName} exposes forbidden context",
        )
      }
    }
    assertEquals(definition.stepIds.toSet(), def.phaseDeclarations.keys)
    assertTrue(def.phaseDeclarations.getValue(def.PHASE_PREPLAN).projectionDeclarations.isEmpty())
  }

  @Test
  fun `remediation selectors retain only the immutable plan and applicable repair projection`() {
    val def = FeatureTaskRuntimePhaseWorkflowDefinition
    val auditRemediation = def.auditRemediationProjections()
    assertEquals(
      listOf(
        def.PHASE_PLAN to "feature_task_runtime.executable_plan",
        def.PHASE_AUDIT to FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.AUDIT_REPAIR_REQUEST,
      ),
      auditRemediation.map {
        (it.sourceRef as FeatureTaskRuntimeHandoffSourceRef.UpstreamPhaseOutput).producingPhaseId to
          it.projectionContractId
      },
    )
    assertTrue(auditRemediation.none { it.projectionContractId == def.UPSTREAM_PHASE_RECEIPT_CONTRACT_ID })
    assertEquals(
      listOf(
        "audit_repair_plan",
        "prior_terminal_repair_outcomes",
        "unresolved_gap_ids",
        "repository_checkpoint",
      ),
      auditRemediation.last().declaredFieldNames,
    )

    val reviewRetry = def.reviewRetryProjections()
    assertEquals(
      listOf(
        def.PHASE_IMPLEMENT_FIX to FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.CHANGE_RECEIPT,
        def.PHASE_AUDIT to FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.AUDIT_CLEARANCE,
      ),
      reviewRetry.map {
        (it.sourceRef as FeatureTaskRuntimeHandoffSourceRef.UpstreamPhaseOutput).producingPhaseId to
          it.projectionContractId
      },
    )
    assertTrue(reviewRetry.none { it.projectionContractId == def.UPSTREAM_PHASE_RECEIPT_CONTRACT_ID })
  }

  @Test
  fun `phase transitions never reject repository movement`() {
    val checkpointedDeclarations = FeatureTaskRuntimePhaseWorkflowDefinition.phaseDeclarations.values
      .flatMap { it.projectionDeclarations }
      .filter { "repository_checkpoint" in it.declaredFieldNames }

    assertTrue(checkpointedDeclarations.isNotEmpty())
    assertTrue(
      checkpointedDeclarations.all {
        it.checkpointPolicy == FeatureTaskRuntimeRepositoryCheckpointPolicy.REFRESH_FROM_REPOSITORY
      },
    )
  }

  @Test
  fun `repair and finalization projections expose only checkpoint-specific request fields`() {
    val def = FeatureTaskRuntimePhaseWorkflowDefinition
    fun fields(consumer: String, projection: String): List<String> =
      def.phaseDeclarations.getValue(consumer).projectionDeclarations
        .single { it.projectionName == projection }
        .declaredFieldNames

    assertEquals(
      listOf("unresolved_blocker_findings", "repository_checkpoint"),
      fields(def.PHASE_IMPLEMENT_FIX, "review_repair_request"),
    )
    assertEquals(
      listOf("clearance_status", "review_scope", "repository_checkpoint"),
      fields(def.PHASE_REVIEW, "audit_clearance"),
    )
    assertEquals(
      listOf("validation_strategy", "changed_paths", "required_checks", "repository_checkpoint"),
      fields(def.PHASE_VALIDATE, "validation_request"),
    )
    assertEquals(
      listOf(
        "path_inventory",
        "required_inclusions",
        "required_exclusions",
        "branch_identity",
        "gate_attestations",
        "repository_checkpoint",
      ),
      fields(def.PHASE_COMMIT_PUSH, "commit_request"),
    )
    assertEquals(
      listOf(
        "completed_task_ids",
        "changed_paths",
        "tests_added",
        "tests_updated",
        "deviations",
        "validation_summary",
        "base_branch",
        "diff_reference",
      ),
      fields(def.PHASE_PR, "pr_request"),
    )
  }

  @Test
  fun `runtime projectors privately combine only the producers needed by finalization consumers`() {
    val def = FeatureTaskRuntimePhaseWorkflowDefinition
    assertEquals(
      setOf(def.PHASE_PLAN, def.PHASE_IMPLEMENT, def.PHASE_AUDIT),
      def.runtimeProjectorProducerPhaseIds(def.PHASE_VALIDATE),
    )
    assertEquals(
      setOf(def.PHASE_IMPLEMENT, def.PHASE_VALIDATE),
      def.runtimeProjectorProducerPhaseIds(def.PHASE_WRITE_HISTORY),
    )
    assertEquals(
      setOf(def.PHASE_IMPLEMENT, def.PHASE_VALIDATE, def.PHASE_WRITE_HISTORY),
      def.runtimeProjectorProducerPhaseIds(def.PHASE_COMMIT_PUSH),
    )
    assertEquals(
      setOf(def.PHASE_IMPLEMENT, def.PHASE_VALIDATE, def.PHASE_COMMIT_PUSH),
      def.runtimeProjectorProducerPhaseIds(def.PHASE_PR),
    )
    assertTrue(def.runtimeProjectorProducerPhaseIds(def.PHASE_REVIEW).isEmpty())
  }

  @Test
  fun `the pipeline is audit-first and review is gated on a satisfied audit`() {
    val def = FeatureTaskRuntimePhaseWorkflowDefinition
    val ids = def.transitions.forwardPhaseIds
    assertTrue(ids.indexOf(def.PHASE_IMPLEMENT) < ids.indexOf(def.PHASE_AUDIT))
    assertTrue(ids.indexOf(def.PHASE_AUDIT) < ids.indexOf(def.PHASE_REVIEW))
    assertTrue(ids.indexOf(def.PHASE_REVIEW) < ids.indexOf(def.PHASE_VALIDATE))
    val gate = def.transitions.entryGates.single()
    assertEquals(def.PHASE_REVIEW, gate.phaseId)
    assertEquals(def.PHASE_AUDIT, gate.requiredPhaseId)
    assertEquals(FeatureTaskRuntimeVerdict.SATISFIED, gate.requiredVerdict)
  }

  @Test
  fun `the review_fix span excludes audit and the audit_gap span excludes review`() {
    val def = FeatureTaskRuntimePhaseWorkflowDefinition
    val transitions = def.transitions
    val reviewFix = transitions.backwardEdges.single { it.loopId == def.REVIEW_FIX_LOOP_ID }
    val auditGap = transitions.backwardEdges.single { it.loopId == def.AUDIT_GAP_LOOP_ID }
    val reviewFixSpan = transitions.spanBetween(reviewFix.destinationPhaseId, reviewFix.fromPhaseId)
    val auditGapSpan = transitions.spanBetween(auditGap.destinationPhaseId, auditGap.fromPhaseId)
    assertEquals(listOf(def.PHASE_IMPLEMENT_FIX, def.PHASE_REVIEW), reviewFixSpan)
    assertEquals(listOf(def.PHASE_IMPLEMENT, def.PHASE_AUDIT), auditGapSpan)
    // No review outcome can reopen the audit repair plan, and no audit gap loop re-runs review.
    assertTrue(def.PHASE_AUDIT !in reviewFixSpan)
    assertTrue(def.PHASE_REVIEW !in auditGapSpan)
  }

  @Test
  fun `an entry gate whose required phase does not precede the gated phase fails at construction`() {
    val error = assertFailsWith<IllegalArgumentException> {
      FeatureTaskRuntimeTransitionDeclaration(
        forwardPhaseIds = listOf("review", "audit"),
        entryGates = listOf(
          FeatureTaskRuntimePhaseEntryGate("review", "audit", FeatureTaskRuntimeVerdict.SATISFIED),
        ),
      )
    }
    assertTrue(error.message.orEmpty().contains("precede"))
  }

  @Test
  fun `terminal summary artifact points at the always-persisted per-phase records store`() {
    assertEquals(
      FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY,
      definition.completedTerminalSummaryArtifact,
    )
  }

  @Test
  fun `all backward edges declare PER_SUBTASK capScope explicitly`() {
    val edges = FeatureTaskRuntimePhaseWorkflowDefinition.transitions.backwardEdges
    assertEquals(5, edges.size, "expected exactly five declared backward edges: ${edges.map { it.loopId }}")
    edges.forEach { edge ->
      assertEquals(
        FeatureTaskRuntimeBackwardEdgeCapScope.PER_SUBTASK,
        edge.capScope,
        "backward edge '${edge.loopId}' must explicitly declare PER_SUBTASK capScope",
      )
    }
  }

  private fun dependenciesOf(phaseId: String): List<String> = definition.requiredArtifactsByStep.getValue(phaseId)
}
