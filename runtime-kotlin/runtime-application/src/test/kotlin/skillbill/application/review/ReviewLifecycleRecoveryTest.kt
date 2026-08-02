package skillbill.application.review

import skillbill.ports.review.model.ReviewLifecycleComponent
import skillbill.ports.review.model.ReviewLifecycleEvent
import skillbill.ports.review.model.ReviewLifecycleEventKind
import skillbill.ports.review.model.ReviewProcessOutcome
import skillbill.ports.review.model.ReviewWorkerLifecycleState
import java.lang.reflect.Proxy
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReviewLifecycleRecoveryTest {
  @Test fun `restart retains durable completion and does not relaunch it`() {
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
    )
    val reviews = Proxy.newProxyInstance(
      skillbill.ports.persistence.ReviewRepository::class.java.classLoader,
      arrayOf(skillbill.ports.persistence.ReviewRepository::class.java),
    ) { _, method, _ ->
      when (method.name) {
        "loadReviewLifecycleEvents" -> listOf(event)
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
      override fun <T> transaction(dbOverride: String?, block: (skillbill.ports.persistence.UnitOfWork) -> T): T =
        block(unitOfWork)
    }
    val recovery = ReviewLifecycleRecovery(database).read("review", setOf("b".repeat(64), "c".repeat(64)))
    assertFalse(recovery.shouldLaunch("b".repeat(64)))
    assertTrue(recovery.shouldLaunch("c".repeat(64)))
  }
}
