package skillbill.workflow.taskruntime

import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditGapState
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditGeneration
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditGenerationHistory
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
import skillbill.workflow.taskruntime.model.requireGapStateTransition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FeatureTaskRuntimeAuditGenerationModelsTest {
  @Test
  fun `gap lifecycle allows only forward transitions and keeps closure terminal`() {
    requireGapStateTransition(null, FeatureTaskRuntimeAuditGapState.NEW, GAP_ID, 1)
    listOf(
      FeatureTaskRuntimeAuditGapState.RECURRING,
      FeatureTaskRuntimeAuditGapState.STILL_OPEN,
      FeatureTaskRuntimeAuditGapState.RESOLVED,
      FeatureTaskRuntimeAuditGapState.SUPERSEDED,
    ).forEach { next ->
      requireGapStateTransition(FeatureTaskRuntimeAuditGapState.NEW, next, GAP_ID, 2)
      requireGapStateTransition(FeatureTaskRuntimeAuditGapState.RECURRING, next, GAP_ID, 2)
      requireGapStateTransition(FeatureTaskRuntimeAuditGapState.STILL_OPEN, next, GAP_ID, 2)
    }

    assertFailsWith<IllegalArgumentException> {
      requireGapStateTransition(null, FeatureTaskRuntimeAuditGapState.RECURRING, GAP_ID, 1)
    }
    listOf(FeatureTaskRuntimeAuditGapState.RESOLVED, FeatureTaskRuntimeAuditGapState.SUPERSEDED).forEach { closed ->
      FeatureTaskRuntimeAuditGapState.values().forEach { next ->
        assertFailsWith<IllegalArgumentException> { requireGapStateTransition(closed, next, GAP_ID, 3) }
      }
    }
    assertTrue(FeatureTaskRuntimeAuditGapState.RESOLVED.terminal)
    assertTrue(FeatureTaskRuntimeAuditGapState.SUPERSEDED.terminal)
    assertTrue(FeatureTaskRuntimeAuditGapState.RECURRING.open)
    assertTrue(FeatureTaskRuntimeAuditGapState.STILL_OPEN.open)
    assertTrue(FeatureTaskRuntimeAuditGapState.NEW.open)
  }

  @Test
  fun `a recurring gap must carry durable recurrence and a reallocated identity is rejected`() {
    assertFailsWith<IllegalArgumentException> {
      gap(state = FeatureTaskRuntimeAuditGapState.RECURRING, recurrenceCount = 0)
    }
    gap(state = FeatureTaskRuntimeAuditGapState.RECURRING, recurrenceCount = 1)

    // A repair item is an ordered child of its own gap; borrowing another gap's item would let a later
    // generation move a live obligation onto a different identity.
    assertFailsWith<IllegalArgumentException> {
      gap(repairItemIds = listOf("ac-002-gap-1-item-1"))
    }
    assertFailsWith<IllegalArgumentException> { gap(gapId = "ac-002-gap-1") }
  }

  @Test
  fun `recurrence for one identity never decreases across generations`() {
    val first = generation(1, listOf(gap(state = FeatureTaskRuntimeAuditGapState.NEW, recurrenceCount = 0)))
    val second = generation(
      2,
      listOf(gap(state = FeatureTaskRuntimeAuditGapState.RECURRING, recurrenceCount = 1)),
      batchOrdinal = 2,
    )
    FeatureTaskRuntimeAuditGenerationHistory(listOf(first, second))

    val regressed = generation(
      3,
      listOf(gap(state = FeatureTaskRuntimeAuditGapState.STILL_OPEN, recurrenceCount = 0)),
      batchOrdinal = 3,
    )
    assertFailsWith<IllegalArgumentException> {
      FeatureTaskRuntimeAuditGenerationHistory(listOf(first, second, regressed))
    }
  }

  @Test
  fun `history rejects reused and sparse ordinals`() {
    val first = generation(1, listOf(gap()))
    assertFailsWith<IllegalArgumentException> {
      FeatureTaskRuntimeAuditGenerationHistory(listOf(first, first))
    }
    assertFailsWith<IllegalArgumentException> {
      FeatureTaskRuntimeAuditGenerationHistory(listOf(generation(2, listOf(gap()), batchOrdinal = 2)))
    }
  }

  @Test
  fun `a superseded disposition requires governance evidence and a verified one forbids it`() {
    assertFailsWith<IllegalArgumentException> {
      FeatureTaskRuntimeRepairItemDisposition(
        repairItemId = ITEM_ID,
        disposition = FeatureTaskRuntimeRepairDisposition.SUPERSEDED,
        resultEvidence = evidence(FeatureTaskRuntimeEvidence.Observation.RESOLUTION_VERIFIED),
      )
    }
    assertFailsWith<IllegalArgumentException> {
      FeatureTaskRuntimeRepairItemDisposition(
        repairItemId = ITEM_ID,
        disposition = FeatureTaskRuntimeRepairDisposition.FIXED,
        resultEvidence = evidence(FeatureTaskRuntimeEvidence.Observation.FIX_VERIFIED),
        governanceEvidence = governance(),
      )
    }
    val governed = FeatureTaskRuntimeRepairItemDisposition(
      repairItemId = ITEM_ID,
      disposition = FeatureTaskRuntimeRepairDisposition.SUPERSEDED,
      resultEvidence = evidence(FeatureTaskRuntimeEvidence.Observation.RESOLUTION_VERIFIED),
      governanceEvidence = governance(),
    )
    assertTrue(governed.terminal)
  }

  @Test
  fun `an open gap must carry repair work and a self authorized batch must be exactly that work`() {
    assertFailsWith<IllegalArgumentException> {
      gap(state = FeatureTaskRuntimeAuditGapState.STILL_OPEN, repairItemIds = emptyList())
    }
    assertFailsWith<IllegalArgumentException> {
      FeatureTaskRuntimeAuditGeneration(
        generationOrdinal = 1,
        repositoryCheckpoint = FeatureTaskRuntimeRepositoryCheckpoint(FINGERPRINT),
        inspectedCriteria = listOf(inspection(FeatureTaskRuntimeCriterionInspectionVerdict.GAP)),
        satisfiedCriterionRefs = emptyList(),
        gaps = listOf(gap()),
        repairBatch = FeatureTaskRuntimeRepairBatch("batch-1", emptyList(), emptyList()),
      )
    }
  }

  @Test
  fun `a criterion cannot be both satisfied and open and every reported criterion is inspected`() {
    assertFailsWith<IllegalArgumentException> {
      FeatureTaskRuntimeAuditGeneration(
        generationOrdinal = 1,
        repositoryCheckpoint = FeatureTaskRuntimeRepositoryCheckpoint(FINGERPRINT),
        inspectedCriteria = listOf(inspection(FeatureTaskRuntimeCriterionInspectionVerdict.SATISFIED)),
        satisfiedCriterionRefs = listOf(CRITERION),
        gaps = listOf(gap()),
        repairBatch = FeatureTaskRuntimeRepairBatch("batch-1", listOf(ITEM_ID), emptyList()),
      )
    }
    assertFailsWith<IllegalArgumentException> {
      FeatureTaskRuntimeAuditGeneration(
        generationOrdinal = 1,
        repositoryCheckpoint = FeatureTaskRuntimeRepositoryCheckpoint(FINGERPRINT),
        inspectedCriteria = listOf(inspection(FeatureTaskRuntimeCriterionInspectionVerdict.GAP)),
        satisfiedCriterionRefs = listOf("AC-009"),
        gaps = listOf(gap()),
        repairBatch = FeatureTaskRuntimeRepairBatch("batch-1", listOf(ITEM_ID), emptyList()),
      )
    }
  }

  @Test
  fun `exactly one batch is active and closure retires it`() {
    val opened = generation(1, listOf(gap()))
    assertEquals("batch-1", FeatureTaskRuntimeAuditGenerationHistory(listOf(opened)).activeRepairBatch()?.batchId)

    val closed = generation(
      2,
      listOf(gap(state = FeatureTaskRuntimeAuditGapState.STILL_OPEN)),
      batchOrdinal = 1,
      dispositions = listOf(
        FeatureTaskRuntimeRepairItemDisposition(
          repairItemId = ITEM_ID,
          disposition = FeatureTaskRuntimeRepairDisposition.FIXED,
          resultEvidence = evidence(FeatureTaskRuntimeEvidence.Observation.FIX_VERIFIED),
        ),
      ),
    )
    val history = FeatureTaskRuntimeAuditGenerationHistory(listOf(opened, closed))
    assertNull(history.activeRepairBatch())
    assertTrue(closed.repairBatch.closureComplete)
  }

  @Test
  fun `a satisfied verdict needs no open gap and a blast radius record`() {
    val open = generation(1, listOf(gap()))
    assertFalse(open.satisfiedVerdictEligible)

    val resolved = generation(
      2,
      listOf(gap(state = FeatureTaskRuntimeAuditGapState.RESOLVED)),
      batchOrdinal = 1,
      blastRadius = FeatureTaskRuntimeBlastRadiusInspection(
        inspectedPaths = listOf("runtime-kotlin/runtime-application/Example.kt"),
        newlyIntroducedGapIds = emptyList(),
        evidence = evidence(FeatureTaskRuntimeEvidence.Observation.RESOLUTION_VERIFIED),
      ),
    )
    assertTrue(resolved.satisfiedVerdictEligible)
    assertFalse(
      generation(2, listOf(gap(state = FeatureTaskRuntimeAuditGapState.RESOLVED)), batchOrdinal = 1)
        .satisfiedVerdictEligible,
    )
  }

  @Test
  fun `convergence metrics are derived from the generation history`() {
    val firstPass = FeatureTaskRuntimeAuditGenerationHistory(
      listOf(
        FeatureTaskRuntimeAuditGeneration(
          generationOrdinal = 1,
          repositoryCheckpoint = FeatureTaskRuntimeRepositoryCheckpoint(FINGERPRINT),
          inspectedCriteria = listOf(inspection(FeatureTaskRuntimeCriterionInspectionVerdict.SATISFIED)),
          satisfiedCriterionRefs = listOf(CRITERION),
          gaps = emptyList(),
          repairBatch = FeatureTaskRuntimeRepairBatch("batch-1", emptyList(), emptyList()),
        ),
      ),
    ).deriveProgress(auditGapIterationCount = 0)
    assertTrue(firstPass.firstPassConvergence)
    assertEquals(0, firstPass.newGapCount)

    val recurring = FeatureTaskRuntimeAuditGenerationHistory(
      listOf(
        generation(1, listOf(gap(state = FeatureTaskRuntimeAuditGapState.NEW, recurrenceCount = 0))),
        generation(
          2,
          listOf(gap(state = FeatureTaskRuntimeAuditGapState.RECURRING, recurrenceCount = 1)),
          batchOrdinal = 2,
        ),
      ),
    ).deriveProgress(auditGapIterationCount = 2)
    assertFalse(recurring.firstPassConvergence)
    assertEquals(1, recurring.newGapCount)
    assertEquals(1, recurring.recurringGapCount)
    // Only the authorizing generation counts its batch's items as attempted, so a carried batch cannot
    // double-count the same obligation.
    assertEquals(2, recurring.attemptedRepairItemCount)
    assertEquals(0, recurring.resolvedRepairItemCount)
    assertEquals(2, recurring.auditGapIterationCount)
  }

  @Test
  fun `latest gap state and recurrence read the newest record for each identity`() {
    val history = FeatureTaskRuntimeAuditGenerationHistory(
      listOf(
        generation(1, listOf(gap(state = FeatureTaskRuntimeAuditGapState.NEW, recurrenceCount = 0))),
        generation(
          2,
          listOf(gap(state = FeatureTaskRuntimeAuditGapState.RECURRING, recurrenceCount = 3)),
          batchOrdinal = 2,
        ),
      ),
    )
    assertEquals(mapOf(GAP_ID to FeatureTaskRuntimeAuditGapState.RECURRING), history.latestGapStates())
    assertEquals(mapOf(GAP_ID to 3), history.recurrenceCounts())
  }

  @Test
  fun `a blast radius inspection must name inspected paths and bounded references`() {
    assertFailsWith<IllegalArgumentException> {
      FeatureTaskRuntimeBlastRadiusInspection(
        inspectedPaths = emptyList(),
        newlyIntroducedGapIds = emptyList(),
        evidence = evidence(FeatureTaskRuntimeEvidence.Observation.RESOLUTION_VERIFIED),
      )
    }
    assertFailsWith<IllegalArgumentException> {
      FeatureTaskRuntimeBlastRadiusInspection(
        inspectedPaths = listOf("a".repeat(300)),
        newlyIntroducedGapIds = emptyList(),
        evidence = evidence(FeatureTaskRuntimeEvidence.Observation.RESOLUTION_VERIFIED),
      )
    }
  }

  @Test
  fun `governance evidence caps its authority reference`() {
    assertFailsWith<IllegalArgumentException> { governance(authorityRef = "docs/" + "a".repeat(300)) }
    assertFailsWith<IllegalArgumentException> { governance(authorityRef = "not a reference") }
  }

  private fun generation(
    ordinal: Int,
    gaps: List<FeatureTaskRuntimeGenerationGap>,
    batchOrdinal: Int = 1,
    dispositions: List<FeatureTaskRuntimeRepairItemDisposition> = emptyList(),
    blastRadius: FeatureTaskRuntimeBlastRadiusInspection? = null,
  ): FeatureTaskRuntimeAuditGeneration {
    val open = gaps.filter { it.state.open }
    return FeatureTaskRuntimeAuditGeneration(
      generationOrdinal = ordinal,
      repositoryCheckpoint = FeatureTaskRuntimeRepositoryCheckpoint(FINGERPRINT),
      inspectedCriteria = listOf(
        inspection(
          if (open.isEmpty()) {
            FeatureTaskRuntimeCriterionInspectionVerdict.SATISFIED
          } else {
            FeatureTaskRuntimeCriterionInspectionVerdict.GAP
          },
        ),
      ),
      satisfiedCriterionRefs = if (open.isEmpty()) listOf(CRITERION) else emptyList(),
      gaps = gaps,
      repairBatch = FeatureTaskRuntimeRepairBatch(
        batchId = "batch-$batchOrdinal",
        repairItemIds = if (batchOrdinal == ordinal) open.flatMap { it.repairItemIds } else listOf(ITEM_ID),
        repairItemDispositions = dispositions,
      ),
      blastRadiusInspection = blastRadius,
    )
  }

  private fun gap(
    gapId: String = GAP_ID,
    state: FeatureTaskRuntimeAuditGapState = FeatureTaskRuntimeAuditGapState.NEW,
    recurrenceCount: Int = 0,
    repairItemIds: List<String> = listOf(ITEM_ID),
  ) = FeatureTaskRuntimeGenerationGap(
    gapId = gapId,
    acceptanceCriterionRef = CRITERION,
    acceptanceCriterionText = "Durable audit history is append-only",
    state = state,
    recurrenceCount = recurrenceCount,
    failureEvidence = evidence(FeatureTaskRuntimeEvidence.Observation.REQUIRED_BEHAVIOR_ABSENT),
    diagnosis = "The generation table is never written",
    affectedBoundary = "runtime-infra-sqlite",
    repairItemIds = repairItemIds,
  )

  private fun inspection(verdict: FeatureTaskRuntimeCriterionInspectionVerdict) =
    FeatureTaskRuntimeCriterionInspection(CRITERION, verdict)

  private fun evidence(observation: FeatureTaskRuntimeEvidence.Observation) = FeatureTaskRuntimeEvidence(
    observation = observation,
    artifactRef = "runtime-kotlin/runtime-infra-sqlite/DatabaseMigrations.kt:addFeatureTaskRuntimeAuditGenerations",
    checkRef = "AC-003",
  )

  private fun governance(authorityRef: String = "docs/skill-source-generation.md") =
    FeatureTaskRuntimeGovernanceEvidence(
      governingDecision = "The obligation is withdrawn by the goal owner",
      authorityRef = authorityRef,
      rationale = "The behavior moved to a follow-up subtask",
    )

  private companion object {
    const val CRITERION = "AC-003"
    const val GAP_ID = "ac-003-gap-1"
    const val ITEM_ID = "ac-003-gap-1-item-1"
    const val FINGERPRINT = "9f2c1ab"
  }
}
