package skillbill.application.featuretask

import skillbill.error.InvalidFeatureTaskRuntimeAuditGenerationSchemaError
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditGapState
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditGeneration
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeBlastRadiusInspection
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeCriterionInspection
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeCriterionInspectionVerdict
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeEvidence
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeGenerationGap
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeGovernanceEvidence
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairBatch
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairDisposition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairItemDisposition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepositoryCheckpoint
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class FeatureTaskRuntimeAuditGenerationWireTest {
  @Test
  fun `a generation carrying every new field round-trips through the wire`() {
    val generation = generation()

    assertEquals(generation, auditGenerationFromWire(auditGenerationToWire(generation), SOURCE))
  }

  @Test
  fun `every gap state survives the round trip`() {
    listOf(
      FeatureTaskRuntimeAuditGapState.NEW to 0,
      FeatureTaskRuntimeAuditGapState.RECURRING to 2,
      FeatureTaskRuntimeAuditGapState.RESOLVED to 1,
      FeatureTaskRuntimeAuditGapState.SUPERSEDED to 1,
      FeatureTaskRuntimeAuditGapState.STILL_OPEN to 0,
    ).forEach { (state, recurrence) ->
      val generation = generation(gapState = state, recurrenceCount = recurrence)

      assertEquals(state, auditGenerationFromWire(auditGenerationToWire(generation), SOURCE).gaps.single().state)
    }
  }

  @Test
  fun `an unknown field cannot enter durable history`() {
    val wire = auditGenerationToWire(generation()) + ("unexpected" to "value")

    val failure = assertFailsWith<InvalidFeatureTaskRuntimeAuditGenerationSchemaError> {
      auditGenerationFromWire(wire, SOURCE)
    }
    assertContains(failure.message.orEmpty(), "unexpected")
  }

  @Test
  fun `a foreign contract version is rejected rather than migrated in place`() {
    val wire = auditGenerationToWire(generation()) + ("contract_version" to "0.2")

    assertFailsWith<InvalidFeatureTaskRuntimeAuditGenerationSchemaError> { auditGenerationFromWire(wire, SOURCE) }
  }

  @Test
  fun `an unauthorized gap state is rejected`() {
    val wire = auditGenerationToWire(generation()).toMutableMap()
    val gaps = (wire["gaps"] as List<*>).map { (it as Map<*, *>) + ("state" to "reopened") }
    wire["gaps"] = gaps

    assertFailsWith<InvalidFeatureTaskRuntimeAuditGenerationSchemaError> { auditGenerationFromWire(wire, SOURCE) }
  }

  @Test
  fun `an evidence reference beyond the durable bound is rejected at the wire layer`() {
    val wire = auditGenerationToWire(generation()).toMutableMap()
    val gaps = (wire["gaps"] as List<*>).map { gap ->
      (gap as Map<*, *>) + (
        "failure_evidence" to mapOf(
          "observation" to "required_behavior_absent",
          "artifact_ref" to "a".repeat(300),
          "check_ref" to "AC-001",
        )
        )
    }
    wire["gaps"] = gaps

    assertFailsWith<InvalidFeatureTaskRuntimeAuditGenerationSchemaError> { auditGenerationFromWire(wire, SOURCE) }
  }

  @Test
  fun `a governed supersession claimed by a repair receipt decodes with its authority`() {
    val governance = supersededRepairItemsFrom(
      mapOf(
        "superseded_repair_items" to listOf(
          mapOf(
            "repair_item_id" to ITEM_ID,
            "governing_decision" to "The goal owner withdrew this obligation",
            "authority_ref" to "docs/skill-source-generation.md",
            "rationale" to "The behavior moved to a follow-up subtask",
          ),
        ),
      ),
    )

    assertEquals(setOf(ITEM_ID), governance.keys)
    assertEquals("docs/skill-source-generation.md", governance.getValue(ITEM_ID).authorityRef)
  }

  @Test
  fun `a supersession missing its governing authority cannot be represented`() {
    assertFailsWith<InvalidFeatureTaskRuntimeAuditGenerationSchemaError> {
      supersededRepairItemsFrom(
        mapOf("superseded_repair_items" to listOf(mapOf("repair_item_id" to ITEM_ID))),
      )
    }
  }

  @Test
  fun `a blast-radius inspection is read only from an audit output`() {
    val produced = mapOf(
      "blast_radius_inspection" to mapOf(
        "inspected_paths" to listOf("runtime-kotlin/runtime-application/Example.kt"),
        "newly_introduced_gap_ids" to listOf("ac-002-gap-1"),
        "evidence" to mapOf(
          "observation" to "resolution_verified",
          "artifact_ref" to "runtime-kotlin/runtime-application/Example.kt:Example",
          "check_ref" to "AC-001",
        ),
      ),
    )

    val inspection = blastRadiusInspectionFrom(produced, FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT)
    assertEquals(listOf("ac-002-gap-1"), inspection?.newlyIntroducedGapIds)
    assertNull(blastRadiusInspectionFrom(produced, FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT))
    assertNull(blastRadiusInspectionFrom(emptyMap(), FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT))
  }

  private fun generation(
    gapState: FeatureTaskRuntimeAuditGapState = FeatureTaskRuntimeAuditGapState.NEW,
    recurrenceCount: Int = 0,
  ): FeatureTaskRuntimeAuditGeneration {
    val gap = FeatureTaskRuntimeGenerationGap(
      gapId = GAP_ID,
      acceptanceCriterionRef = CRITERION,
      acceptanceCriterionText = "The initial completeness audit persists one generation",
      state = gapState,
      recurrenceCount = recurrenceCount,
      failureEvidence = evidence(FeatureTaskRuntimeEvidence.Observation.REQUIRED_BEHAVIOR_ABSENT),
      diagnosis = "The generation table is never written on settlement",
      affectedBoundary = "runtime-application",
      repairItemIds = listOf(ITEM_ID),
    )
    val open = gap.state.open
    return FeatureTaskRuntimeAuditGeneration(
      generationOrdinal = 1,
      repositoryCheckpoint = FeatureTaskRuntimeRepositoryCheckpoint("9f2c1ab"),
      inspectedCriteria = listOf(
        FeatureTaskRuntimeCriterionInspection(
          CRITERION,
          if (open) {
            FeatureTaskRuntimeCriterionInspectionVerdict.GAP
          } else {
            FeatureTaskRuntimeCriterionInspectionVerdict.SATISFIED
          },
        ),
      ),
      satisfiedCriterionRefs = if (open) emptyList() else listOf(CRITERION),
      gaps = listOf(gap),
      repairBatch = FeatureTaskRuntimeRepairBatch(
        batchId = "batch-1",
        repairItemIds = if (open) listOf(ITEM_ID) else emptyList(),
        repairItemDispositions = if (!open) {
          emptyList()
        } else {
          listOf(
            FeatureTaskRuntimeRepairItemDisposition(
              repairItemId = ITEM_ID,
              disposition = FeatureTaskRuntimeRepairDisposition.SUPERSEDED,
              resultEvidence = evidence(FeatureTaskRuntimeEvidence.Observation.RESOLUTION_VERIFIED),
              governanceEvidence = FeatureTaskRuntimeGovernanceEvidence(
                governingDecision = "The goal owner withdrew this obligation",
                authorityRef = "docs/skill-source-generation.md",
                rationale = "The behavior moved to a follow-up subtask",
              ),
            ),
          )
        },
      ),
      blastRadiusInspection = FeatureTaskRuntimeBlastRadiusInspection(
        inspectedPaths = listOf("runtime-kotlin/runtime-application/Example.kt"),
        newlyIntroducedGapIds = emptyList(),
        evidence = evidence(FeatureTaskRuntimeEvidence.Observation.RESOLUTION_VERIFIED),
      ),
    )
  }

  private fun evidence(observation: FeatureTaskRuntimeEvidence.Observation) = FeatureTaskRuntimeEvidence(
    observation = observation,
    artifactRef = "runtime-kotlin/runtime-application/Example.kt:Example",
    checkRef = CRITERION,
  )

  private companion object {
    const val SOURCE = "audit_generation:test#1"
    const val CRITERION = "AC-001"
    const val GAP_ID = "ac-001-gap-1"
    const val ITEM_ID = "ac-001-gap-1-item-1"
  }
}
