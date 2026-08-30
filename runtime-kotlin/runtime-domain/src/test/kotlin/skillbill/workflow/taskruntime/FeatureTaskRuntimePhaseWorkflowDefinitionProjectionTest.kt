package skillbill.workflow.taskruntime

import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeBackwardEdgeCapScope
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeCapExhaustionBehavior
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffSourceRef
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseEntryGate
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePlanningProjectionContract
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePriorGapMemory
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepositoryCheckpointPolicy
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeSharedReviewEvidenceReference
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeTransitionDeclaration
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerdict
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FeatureTaskRuntimePhaseWorkflowDefinitionProjectionTest {
  private val definition = FeatureTaskRuntimePhaseWorkflowDefinition.definition

  @Test
  fun `consumer projection matrix is exact and downstream edges never receive whole phase receipts`() {
    assertConsumerProjectionMatrixExact(definition)
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
        def.PHASE_AUDIT to FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.PHASE_PROSE,
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
      FeatureTaskRuntimeRepositoryCheckpointPolicy.REFRESH_FROM_REPOSITORY,
      upstreamRemediation.last().checkpointPolicy,
      "audit-to-implement prose refreshes the repository checkpoint",
    )
    assertEquals(
      listOf("value", "directive"),
      upstreamRemediation.last().declaredFieldNames,
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
      listOf("changed_paths", "repository_checkpoint"),
      fields(def.PHASE_VALIDATE, "validation_request"),
    )
    assertEquals(
      listOf(
        "path_inventory",
        "required_inclusions",
        "branch_identity",
        "gate_attestations",
        "repository_checkpoint",
      ),
      fields(def.PHASE_COMMIT_PUSH, "commit_request"),
    )
    assertEquals(
      listOf(
        "changed_paths",
        "validation_summary",
        "base_branch",
        "diff_reference",
      ),
      fields(def.PHASE_PR, "pr_request"),
    )
    val prRequestFields = fields(def.PHASE_PR, "pr_request")
    assertTrue("completed_task_ids" !in prRequestFields)
    assertTrue("tests_added" !in prRequestFields)
    assertTrue("tests_updated" !in prRequestFields)
    assertTrue("deviations" !in prRequestFields)
    val commitRequestFields = fields(def.PHASE_COMMIT_PUSH, "commit_request")
    assertTrue("required_exclusions" !in commitRequestFields)
    listOf(
      def.PHASE_VALIDATE,
      def.PHASE_BUILD,
      def.PHASE_WRITE_HISTORY,
      def.PHASE_COMMIT_PUSH,
      def.PHASE_PR,
    ).forEach { consumer ->
      val prose = def.phaseDeclarations.getValue(consumer).projectionDeclarations.filter {
        it.projectionContractId == FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.PHASE_PROSE
      }
      assertTrue(prose.isNotEmpty(), "$consumer must declare phase_prose")
      prose.forEach { declaration ->
        assertEquals(
          FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.PHASE_PROSE,
          declaration.projectionContractId,
        )
      }
    }
  }

  @Test
  fun `runtime projectors privately combine only the producers needed by finalization consumers`() {
    val def = FeatureTaskRuntimePhaseWorkflowDefinition
    assertEquals(
      setOf(def.PHASE_PLAN, def.PHASE_AUDIT),
      def.runtimeProjectorProducerPhaseIds(def.PHASE_VALIDATE),
    )
    assertEquals(
      setOf(def.PHASE_PLAN, def.PHASE_AUDIT),
      def.runtimeProjectorProducerPhaseIds(def.PHASE_BUILD),
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
    assertEquals(
      setOf(def.PHASE_REVIEW),
      def.runtimeProjectorProducerPhaseIds(def.PHASE_IMPLEMENT_FIX),
    )
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
}
