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
      override fun <T> read(
        dbOverride: String?,
        block: (skillbill.ports.persistence.UnitOfWork) -> T,
      ): T = block(unitOfWork)

      override fun <T> transaction(
        dbOverride: String?,
        block: (skillbill.ports.persistence.UnitOfWork) -> T,
      ): T {
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

  private fun database(event: ReviewLifecycleEvent): skillbill.ports.persistence.DatabaseSessionFactory {
    val reviews = Proxy.newProxyInstance(
      skillbill.ports.persistence.ReviewRepository::class.java.classLoader,
      arrayOf(skillbill.ports.persistence.ReviewRepository::class.java),
    ) { _, method, _ ->
      when (method.name) {
        "loadReviewLifecycleEvents" -> listOf(event)
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
