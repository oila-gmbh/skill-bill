package skillbill.application.goalrunner

import skillbill.review.ParallelReviewMerger
import skillbill.review.model.ParallelReviewLaneResult
import skillbill.review.model.ParallelReviewRawFinding
import skillbill.review.model.ParallelReviewSeverity
import skillbill.review.model.ReviewClaimVerdict
import skillbill.review.model.ReviewFindingCitation
import skillbill.review.model.ReviewFindingVerdict
import skillbill.review.model.ReviewStage
import skillbill.workflow.model.ValidationDepth
import skillbill.workflow.taskruntime.FeatureTaskRuntimeHandoffProjectionValidator
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffProjectionBudget
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffProjectionInputs
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffProjectionValue
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffPromptVisibility
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffSourceRef
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepositoryCheckpoint
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepositoryCheckpointPolicy
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeResolvedUpstreamOutputs
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRunInvariants
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.model.GoalSubtaskBlockerDispositionVerdict
import skillbill.workflow.taskruntime.model.PhaseHandoffProjectionDeclaration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class VerdictAwareRegisterAndConsumersTest {
  @Test
  fun `a refuted finding stays in the register, leaves the implement_fix projection, and stores claim_verdict`() {
    val citation = ReviewFindingCitation("Auth.kt", 10)
    val recordedVerdicts = listOf(
      ReviewFindingVerdict(
        stage = ReviewStage.VERIFICATION,
        findingRef = "F-001",
        claimVerdict = ReviewClaimVerdict.REFUTED,
        citations = listOf(citation),
        recordedAt = "2026-08-14T00:00:00Z",
      ),
    )
    val assembled = ParallelReviewMerger.withRecordedVerdicts(tokenLoggedLaneMerge(), recordedVerdicts)
    assertTrue(assembled.formattedOutput.contains("[F-001]"))
    assertTrue(assembled.formattedOutput.contains("Token logged"))
    assertTrue(assembled.formattedOutput.contains("Refuted"))
    assertTrue(assembled.formattedOutput.contains("claim_verdict=refuted"))

    val output = refutedFindingProducerOutput()
    val compact = GoalSubtaskReviewSummaryReducer.fromOutput(output, recordedVerdicts)
    val outcome = GoalSubtaskReviewSummaryReducer.outcomeFor(output, compact)
    assertTrue(compact.isEmpty())
    assertEquals(FeatureTaskRuntimeVerdict.APPROVED, outcome.verdict)
    assertEquals(0, outcome.unresolvedFindingCount)

    val ledger = GoalSubtaskReviewSummaryReducer.unaddressedFindings(
      output,
      "SKILL-191",
      6,
      "workflow",
      1,
      recordedVerdicts,
    )
    assertEquals(1, ledger.size)
    assertEquals(ReviewClaimVerdict.REFUTED, ledger.single().claimVerdict)
    assertEquals(listOf(citation), ledger.single().citations)

    val projected = assertIs<FeatureTaskRuntimeHandoffProjectionValue.TextList>(
      implementFixReviewRepairEnvelope(recordedVerdicts).projections.single().fields.first().value,
    )
    assertTrue(projected.items.none { it.contains("F-001") })
    assertFalse(projected.items.any { it.contains("Token logged") })
  }

  @Test
  fun `a refuted Blocker on a remediation pass becomes superseded with verification evidence`() {
    val location = "Auth.kt:10"
    val summary = "Token logged"
    val citation = ReviewFindingCitation("Auth.kt", 10)
    val recordedVerdicts = listOf(
      ReviewFindingVerdict(
        stage = ReviewStage.VERIFICATION,
        findingRef = "F-007",
        claimVerdict = ReviewClaimVerdict.REFUTED,
        citations = listOf(citation),
        recordedAt = "2026-08-14T00:00:00Z",
      ),
    )
    val prior = GoalSubtaskReviewSummaryReducer.unaddressedFindings(
      mapOf(
        "produced_outputs" to mapOf(
          "findings" to listOf(
            mapOf(
              "id" to "F-001",
              "severity" to "blocker",
              "location" to location,
              "message" to summary,
            ),
          ),
        ),
      ),
      "SKILL-191",
      6,
      "workflow",
      1,
    )
    val current = GoalSubtaskReviewSummaryReducer.unaddressedFindings(
      mapOf(
        "produced_outputs" to mapOf(
          "findings" to listOf(
            mapOf(
              "id" to "F-007",
              "severity" to "blocker",
              "location" to location,
              "message" to summary,
            ),
          ),
        ),
      ),
      "SKILL-191",
      6,
      "workflow",
      2,
      recordedVerdicts,
    )
    val superseded = GoalSubtaskReviewSummaryReducer.refutedBlockerSupersedes(prior, current, recordedVerdicts)
    assertEquals("F-001", superseded.single().findingId)
    assertEquals(GoalSubtaskBlockerDispositionVerdict.SUPERSEDED, superseded.single().verdict)
    assertEquals(listOf("Auth.kt:10"), superseded.single().evidence)
  }

  private fun tokenLoggedLaneMerge() = ParallelReviewMerger.merge(
    ParallelReviewLaneResult(
      "claude",
      listOf(
        ParallelReviewRawFinding(
          ParallelReviewSeverity.BLOCKER,
          "High",
          "Auth.kt:10",
          "Token logged",
          repositoryPath = "Auth.kt",
          line = 10,
        ),
      ),
    ),
    ParallelReviewLaneResult("codex", emptyList()),
  )

  private fun refutedFindingProducerOutput(): Map<String, Any?> = mapOf(
    "verdict" to FeatureTaskRuntimeVerdict.CHANGES_REQUESTED.wireValue,
    "produced_outputs" to mapOf(
      "review_run_id" to "rvw-191",
      "findings" to listOf(
        mapOf(
          "id" to "F-001",
          "finding_id" to "F-001",
          "severity" to "blocker",
          "location" to "Auth.kt:10",
          "message" to "Token logged",
        ),
      ),
    ),
  )

  private fun implementFixReviewRepairEnvelope(
    recordedVerdicts: List<ReviewFindingVerdict>,
  ) = FeatureTaskRuntimeHandoffProjectionValidator.validate(
    FeatureTaskRuntimeHandoffProjectionInputs(
      consumerPhaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT_FIX,
      declarations = listOf(
        PhaseHandoffProjectionDeclaration(
          consumerPhaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT_FIX,
          sourceRef = FeatureTaskRuntimeHandoffSourceRef.UpstreamPhaseOutput(
            FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW,
          ),
          projectionName = "review_repair_request",
          projectionContractId =
            FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.REVIEW_REPAIR_REQUEST,
          projectionContractVersion = "0.1",
          promptVisibility = FeatureTaskRuntimeHandoffPromptVisibility.PROMPT_VISIBLE,
          budget = FeatureTaskRuntimeHandoffProjectionBudget.PHASE_RECEIPT,
          declaredFieldNames = listOf("unresolved_blocker_findings", "repository_checkpoint"),
          checkpointPolicy = FeatureTaskRuntimeRepositoryCheckpointPolicy.MUST_MATCH,
          required = true,
        ),
      ),
      resolvedUpstream = FeatureTaskRuntimeResolvedUpstreamOutputs(
        mapOf(
          FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW to FeatureTaskRuntimePhaseOutput(
            FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW,
            1,
            """{"produced_outputs":{"findings":[{"finding_id":"F-001","severity":"Blocker",""" +
              """"location":"Auth.kt:10","message":"Token logged",""" +
              """"citations":[{"path":"Auth.kt","line":10}]}]}}""",
          ),
        ),
      ),
      runInvariants = FeatureTaskRuntimeRunInvariants(
        specReference = ".feature-specs/SKILL-191/spec.md",
        acceptanceCriteria = listOf("AC-005"),
        mandatesAndOverrides = emptyList(),
      ),
      resolvedCheckpoint = FeatureTaskRuntimeRepositoryCheckpoint("reviewed-tree"),
      expectedCheckpoint = FeatureTaskRuntimeRepositoryCheckpoint("reviewed-tree"),
      workflowId = "wftr-1",
      validationDepth = ValidationDepth.DEFAULT,
      recordedFindingVerdicts = recordedVerdicts,
    ),
  )
}
