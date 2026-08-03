package skillbill.ports.review.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReviewLifecycleEvidenceModelsTest {
  private val packetDigest = "a".repeat(64)
  private val assignmentDigest = "b".repeat(64)

  @Test fun `durable lifecycle distinguishes observations from specialist progress`() {
    val heartbeat = ReviewLifecycleEvent(
      eventId = "heartbeat",
      reviewId = "review",
      sequence = 1,
      occurredAt = "2026-08-02T00:00:00Z",
      component = ReviewLifecycleComponent.WORKER,
      eventKind = ReviewLifecycleEventKind.WORKER_RUNNING,
      packetDigest = packetDigest,
      workerId = "worker",
      providerId = "codex",
      attempt = 1,
      assignmentDigest = assignmentDigest,
      routedArea = "architecture",
      livenessObservations = listOf(
        ReviewLivenessObservation(
          ReviewLivenessObservation.Kind.PROCESS_HEARTBEAT,
          "2026-08-02T00:00:00Z",
          "alive",
        ),
      ),
    )
    assertTrue(heartbeat.durableProgress == null)
    assertFailsWith<IllegalArgumentException> {
      ReviewLifecycleEvent(
        eventId = "fake-progress",
        reviewId = "review",
        sequence = 2,
        occurredAt = "2026-08-02T00:00:01Z",
        component = ReviewLifecycleComponent.WORKER,
        eventKind = ReviewLifecycleEventKind.WORKER_PROGRESS,
        packetDigest = packetDigest,
        workerId = "worker",
        providerId = "codex",
        attempt = 1,
        assignmentDigest = assignmentDigest,
        routedArea = "architecture",
        declaredProgress = ReviewDeclaredSpecialistProgress("2026-08-02T00:00:01Z", "declared", "read file"),
      )
    }
  }

  @Test fun `ledger makes duplicate event writes idempotent and rejects failed aggregation`() {
    val ledger = ReviewLifecycleLedger()
    val selected = event(
      1,
      ReviewLifecycleEventKind.WORKER_SELECTED,
      EventFixture(state = ReviewWorkerLifecycleState.SELECTED),
    )
    assertTrue(ledger.append(selected))
    assertFalse(ledger.append(selected))
    assertEquals(1, ledger.events.size)
    ledger.append(
      event(
        2,
        ReviewLifecycleEventKind.WORKER_FAILED,
        EventFixture(ReviewWorkerLifecycleState.FAILED, ReviewProcessOutcome.NON_ZERO_EXIT),
      ),
    )
    assertFalse(ledger.canAggregate(setOf(assignmentDigest)))
    assertFailsWith<IllegalArgumentException> {
      ledger.append(
        event(
          3,
          ReviewLifecycleEventKind.AGGREGATION_COMPLETED,
          EventFixture(
            outcome = ReviewProcessOutcome.ZERO_EXIT,
            terminal = ReviewTerminalCompletion("2026-08-02T00:00:03Z", ReviewProcessOutcome.ZERO_EXIT),
          ),
        ),
      )
    }
  }

  @Test fun `normal worker completion is the only successful aggregation input`() {
    val ledger = ReviewLifecycleLedger()
    ledger.append(
      event(
        1,
        ReviewLifecycleEventKind.WORKER_SELECTED,
        EventFixture(state = ReviewWorkerLifecycleState.SELECTED),
      ),
    )
    ledger.append(
      event(
        2,
        ReviewLifecycleEventKind.WORKER_COMPLETED,
        EventFixture(ReviewWorkerLifecycleState.COMPLETED, ReviewProcessOutcome.ZERO_EXIT),
      ),
    )
    assertTrue(ledger.canAggregate(setOf(assignmentDigest)))
    ledger.append(
      event(
        3,
        ReviewLifecycleEventKind.AGGREGATION_COMPLETED,
        EventFixture(
          outcome = ReviewProcessOutcome.ZERO_EXIT,
          terminal = ReviewTerminalCompletion("2026-08-02T00:00:03Z", ReviewProcessOutcome.ZERO_EXIT),
        ),
      ),
    )
  }

  @Test fun `successful retry supersedes an earlier failed worker attempt`() {
    val ledger = ReviewLifecycleLedger()
    ledger.append(
      event(
        1,
        ReviewLifecycleEventKind.WORKER_SELECTED,
        EventFixture(state = ReviewWorkerLifecycleState.SELECTED),
      ),
    )
    ledger.append(
      event(
        2,
        ReviewLifecycleEventKind.WORKER_FAILED,
        EventFixture(ReviewWorkerLifecycleState.FAILED, ReviewProcessOutcome.NON_ZERO_EXIT, attempt = 1),
      ),
    )
    ledger.append(
      event(
        3,
        ReviewLifecycleEventKind.WORKER_COMPLETED,
        EventFixture(ReviewWorkerLifecycleState.COMPLETED, ReviewProcessOutcome.ZERO_EXIT, attempt = 2),
      ),
    )

    assertTrue(ledger.canAggregate(setOf(assignmentDigest)))
    ledger.append(
      event(
        4,
        ReviewLifecycleEventKind.AGGREGATION_COMPLETED,
        EventFixture(
          outcome = ReviewProcessOutcome.ZERO_EXIT,
          terminal = ReviewTerminalCompletion("2026-08-02T00:00:04Z", ReviewProcessOutcome.ZERO_EXIT),
        ),
      ),
    )
  }

  @Test fun `aggregation failure remains non-repairable`() {
    val ledger = ReviewLifecycleLedger()
    ledger.append(
      event(
        1,
        ReviewLifecycleEventKind.WORKER_SELECTED,
        EventFixture(state = ReviewWorkerLifecycleState.SELECTED),
      ),
    )
    ledger.append(
      event(
        2,
        ReviewLifecycleEventKind.WORKER_COMPLETED,
        EventFixture(ReviewWorkerLifecycleState.COMPLETED, ReviewProcessOutcome.ZERO_EXIT),
      ),
    )
    ledger.append(
      event(
        3,
        ReviewLifecycleEventKind.AGGREGATION_FAILED,
        EventFixture(outcome = ReviewProcessOutcome.AGGREGATION_FAILURE),
      ),
    )

    assertFalse(ledger.canAggregate(setOf(assignmentDigest)))
  }

  private data class EventFixture(
    val state: ReviewWorkerLifecycleState? = null,
    val outcome: ReviewProcessOutcome = ReviewProcessOutcome.NOT_STARTED,
    val terminal: ReviewTerminalCompletion? = null,
    val attempt: Int = 1,
  )

  private fun event(sequence: Int, kind: ReviewLifecycleEventKind, fixture: EventFixture) = ReviewLifecycleEvent(
    eventId = "event-$sequence",
    reviewId = "review",
    sequence = sequence.toLong(),
    occurredAt = "2026-08-02T00:00:0${sequence}Z",
    component = if (kind.name.startsWith("AGGREGATION")) {
      ReviewLifecycleComponent.AGGREGATION
    } else {
      ReviewLifecycleComponent.WORKER
    },
    eventKind = kind,
    packetDigest = packetDigest,
    workerId = if (kind.name.startsWith("AGGREGATION")) null else "worker",
    providerId = if (kind.name.startsWith("AGGREGATION")) null else "codex",
    attempt = if (kind.name.startsWith("AGGREGATION")) null else fixture.attempt,
    assignmentDigest = if (kind.name.startsWith("AGGREGATION")) null else assignmentDigest,
    routedArea = if (kind.name.startsWith("AGGREGATION")) null else "architecture",
    state = fixture.state,
    processOutcome = fixture.outcome,
    resultEnvelope = ReviewWorkerResultEnvelope(emptyList()).takeIf {
      kind == ReviewLifecycleEventKind.WORKER_COMPLETED
    },
    terminalCompletion = fixture.terminal,
  )
}
