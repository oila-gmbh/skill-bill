package skillbill.application.featuretask

import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeImplementationAttempt
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeImplementationAttemptStatus
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeReceiptCheckpoint
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeReceiptDeviation
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeReceiptReconciliation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FeatureTaskRuntimeImplementationContinuationTest {
  @Test
  fun `no prior attempt yields no continuation`() {
    assertNull(featureTaskRuntimeImplementationContinuationFrom("implement", emptyList(), obligations()))
  }

  @Test
  fun `closures from segment one stay closed and only still-open obligations reach segment three`() {
    val history = listOf(
      attempt(sequenceNumber = 1, completedTaskIds = listOf("task-1")),
      attempt(sequenceNumber = 2, completedTaskIds = listOf("task-2")),
    )

    val continuation = assertNotNull(
      featureTaskRuntimeImplementationContinuationFrom("implement", history, obligations()),
    )

    assertEquals(3, continuation.segmentNumber)
    assertEquals(listOf("task-1", "task-2"), continuation.completedTaskIds)
    assertEquals(listOf("task-3"), continuation.openObligationIds)
  }

  @Test
  fun `the projection is byte-identical for the same durable state regardless of who rebuilds it`() {
    val history = listOf(attempt(sequenceNumber = 1, completedTaskIds = listOf("task-1")))

    val inProcessRetry = featureTaskRuntimeImplementationContinuationFrom("implement", history, obligations())
    val freshProcessResume = featureTaskRuntimeImplementationContinuationFrom(
      "implement",
      history.map { FeatureTaskRuntimeImplementationAttempt.fromArtifactMap(it.toArtifactMap()) },
      obligations(),
    )

    assertEquals(inProcessRetry, freshProcessResume)
  }

  @Test
  fun `attempts under a different loop id are not mixed into the projection`() {
    val history = listOf(
      attempt(sequenceNumber = 1, completedTaskIds = listOf("task-1")),
      attempt(sequenceNumber = 2, completedTaskIds = listOf("task-2")).copy(loopId = "audit_gap"),
    )

    val continuation = assertNotNull(
      featureTaskRuntimeImplementationContinuationFrom("implement", history, obligations()),
    )

    assertEquals(listOf("task-1"), continuation.completedTaskIds)
    assertEquals(listOf("task-2", "task-3"), continuation.openObligationIds)
  }

  @Test
  fun `the continuation directive names every still-open obligation and the bounded prior receipt`() {
    val continuation = assertNotNull(
      featureTaskRuntimeImplementationContinuationFrom(
        "implement",
        listOf(
          attempt(sequenceNumber = 1, completedTaskIds = listOf("task-1")).copy(
            deviations = listOf(FeatureTaskRuntimeReceiptDeviation("task-1", "used a sibling file")),
            unresolvedItems = listOf("task-2 tests still owed"),
            reconciliationEvidence = FeatureTaskRuntimeReceiptReconciliation(true, "re-read every changed path"),
            repositoryCheckpoint = FeatureTaskRuntimeReceiptCheckpoint("abc123", "main", "feat/x"),
          ),
        ),
        obligations(),
      ),
    )

    val directive = implementationContinuationDirective("implement", continuation)

    assertTrue(directive.contains("segment 2"), directive)
    listOf(
      "task-2",
      "task-3",
      "task-1",
      "used a sibling file",
      "task-2 tests still owed",
    ).forEach { expected ->
      assertTrue(directive.contains(expected), "Directive must carry '$expected'; got:\n$directive")
    }
  }

  @Test
  fun `the continuation directive is empty for a different phase or no continuation`() {
    val continuation = featureTaskRuntimeImplementationContinuationFrom(
      "implement",
      listOf(attempt(sequenceNumber = 1, completedTaskIds = listOf("task-1"))),
      obligations(),
    )

    assertEquals("", implementationContinuationDirective("audit", continuation))
    assertEquals("", implementationContinuationDirective("implement", null))
  }

  private fun obligations(): FeatureTaskRuntimeImplementationObligations = FeatureTaskRuntimeImplementationObligations(
    plannedTaskIds = listOf("task-1", "task-2", "task-3"),
    carriedRepairItemIds = emptyList(),
    loopId = null,
  )

  private fun attempt(sequenceNumber: Int, completedTaskIds: List<String>): FeatureTaskRuntimeImplementationAttempt =
    FeatureTaskRuntimeImplementationAttempt(
      sequenceNumber = sequenceNumber,
      phaseId = "implement",
      attemptNumber = sequenceNumber,
      agentId = "claude",
      status = FeatureTaskRuntimeImplementationAttemptStatus.INCOMPLETE,
      recordedAt = "2026-08-04T10:0$sequenceNumber:00Z",
      completedTaskIds = completedTaskIds,
      changedPaths = listOf("src/Foo.kt"),
    )
}
