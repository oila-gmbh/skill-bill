package skillbill.infrastructure.sqlite.review

import skillbill.db.core.DatabaseSchema
import skillbill.error.InvalidReviewLifecycleEvidenceSchemaError
import skillbill.ports.review.model.ReviewLifecycleComponent
import skillbill.ports.review.model.ReviewLifecycleEvent
import skillbill.ports.review.model.ReviewLifecycleEventKind
import skillbill.ports.review.model.ReviewWorkerLifecycleState
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ReviewLifecyclePersistenceTest {
  @Test fun `lifecycle append is durable and duplicate replay is a no-op`() {
    DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
      DatabaseSchema.createBaseSchema(connection)
      val repository = skillbill.infrastructure.sqlite.SQLiteReviewRepository(connection)
      val event = event()
      assertTrue(repository.appendReviewLifecycleEvent(event))
      assertFalse(repository.appendReviewLifecycleEvent(event))
      assertEquals(listOf(event), repository.loadReviewLifecycleEvents("review"))
    }
  }

  @Test fun `lifecycle read rejects contract drift before decoding`() {
    DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
      DatabaseSchema.createBaseSchema(connection)
      val repository = skillbill.infrastructure.sqlite.SQLiteReviewRepository(connection)
      repository.appendReviewLifecycleEvent(event())
      connection.prepareStatement(
        "UPDATE review_lifecycle_events SET payload_json = replace(payload_json, '0.1', '0.2')",
      ).use { statement -> statement.executeUpdate() }

      assertFailsWith<InvalidReviewLifecycleEvidenceSchemaError> {
        repository.loadReviewLifecycleEvents("review")
      }
    }
  }

  private fun event() = ReviewLifecycleEvent(
    eventId = "review:worker-selected",
    reviewId = "review",
    sequence = 1,
    occurredAt = "2026-08-02T00:00:00Z",
    component = ReviewLifecycleComponent.WORKER,
    eventKind = ReviewLifecycleEventKind.WORKER_SELECTED,
    packetDigest = "a".repeat(64),
    workerId = "worker",
    providerId = "codex",
    attempt = 1,
    assignmentDigest = "b".repeat(64),
    routedArea = "architecture",
    state = ReviewWorkerLifecycleState.SELECTED,
  )
}
