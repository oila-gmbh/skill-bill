package skillbill.review

import skillbill.application.review.RecordedWorkerResponse
import skillbill.application.review.ReviewHarnessConfig
import skillbill.application.review.ReviewRecorder
import skillbill.application.review.diffForChanges
import skillbill.application.review.harnessRequest
import skillbill.application.review.reviewHarness
import skillbill.application.review.reviewPack
import skillbill.application.review.toBoundedPayload
import skillbill.contracts.JsonSupport
import skillbill.contracts.review.ReviewContextSchemaValidator
import skillbill.db.core.DatabaseRuntime
import skillbill.infrastructure.sqlite.review.loadReviewAccounting
import skillbill.infrastructure.sqlite.review.reviewFinishedPayload
import skillbill.infrastructure.sqlite.review.upsertReviewAccounting
import skillbill.ports.persistence.model.ReviewAccountingRecord
import skillbill.ports.telemetry.model.toReviewFinishedTelemetryPayload
import skillbill.review.context.model.ProviderTokenUsage
import skillbill.review.context.model.ReviewAccountingSummary
import skillbill.review.model.ReviewSummary
import java.nio.file.Files
import java.sql.Connection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Content-bearing review inputs are measured, never retained. The summary under test comes from a
 * real run of the production review runner whose diff, rubric, brokered evidence, and lane results
 * all carry sentinel bodies, so every absence assertion below has something it could have failed on.
 */
class ReviewAccountingDurableRedactionTest {
  private val diffBody = "DIFF_SENTINEL ".repeat(64)
  private val sourceBody = "SOURCE_SENTINEL ".repeat(64)
  private val guidanceBody = "GUIDANCE_SENTINEL ".repeat(64)
  private val rubricBody = "RUBRIC_SENTINEL ".repeat(64)
  private val toolOutputBody = "TOOL_OUTPUT_SENTINEL ".repeat(64)
  private val sentinels = listOf(
    "DIFF_SENTINEL",
    "SOURCE_SENTINEL",
    "GUIDANCE_SENTINEL",
    "RUBRIC_SENTINEL",
    "TOOL_OUTPUT_SENTINEL",
  )

  @Test fun `the measured review really did carry every content sentinel`() {
    val (recorder, summary) = recordedReview()

    val prompts = recorder.nativeLaunches.map { it.prompt }
    assertTrue(prompts.isNotEmpty(), "The redaction proof is only meaningful if specialists were launched.")
    assertTrue(prompts.all { it.contains("DIFF_SENTINEL") }, "The diff body must reach the specialist prompt.")
    assertTrue(prompts.all { it.contains("RUBRIC_SENTINEL") }, "The rubric body must reach the specialist prompt.")
    assertTrue(prompts.all { it.contains("SOURCE_SENTINEL") }, "Brokered source must reach the specialist prompt.")
    assertTrue(prompts.all { it.contains("GUIDANCE_SENTINEL") }, "The changed guidance body must reach the prompt.")
    assertTrue(summary.aggregateCounters.launchBytes > 0)
    assertTrue(summary.aggregateCounters.evidenceBytes > 0)
    assertTrue(summary.aggregateCounters.resultBytes > 0)
  }

  @Test fun `bounded accounting validates against the governed review-context schema`() {
    ReviewContextSchemaValidator.validate(recordedReview().second.toBoundedPayload(), "review-accounting")
  }

  @Test fun `a recorded review retains its measured sizes and none of the measured bodies`() {
    val (recorder, summary) = recordedReview()
    val payload = summary.toBoundedPayload()

    assertEquals(
      recorder.nativeLaunches.sumOf { it.prompt.toByteArray().size.toLong() },
      aggregate(payload)["launch_bytes"],
      "Launch bytes measure exactly the prompts the specialists were given.",
    )
    assertEquals(
      recorder.nativeLaunches.size * toolOutputBody.toByteArray().size.toLong(),
      aggregate(payload)["result_bytes"],
    )
    assertEquals(
      recorder.evidenceBatches.sumOf { it.requests.size } * sourceBody.toByteArray().size.toLong(),
      aggregate(payload)["evidence_bytes"],
      "Evidence bytes measure exactly the brokered files the lanes were served.",
    )
    assertNoSentinels(payload.toString())
  }

  @Test fun `sqlite round trip preserves the payload and retains no measured content`() {
    withConnection { connection ->
      val summary = recordedReview().second
      val payload = summary.toBoundedPayload()

      upsertReviewAccounting(connection, ReviewAccountingRecord(REVIEW_RUN_ID, summary.packetDigest, payload))
      val loaded = assertNotNull(loadReviewAccounting(connection, REVIEW_RUN_ID))

      assertEquals(JsonSupport.mapToJsonString(payload), JsonSupport.mapToJsonString(loaded.boundedPayload))
      assertNoSentinels(storedAccountingJson(connection))
      ReviewContextSchemaValidator.validate(loaded.boundedPayload, "durable-review-accounting")
    }
  }

  @Test fun `review-finished telemetry carries bounded accounting and no measured content`() {
    withConnection { connection ->
      val summary = recordedReview().second
      upsertReviewAccounting(
        connection,
        ReviewAccountingRecord(REVIEW_RUN_ID, summary.packetDigest, summary.toBoundedPayload()),
      )

      val telemetry = reviewFinishedPayload(connection, reviewSummary(), findingRows = emptyList(), level = "full")
      val payload = telemetry.toReviewFinishedTelemetryPayload().toPayload()

      @Suppress("UNCHECKED_CAST")
      val accounting = assertNotNull(payload["review_context_accounting"] as? Map<String, Any?>)
      assertEquals(
        JsonSupport.mapToJsonString(summary.toBoundedPayload()),
        JsonSupport.mapToJsonString(accounting),
      )
      assertNoSentinels(payload.toString())
      assertTrue(accounting.keys.none { it.contains("prompt") })
    }
  }

  @Test fun `accounting keyed by anything other than the review run id is unreachable from telemetry`() {
    withConnection { connection ->
      val summary = recordedReview().second
      upsertReviewAccounting(
        connection,
        ReviewAccountingRecord("code-review-parallel-abc123", summary.packetDigest, summary.toBoundedPayload()),
      )

      val telemetry = reviewFinishedPayload(connection, reviewSummary(), findingRows = emptyList(), level = "full")

      assertNull(
        telemetry.toReviewFinishedTelemetryPayload().toPayload()["review_context_accounting"],
        "Review accounting must be written under the same review run id review_finished resolves.",
      )
    }
  }

  /**
   * One production review whose every content-bearing input carries a sentinel: the diff and the
   * changed guidance file, the resolved rubric, the brokered source, and the lane result.
   */
  private fun recordedReview(): Pair<ReviewRecorder, ReviewAccountingSummary> {
    val recorder = ReviewRecorder()
    val runner = reviewHarness(
      ReviewHarnessConfig(
        manifests = listOf(
          reviewPack("kotlin", listOf("architecture", "security"), routingSignals = listOf("*.kt", "*.md")),
        ),
        diff = diffForChanges(
          "src/Repo.kt" to "val diffBody = \"$diffBody\"",
          "docs/GUIDANCE.md" to guidanceBody,
        ),
        response = {
          RecordedWorkerResponse(
            stdout = toolOutputBody,
            usage = ProviderTokenUsage(1_000, 400, 200, 50, 1_200),
          )
        },
        evidenceBody = { sourceBody },
        rubricBody = { rubricBody },
      ),
      recorder,
    )

    val result = runner.run(harnessRequest(reviewRunId = REVIEW_RUN_ID))

    return recorder to assertNotNull(result.accountingSummary, "The recorded review produced no accounting.")
  }

  @Suppress("UNCHECKED_CAST")
  private fun aggregate(payload: Map<String, Any?>) = payload["aggregate_counters"] as Map<String, Any?>

  private fun assertNoSentinels(serialized: String) = sentinels.forEach { sentinel ->
    assertFalse(serialized.contains(sentinel), "Review accounting leaked '$sentinel'.")
  }

  private fun storedAccountingJson(connection: Connection): String =
    connection.prepareStatement("SELECT bounded_payload_json FROM review_accounting").use { statement ->
      statement.executeQuery().use { rows ->
        buildString { while (rows.next()) append(rows.getString(1)) }
      }
    }

  private fun reviewSummary() = ReviewSummary(
    reviewRunId = REVIEW_RUN_ID,
    reviewSessionId = "rvs-1",
    routedSkill = "bill-kotlin-code-review",
    detectedScope = "branch diff",
    detectedStack = "kotlin",
    executionMode = "delegated",
    specialistReviewsRaw = "architecture",
    reviewFinishedAt = "2026-07-22T00:00:00Z",
    reviewFinishedEventEmittedAt = null,
    orchestratedRun = true,
  )

  private fun withConnection(block: (Connection) -> Unit) {
    val dbPath = Files.createTempDirectory("review-accounting-redaction").resolve("metrics.db")
    DatabaseRuntime.ensureDatabase(dbPath).use(block)
  }

  private companion object {
    const val REVIEW_RUN_ID = "rvw-20260722-101500-ab12"
  }
}
