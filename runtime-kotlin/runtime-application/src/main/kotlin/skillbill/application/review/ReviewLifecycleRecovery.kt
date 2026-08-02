package skillbill.application.review

import skillbill.ports.persistence.DatabaseSessionFactory
import skillbill.ports.review.model.ReviewLifecycleComponent
import skillbill.ports.review.model.ReviewLifecycleEvent
import skillbill.ports.review.model.ReviewLifecycleEventKind
import skillbill.ports.review.model.ReviewLifecycleLedger
import skillbill.ports.review.model.ReviewProcessOutcome
import skillbill.ports.review.model.ReviewWorkerResultEnvelope

data class ReviewLifecycleRecoveredWorkerResult(
  val assignmentDigest: String,
  val workerId: String,
  val providerId: String,
  val attempt: Int,
  val resultEnvelope: ReviewWorkerResultEnvelope,
)

data class ReviewLifecycleWorkerIdentity(
  val workerId: String,
  val providerId: String,
)

data class ReviewLifecycleRecoverySnapshot(
  val reviewId: String,
  val completedResults: Map<String, ReviewLifecycleRecoveredWorkerResult>,
  val pendingAssignmentDigests: Set<String>,
  val terminalEvent: ReviewLifecycleEventKind?,
  val aggregationEvent: ReviewLifecycleEvent? = null,
  val terminalRecord: ReviewLifecycleEvent? = null,
  val attemptByAssignment: Map<String, Int> = emptyMap(),
) {
  val completedAssignmentDigests: Set<String> get() = completedResults.keys

  fun shouldLaunch(assignmentDigest: String): Boolean =
    terminalRecord == null && aggregationEvent?.eventKind != ReviewLifecycleEventKind.AGGREGATION_FAILED &&
      assignmentDigest in pendingAssignmentDigests

  fun attemptFor(assignmentDigest: String): Int = attemptByAssignment[assignmentDigest] ?: 1
}

/** Reconciles a restarted coordinator from durable events; it never infers completion from lanes. */
class ReviewLifecycleRecovery(private val database: DatabaseSessionFactory) {
  fun read(
    reviewId: String,
    selectedAssignments: Map<String, ReviewLifecycleWorkerIdentity>,
  ): ReviewLifecycleRecoverySnapshot {
    val events = database.read { unitOfWork -> unitOfWork.reviews.loadReviewLifecycleEvents(reviewId) }
    val ledger = ReviewLifecycleLedger(events)
    val terminalRecord = ledger.events.lastOrNull {
      it.component == ReviewLifecycleComponent.TERMINAL
    }
    val aggregationEvent = ledger.events.lastOrNull {
      it.eventKind == ReviewLifecycleEventKind.AGGREGATION_COMPLETED ||
        it.eventKind == ReviewLifecycleEventKind.AGGREGATION_FAILED
    }
    val latestWorkerTerminalEvents = ledger.events
      .filter { it.component == ReviewLifecycleComponent.WORKER && it.isTerminalWorkerEvent() }
      .groupBy { requireNotNull(it.assignmentDigest) }
      .values
      .map { assignmentEvents ->
        assignmentEvents.maxWithOrNull(compareBy<ReviewLifecycleEvent>({ it.attempt ?: 0 }, { it.sequence }))
          ?: error("Worker lifecycle event group cannot be empty.")
      }
    val completed = latestWorkerTerminalEvents
      .filter {
        it.eventKind == ReviewLifecycleEventKind.WORKER_COMPLETED &&
          it.processOutcome == ReviewProcessOutcome.ZERO_EXIT
      }
      .mapNotNull { event ->
        val assignmentDigest = event.assignmentDigest ?: return@mapNotNull null
        val resultEnvelope = event.resultEnvelope ?: return@mapNotNull null
        val workerId = event.workerId ?: return@mapNotNull null
        val providerId = event.providerId ?: return@mapNotNull null
        val expected = selectedAssignments[assignmentDigest] ?: return@mapNotNull null
        if (expected.workerId != workerId || expected.providerId != providerId) return@mapNotNull null
        assignmentDigest to ReviewLifecycleRecoveredWorkerResult(
          assignmentDigest = assignmentDigest,
          workerId = workerId,
          providerId = providerId,
          attempt = event.attempt ?: return@mapNotNull null,
          resultEnvelope = resultEnvelope,
        )
      }
      .toMap()
    val pending = if (terminalRecord == null && aggregationEvent?.eventKind != ReviewLifecycleEventKind.AGGREGATION_FAILED) {
      selectedAssignments.keys - completed.keys
    } else {
      emptySet()
    }
    val attemptByAssignment = selectedAssignments.keys.associateWith { assignmentDigest ->
      val latestAttempt = ledger.events
        .filter {
          it.component == ReviewLifecycleComponent.WORKER &&
            it.assignmentDigest == assignmentDigest
        }
        .mapNotNull { it.attempt }
        .maxOrNull()
        ?: 0
      if (assignmentDigest in pending) latestAttempt + 1 else latestAttempt.coerceAtLeast(1)
    }
    return ReviewLifecycleRecoverySnapshot(
      reviewId = reviewId,
      completedResults = completed,
      pendingAssignmentDigests = pending,
      terminalEvent = terminalRecord?.eventKind,
      aggregationEvent = aggregationEvent,
      terminalRecord = terminalRecord,
      attemptByAssignment = attemptByAssignment,
    )
  }

  private fun ReviewLifecycleEvent.isTerminalWorkerEvent(): Boolean = eventKind in setOf(
    ReviewLifecycleEventKind.WORKER_COMPLETED,
    ReviewLifecycleEventKind.WORKER_FAILED,
    ReviewLifecycleEventKind.WORKER_TIMED_OUT,
    ReviewLifecycleEventKind.WORKER_CANCELLED,
    ReviewLifecycleEventKind.WORKER_UNAVAILABLE,
    ReviewLifecycleEventKind.WORKER_INVALID_OUTPUT,
  )
}
