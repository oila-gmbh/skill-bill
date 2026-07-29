package skillbill.workflow.taskruntime.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FeatureTaskRuntimeImplementationCompletionDecisionTest {
  @Test
  fun `exact task closure advances`() {
    val decision = decision(receipt(completedTaskIds = listOf("task-1", "task-2")))

    assertTrue(decision.canAdvance)
    assertIs<ImplementationCompleted>(decision.outcome)
    assertEquals(ImplementationAdvance, decision.disposition)
  }

  @Test
  fun `missing and unknown task ids remain semantic incomplete work`() {
    val missing = decision(receipt(completedTaskIds = listOf("task-1")))
    val unknown = decision(receipt(completedTaskIds = listOf("task-1", "task-2", "task-ghost")))

    assertFalse(missing.canAdvance)
    assertEquals(listOf("task-2"), assertIs<ImplementationIncomplete>(missing.outcome).missingTaskIds)
    assertEquals("task-2", missing.exactMissingTaskId())
    assertFalse(unknown.canAdvance)
    assertEquals(listOf("task-ghost"), assertIs<ImplementationIncomplete>(unknown.outcome).unknownTaskIds)
    assertEquals("unknown completed task ID: task-ghost", unknown.exactUnresolvedField())
  }

  @Test
  fun `unresolved items and actionable deviations prevent advancement`() {
    val unresolved = decision(
      receipt(
        completedTaskIds = listOf("task-1", "task-2"),
        unresolvedItems = listOf("finish persistence seam"),
      ),
    )
    val deviated = decision(
      receipt(
        completedTaskIds = listOf("task-1", "task-2"),
        deviations = listOf(FeatureTaskRuntimeDeviation("task-2", "transaction boundary remains open")),
      ),
    )

    assertEquals("finish persistence seam", unresolved.exactUnresolvedField())
    assertEquals("task-2: transaction boundary remains open", deviated.exactUnresolvedField())
    assertIs<SemanticIncompleteWorkContinuation>(unresolved.disposition)
    assertIs<SemanticIncompleteWorkContinuation>(deviated.disposition)
  }

  @Test
  fun `governed implementation outcomes select replan and decomposition dispositions`() {
    val completeReceipt = receipt(completedTaskIds = listOf("task-1", "task-2"))

    val replan = implementationCompletionDecisionFromContext(
      plan(), emptyConvergence(), completeReceipt, GovernedImplementationOutcome.Replan("plan-digest"),
    )
    val decomposition = implementationCompletionDecisionFromContext(
      plan(), emptyConvergence(), completeReceipt, GovernedImplementationOutcome.Decomposition("manifest-digest"),
    )

    assertIs<GovernedReplan>(replan.disposition)
    assertIs<GovernedDecomposition>(decomposition.disposition)
    assertFalse(replan.canAdvance)
    assertFalse(decomposition.canAdvance)
  }

  @Test
  fun `open durable obligation is reported exactly`() {
    val convergence = UnresolvedConvergence(
      implementationObligations = listOf(
        ConvergenceRecord(
          recordId = ConvergenceIdentities.record(
            "workflow-1", ConvergenceRecordKind.IMPLEMENTATION_OBLIGATION, "obligation-1", 1,
          ),
          logicalId = "obligation-1",
          kind = ConvergenceRecordKind.IMPLEMENTATION_OBLIGATION,
          status = ConvergenceStatus.OPEN,
          summary = "persist receipt",
          provenance = ConvergenceProvenance("workflow-1", 1, "implement", attempt = 1),
          evidenceDigest = "a".repeat(64),
          createdAt = "2026-01-01T00:00:00Z",
        ),
      ),
      auditRepairs = emptyList(),
      reviewBlockers = emptyList(),
    )

    val decision = implementationCompletionDecisionFromContext(
      plan(), convergence, receipt(completedTaskIds = listOf("task-1", "task-2")),
    )

    assertEquals("open durable obligation: obligation-1", decision.exactUnresolvedField())
  }

  private fun decision(receipt: FeatureTaskRuntimeImplementationReceipt): ImplementationCompletionDecision =
    implementationCompletionDecisionFromContext(plan(), emptyConvergence(), receipt)

  private fun plan() = FeatureTaskRuntimeExecutablePlan(
    mode = FeatureTaskRuntimePlanMode.DIRECT,
    tasks = listOf(
      FeatureTaskRuntimePlanTask(
        taskId = "task-1",
        description = "one",
        criterionRefs = listOf("AC-001"),
        testObligations = listOf("test one"),
      ),
      FeatureTaskRuntimePlanTask(
        taskId = "task-2",
        dependsOn = listOf("task-1"),
        description = "two",
        criterionRefs = listOf("AC-002"),
        testObligations = listOf("test two"),
      ),
    ),
    validationStrategy = listOf("focused tests"),
  )

  private fun receipt(
    completedTaskIds: List<String>,
    unresolvedItems: List<String> = emptyList(),
    deviations: List<FeatureTaskRuntimeDeviation> = emptyList(),
  ) = FeatureTaskRuntimeImplementationReceipt(
    completedTaskIds = completedTaskIds,
    changedPaths = listOf("runtime-kotlin/runtime-domain/src/main/kotlin/Receipt.kt"),
    testsExecuted = emptyList(),
    deviations = deviations,
    unresolvedItems = unresolvedItems,
    reconciliationEvidence = FeatureTaskRuntimeReconciliationEvidence(true, "tree reconciled"),
    repositoryCheckpoint = FeatureTaskRuntimeRepositoryCheckpoint("checkpoint"),
  )

  private fun emptyConvergence() = UnresolvedConvergence(
    implementationObligations = emptyList(),
    auditRepairs = emptyList(),
    reviewBlockers = emptyList(),
  )
}
