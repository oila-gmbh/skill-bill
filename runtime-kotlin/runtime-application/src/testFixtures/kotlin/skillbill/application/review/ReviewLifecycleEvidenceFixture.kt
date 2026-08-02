package skillbill.application.review

import skillbill.ports.review.model.ReviewLifecycleEvent
import skillbill.ports.review.model.ReviewLifecycleEventKind
import skillbill.ports.review.model.ReviewLifecycleLedger
import skillbill.ports.review.model.ReviewProcessOutcome
import skillbill.ports.review.model.ReviewWorkerResultEnvelope
import skillbill.ports.review.model.ReviewWorkerLifecycleState

/** Deterministic lifecycle fixture used by failure reproduction tests; no provider is contacted. */
class ReviewLifecycleEvidenceFixture(
  private val reviewId: String = "review-fixture",
  private val packetDigest: String = "a".repeat(64),
  private val clock: FakeReviewClock = FakeReviewClock(),
) {
  val persistence = FakeReviewLifecyclePersistence()
  val launcher = FakeReviewLauncher()
  val provider = FakeReviewProvider()
  val aggregation = FakeReviewAggregation(persistence)

  fun coordinatorPrepared() = append(
    kind = ReviewLifecycleEventKind.COORDINATOR_PREPARED,
    component = skillbill.ports.review.model.ReviewLifecycleComponent.COORDINATOR,
    outcome = ReviewProcessOutcome.NOT_STARTED,
  )

  fun workerSelected(workerId: String, assignmentDigest: String = "b".repeat(64)) = append(
    ReviewLifecycleEventKind.WORKER_SELECTED,
    workerId,
    ReviewWorkerLifecycleState.SELECTED,
    ReviewProcessOutcome.NOT_STARTED,
    assignmentDigest = assignmentDigest,
  )

  fun workerLaunched(workerId: String, assignmentDigest: String = "b".repeat(64), attempt: Int = 1) = append(
    ReviewLifecycleEventKind.WORKER_LAUNCHED,
    workerId,
    ReviewWorkerLifecycleState.LAUNCHED,
    ReviewProcessOutcome.NOT_STARTED,
    assignmentDigest = assignmentDigest,
    attempt = attempt,
  )

  fun workerCompleted(workerId: String, assignmentDigest: String = "b".repeat(64), attempt: Int = 1) = append(
    ReviewLifecycleEventKind.WORKER_COMPLETED,
    workerId,
    ReviewWorkerLifecycleState.COMPLETED,
    ReviewProcessOutcome.ZERO_EXIT,
    assignmentDigest = assignmentDigest,
    attempt = attempt,
  )

  fun workerFailed(
    workerId: String,
    outcome: ReviewProcessOutcome,
    assignmentDigest: String = "b".repeat(64),
    attempt: Int = 1,
  ) = append(
    when (outcome) {
      ReviewProcessOutcome.TIMED_OUT -> ReviewLifecycleEventKind.WORKER_TIMED_OUT
      ReviewProcessOutcome.INTERRUPTED -> ReviewLifecycleEventKind.WORKER_CANCELLED
      ReviewProcessOutcome.UNAVAILABLE -> ReviewLifecycleEventKind.WORKER_UNAVAILABLE
      ReviewProcessOutcome.INVALID_OUTPUT -> ReviewLifecycleEventKind.WORKER_INVALID_OUTPUT
      else -> ReviewLifecycleEventKind.WORKER_FAILED
    },
    workerId,
    when (outcome) {
      ReviewProcessOutcome.TIMED_OUT -> ReviewWorkerLifecycleState.TIMED_OUT
      ReviewProcessOutcome.INTERRUPTED -> ReviewWorkerLifecycleState.CANCELLED
      ReviewProcessOutcome.UNAVAILABLE -> ReviewWorkerLifecycleState.UNAVAILABLE
      ReviewProcessOutcome.INVALID_OUTPUT -> ReviewWorkerLifecycleState.INVALID_OUTPUT
      else -> ReviewWorkerLifecycleState.FAILED
    },
    outcome,
    assignmentDigest = assignmentDigest,
    attempt = attempt,
  )

  fun coordinatorCrashed() = append(
    kind = ReviewLifecycleEventKind.COORDINATOR_CRASHED,
    component = skillbill.ports.review.model.ReviewLifecycleComponent.COORDINATOR,
    outcome = ReviewProcessOutcome.COORDINATOR_CRASH,
  )

  fun aggregationStarted() = append(
    kind = ReviewLifecycleEventKind.AGGREGATION_STARTED,
    component = skillbill.ports.review.model.ReviewLifecycleComponent.AGGREGATION,
    outcome = ReviewProcessOutcome.NOT_STARTED,
  )

  fun aggregationFailed() = append(
    kind = ReviewLifecycleEventKind.AGGREGATION_FAILED,
    component = skillbill.ports.review.model.ReviewLifecycleComponent.AGGREGATION,
    outcome = ReviewProcessOutcome.AGGREGATION_FAILURE,
  )

  private fun append(
    kind: ReviewLifecycleEventKind,
    workerId: String? = null,
    state: ReviewWorkerLifecycleState? = null,
    outcome: ReviewProcessOutcome,
    assignmentDigest: String? = null,
    attempt: Int = 1,
    component: skillbill.ports.review.model.ReviewLifecycleComponent =
      skillbill.ports.review.model.ReviewLifecycleComponent.WORKER,
  ): ReviewLifecycleEvent {
    val event = ReviewLifecycleEvent(
      eventId = "${reviewId}:${persistence.events.size + 1}:$kind:$workerId",
      reviewId = reviewId,
      sequence = persistence.events.size + 1L,
      occurredAt = clock.next(),
      component = component,
      eventKind = kind,
      packetDigest = packetDigest,
      workerId = workerId,
      providerId = workerId,
      attempt = assignmentDigest?.let { attempt },
      assignmentDigest = assignmentDigest,
      routedArea = assignmentDigest?.let { "architecture" },
      state = state,
      processOutcome = outcome,
      resultEnvelope = ReviewWorkerResultEnvelope(emptyList()).takeIf {
        kind == ReviewLifecycleEventKind.WORKER_COMPLETED
      },
    )
    persistence.append(event)
    return event
  }
}

class FakeReviewClock(private var tick: Int = 0) {
  fun next(): String = "2026-08-02T00:00:${(tick++).toString().padStart(2, '0')}Z"
}

class FakeReviewLifecyclePersistence {
  private val ledger = ReviewLifecycleLedger()
  val events: List<ReviewLifecycleEvent> get() = ledger.events
  fun append(event: ReviewLifecycleEvent): Boolean = ledger.append(event)
}

class FakeReviewLauncher {
  var launchCount: Int = 0
    private set

  fun launch(): Unit { launchCount += 1 }
}

class FakeReviewProvider {
  var output: String = ""
  var outcome: ReviewProcessOutcome = ReviewProcessOutcome.ZERO_EXIT
}

class FakeReviewAggregation(private val persistence: FakeReviewLifecyclePersistence) {
  fun isComplete(assignments: Set<String>): Boolean =
    persistence.events.filter { it.eventKind == ReviewLifecycleEventKind.WORKER_COMPLETED }
      .mapNotNull(ReviewLifecycleEvent::assignmentDigest).toSet() == assignments

  fun canPromote(assignments: Set<String>): Boolean =
    isComplete(assignments) && persistence.events.none {
      it.eventKind == ReviewLifecycleEventKind.AGGREGATION_FAILED ||
        it.eventKind == ReviewLifecycleEventKind.COORDINATOR_CRASHED
    }
}
