package skillbill.review

import skillbill.application.review.toBoundedPayload
import skillbill.contracts.JsonSupport
import skillbill.contracts.review.ReviewContextSchemaValidator
import skillbill.db.core.DatabaseRuntime
import skillbill.infrastructure.sqlite.review.loadReviewAccounting
import skillbill.infrastructure.sqlite.review.reviewFinishedPayload
import skillbill.infrastructure.sqlite.review.upsertReviewAccounting
import skillbill.ports.persistence.model.ReviewAccountingRecord
import skillbill.ports.telemetry.model.toReviewFinishedTelemetryPayload
import skillbill.review.context.ReviewTreeAccounting
import skillbill.review.context.model.ProviderTokenUsage
import skillbill.review.context.model.ReviewAccountingCounters
import skillbill.review.context.model.ReviewAccountingInput
import skillbill.review.context.model.ReviewAccountingSummary
import skillbill.review.context.model.TokenOwnership
import skillbill.review.model.ReviewSummary
import java.nio.file.Files
import java.security.MessageDigest
import java.sql.Connection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Content-bearing review inputs are measured, never retained. Each sentinel below stands for one
 * such input; the accounting artifact may carry its size but never a byte of its body.
 */
class ReviewAccountingDurableRedactionTest {
  private val prompt = "PROMPT_SENTINEL ".repeat(64)
  private val diff = "DIFF_SENTINEL ".repeat(64)
  private val source = "SOURCE_SENTINEL ".repeat(64)
  private val guidance = "GUIDANCE_SENTINEL ".repeat(64)
  private val rubric = "RUBRIC_SENTINEL ".repeat(64)
  private val toolOutput = "TOOL_OUTPUT_SENTINEL ".repeat(64)
  private val sentinels = listOf(
    "PROMPT_SENTINEL",
    "DIFF_SENTINEL",
    "SOURCE_SENTINEL",
    "GUIDANCE_SENTINEL",
    "RUBRIC_SENTINEL",
    "TOOL_OUTPUT_SENTINEL",
  )

  @Test fun `bounded accounting validates against the governed review-context schema`() {
    ReviewContextSchemaValidator.validate(summary().toBoundedPayload(), "review-accounting")
  }

  @Test fun `sqlite round trip preserves the payload and retains no measured content`() {
    withConnection { connection ->
      val payload = summary().toBoundedPayload()

      upsertReviewAccounting(connection, ReviewAccountingRecord("rvw-1", "packet-digest", payload))
      val loaded = assertNotNull(loadReviewAccounting(connection, "rvw-1"))

      assertEquals(JsonSupport.mapToJsonString(payload), JsonSupport.mapToJsonString(loaded.boundedPayload))
      assertNoSentinels(storedAccountingJson(connection))
      ReviewContextSchemaValidator.validate(loaded.boundedPayload, "durable-review-accounting")
    }
  }

  @Test fun `review-finished telemetry carries bounded accounting and no measured content`() {
    withConnection { connection ->
      upsertReviewAccounting(
        connection,
        ReviewAccountingRecord("rvw-1", "packet-digest", summary().toBoundedPayload()),
      )

      val telemetry = reviewFinishedPayload(connection, reviewSummary(), findingRows = emptyList(), level = "full")
      val payload = telemetry.toReviewFinishedTelemetryPayload().toPayload()

      @Suppress("UNCHECKED_CAST")
      val accounting = assertNotNull(payload["review_context_accounting"] as? Map<String, Any?>)
      assertEquals(
        JsonSupport.mapToJsonString(summary().toBoundedPayload()),
        JsonSupport.mapToJsonString(accounting),
      )
      assertNoSentinels(payload.toString())
      assertTrue(accounting.keys.none { it.contains("prompt") })
    }
  }

  @Test fun `accounting keyed by anything other than the review run id is unreachable from telemetry`() {
    withConnection { connection ->
      upsertReviewAccounting(
        connection,
        ReviewAccountingRecord("code-review-parallel-abc123", "packet-digest", summary().toBoundedPayload()),
      )

      val telemetry = reviewFinishedPayload(connection, reviewSummary(), findingRows = emptyList(), level = "full")

      assertNull(
        telemetry.toReviewFinishedTelemetryPayload().toPayload()["review_context_accounting"],
        "Review accounting must be written under the same review run id review_finished resolves.",
      )
    }
  }

  @Test fun `measured sizes survive while the measured bodies do not`() {
    val payload = summary().toBoundedPayload()

    @Suppress("UNCHECKED_CAST")
    val aggregate = payload["aggregate_counters"] as Map<String, Any?>
    assertEquals(prompt.toByteArray().size.toLong(), aggregate["launch_bytes"])
    assertEquals((diff.toByteArray().size + source.toByteArray().size).toLong(), aggregate["evidence_bytes"])
    assertEquals(toolOutput.toByteArray().size.toLong(), aggregate["result_bytes"])
    assertNoSentinels(payload.toString())
  }

  private fun assertNoSentinels(serialized: String) = sentinels.forEach { sentinel ->
    assertFalse(serialized.contains(sentinel), "Review accounting leaked '$sentinel'.")
  }

  private fun storedAccountingJson(connection: Connection): String =
    connection.prepareStatement("SELECT bounded_payload_json FROM review_accounting").use { statement ->
      statement.executeQuery().use { rows ->
        buildString { while (rows.next()) append(rows.getString(1)) }
      }
    }

  private fun summary(): ReviewAccountingSummary = ReviewTreeAccounting.summarize(
    "rvw-1",
    digest("packet"),
    ReviewAccountingInput(
      lane = "parallel-review",
      assignmentDigest = digest("parent"),
      children = listOf(
        ReviewAccountingInput(
          lane = "architecture",
          assignmentDigest = digest("architecture"),
          counters = ReviewAccountingCounters(
            launchBytes = prompt.toByteArray().size.toLong(),
            evidenceBytes = (diff.toByteArray().size + source.toByteArray().size).toLong(),
            resultBytes = toolOutput.toByteArray().size.toLong(),
            expansions = 1,
            toolCalls = 2,
            modelTurns = 3,
          ),
          usage = ProviderTokenUsage(
            inputTokens = (guidance.length + rubric.length).toLong(),
            cachedInputTokens = guidance.length.toLong(),
            outputTokens = 42,
            reasoningTokens = 7,
            totalTokens = (guidance.length + rubric.length + 42).toLong(),
            ownership = TokenOwnership.DIRECT,
          ),
        ),
      ),
    ),
  )

  private fun reviewSummary() = ReviewSummary(
    reviewRunId = "rvw-1",
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

  private fun digest(label: String): String = MessageDigest.getInstance("SHA-256")
    .digest(label.toByteArray())
    .joinToString("") { "%02x".format(it) }

  private fun withConnection(block: (Connection) -> Unit) {
    val dbPath = Files.createTempDirectory("review-accounting-redaction").resolve("metrics.db")
    DatabaseRuntime.ensureDatabase(dbPath).use(block)
  }
}
