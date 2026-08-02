package skillbill.application.review

import skillbill.ports.persistence.DatabaseSessionFactory
import skillbill.ports.review.model.ReviewLifecycleEventKind
import skillbill.ports.review.model.ReviewLifecycleLedger

data class ReviewLifecycleRecoverySnapshot(
  val reviewId: String,
  val completedAssignmentDigests: Set<String>,
  val pendingAssignmentDigests: Set<String>,
  val terminalEvent: ReviewLifecycleEventKind?,
) {
  fun shouldLaunch(assignmentDigest: String): Boolean = assignmentDigest in pendingAssignmentDigests
}

/** Reconciles a restarted coordinator from durable events; it never infers completion from lanes. */
class ReviewLifecycleRecovery(private val database: DatabaseSessionFactory) {
  fun read(reviewId: String, selectedAssignmentDigests: Set<String>): ReviewLifecycleRecoverySnapshot {
    val events = database.read { unitOfWork -> unitOfWork.reviews.loadReviewLifecycleEvents(reviewId) }
    val ledger = ReviewLifecycleLedger(events)
    val completed = ledger.events
      .filter { it.eventKind == ReviewLifecycleEventKind.WORKER_COMPLETED }
      .mapNotNull { it.assignmentDigest }
      .toSet()
    val terminal = ledger.events.lastOrNull {
      it.component == skillbill.ports.review.model.ReviewLifecycleComponent.TERMINAL
    }?.eventKind
    return ReviewLifecycleRecoverySnapshot(
      reviewId = reviewId,
      completedAssignmentDigests = completed,
      pendingAssignmentDigests = selectedAssignmentDigests - completed,
      terminalEvent = terminal,
    )
  }
}
