package skillbill.review

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals

class DelegatedReviewAggregationGateTest {
  private val assignment = DelegatedReviewAssignmentOwnership(
    workerId = "worker-a",
    providerId = "codex",
    attempt = 1,
    assignmentDigest = "a".repeat(64),
    area = "security",
  )

  @Test
  fun `aggregation requires exact selected ownership and declared area coverage`() {
    val result = DelegatedReviewAggregationGate.validate(
      DelegatedReviewAggregationRequest(
        selectedAssignments = listOf(assignment),
        declaredAreas = setOf("security"),
        workerResults = listOf(
          DelegatedReviewWorkerResult(
            identity = assignment,
            state = DelegatedReviewAggregationState.COMPLETED,
            findings = emptyList(),
          ),
        ),
      ),
    )
    assertEquals(1, result.assignments.size)
  }

  @Test
  fun `aggregation rejects missing result duplicate ownership identity drift and invalid state`() {
    val base = DelegatedReviewAggregationRequest(
      selectedAssignments = listOf(assignment),
      declaredAreas = setOf("security"),
      workerResults = emptyList(),
    )
    assertFailsWith<IllegalArgumentException> { DelegatedReviewAggregationGate.validate(base) }
    val otherAttempt = assignment.copy(attempt = 2)
    assertFailsWith<IllegalArgumentException> {
      DelegatedReviewAggregationGate.validate(
        base.copy(
          workerResults = listOf(
            DelegatedReviewWorkerResult(otherAttempt, DelegatedReviewAggregationState.COMPLETED, emptyList()),
          ),
        ),
      )
    }
    assertFailsWith<IllegalArgumentException> {
      DelegatedReviewAggregationGate.validate(
        base.copy(
          workerResults = listOf(
            DelegatedReviewWorkerResult(assignment, DelegatedReviewAggregationState.TIMED_OUT, emptyList()),
          ),
        ),
      )
    }
    assertFailsWith<IllegalArgumentException> {
      val duplicateArea = assignment.copy(workerId = "worker-b", assignmentDigest = "b".repeat(64))
      DelegatedReviewAggregationGate.validate(
        base.copy(
          selectedAssignments = listOf(assignment, duplicateArea),
          declaredAreas = setOf("security"),
          workerResults = listOf(
            DelegatedReviewWorkerResult(assignment, DelegatedReviewAggregationState.COMPLETED, emptyList()),
            DelegatedReviewWorkerResult(duplicateArea, DelegatedReviewAggregationState.COMPLETED, emptyList()),
          ),
        ),
      )
    }
  }
}
