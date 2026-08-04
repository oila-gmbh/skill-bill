package skillbill.application.featuretask

import skillbill.ports.persistence.FeatureTaskRuntimeAuditGenerationRepository
import skillbill.ports.persistence.FeatureTaskRuntimeAuditGenerationRow
import skillbill.workflow.taskruntime.model.AUDIT_REPAIR_CONTRACT_VERSION
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditGap
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditGapState
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditGenerationHistory
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditRepairPlan
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeBlastRadiusInspection
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeEvidence
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeGovernanceEvidence
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePriorGapDisposition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairDisposition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairItem
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairItemOutcome
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairItemResult
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The append-only audit authority under the sequence that motivated it: repairs that claim to fix a defect
 * while leaving it in the tree. The gap must come back under the identity it was opened with, its recurrence
 * must climb, and no earlier generation may be rewritten to make the run look convergent.
 */
class FeatureTaskRuntimeAuditGenerationRecorderTest {
  private val generations = InMemoryAuditGenerations()

  @Test
  fun `two repairs claiming fixes leave both gaps recurring under their original identities`() {
    appendInitialAudit()
    val opened = generations.history()
    assertEquals(listOf("ac-001-gap-1", "ac-002-gap-1"), opened.latestGeneration?.gaps?.map { it.gapId })
    assertEquals(
      listOf(FeatureTaskRuntimeAuditGapState.NEW, FeatureTaskRuntimeAuditGapState.NEW),
      opened.latestGeneration?.gaps?.map { it.state },
    )

    appendRepair(itemIds = listOf(EXACT_BYTE_ITEM, CANONICAL_SCHEMA_ITEM))
    appendFollowUpAudit(
      recurringGapIds = listOf("ac-001-gap-1", "ac-002-gap-1"),
      plan = plan(EXACT_BYTE_GAP_TEXT, CANONICAL_SCHEMA_GAP_TEXT),
    )

    appendRepair(itemIds = listOf(EXACT_BYTE_ITEM, CANONICAL_SCHEMA_ITEM))
    appendFollowUpAudit(
      recurringGapIds = listOf("ac-001-gap-1", "ac-002-gap-1"),
      plan = plan(EXACT_BYTE_GAP_TEXT, CANONICAL_SCHEMA_GAP_TEXT),
    )

    val history = generations.history()
    assertEquals(
      mapOf(
        "ac-001-gap-1" to FeatureTaskRuntimeAuditGapState.RECURRING,
        "ac-002-gap-1" to FeatureTaskRuntimeAuditGapState.RECURRING,
      ),
      history.latestGapStates(),
    )
    assertEquals(mapOf("ac-001-gap-1" to 2, "ac-002-gap-1" to 2), history.recurrenceCounts())
    assertTrue(history.latestGapStates().values.all { it.open })
  }

  @Test
  fun `accepting a later plan or checkpoint leaves every earlier generation byte-identical`() {
    appendInitialAudit()
    val firstGenerationJson = generations.rows.single().generationJson

    appendRepair(itemIds = listOf(EXACT_BYTE_ITEM, CANONICAL_SCHEMA_ITEM))
    appendFollowUpAudit(
      recurringGapIds = listOf("ac-001-gap-1", "ac-002-gap-1"),
      plan = plan(EXACT_BYTE_GAP_TEXT, CANONICAL_SCHEMA_GAP_TEXT),
      fingerprint = "later-checkpoint",
    )

    assertEquals(firstGenerationJson, generations.rows.first().generationJson)
    assertEquals(CHECKPOINT, generations.rows.first().repositoryCheckpoint)
    assertEquals("later-checkpoint", generations.rows.last().repositoryCheckpoint)
    assertEquals(listOf(1, 2, 3), generations.rows.map { it.generationOrdinal })
  }

  @Test
  fun `a repair generation records dispositions against the batch it was carried`() {
    appendInitialAudit()

    appendRepair(itemIds = listOf(EXACT_BYTE_ITEM))

    val latest = assertNotNull(generations.history().latestGeneration)
    assertEquals("batch-1", latest.repairBatch.batchId)
    assertEquals(listOf(EXACT_BYTE_ITEM), latest.repairBatch.repairItemDispositions.map { it.repairItemId })
    assertEquals(
      listOf(FeatureTaskRuntimeRepairDisposition.FIXED),
      latest.repairBatch.repairItemDispositions.map { it.disposition },
    )
    assertEquals(listOf(CANONICAL_SCHEMA_ITEM), latest.repairBatch.unclosedRepairItemIds)
  }

  @Test
  fun `a governed supersession closes a carried item and an ungoverned one cannot be represented`() {
    appendInitialAudit()

    FeatureTaskRuntimeAuditGenerationRecorder.append(
      generations,
      AuditGenerationAppend(
        workflowId = WORKFLOW,
        repositoryFingerprint = CHECKPOINT,
        auditScopeCriterionRefs = SCOPE,
        repairResults = listOf(result(CANONICAL_SCHEMA_ITEM)),
        supersededRepairItems = mapOf(CANONICAL_SCHEMA_ITEM to governance()),
      ),
    )

    val disposition = generations.history().latestGeneration
      ?.repairBatch
      ?.repairItemDispositions
      ?.single { it.repairItemId == CANONICAL_SCHEMA_ITEM }
    assertEquals(FeatureTaskRuntimeRepairDisposition.SUPERSEDED, disposition?.disposition)
    assertNotNull(disposition?.governanceEvidence)
  }

  @Test
  fun `the repair closure gate names the exact unclosed repair item and rejects undeclared ones`() {
    appendInitialAudit()
    val batch = assertNotNull(generations.history().activeRepairBatch())

    val partial = FeatureTaskRuntimeAuditGenerationGates.repairClosureBlockReason(
      batch,
      reportedRepairItemIds = listOf(EXACT_BYTE_ITEM),
    )
    assertNotNull(partial)
    assertContains(partial, CANONICAL_SCHEMA_ITEM)
    assertContains(partial, "resumable")

    assertNull(
      FeatureTaskRuntimeAuditGenerationGates.repairClosureBlockReason(
        batch,
        reportedRepairItemIds = listOf(EXACT_BYTE_ITEM, CANONICAL_SCHEMA_ITEM),
      ),
    )

    val undeclared = FeatureTaskRuntimeAuditGenerationGates.repairClosureBlockReason(
      batch,
      reportedRepairItemIds = listOf(EXACT_BYTE_ITEM, CANONICAL_SCHEMA_ITEM, "ac-009-gap-1-item-1"),
    )
    assertNotNull(undeclared)
    assertContains(undeclared, "ac-009-gap-1-item-1")
  }

  @Test
  fun `partial repair re-delivers only the still-open item and never duplicates a closed one`() {
    appendInitialAudit()
    appendRepair(itemIds = listOf(EXACT_BYTE_ITEM))

    val batch = assertNotNull(generations.history().activeRepairBatch())
    assertEquals(listOf(CANONICAL_SCHEMA_ITEM), batch.unclosedRepairItemIds)
    assertNull(
      FeatureTaskRuntimeAuditGenerationGates.repairClosureBlockReason(
        batch,
        reportedRepairItemIds = listOf(CANONICAL_SCHEMA_ITEM),
      ),
    )
  }

  @Test
  fun `follow-up audit cannot claim convergence without dispositioning every carried gap`() {
    appendInitialAudit()
    val history = generations.history()

    val undispositioned = FeatureTaskRuntimeAuditGenerationGates.followUpAuditBlockReason(
      history = history,
      dispositionedGapIds = listOf("ac-001-gap-1"),
      blastRadiusInspection = blastRadius(),
      reportsGaps = false,
    )
    assertNotNull(undispositioned)
    assertContains(undispositioned, "ac-002-gap-1")
  }

  @Test
  fun `follow-up audit cannot claim convergence without a blast-radius inspection`() {
    appendInitialAudit()
    val dispositioned = listOf("ac-001-gap-1", "ac-002-gap-1")

    val missing = FeatureTaskRuntimeAuditGenerationGates.followUpAuditBlockReason(
      history = generations.history(),
      dispositionedGapIds = dispositioned,
      blastRadiusInspection = null,
      reportsGaps = false,
    )
    assertNotNull(missing)
    assertContains(missing, "blast-radius")

    assertNull(
      FeatureTaskRuntimeAuditGenerationGates.followUpAuditBlockReason(
        history = generations.history(),
        dispositionedGapIds = dispositioned,
        blastRadiusInspection = blastRadius(),
        reportsGaps = false,
      ),
    )
  }

  @Test
  fun `a gap-reporting follow-up audit owes no blast radius because it is not claiming convergence`() {
    appendInitialAudit()
    val dispositioned = listOf("ac-001-gap-1", "ac-002-gap-1")

    assertNull(
      FeatureTaskRuntimeAuditGenerationGates.followUpAuditBlockReason(
        history = generations.history(),
        dispositionedGapIds = dispositioned,
        blastRadiusInspection = null,
        reportsGaps = true,
      ),
    )
  }

  @Test
  fun `a correct closure-complete repair resolves the carried batch in one follow-up audit`() {
    appendInitialAudit()
    appendRepair(itemIds = listOf(EXACT_BYTE_ITEM, CANONICAL_SCHEMA_ITEM))

    appendFollowUpAudit(resolvedGapIds = listOf("ac-001-gap-1", "ac-002-gap-1"))

    val history = generations.history()
    assertEquals(3, history.generations.size, "no second repair generation is opened")
    assertEquals(
      mapOf(
        "ac-001-gap-1" to FeatureTaskRuntimeAuditGapState.RESOLVED,
        "ac-002-gap-1" to FeatureTaskRuntimeAuditGapState.RESOLVED,
      ),
      history.latestGapStates(),
    )
    assertNull(history.activeRepairBatch())
    assertEquals(SCOPE, history.latestGeneration?.satisfiedCriterionRefs)
  }

  @Test
  fun `a closed gap identity cannot be reopened so a later defect is a new identity`() {
    appendInitialAudit()
    appendRepair(itemIds = listOf(EXACT_BYTE_ITEM, CANONICAL_SCHEMA_ITEM))
    appendFollowUpAudit(resolvedGapIds = listOf("ac-001-gap-1", "ac-002-gap-1"))

    val reopening = assertFailsWith<IllegalArgumentException> {
      FeatureTaskRuntimeAuditGenerationHistory(
        generations.history().generations +
          FeatureTaskRuntimeAuditGenerationRecorder.build(
            generations.history(),
            AuditGenerationAppend(
              workflowId = WORKFLOW,
              repositoryFingerprint = CHECKPOINT,
              auditScopeCriterionRefs = SCOPE,
              latestPlan = plan(EXACT_BYTE_GAP_TEXT),
            ),
          ),
      )
    }
    assertContains(reopening.message.orEmpty(), "ac-001-gap-1")
  }

  @Test
  fun `convergence metrics derived from the generation history agree with the recorded batches`() {
    appendInitialAudit()
    appendRepair(itemIds = listOf(EXACT_BYTE_ITEM))
    appendFollowUpAudit(
      recurringGapIds = listOf("ac-001-gap-1", "ac-002-gap-1"),
      plan = plan(EXACT_BYTE_GAP_TEXT, CANONICAL_SCHEMA_GAP_TEXT),
    )

    val progress = generations.history().deriveProgress(auditGapIterationCount = 1)
    assertEquals(false, progress.firstPassConvergence)
    assertEquals(2, progress.newGapCount)
    assertEquals(2, progress.recurringGapCount)
    assertEquals(1, progress.resolvedRepairItemCount)
    assertEquals(1, progress.auditGapIterationCount)
  }

  @Test
  fun `a kill before the audit-plan append leaves no generation and the retry lands exactly one`() {
    // Kill-before: the settlement transaction never committed, so the authority is untouched.
    assertEquals(emptyList(), generations.rows)

    appendInitialAudit()

    assertEquals(listOf(1), generations.rows.map { it.generationOrdinal })
    assertEquals("batch-1", generations.history().activeRepairBatch()?.batchId)
  }

  @Test
  fun `a kill after the audit-plan append cannot duplicate the generation on retry`() {
    appendInitialAudit()

    // Kill-after: the row is durable but the process died before reporting. Ordinal 1 is never rewritten —
    // re-inserting it is rejected outright — and the latest generation is still the single active batch.
    val firstGeneration = generations.rows.single()
    assertFailsWith<IllegalArgumentException> { generations.append(firstGeneration) }

    assertEquals(listOf(1), generations.rows.map { it.generationOrdinal })
    assertEquals(firstGeneration.generationJson, generations.rows.single().generationJson)
    assertEquals(1, activeBatchCount())
  }

  @Test
  fun `a kill at the repair-result seam preserves exactly one active batch and the prior history`() {
    appendInitialAudit()
    val historyBefore = generations.rows.map { it.generationJson }

    appendRepair(itemIds = listOf(EXACT_BYTE_ITEM))

    assertEquals(historyBefore, generations.rows.dropLast(1).map { it.generationJson })
    assertEquals(1, activeBatchCount())
    assertEquals(listOf(CANONICAL_SCHEMA_ITEM), generations.history().activeRepairBatch()?.unclosedRepairItemIds)

    // Resume reads only durable records: the same remaining obligation is derived again, once.
    val resumed = FeatureTaskRuntimeAuditGenerationRecorder.loadHistory(generations, WORKFLOW)
    assertEquals(listOf(CANONICAL_SCHEMA_ITEM), resumed.activeRepairBatch()?.unclosedRepairItemIds)
  }

  @Test
  fun `a kill at the follow-up disposition seam preserves the complete prior history`() {
    appendInitialAudit()
    appendRepair(itemIds = listOf(EXACT_BYTE_ITEM, CANONICAL_SCHEMA_ITEM))
    val historyBefore = generations.rows.map { it.generationJson }

    appendFollowUpAudit(resolvedGapIds = listOf("ac-001-gap-1", "ac-002-gap-1"))

    assertEquals(historyBefore, generations.rows.dropLast(1).map { it.generationJson })
    assertEquals(0, activeBatchCount())

    // Resume reads only durable rows: a fresh repository seeded with exactly those rows rebuilds the same
    // history, so no in-memory carry-over is load-bearing.
    val replica = InMemoryAuditGenerations()
    generations.rows.forEach(replica::append)
    assertEquals(generations.history(), replica.history())
  }

  @Test
  fun `quarantine clears a workflow's authority so a legacy live run regenerates in band`() {
    appendInitialAudit()

    assertEquals(1, generations.quarantineAll(WORKFLOW))

    assertEquals(emptyList(), generations.history().generations)
    appendInitialAudit()
    assertEquals(listOf(1), generations.rows.map { it.generationOrdinal })
  }

  @Test
  fun `a zero-gap audit settlement persists its generation with an empty closure-complete batch`() {
    FeatureTaskRuntimeAuditGenerationRecorder.append(
      generations,
      AuditGenerationAppend(
        workflowId = WORKFLOW,
        repositoryFingerprint = CHECKPOINT,
        auditScopeCriterionRefs = SCOPE,
        auditSettlement = true,
      ),
    )

    val generation = assertNotNull(generations.history().latestGeneration)
    assertEquals(1, generation.generationOrdinal)
    assertEquals(CHECKPOINT, generation.repositoryCheckpoint.fingerprint)
    assertEquals(SCOPE, generation.inspectedCriteria.map { it.acceptanceCriterionRef })
    assertEquals(SCOPE, generation.satisfiedCriterionRefs)
    assertEquals(emptyList(), generation.gaps)
    assertEquals(emptyList(), generation.repairBatch.repairItemIds)
    assertNull(generations.history().activeRepairBatch())
  }

  @Test
  fun `first-pass convergence is derived from the zero-gap generation rather than a phase record`() {
    FeatureTaskRuntimeAuditGenerationRecorder.append(
      generations,
      AuditGenerationAppend(
        workflowId = WORKFLOW,
        repositoryFingerprint = CHECKPOINT,
        auditScopeCriterionRefs = SCOPE,
        auditSettlement = true,
      ),
    )

    val progress = generations.history().deriveProgress(auditGapIterationCount = 0)
    assertTrue(progress.firstPassConvergence)
    assertEquals(0, progress.newGapCount)
    assertEquals(0, progress.attemptedRepairItemCount)
  }

  @Test
  fun `a recurring gap re-opens its repair item as an unclosed obligation of the new batch`() {
    appendInitialAudit()
    appendRepair(itemIds = listOf(EXACT_BYTE_ITEM, CANONICAL_SCHEMA_ITEM))
    appendFollowUpAudit(
      recurringGapIds = listOf("ac-001-gap-1", "ac-002-gap-1"),
      plan = plan(EXACT_BYTE_GAP_TEXT, CANONICAL_SCHEMA_GAP_TEXT),
    )

    // The repair ids repeat because the identities are stable, so the earlier round's terminal results must
    // not read as closure for the new batch: re-entry owes both items again.
    val batch = assertNotNull(generations.history().activeRepairBatch())
    assertEquals("batch-3", batch.batchId)
    assertEquals(listOf(EXACT_BYTE_ITEM, CANONICAL_SCHEMA_ITEM), batch.repairItemIds)
    assertEquals(listOf(EXACT_BYTE_ITEM, CANONICAL_SCHEMA_ITEM), batch.unclosedRepairItemIds)
    assertEquals(emptyList(), batch.repairItemDispositions)
  }

  @Test
  fun `an audit dispositioning by omission still owes the blast-radius record before claiming convergence`() {
    appendInitialAudit()
    val carried = generations.history().latestGapStates().filterValues { it.open }.keys

    // The compact audit shape cannot carry prior_gap_dispositions, so every carried gap counts as
    // dispositioned by omission; the blast radius is then the only evidence the verdict rests on.
    val missing = FeatureTaskRuntimeAuditGenerationGates.followUpAuditBlockReason(
      history = generations.history(),
      dispositionedGapIds = carried,
      blastRadiusInspection = null,
      reportsGaps = false,
    )
    assertNotNull(missing)
    assertContains(missing, "blast-radius")

    assertNull(
      FeatureTaskRuntimeAuditGenerationGates.followUpAuditBlockReason(
        history = generations.history(),
        dispositionedGapIds = carried,
        blastRadiusInspection = blastRadius(),
        reportsGaps = false,
      ),
    )
  }

  private fun activeBatchCount(): Int = if (generations.history().activeRepairBatch() == null) 0 else 1

  private fun appendInitialAudit() {
    FeatureTaskRuntimeAuditGenerationRecorder.append(
      generations,
      AuditGenerationAppend(
        workflowId = WORKFLOW,
        repositoryFingerprint = CHECKPOINT,
        auditScopeCriterionRefs = SCOPE,
        latestPlan = plan(EXACT_BYTE_GAP_TEXT, CANONICAL_SCHEMA_GAP_TEXT),
      ),
    )
  }

  private fun appendRepair(itemIds: List<String>) {
    FeatureTaskRuntimeAuditGenerationRecorder.append(
      generations,
      AuditGenerationAppend(
        workflowId = WORKFLOW,
        repositoryFingerprint = CHECKPOINT,
        auditScopeCriterionRefs = SCOPE,
        repairResults = itemIds.map(::result),
      ),
    )
  }

  private fun appendFollowUpAudit(
    recurringGapIds: List<String> = emptyList(),
    resolvedGapIds: List<String> = emptyList(),
    plan: FeatureTaskRuntimeAuditRepairPlan? = null,
    fingerprint: String = CHECKPOINT,
  ) {
    FeatureTaskRuntimeAuditGenerationRecorder.append(
      generations,
      AuditGenerationAppend(
        workflowId = WORKFLOW,
        repositoryFingerprint = fingerprint,
        auditScopeCriterionRefs = SCOPE,
        latestPlan = plan,
        dispositions = recurringGapIds.map {
          disposition(it, FeatureTaskRuntimePriorGapDisposition.Status.RECURRING)
        } + resolvedGapIds.map { disposition(it, FeatureTaskRuntimePriorGapDisposition.Status.RESOLVED) },
        blastRadiusInspection = if (resolvedGapIds.isEmpty()) null else blastRadius(),
      ),
    )
  }

  private fun plan(vararg gapTexts: String): FeatureTaskRuntimeAuditRepairPlan =
    FeatureTaskRuntimeAuditRepairPlan(
      AUDIT_REPAIR_CONTRACT_VERSION,
      gapTexts.mapIndexed { index, text ->
        val criterion = "AC-00${index + 1}"
        val gapId = "ac-00${index + 1}-gap-1"
        FeatureTaskRuntimeAuditGap(
          gapId = gapId,
          acceptanceCriterionRef = criterion,
          acceptanceCriterionText = text,
          failureEvidence = evidence(FeatureTaskRuntimeEvidence.Observation.REQUIRED_BEHAVIOR_ABSENT, criterion),
          diagnosis = "The behavior the criterion names is absent at that boundary",
          affectedBoundary = "runtime-application",
          repairItems = listOf(
            FeatureTaskRuntimeRepairItem(
              repairItemId = "$gapId-item-1",
              intendedOutcome = "The named behavior exists at that boundary",
              implementationActions = listOf("Add the missing behavior"),
              affectedPathsOrSymbols = listOf("runtime-kotlin/runtime-application/Example.kt"),
              requiredVerification = listOf("Re-read the changed symbol"),
              dependsOn = emptyList(),
            ),
          ),
        )
      },
    )

  private fun result(repairItemId: String) = FeatureTaskRuntimeRepairItemResult(
    repairItemId = repairItemId,
    outcome = FeatureTaskRuntimeRepairItemOutcome.FIXED,
    changedPathsOrSymbols = listOf("runtime-kotlin/runtime-application/Example.kt"),
    executedVerification = listOf("Re-read the changed symbol"),
    resultEvidence = evidence(
      FeatureTaskRuntimeEvidence.Observation.FIX_VERIFIED,
      "AC-00${repairItemId.substringAfter("ac-00").take(1)}",
    ),
  )

  private fun disposition(gapId: String, status: FeatureTaskRuntimePriorGapDisposition.Status) =
    FeatureTaskRuntimePriorGapDisposition(
      gapId = gapId,
      status = status,
      evidence = evidence(
        when (status) {
          FeatureTaskRuntimePriorGapDisposition.Status.RESOLVED ->
            FeatureTaskRuntimeEvidence.Observation.RESOLUTION_VERIFIED
          FeatureTaskRuntimePriorGapDisposition.Status.RECURRING ->
            FeatureTaskRuntimeEvidence.Observation.RECURRENCE_VERIFIED
        },
        "AC-00${gapId.substringAfter("ac-00").take(1)}",
      ),
    )

  private fun blastRadius() = FeatureTaskRuntimeBlastRadiusInspection(
    inspectedPaths = listOf("runtime-kotlin/runtime-application/Example.kt"),
    newlyIntroducedGapIds = emptyList(),
    evidence = evidence(FeatureTaskRuntimeEvidence.Observation.RESOLUTION_VERIFIED, "AC-001"),
  )

  private fun governance() = FeatureTaskRuntimeGovernanceEvidence(
    governingDecision = "The goal owner withdrew this obligation",
    authorityRef = "docs/skill-source-generation.md",
    rationale = "The behavior moved to a follow-up subtask",
  )

  private fun evidence(observation: FeatureTaskRuntimeEvidence.Observation, checkRef: String) =
    FeatureTaskRuntimeEvidence(
      observation = observation,
      artifactRef = "runtime-kotlin/runtime-application/Example.kt:Example",
      checkRef = checkRef,
    )

  private class InMemoryAuditGenerations : FeatureTaskRuntimeAuditGenerationRepository {
    val rows = mutableListOf<FeatureTaskRuntimeAuditGenerationRow>()

    override fun append(row: FeatureTaskRuntimeAuditGenerationRow) {
      require(rows.none { it.workflowId == row.workflowId && it.generationOrdinal == row.generationOrdinal }) {
        "generation ${row.generationOrdinal} already exists for ${row.workflowId}"
      }
      rows += row
    }

    override fun listOrdered(workflowId: String): List<FeatureTaskRuntimeAuditGenerationRow> =
      rows.filter { it.workflowId == workflowId }.sortedBy { it.generationOrdinal }

    override fun quarantineAll(workflowId: String): Int {
      val removed = rows.count { it.workflowId == workflowId }
      rows.removeAll { it.workflowId == workflowId }
      return removed
    }

    fun history(): FeatureTaskRuntimeAuditGenerationHistory =
      FeatureTaskRuntimeAuditGenerationRecorder.loadHistory(this, WORKFLOW)
  }

  private companion object {
    const val WORKFLOW = "wf-audit-generation-recorder"
    const val CHECKPOINT = "9f2c1ab"
    val SCOPE = listOf("AC-001", "AC-002")
    const val EXACT_BYTE_GAP_TEXT = "The migration is appended by name and applies exactly once"
    const val CANONICAL_SCHEMA_GAP_TEXT = "The canonical schema pins its contract version to the constant"
    const val EXACT_BYTE_ITEM = "ac-001-gap-1-item-1"
    const val CANONICAL_SCHEMA_ITEM = "ac-002-gap-1-item-1"
  }
}
