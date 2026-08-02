package skillbill.application.review

import skillbill.ports.persistence.DatabaseSessionFactory
import skillbill.ports.review.model.ReviewLifecycleEventKind
import skillbill.ports.review.model.ReviewLifecycleLedger
import skillbill.ports.review.model.ReviewWorkerResultEnvelope

data class ReviewLifecycleRecoveredWorkerResult(
  val assignmentDigest: String,
  val workerId: String,
  val providerId: String,
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
) {
  val completedAssignmentDigests: Set<String> get() = completedResults.keys

  fun shouldLaunch(assignmentDigest: String): Boolean = assignmentDigest in pendingAssignmentDigests
}

/** Reconciles a restarted coordinator from durable events; it never infers completion from lanes. */
class ReviewLifecycleRecovery(private val database: DatabaseSessionFactory) {
  fun read(
    reviewId: String,
    selectedAssignments: Map<String, ReviewLifecycleWorkerIdentity>,
  ): ReviewLifecycleRecoverySnapshot {
    val events = database.read { unitOfWork -> unitOfWork.reviews.loadReviewLifecycleEvents(reviewId) }
    val ledger = ReviewLifecycleLedger(events)
    val completed = ledger.events
      .filter {
        it.eventKind == ReviewLifecycleEventKind.WORKER_COMPLETED &&
          it.processOutcome == skillbill.ports.review.model.ReviewProcessOutcome.ZERO_EXIT
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
          resultEnvelope = resultEnvelope,
        )
      }
      .toMap()
    val terminal = ledger.events.lastOrNull {
      it.component == skillbill.ports.review.model.ReviewLifecycleComponent.TERMINAL
    }?.eventKind
    return ReviewLifecycleRecoverySnapshot(
      reviewId = reviewId,
      completedResults = completed,
      pendingAssignmentDigests = selectedAssignments.keys - completed.keys,
      terminalEvent = terminal,
    )
  }
}
