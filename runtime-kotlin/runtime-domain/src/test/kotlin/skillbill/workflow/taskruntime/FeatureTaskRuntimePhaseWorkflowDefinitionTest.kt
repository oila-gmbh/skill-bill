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

@Suppress("LargeClass")
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
  @Suppress("LongMethod")
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
      listOf(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW),
      dependenciesOf(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VERIFY_FINDINGS),
    )
    assertEquals(
      listOf(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VERIFY_FINDINGS),
      dependenciesOf(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT_FIX),
    )
    assertEquals(
      listOf(
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT,
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT,
      ),
      dependenciesOf(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD),
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
      if (phaseId in loopOnly) return@forEachIndexed
      dependenciesOf(phaseId).forEach { upstream ->
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
      dependenciesOf(def.PHASE_IMPLEMENT_FIX),
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
          def.phaseDeclarationForQualityGate(
            phaseId,
            FeatureTaskRuntimeFeatureSize.MEDIUM,
            FeatureTaskRuntimeQualityGateSelection.VALIDATE,
          )
        else -> declarations.getValue(phaseId)
      }
      assertEquals(dependenciesOf(phaseId), declaration.consumedUpstreamPhaseIds, phaseId)
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
      FeatureTaskRuntimePhaseWorkflowDefinition.phaseDeclaration(
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW,
        FeatureTaskRuntimeFeatureSize.SMALL,
      ).derivedContextKeys,
    )
    assertEquals(
      listOf("diff"),
      FeatureTaskRuntimePhaseWorkflowDefinition.phaseDeclaration(
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

  @Test
  @Suppress("LongMethod")
  fun `consumer projection matrix is exact and downstream edges never receive whole phase receipts`() {
    val def = FeatureTaskRuntimePhaseWorkflowDefinition
    val expected = mapOf(
      def.PHASE_PLAN to setOf(def.PHASE_PREPLAN to "feature_task_runtime.phase_prose"),
      def.PHASE_IMPLEMENT to setOf(def.PHASE_PLAN to "feature_task_runtime.phase_prose"),
      def.PHASE_AUDIT to setOf(
        def.PHASE_PLAN to "feature_task_runtime.phase_prose",
        def.PHASE_IMPLEMENT to "feature_task_runtime.phase_prose",
      ),
      def.PHASE_IMPLEMENT_FIX to setOf(
        def.PHASE_VERIFY_FINDINGS to
          FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.REVIEW_REPAIR_REQUEST,
      ),
      def.PHASE_VERIFY_FINDINGS to setOf(
        def.PHASE_REVIEW to
          FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.FINDINGS_VERIFICATION_INPUT,
      ),
      def.PHASE_REVIEW to setOf(
        def.PHASE_AUDIT to FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.AUDIT_CLEARANCE,
      ),
      def.PHASE_VALIDATE to setOf(
        def.PHASE_IMPLEMENT to FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.VALIDATION_REQUEST,
        def.PHASE_AUDIT to FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.AUDIT_CLEARANCE,
      ),
      def.PHASE_BUILD to setOf(
        def.PHASE_IMPLEMENT to FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.VALIDATION_REQUEST,
        def.PHASE_AUDIT to FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.AUDIT_CLEARANCE,
      ),
      def.PHASE_WRITE_HISTORY to setOf(
        def.PHASE_IMPLEMENT to FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.BOUNDARY_CANDIDATES,
        def.PHASE_VALIDATE to FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.VALIDATION_RECEIPT,
        def.PHASE_BUILD to FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.BUILD_RECEIPT,
      ),
      def.PHASE_COMMIT_PUSH to setOf(
        def.PHASE_IMPLEMENT to FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.COMMIT_REQUEST,
        def.PHASE_VALIDATE to FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.VALIDATION_RECEIPT,
        def.PHASE_BUILD to FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.BUILD_RECEIPT,
        def.PHASE_WRITE_HISTORY to FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.HISTORY_RECEIPT,
      ),
      def.PHASE_PR to setOf(
        def.PHASE_IMPLEMENT to FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.PR_REQUEST,
        def.PHASE_COMMIT_PUSH to FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.COMMIT_RECEIPT,
      ),
    )
    expected.forEach { (consumer, expectedEdges) ->
      val upstream = def.phaseDeclarations.getValue(consumer).projectionDeclarations.filter {
        it.sourceRef is FeatureTaskRuntimeHandoffSourceRef.UpstreamPhaseOutput
      }
      val actual = upstream.map { declaration ->
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
      upstream.forEach { declaration ->
        val isBuildReceipt =
          declaration.projectionContractId ==
            FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.BUILD_RECEIPT
        val producingBuild =
          (declaration.sourceRef as FeatureTaskRuntimeHandoffSourceRef.UpstreamPhaseOutput)
            .producingPhaseId == def.PHASE_BUILD
        val optionalBuildReceipt = isBuildReceipt && producingBuild
        assertEquals(
          !optionalBuildReceipt,
          declaration.required,
          "${declaration.projectionName} required flag",
        )
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
  fun `audit remediation selectors retain only the immutable plan and applicable repair projection`() {
    val def = FeatureTaskRuntimePhaseWorkflowDefinition
    val auditRemediation = def.auditRemediationProjections()
    val upstreamRemediation = auditRemediation.filter {
      it.sourceRef is FeatureTaskRuntimeHandoffSourceRef.UpstreamPhaseOutput
    }
    assertEquals(
      listOf(
        def.PHASE_PLAN to FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.PHASE_PROSE,
        def.PHASE_IMPLEMENT to FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.PHASE_PROSE,
        def.PHASE_AUDIT to FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.AUDIT_REPAIR_REQUEST,
      ),
      upstreamRemediation.map {
        (it.sourceRef as FeatureTaskRuntimeHandoffSourceRef.UpstreamPhaseOutput).producingPhaseId to
          it.projectionContractId
      },
    )
    assertTrue(
      auditRemediation.any { it.sourceRef == FeatureTaskRuntimeHandoffSourceRef.PriorGapMemory },
    )
    assertTrue(auditRemediation.none { it.projectionContractId == def.UPSTREAM_PHASE_RECEIPT_CONTRACT_ID })
    assertEquals(
      listOf("unmet_criteria", "repository_checkpoint"),
      upstreamRemediation.last().declaredFieldNames,
      "the remediation handoff carries the unmet criteria and the checkpoint, and nothing else",
    )
  }

  @Test
  fun `forward checkpoints refresh while reviewed remediation alone requires an exact match`() {
    val def = FeatureTaskRuntimePhaseWorkflowDefinition
    val checkpointedDeclarations = FeatureTaskRuntimePhaseWorkflowDefinition.phaseDeclarations.values
      .flatMap { it.projectionDeclarations }
      .filter { "repository_checkpoint" in it.declaredFieldNames }
    val mustMatch = checkpointedDeclarations.filter {
      it.checkpointPolicy == FeatureTaskRuntimeRepositoryCheckpointPolicy.MUST_MATCH
    }

    assertTrue(checkpointedDeclarations.isNotEmpty())
    assertEquals(
      listOf(def.PHASE_IMPLEMENT_FIX to "review_repair_request"),
      mustMatch.map { it.consumerPhaseId to it.projectionName },
    )
    assertTrue(
      checkpointedDeclarations.filterNot { it in mustMatch }.all {
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
      setOf(def.PHASE_IMPLEMENT, def.PHASE_VALIDATE, def.PHASE_BUILD),
      def.runtimeProjectorProducerPhaseIds(def.PHASE_WRITE_HISTORY),
    )
    assertEquals(
      setOf(def.PHASE_IMPLEMENT, def.PHASE_VALIDATE, def.PHASE_BUILD, def.PHASE_WRITE_HISTORY),
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
    assertTrue(ids.indexOf(def.PHASE_REVIEW) < ids.indexOf(def.PHASE_VERIFY_FINDINGS))
    assertTrue(ids.indexOf(def.PHASE_VERIFY_FINDINGS) < ids.indexOf(def.PHASE_IMPLEMENT_FIX))
    assertTrue(ids.indexOf(def.PHASE_IMPLEMENT_FIX) < ids.indexOf(def.PHASE_BUILD))
    assertTrue(ids.indexOf(def.PHASE_BUILD) < ids.indexOf(def.PHASE_VALIDATE))
    assertTrue(def.PHASE_BUILD in def.transitions.loopOnlyPhaseIds)
    assertTrue(def.PHASE_IMPLEMENT_FIX in def.transitions.loopOnlyPhaseIds)
    assertTrue(def.PHASE_VERIFY_FINDINGS !in def.transitions.loopOnlyPhaseIds)
    val reviewGate = def.transitions.entryGates.single { it.phaseId == def.PHASE_REVIEW }
    assertEquals(def.PHASE_AUDIT, reviewGate.requiredPhaseId)
    assertEquals(FeatureTaskRuntimeVerdict.SATISFIED, reviewGate.requiredVerdict)
    val implementFixGate = def.transitions.entryGates.single { it.phaseId == def.PHASE_IMPLEMENT_FIX }
    assertEquals(def.PHASE_VERIFY_FINDINGS, implementFixGate.requiredPhaseId)
    assertEquals(FeatureTaskRuntimeVerdict.FINDINGS_VERIFIED, implementFixGate.requiredVerdict)
  }

  @Test
  fun `the review_fix span excludes audit and the audit_gap span excludes review`() {
    val def = FeatureTaskRuntimePhaseWorkflowDefinition
    val transitions = def.transitions
    val reviewFix = transitions.backwardEdges.single { it.loopId == def.REVIEW_FIX_LOOP_ID }
    val auditGap = transitions.backwardEdges.single { it.loopId == def.AUDIT_GAP_LOOP_ID }
    val reviewFixSpan = transitions.spanBetween(reviewFix.destinationPhaseId, reviewFix.fromPhaseId)
    val auditGapSpan = transitions.spanBetween(auditGap.destinationPhaseId, auditGap.fromPhaseId)
    assertEquals(listOf(def.PHASE_IMPLEMENT_FIX), reviewFixSpan)
    assertEquals(listOf(def.PHASE_IMPLEMENT, def.PHASE_AUDIT), auditGapSpan)
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
    assertEquals(2, edges.size, "expected exactly two declared backward edges: ${edges.map { it.loopId }}")
    edges.forEach { edge ->
      assertEquals(
        FeatureTaskRuntimeBackwardEdgeCapScope.PER_SUBTASK,
        edge.capScope,
        "backward edge '${edge.loopId}' must explicitly declare PER_SUBTASK capScope",
      )
    }
  }

  @Test
  fun `review and audit both declare the shared review evidence projection`() {
    val def = FeatureTaskRuntimePhaseWorkflowDefinition
    listOf(def.PHASE_REVIEW, def.PHASE_AUDIT).forEach { phaseId ->
      val shared = def.phaseDeclarations.getValue(phaseId).projectionDeclarations.single {
        it.sourceRef == FeatureTaskRuntimeHandoffSourceRef.SharedReviewEvidence
      }
      assertEquals(def.SHARED_REVIEW_EVIDENCE_PROJECTION_NAME, shared.projectionName)
      assertEquals(
        FeatureTaskRuntimePlanningProjectionContract.SHARED_REVIEW_EVIDENCE_ID,
        shared.projectionContractId,
      )
      assertEquals(FeatureTaskRuntimeSharedReviewEvidenceReference.DECLARED_FIELD_NAMES, shared.declaredFieldNames)
      assertEquals(false, shared.required)
      assertEquals(
        FeatureTaskRuntimeRepositoryCheckpointPolicy.REFRESH_FROM_REPOSITORY,
        shared.checkpointPolicy,
      )
    }
  }

  @Test
  fun `audit keeps its existing projections and scoped repository state alongside the shared evidence`() {
    val def = FeatureTaskRuntimePhaseWorkflowDefinition
    val audit = def.phaseDeclarations.getValue(def.PHASE_AUDIT)
    assertEquals(
      listOf(
        FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.PHASE_PROSE,
        FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.PHASE_PROSE,
        FeatureTaskRuntimePlanningProjectionContract.SHARED_REVIEW_EVIDENCE_ID,
      ),
      audit.projectionDeclarations.map { it.projectionContractId },
    )
    assertEquals(
      listOf("plan_prose", "implement_prose"),
      audit.projectionDeclarations
        .filter {
          it.projectionContractId ==
            FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.PHASE_PROSE
        }
        .map { it.projectionName },
    )
    assertEquals(
      listOf(FeatureTaskRuntimePhaseWorkflowDefinition.DERIVED_CONTEXT_SCOPED_REPOSITORY_STATE),
      audit.derivedContextKeys,
    )
  }

  @Test
  fun `phase topology matches the single-round review remediation contract`() {
    val transitions = FeatureTaskRuntimePhaseWorkflowDefinition.transitions
    val def = FeatureTaskRuntimePhaseWorkflowDefinition
    assertEquals(definition.stepIds, transitions.forwardPhaseIds)
    assertEquals(
      listOf(
        FeatureTaskRuntimePhaseEntryGate(
          phaseId = def.PHASE_REVIEW,
          requiredPhaseId = def.PHASE_AUDIT,
          requiredVerdict = FeatureTaskRuntimeVerdict.SATISFIED,
        ),
        FeatureTaskRuntimePhaseEntryGate(
          phaseId = def.PHASE_IMPLEMENT_FIX,
          requiredPhaseId = def.PHASE_VERIFY_FINDINGS,
          requiredVerdict = FeatureTaskRuntimeVerdict.FINDINGS_VERIFIED,
        ),
      ),
      transitions.entryGates,
    )
    val semantic = transitions.backwardEdges.filterNot { def.isRegenerationLoopId(it.loopId) }
    assertEquals(
      listOf(
        Triple(def.PHASE_VERIFY_FINDINGS, FeatureTaskRuntimeVerdict.FINDINGS_VERIFIED, def.PHASE_IMPLEMENT_FIX),
        Triple(def.PHASE_AUDIT, FeatureTaskRuntimeVerdict.GAPS_FOUND, def.PHASE_IMPLEMENT),
      ),
      semantic.map { Triple(it.fromPhaseId, it.triggeringVerdict, it.destinationPhaseId) },
    )
    assertTrue(semantic.none { it.fromPhaseId == def.PHASE_REVIEW && it.loopId == def.REVIEW_FIX_LOOP_ID })
    val reviewFixEdge = semantic.single { it.loopId == def.REVIEW_FIX_LOOP_ID }
    assertEquals(1, reviewFixEdge.perEdgeCap)
    assertEquals(FeatureTaskRuntimeCapExhaustionBehavior.ADVANCE, reviewFixEdge.capExhaustionBehavior)
    assertNull(semantic.single { it.loopId == def.AUDIT_GAP_LOOP_ID }.perEdgeCap)
  }

  @Test
  fun `prior gap memory source ref round-trips and the declaration matches the model field set`() {
    val def = FeatureTaskRuntimePhaseWorkflowDefinition
    val source = FeatureTaskRuntimeHandoffSourceRef.fromWire(
      FeatureTaskRuntimeHandoffSourceRef.PRIOR_GAP_MEMORY_WIRE,
    )
    assertEquals(FeatureTaskRuntimeHandoffSourceRef.PriorGapMemory, source)
    assertEquals(
      mapOf("kind" to "prior_gap_memory", "id" to "prior_gap_memory"),
      source.toDeclarationMap(),
    )
    assertEquals(
      FeatureTaskRuntimeHandoffSourceRef.PriorGapMemory,
      FeatureTaskRuntimeHandoffSourceRef.fromWire(
        FeatureTaskRuntimeHandoffSourceRef.fromWire(
          FeatureTaskRuntimeHandoffSourceRef.PRIOR_GAP_MEMORY_WIRE,
        ).wireValue,
      ),
    )

    val declaration = def.priorGapMemoryDeclaration(def.PHASE_IMPLEMENT)
    assertEquals(def.PRIOR_GAP_MEMORY_PROJECTION_NAME, declaration.projectionName)
    assertEquals(
      FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.PRIOR_GAP_MEMORY,
      declaration.projectionContractId,
    )
    assertEquals(FeatureTaskRuntimeHandoffSourceRef.PriorGapMemory, declaration.sourceRef)
    assertEquals(FeatureTaskRuntimePriorGapMemory.DECLARED_FIELD_NAMES, declaration.declaredFieldNames)
    assertEquals(false, declaration.required, "absent memory must omit, never reject a predating in-flight run")
  }

  private fun dependenciesOf(phaseId: String): List<String> = definition.requiredArtifactsByStep.getValue(phaseId)
}
