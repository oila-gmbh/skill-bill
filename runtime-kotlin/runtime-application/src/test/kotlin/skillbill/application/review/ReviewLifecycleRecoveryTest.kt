package skillbill.application.review

import skillbill.ports.review.model.ReviewLifecycleComponent
import skillbill.ports.review.model.ReviewLifecycleEvent
import skillbill.ports.review.model.ReviewLifecycleEventKind
import skillbill.ports.review.model.ReviewProcessOutcome
import skillbill.ports.review.model.ReviewTerminalCompletion
import skillbill.ports.review.model.ReviewWorkerLifecycleState
import skillbill.ports.review.model.ReviewWorkerResultEnvelope
import skillbill.review.model.ParallelReviewRawFinding
import skillbill.review.model.ParallelReviewSeverity
import skillbill.review.plan.model.DelegatedReviewWave
import java.lang.reflect.Proxy
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReviewLifecycleRecoveryTest {
  @Test fun `interruption boundary and lifecycle projection share one transaction`() {
    val operations = mutableListOf<String>()
    var transactionCount = 0
    val reviews = Proxy.newProxyInstance(
      skillbill.ports.persistence.ReviewRepository::class.java.classLoader,
      arrayOf(skillbill.ports.persistence.ReviewRepository::class.java),
    ) { _, method, _ ->
      when (method.name) {
        "loadReviewLifecycleEvents" -> emptyList<ReviewLifecycleEvent>()
        "appendReviewLifecycleEvent" -> operations.add("event").let { true }
        else -> null
      }
    } as skillbill.ports.persistence.ReviewRepository
    val unitOfWork = Proxy.newProxyInstance(
      skillbill.ports.persistence.UnitOfWork::class.java.classLoader,
      arrayOf(skillbill.ports.persistence.UnitOfWork::class.java),
    ) { _, method, _ ->
      when (method.name) {
        "getReviews" -> reviews
        "getDbPath" -> Path.of("/tmp/review.db")
        else -> error("Unexpected unit-of-work call: ${method.name}")
      }
    } as skillbill.ports.persistence.UnitOfWork
    val database = object : skillbill.ports.persistence.DatabaseSessionFactory {
      override fun resolveDbPath(dbOverride: String?) = Path.of("/tmp/review.db")
      override fun databaseExists(dbOverride: String?) = true
      override fun <T> read(dbOverride: String?, block: (skillbill.ports.persistence.UnitOfWork) -> T): T =
        block(unitOfWork)

      override fun <T> transaction(dbOverride: String?, block: (skillbill.ports.persistence.UnitOfWork) -> T): T {
        transactionCount += 1
        return block(unitOfWork)
      }
    }
    val boundary = ReviewLifecycleRecord(
      reviewId = "review",
      packetDigest = "a".repeat(64),
      component = ReviewLifecycleComponent.TERMINAL,
      eventKind = ReviewLifecycleEventKind.TERMINAL_CANCELLED,
      processOutcome = ReviewProcessOutcome.INTERRUPTED,
      terminalCompletion = ReviewTerminalCompletion(
        "2026-08-02T00:00:00Z",
        ReviewProcessOutcome.INTERRUPTED,
      ),
    )

    ReviewLifecycleRecorder(database).recordAllAndPersist(listOf(boundary)) { _, events ->
      assertEquals(1, events.size)
      operations += "projection"
    }

    assertEquals(1, transactionCount)
    assertEquals(listOf("event", "projection"), operations)
  }

  @Test fun `restart retains durable completion and does not relaunch it`() {
    val result = ReviewWorkerResultEnvelope(
      listOf(
        ParallelReviewRawFinding(
          severity = ParallelReviewSeverity.BLOCKER,
          confidence = "High",
          location = "src/Review.kt:12",
          description = "The recovered finding is retained for aggregation after restart.",
          repositoryPath = "src/Review.kt",
          line = 12,
        ),
      ),
    )
    val event = ReviewLifecycleEvent(
      eventId = "worker-completed",
      reviewId = "review",
      sequence = 1,
      occurredAt = "2026-08-02T00:00:00Z",
      component = ReviewLifecycleComponent.WORKER,
      eventKind = ReviewLifecycleEventKind.WORKER_COMPLETED,
      packetDigest = "a".repeat(64),
      workerId = "worker",
      providerId = "codex",
      attempt = 1,
      assignmentDigest = "b".repeat(64),
      routedArea = "architecture",
      state = ReviewWorkerLifecycleState.COMPLETED,
      processOutcome = ReviewProcessOutcome.ZERO_EXIT,
      resultEnvelope = result,
    )
    val recovery = ReviewLifecycleRecovery(database(event)).read(
      "review",
      mapOf(
        "b".repeat(64) to ReviewLifecycleWorkerIdentity("worker", "codex"),
        "c".repeat(64) to ReviewLifecycleWorkerIdentity("other-worker", "codex"),
      ),
    )
    assertFalse(recovery.shouldLaunch("b".repeat(64)))
    assertTrue(recovery.shouldLaunch("c".repeat(64)))
    assertEquals(1, recovery.attemptFor("b".repeat(64)))
    assertEquals(1, recovery.attemptFor("c".repeat(64)))
    assertEquals(1, recovery.completedResults["b".repeat(64)]?.attempt)
    assertEquals(result, recovery.completedResults["b".repeat(64)]?.resultEnvelope)
  }

  @Test fun `identity mismatch remains relaunchable instead of omitting recovered output`() {
    val event = ReviewLifecycleEvent(
      eventId = "worker-completed",
      reviewId = "review",
      sequence = 1,
      occurredAt = "2026-08-02T00:00:00Z",
      component = ReviewLifecycleComponent.WORKER,
      eventKind = ReviewLifecycleEventKind.WORKER_COMPLETED,
      packetDigest = "a".repeat(64),
      workerId = "worker",
      providerId = "codex",
      attempt = 1,
      assignmentDigest = "b".repeat(64),
      routedArea = "architecture",
      state = ReviewWorkerLifecycleState.COMPLETED,
      processOutcome = ReviewProcessOutcome.ZERO_EXIT,
      resultEnvelope = ReviewWorkerResultEnvelope(emptyList()),
    )
    val recovery = ReviewLifecycleRecovery(database(event)).read(
      "review",
      mapOf("b".repeat(64) to ReviewLifecycleWorkerIdentity("worker", "different-provider")),
    )

    assertTrue(recovery.shouldLaunch("b".repeat(64)))
    assertEquals(2, recovery.attemptFor("b".repeat(64)))
    assertTrue(recovery.completedResults.isEmpty())
  }

  @Test fun `recovery preserves historical wave membership across retry attempts`() {
    val firstAssignment = "b".repeat(64)
    val secondAssignment = "c".repeat(64)
    val events = listOf(
      workerEvent(
        eventId = "first-launched",
        sequence = 1,
        eventKind = ReviewLifecycleEventKind.WORKER_LAUNCHED,
        identity = workerIdentity("first-worker", firstAssignment, 1, ReviewWorkerLifecycleState.LAUNCHED),
      ),
      workerEvent(
        eventId = "first-failed",
        sequence = 2,
        eventKind = ReviewLifecycleEventKind.WORKER_FAILED,
        identity = workerIdentity("first-worker", firstAssignment, 1, ReviewWorkerLifecycleState.FAILED),
        terminal = WorkerEventTerminal(ReviewProcessOutcome.NON_ZERO_EXIT),
      ),
      workerEvent(
        eventId = "second-launched",
        sequence = 3,
        eventKind = ReviewLifecycleEventKind.WORKER_LAUNCHED,
        identity = workerIdentity("second-worker", secondAssignment, 2, ReviewWorkerLifecycleState.LAUNCHED),
      ),
      workerEvent(
        eventId = "second-completed",
        sequence = 4,
        eventKind = ReviewLifecycleEventKind.WORKER_COMPLETED,
        identity = workerIdentity("second-worker", secondAssignment, 2, ReviewWorkerLifecycleState.COMPLETED),
        terminal = WorkerEventTerminal(
          ReviewProcessOutcome.ZERO_EXIT,
          ReviewWorkerResultEnvelope(emptyList()),
        ),
      ),
    )

    val recovery = ReviewLifecycleRecovery(database(events)).read(
      "review",
      mapOf(
        firstAssignment to ReviewLifecycleWorkerIdentity("first-worker", "codex"),
        secondAssignment to ReviewLifecycleWorkerIdentity("second-worker", "codex"),
      ),
    )

    assertEquals(setOf(firstAssignment), recovery.pendingAssignmentDigests)
    assertEquals(2, recovery.attemptFor(firstAssignment))
    assertEquals(listOf(1, 2), recovery.actualWaves.map(DelegatedReviewWave::number))
    assertEquals(listOf(firstAssignment), recovery.actualWaves[0].workerIds)
    assertEquals(listOf(secondAssignment), recovery.actualWaves[1].workerIds)
  }

  private fun workerEvent(
    eventId: String,
    sequence: Long,
    eventKind: ReviewLifecycleEventKind,
    identity: WorkerEventIdentity,
    terminal: WorkerEventTerminal = WorkerEventTerminal(),
  ) = ReviewLifecycleEvent(
    eventId = eventId,
    reviewId = "review",
    sequence = sequence,
    occurredAt = "2026-08-02T00:00:00Z",
    component = ReviewLifecycleComponent.WORKER,
    eventKind = eventKind,
    packetDigest = "a".repeat(64),
    workerId = identity.workerId,
    providerId = "codex",
    attempt = identity.attempt,
    assignmentDigest = identity.assignmentDigest,
    routedArea = identity.workerId,
    waveNumber = identity.waveNumber,
    state = identity.state,
    processOutcome = terminal.processOutcome,
    resultEnvelope = terminal.resultEnvelope,
  )

  private fun workerIdentity(
    workerId: String,
    assignmentDigest: String,
    waveNumber: Int,
    state: ReviewWorkerLifecycleState,
  ) = WorkerEventIdentity(workerId, assignmentDigest, 1, waveNumber, state)

  private data class WorkerEventIdentity(
    val workerId: String,
    val assignmentDigest: String,
    val attempt: Int,
    val waveNumber: Int,
    val state: ReviewWorkerLifecycleState,
  )

  private data class WorkerEventTerminal(
    val processOutcome: ReviewProcessOutcome? = null,
    val resultEnvelope: ReviewWorkerResultEnvelope? = null,
  )

  private fun database(event: ReviewLifecycleEvent): skillbill.ports.persistence.DatabaseSessionFactory =
    database(listOf(event))

  private fun database(events: List<ReviewLifecycleEvent>): skillbill.ports.persistence.DatabaseSessionFactory {
    val reviews = Proxy.newProxyInstance(
      skillbill.ports.persistence.ReviewRepository::class.java.classLoader,
      arrayOf(skillbill.ports.persistence.ReviewRepository::class.java),
    ) { _, method, _ ->
      when (method.name) {
        "loadReviewLifecycleEvents" -> events
        "saveDelegatedReviewLifecycle" -> Unit
        "loadDelegatedReviewLifecycle" -> null
        else -> null
      }
    } as skillbill.ports.persistence.ReviewRepository
    val unitOfWork = Proxy.newProxyInstance(
      skillbill.ports.persistence.UnitOfWork::class.java.classLoader,
      arrayOf(skillbill.ports.persistence.UnitOfWork::class.java),
    ) { _, method, _ ->
      when (method.name) {
        "getReviews" -> reviews
        "getDbPath" -> Path.of("/tmp/review.db")
        else -> error("Unexpected unit-of-work call: ${method.name}")
      }
    } as skillbill.ports.persistence.UnitOfWork
    return object : skillbill.ports.persistence.DatabaseSessionFactory {
      override fun resolveDbPath(dbOverride: String?) = Path.of("/tmp/review.db")
      override fun databaseExists(dbOverride: String?) = true
      override fun <T> read(dbOverride: String?, block: (skillbill.ports.persistence.UnitOfWork) -> T): T =
        block(unitOfWork)
      override fun <T> transaction(dbOverride: String?, block: (skillbill.ports.persistence.UnitOfWork) -> T): T =
        block(unitOfWork)
    }
  }
}
