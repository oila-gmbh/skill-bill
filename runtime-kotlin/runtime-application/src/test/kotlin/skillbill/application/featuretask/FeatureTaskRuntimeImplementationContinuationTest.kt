package skillbill.application.featuretask

import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeImplementationAttempt
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeImplementationAttemptStatus
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
  fun `prior value segments accumulate and the next segment number advances`() {
    val history = listOf(
      attempt(sequenceNumber = 1, value = "segment one"),
      attempt(sequenceNumber = 2, value = "segment two"),
    )

    val continuation = assertNotNull(
      featureTaskRuntimeImplementationContinuationFrom("implement", history, obligations()),
    )

    assertEquals(3, continuation.segmentNumber)
    assertEquals(listOf("segment one", "segment two"), continuation.priorValueSegments)
    assertEquals("optional directive", continuation.latestPrompt)
  }

  @Test
  fun `the projection is byte-identical for the same durable state regardless of who rebuilds it`() {
    val history = listOf(attempt(sequenceNumber = 1, value = "segment one"))

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
      attempt(sequenceNumber = 1, value = "segment one"),
      attempt(sequenceNumber = 2, value = "segment two").copy(loopId = "audit_gap"),
    )

    val continuation = assertNotNull(
      featureTaskRuntimeImplementationContinuationFrom("implement", history, obligations()),
    )

    assertEquals(listOf("segment one"), continuation.priorValueSegments)
    assertEquals(2, continuation.segmentNumber)
  }

  @Test
  fun `the continuation directive names every prior value segment`() {
    val continuation = assertNotNull(
      featureTaskRuntimeImplementationContinuationFrom(
        "implement",
        listOf(
          attempt(sequenceNumber = 1, value = "segment one prose").copy(
            prompt = "keep going on task-2",
            failureDisposition = null,
          ),
        ),
        obligations(),
      ),
    )

    val directive = implementationContinuationDirective("implement", continuation)

    assertTrue(directive.contains("segment 2"), directive)
    listOf("segment one prose", "keep going on task-2").forEach { expected ->
      assertTrue(directive.contains(expected), "Directive must carry '$expected'; got:\n$directive")
    }
  }

  @Test
  fun `the continuation directive is empty for a different phase or no continuation`() {
    val continuation = featureTaskRuntimeImplementationContinuationFrom(
      "implement",
      listOf(attempt(sequenceNumber = 1, value = "segment one")),
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

  private fun attempt(sequenceNumber: Int, value: String): FeatureTaskRuntimeImplementationAttempt =
    FeatureTaskRuntimeImplementationAttempt(
      sequenceNumber = sequenceNumber,
      phaseId = "implement",
      attemptNumber = sequenceNumber,
      agentId = "claude",
      status = FeatureTaskRuntimeImplementationAttemptStatus.INCOMPLETE,
      recordedAt = "2026-08-04T10:0$sequenceNumber:00Z",
      value = value,
      prompt = "optional directive",
    )
}
