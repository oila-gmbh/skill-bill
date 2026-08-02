package skillbill.application.review

import skillbill.ports.persistence.DatabaseSessionFactory
import skillbill.ports.review.model.ReviewDiagnosticReference
import skillbill.ports.review.model.ReviewDurableWorkerProgress
import skillbill.ports.review.model.ReviewLifecycleComponent
import skillbill.ports.review.model.ReviewLifecycleEvent
import skillbill.ports.review.model.ReviewLifecycleEventKind
import skillbill.ports.review.model.ReviewLifecycleEvidencePackage
import skillbill.ports.review.model.ReviewProcessOutcome
import skillbill.ports.review.model.ReviewTerminalCompletion
import skillbill.ports.review.model.ReviewWorkerLifecycleState
import java.time.Instant

/**
 * Serializes lifecycle transitions before ownership moves to the next review boundary. The
 * repository remains authoritative; the in-memory sequence only makes concurrent lane writes
 * deterministic within one coordinator process.
 */
class ReviewLifecycleRecorder(
  private val database: DatabaseSessionFactory,
  private val now: () -> String = { Instant.now().toString() },
) {
  private val lock = Any()
  private val ledgers = mutableMapOf<String, MutableList<ReviewLifecycleEvent>>()

  fun timestamp(): String = now()

  fun record(
    reviewId: String,
    packetDigest: String,
    component: ReviewLifecycleComponent,
    eventKind: ReviewLifecycleEventKind,
    workerId: String? = null,
    providerId: String? = null,
    attempt: Int? = null,
    assignmentDigest: String? = null,
    routedArea: String? = null,
    state: ReviewWorkerLifecycleState? = null,
    processOutcome: ReviewProcessOutcome? = null,
    durableProgress: ReviewDurableWorkerProgress? = null,
    terminalCompletion: ReviewTerminalCompletion? = null,
    diagnostic: ReviewDiagnosticReference? = null,
  ): ReviewLifecycleEvent = synchronized(lock) {
    val events = ledgers.getOrPut(reviewId) {
      database.read { unitOfWork -> unitOfWork.reviews.loadReviewLifecycleEvents(reviewId).toMutableList() }
    }
    val eventId = listOf(
      reviewId,
      eventKind.name,
      workerId.orEmpty(),
      assignmentDigest.orEmpty(),
      attempt?.toString().orEmpty(),
    ).joinToString(":")
    events.firstOrNull { it.eventId == eventId }?.let { existing ->
      require(
        existing.component == component &&
          existing.eventKind == eventKind &&
          existing.packetDigest == packetDigest &&
          existing.workerId == workerId &&
          existing.providerId == providerId &&
          existing.attempt == attempt &&
          existing.assignmentDigest == assignmentDigest &&
          existing.routedArea == routedArea &&
          existing.state == state &&
          existing.processOutcome == processOutcome &&
          existing.durableProgress == durableProgress &&
          existing.terminalCompletion == terminalCompletion &&
          existing.diagnostic == diagnostic,
      ) { "Lifecycle event '$eventId' was replayed with different evidence." }
      return@synchronized existing
    }
    val event = ReviewLifecycleEvent(
      eventId = eventId,
      reviewId = reviewId,
      sequence = (events.maxOfOrNull(ReviewLifecycleEvent::sequence) ?: 0) + 1,
      occurredAt = now(),
      component = component,
      eventKind = eventKind,
      packetDigest = packetDigest,
      workerId = workerId,
      providerId = providerId,
      attempt = attempt,
      assignmentDigest = assignmentDigest,
      routedArea = routedArea,
      state = state,
      processOutcome = processOutcome,
      durableProgress = durableProgress,
      terminalCompletion = terminalCompletion,
      diagnostic = diagnostic,
    )
    database.transaction { unitOfWork -> unitOfWork.reviews.appendReviewLifecycleEvent(event) }
    events += event
    event
  }

  fun evidence(reviewId: String): ReviewLifecycleEvidencePackage = synchronized(lock) {
    val events = ledgers[reviewId] ?: database.read { unitOfWork ->
      unitOfWork.reviews.loadReviewLifecycleEvents(reviewId)
    }
    require(events.isNotEmpty()) { "No lifecycle evidence exists for review '$reviewId'." }
    ReviewLifecycleEvidencePackage(reviewId, events.first().packetDigest, events)
  }
}
