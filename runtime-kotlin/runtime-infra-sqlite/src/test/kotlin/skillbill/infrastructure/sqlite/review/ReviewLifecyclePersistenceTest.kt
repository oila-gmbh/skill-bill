package skillbill.infrastructure.sqlite.review

import skillbill.contracts.review.REVIEW_LIFECYCLE_EVIDENCE_CONTRACT_VERSION
import skillbill.db.core.DatabaseSchema
import skillbill.error.InvalidReviewLifecycleEvidenceSchemaError
import skillbill.error.InvalidReviewLifecycleSchemaError
import skillbill.ports.review.model.DelegatedReviewLifecycleMetrics
import skillbill.ports.review.model.DelegatedReviewLifecycleSnapshot
import skillbill.ports.review.model.DelegatedReviewWaveRecord
import skillbill.ports.review.model.DelegatedReviewWorkerRecord
import skillbill.ports.review.model.DelegatedReviewWorkerState
import skillbill.ports.review.model.ReviewLifecycleComponent
import skillbill.ports.review.model.ReviewLifecycleEvent
import skillbill.ports.review.model.ReviewLifecycleEventKind
import skillbill.ports.review.model.ReviewProcessOutcome
import skillbill.ports.review.model.ReviewWorkerLifecycleState
import skillbill.ports.review.model.ReviewWorkerResultEnvelope
import skillbill.review.model.ParallelReviewRawFinding
import skillbill.review.model.ParallelReviewSeverity
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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
      val replacement =
        "replace(payload_json, '$REVIEW_LIFECYCLE_EVIDENCE_CONTRACT_VERSION', '0.99')"
      connection.prepareStatement(
        "UPDATE review_lifecycle_events SET payload_json = $replacement",
      ).use { statement -> statement.executeUpdate() }

      assertFailsWith<InvalidReviewLifecycleEvidenceSchemaError> {
        repository.loadReviewLifecycleEvents("review")
      }
    }
  }

  @Test fun `completed lifecycle result envelope round trips bounded findings`() {
    DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
      DatabaseSchema.createBaseSchema(connection)
      val repository = skillbill.infrastructure.sqlite.SQLiteReviewRepository(connection)
      val result = ReviewWorkerResultEnvelope(
        listOf(
          ParallelReviewRawFinding(
            severity = ParallelReviewSeverity.BLOCKER,
            confidence = "High",
            location = "src/Review.kt:12",
            description = "The recovered finding remains available after coordinator restart.",
            specialistSkillName = "bill-kotlin-code-review-architecture",
            originLayerChains = listOf(listOf("kotlin", "architecture")),
            repositoryPath = "src/Review.kt",
            line = 12,
          ),
        ),
      )
      val completed = event().copy(
        eventId = "review:worker-completed",
        eventKind = ReviewLifecycleEventKind.WORKER_COMPLETED,
        state = ReviewWorkerLifecycleState.COMPLETED,
        processOutcome = ReviewProcessOutcome.ZERO_EXIT,
        resultEnvelope = result,
      )

      assertTrue(repository.appendReviewLifecycleEvent(completed))
      assertEquals(result, repository.loadReviewLifecycleEvents("review").single().resultEnvelope)
    }
  }

  @Test fun `delegated lifecycle projection survives restart and preserves wave accounting`() {
    DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
      DatabaseSchema.createBaseSchema(connection)
      val repository = skillbill.infrastructure.sqlite.SQLiteReviewRepository(connection)
      val snapshot = snapshot()
      repository.saveDelegatedReviewLifecycle(snapshot)
      assertEquals(snapshot, repository.loadDelegatedReviewLifecycle("review"))
    }
  }

  @Test fun `delegated lifecycle read rejects contract drift with the typed schema error`() {
    DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
      DatabaseSchema.createBaseSchema(connection)
      val repository = skillbill.infrastructure.sqlite.SQLiteReviewRepository(connection)
      repository.saveDelegatedReviewLifecycle(snapshot())
      connection.prepareStatement(
        "UPDATE review_delegated_lifecycle SET bounded_payload_json = " +
          "replace(bounded_payload_json, '0.1', '0.99')",
      ).use { statement -> statement.executeUpdate() }

      assertFailsWith<InvalidReviewLifecycleSchemaError> {
        repository.loadDelegatedReviewLifecycle("review")
      }
    }
  }

  @Test fun `delegated lifecycle read rejects corrupt payload with the typed schema error`() {
    DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
      DatabaseSchema.createBaseSchema(connection)
      val repository = skillbill.infrastructure.sqlite.SQLiteReviewRepository(connection)
      repository.saveDelegatedReviewLifecycle(snapshot())
      connection.prepareStatement(
        "UPDATE review_delegated_lifecycle SET bounded_payload_json = '{\"kind\":\"delegated_review_lifecycle\"}'",
      ).use { statement -> statement.executeUpdate() }

      assertFailsWith<InvalidReviewLifecycleSchemaError> {
        repository.loadDelegatedReviewLifecycle("review")
      }
    }
  }

  private fun snapshot() = DelegatedReviewLifecycleSnapshot(
    reviewId = "review",
    packetDigest = "a".repeat(64),
    selectedAreaCount = 1,
    predictedWaveCount = 1,
    actualWaveCount = 1,
    coordinatorSlots = 1,
    workers = listOf(
      DelegatedReviewWorkerRecord(
        workerId = "codex:security",
        providerId = "codex",
        assignmentDigest = "b".repeat(64),
        attempt = 1,
        area = "security",
        state = DelegatedReviewWorkerState.COMPLETED,
      ),
    ),
    waves = listOf(DelegatedReviewWaveRecord(1, listOf("codex:security"))),
    deadlines = emptyList(),
    metrics = DelegatedReviewLifecycleMetrics(
      elapsedMs = 10,
      totalTokens = 20,
      processCount = 1,
      mcpStartupCount = 1,
      selectedAreaCount = 1,
      completedAreaCount = 1,
      lostWorkerCount = 0,
    ),
  )

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
