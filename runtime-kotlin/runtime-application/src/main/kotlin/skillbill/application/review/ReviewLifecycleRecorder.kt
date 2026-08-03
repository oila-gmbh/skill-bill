package skillbill.application.review

import skillbill.application.featuretask.sha256HexUtf8
import skillbill.ports.persistence.DatabaseSessionFactory
import skillbill.ports.persistence.UnitOfWork
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
  val waveNumber: Int? = null,
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
    waveNumber = waveNumber,
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

  fun matches(event: ReviewLifecycleEvent): Boolean {
    val generated = toEvent(event.sequence, event.occurredAt)
    return generated == event ||
      (waveNumber != null && event.waveNumber == null && generated.copy(waveNumber = null) == event)
  }
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

  /**
   * Appends a boundary's evidence in one repository transaction so cancellation cannot expose a
   * partial terminal set.
   */
  internal fun recordAll(records: List<ReviewLifecycleRecord>): List<ReviewLifecycleEvent> = synchronized(lock) {
    if (records.isEmpty()) return@synchronized emptyList()
    val events = ledgers.getOrPut(records.first().reviewId) {
      database.read { unitOfWork ->
        unitOfWork.reviews.loadReviewLifecycleEvents(records.first().reviewId).toMutableList()
      }
    }
    require(records.all { it.reviewId == records.first().reviewId }) {
      "A lifecycle boundary batch must belong to one review."
    }
    val known = events.associateBy(ReviewLifecycleEvent::eventId).toMutableMap()
    val pending = mutableListOf<ReviewLifecycleEvent>()
    records.forEach { record ->
      known[record.eventId()]?.let { existing ->
        require(record.matches(existing)) {
          "Lifecycle event '${existing.eventId}' was replayed with different evidence."
        }
        return@forEach
      }
      val event = record.toEvent(
        sequence = (known.values.maxOfOrNull(ReviewLifecycleEvent::sequence) ?: 0) + 1,
        occurredAt = now(),
      )
      known[event.eventId] = event
      pending += event
    }
    if (pending.isNotEmpty()) {
      database.transaction { unitOfWork ->
        pending.forEach { event -> unitOfWork.reviews.appendReviewLifecycleEvent(event) }
      }
      events += pending
    }
    pending
  }

  /**
   * Appends a lifecycle boundary and persists its derived projection in the same repository
   * transaction. Recovery must never observe the boundary without the classification it explains.
   */
  internal fun recordAllAndPersist(
    records: List<ReviewLifecycleRecord>,
    persist: (UnitOfWork, List<ReviewLifecycleEvent>) -> Unit,
  ): List<ReviewLifecycleEvent> = synchronized(lock) {
    require(records.isNotEmpty()) { "A persisted lifecycle boundary cannot be empty." }
    val events = ledgers.getOrPut(records.first().reviewId) {
      database.read { unitOfWork ->
        unitOfWork.reviews.loadReviewLifecycleEvents(records.first().reviewId).toMutableList()
      }
    }
    require(records.all { it.reviewId == records.first().reviewId }) {
      "A lifecycle boundary batch must belong to one review."
    }
    val known = events.associateBy(ReviewLifecycleEvent::eventId).toMutableMap()
    val pending = mutableListOf<ReviewLifecycleEvent>()
    records.forEach { record ->
      known[record.eventId()]?.let { existing ->
        require(record.matches(existing)) {
          "Lifecycle event '${existing.eventId}' was replayed with different evidence."
        }
        return@forEach
      }
      val event = record.toEvent(
        sequence = (known.values.maxOfOrNull(ReviewLifecycleEvent::sequence) ?: 0) + 1,
        occurredAt = now(),
      )
      known[event.eventId] = event
      pending += event
    }
    database.transaction { unitOfWork ->
      pending.forEach { event -> unitOfWork.reviews.appendReviewLifecycleEvent(event) }
      persist(unitOfWork, known.values.sortedBy(ReviewLifecycleEvent::sequence))
    }
    events += pending
    pending
  }

  fun evidence(reviewId: String): ReviewLifecycleEvidencePackage = synchronized(lock) {
    val events = ledgers[reviewId] ?: database.read { unitOfWork ->
      unitOfWork.reviews.loadReviewLifecycleEvents(reviewId)
    }
    require(events.isNotEmpty()) { "No lifecycle evidence exists for review '$reviewId'." }
    ReviewLifecycleEvidencePackage(reviewId, events.first().packetDigest, events)
  }
}
