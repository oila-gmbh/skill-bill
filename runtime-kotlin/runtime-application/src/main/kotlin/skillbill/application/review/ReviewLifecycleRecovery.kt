package skillbill.application.review

import skillbill.ports.persistence.DatabaseSessionFactory
import skillbill.ports.review.model.DelegatedReviewTerminalClassification
import skillbill.ports.review.model.DelegatedReviewLifecycleSnapshot
import skillbill.ports.review.model.ReviewLifecycleComponent
import skillbill.ports.review.model.ReviewLifecycleEvent
import skillbill.ports.review.model.ReviewLifecycleEventKind
import skillbill.ports.review.model.ReviewLifecycleLedger
import skillbill.ports.review.model.ReviewProcessOutcome
import skillbill.ports.review.model.ReviewWorkerResultEnvelope
import skillbill.review.plan.DelegatedReviewWave

internal data class ReviewLifecycleRecoveredWorkerResult(
  val assignmentDigest: String,
  val workerId: String,
  val providerId: String,
  val attempt: Int,
  val resultEnvelope: ReviewWorkerResultEnvelope,
)

internal data class ReviewLifecycleWorkerIdentity(
  val workerId: String,
  val providerId: String,
)

internal data class ReviewLifecycleRecoverySnapshot(
  val reviewId: String,
  val completedResults: Map<String, ReviewLifecycleRecoveredWorkerResult>,
  val pendingAssignmentDigests: Set<String>,
  val terminalEvent: ReviewLifecycleEventKind?,
  val aggregationEvent: ReviewLifecycleEvent? = null,
  val terminalRecord: ReviewLifecycleEvent? = null,
  val terminalClassification: DelegatedReviewTerminalClassification? = null,
  val persistedProjection: DelegatedReviewLifecycleSnapshot? = null,
  val attemptByAssignment: Map<String, Int> = emptyMap(),
  val actualWaves: List<DelegatedReviewWave> = emptyList(),
) {
  val completedAssignmentDigests: Set<String> get() = completedResults.keys

  fun shouldLaunch(assignmentDigest: String): Boolean =
    terminalRecord == null && aggregationEvent?.eventKind != ReviewLifecycleEventKind.AGGREGATION_FAILED &&
      assignmentDigest in pendingAssignmentDigests

  fun attemptFor(assignmentDigest: String): Int = attemptByAssignment[assignmentDigest] ?: 1
}

/** Reconciles a restarted coordinator from durable events; it never infers completion from lanes. */
class ReviewLifecycleRecovery(private val database: DatabaseSessionFactory) {
  internal fun read(
    reviewId: String,
    selectedAssignments: Map<String, ReviewLifecycleWorkerIdentity>,
  ): ReviewLifecycleRecoverySnapshot {
    val (events, persistedProjection) = database.read { unitOfWork ->
      unitOfWork.reviews.loadReviewLifecycleEvents(reviewId) to
        unitOfWork.reviews.loadDelegatedReviewLifecycle(reviewId)
    }
    val ledger = ReviewLifecycleLedger(events)
    val terminalRecord = ledger.events.lastOrNull { it.component == ReviewLifecycleComponent.TERMINAL }
    val aggregationEvent = latestAggregationEvent(ledger.events)
    val completed = completedResults(ledger.events, selectedAssignments)
    val pending = pendingAssignments(selectedAssignments.keys, completed.keys, terminalRecord, aggregationEvent)
    val attempts = attemptsByAssignment(ledger.events, selectedAssignments.keys, pending)
    return ReviewLifecycleRecoverySnapshot(
      reviewId = reviewId,
      completedResults = completed,
      pendingAssignmentDigests = pending,
      terminalEvent = terminalRecord?.eventKind,
      aggregationEvent = aggregationEvent,
      terminalRecord = terminalRecord,
      terminalClassification = persistedProjection?.terminalClassification,
      persistedProjection = persistedProjection,
      attemptByAssignment = attempts,
      actualWaves = actualWaves(ledger.events, selectedAssignments),
    )
  }

  private fun latestAggregationEvent(events: List<ReviewLifecycleEvent>) = events.lastOrNull {
    it.eventKind == ReviewLifecycleEventKind.AGGREGATION_COMPLETED ||
      it.eventKind == ReviewLifecycleEventKind.AGGREGATION_FAILED
  }

  private fun completedResults(
    events: List<ReviewLifecycleEvent>,
    selectedAssignments: Map<String, ReviewLifecycleWorkerIdentity>,
  ): Map<String, ReviewLifecycleRecoveredWorkerResult> = events
    .filter { it.component == ReviewLifecycleComponent.WORKER && it.isTerminalWorkerEvent() }
    .groupBy { requireNotNull(it.assignmentDigest) }
    .values
    .map { assignmentEvents ->
      assignmentEvents.maxWithOrNull(compareBy<ReviewLifecycleEvent>({ it.attempt ?: 0 }, { it.sequence }))
        ?: error("Worker lifecycle event group cannot be empty.")
    }
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

  private fun pendingAssignments(
    selectedAssignments: Set<String>,
    completedAssignments: Set<String>,
    terminalRecord: ReviewLifecycleEvent?,
    aggregationEvent: ReviewLifecycleEvent?,
  ): Set<String> =
    if (terminalRecord == null && aggregationEvent?.eventKind != ReviewLifecycleEventKind.AGGREGATION_FAILED) {
      selectedAssignments - completedAssignments
    } else {
      emptySet()
    }

  private fun attemptsByAssignment(
    events: List<ReviewLifecycleEvent>,
    selectedAssignments: Set<String>,
    pendingAssignments: Set<String>,
  ): Map<String, Int> = selectedAssignments.associateWith { assignmentDigest ->
    val latestAttempt = events
      .filter {
        it.component == ReviewLifecycleComponent.WORKER &&
          it.assignmentDigest == assignmentDigest
      }
      .mapNotNull { it.attempt }
      .maxOrNull()
      ?: 0
    if (assignmentDigest in pendingAssignments) latestAttempt + 1 else latestAttempt.coerceAtLeast(1)
  }

  private fun actualWaves(
    events: List<ReviewLifecycleEvent>,
    selectedAssignments: Map<String, ReviewLifecycleWorkerIdentity>,
  ): List<DelegatedReviewWave> = events
    .asSequence()
    .filter { it.component == ReviewLifecycleComponent.WORKER }
    .filter { it.eventKind == ReviewLifecycleEventKind.WORKER_LAUNCHED }
    .filter { it.waveNumber != null }
    .filter { event ->
      val digest = event.assignmentDigest
      digest != null && digest in selectedAssignments
    }
    .groupBy { requireNotNull(it.waveNumber) }
    .toSortedMap()
    .map { (number, waveEvents) ->
      DelegatedReviewWave(number, waveEvents.mapNotNull { it.assignmentDigest }.distinct())
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
