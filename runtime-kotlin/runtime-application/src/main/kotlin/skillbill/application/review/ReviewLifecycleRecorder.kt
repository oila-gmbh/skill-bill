package skillbill.application.review

import skillbill.application.featuretask.sha256HexUtf8
import skillbill.ports.persistence.DatabaseSessionFactory
import skillbill.ports.review.model.ReviewDeclaredSpecialistProgress
import skillbill.ports.review.model.ReviewDiagnosticReference
import skillbill.ports.review.model.ReviewDurableWorkerProgress
import skillbill.ports.review.model.ReviewLifecycleComponent
import skillbill.ports.review.model.ReviewLifecycleEvent
import skillbill.ports.review.model.ReviewLifecycleEventKind
import skillbill.ports.review.model.ReviewLifecycleEvidencePackage
import skillbill.ports.review.model.ReviewLivenessObservation
import skillbill.ports.review.model.ReviewProcessOutcome
import skillbill.ports.review.model.ReviewProviderOutputObservation
import skillbill.ports.review.model.ReviewTerminalCompletion
import skillbill.ports.review.model.ReviewWorkerLifecycleState
import skillbill.ports.review.model.ReviewWorkerResultEnvelope
import java.time.Instant

internal data class ReviewLifecycleRecord(
  val reviewId: String,
  val packetDigest: String,
  val component: ReviewLifecycleComponent,
  val eventKind: ReviewLifecycleEventKind,
  val workerId: String? = null,
  val providerId: String? = null,
  val attempt: Int? = null,
  val assignmentDigest: String? = null,
  val routedArea: String? = null,
  val state: ReviewWorkerLifecycleState? = null,
  val processOutcome: ReviewProcessOutcome? = null,
  val durableProgress: ReviewDurableWorkerProgress? = null,
  val terminalCompletion: ReviewTerminalCompletion? = null,
  val diagnostic: ReviewDiagnosticReference? = null,
  val livenessObservations: List<ReviewLivenessObservation> = emptyList(),
  val providerOutput: ReviewProviderOutputObservation? = null,
  val declaredProgress: ReviewDeclaredSpecialistProgress? = null,
  val resultEnvelope: ReviewWorkerResultEnvelope? = null,
) {
  fun eventId(): String = listOf(
    reviewId,
    eventKind.name,
    workerId.orEmpty(),
    assignmentDigest.orEmpty(),
    attempt?.toString().orEmpty(),
  ).joinToString(":").let(::sha256HexUtf8).let { "review-lifecycle-$it" }

  fun toEvent(sequence: Long, occurredAt: String) = ReviewLifecycleEvent(
    eventId = eventId(),
    reviewId = reviewId,
    sequence = sequence,
    occurredAt = occurredAt,
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
    livenessObservations = livenessObservations,
    providerOutput = providerOutput,
    declaredProgress = declaredProgress,
    durableProgress = durableProgress,
    resultEnvelope = resultEnvelope,
    terminalCompletion = terminalCompletion,
    diagnostic = diagnostic,
  )

  fun matches(event: ReviewLifecycleEvent): Boolean = toEvent(event.sequence, event.occurredAt) == event
}

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

  internal fun record(record: ReviewLifecycleRecord): ReviewLifecycleEvent = synchronized(lock) {
    val events = ledgers.getOrPut(record.reviewId) {
      database.read { unitOfWork ->
        unitOfWork.reviews.loadReviewLifecycleEvents(record.reviewId).toMutableList()
      }
    }
    events.firstOrNull { it.eventId == record.eventId() }?.let { existing ->
      require(record.matches(existing)) {
        "Lifecycle event '${existing.eventId}' was replayed with different evidence."
      }
      return@synchronized existing
    }
    val event = record.toEvent(
      sequence = (events.maxOfOrNull(ReviewLifecycleEvent::sequence) ?: 0) + 1,
      occurredAt = now(),
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
