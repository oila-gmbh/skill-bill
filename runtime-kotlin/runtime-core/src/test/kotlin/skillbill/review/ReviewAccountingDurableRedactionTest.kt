package skillbill.review

import skillbill.application.model.ReviewPrelaunchExpansion
import skillbill.application.review.RecordedWorkerResponse
import skillbill.application.review.ReviewHarnessConfig
import skillbill.application.review.ReviewRecorder
import skillbill.application.review.diffForChanges
import skillbill.application.review.harnessRequest
import skillbill.application.review.reviewHarness
import skillbill.application.review.reviewPack
import skillbill.application.review.toBoundedPayload
import skillbill.contracts.JsonSupport
import skillbill.contracts.review.REVIEW_CONTEXT_CONTRACT_VERSION
import skillbill.contracts.review.ReviewContextSchemaValidator
import skillbill.db.core.DatabaseRuntime
import skillbill.infrastructure.sqlite.review.loadReviewAccounting
import skillbill.infrastructure.sqlite.review.reviewFinishedPayload
import skillbill.infrastructure.sqlite.review.upsertReviewAccounting
import skillbill.ports.persistence.model.ReviewAccountingRecord
import skillbill.ports.telemetry.model.toReviewFinishedTelemetryPayload
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
 * real run of the production review runner whose diff, rubric, and lane results all carry sentinel
 * bodies, so every absence assertion below has something it could have failed on.
 */
class ReviewAccountingDurableRedactionTest {
  private val diffBody = "DIFF_SENTINEL ".repeat(64)
  private val guidanceBody = "GUIDANCE_SENTINEL ".repeat(64)
  private val rubricBody = "RUBRIC_SENTINEL ".repeat(64)
  private val toolOutputBody = "TOOL_OUTPUT_SENTINEL ".repeat(64)
  private val sentinels = listOf(
    "DIFF_SENTINEL",
    "GUIDANCE_SENTINEL",
    "RUBRIC_SENTINEL",
    "TOOL_OUTPUT_SENTINEL",
  )

  @Test fun `the measured review really did carry every content sentinel`() {
    val (recorder, summary) = recordedReview()

    val prompts = recorder.parentPrompts
    assertTrue(prompts.isNotEmpty(), "The redaction proof is only meaningful if a lane was launched.")
    assertTrue(prompts.all { it.contains("hunk_id:") }, "Indexed hunk locators must reach the lane prompt.")
    assertTrue(prompts.all { !it.contains("DIFF_SENTINEL") }, "The stored hunk body must not be inlined in the prompt.")
    assertTrue(prompts.all { it.contains("RUBRIC_SENTINEL") }, "The rubric body must reach the lane prompt.")
    assertTrue(prompts.all { it.contains("docs/GUIDANCE.md") }, "Changed guidance paths must reach the prompt.")
    assertTrue(prompts.all { !it.contains("GUIDANCE_SENTINEL") }, "Guidance hunk bodies must not be inlined.")
    assertTrue(summary.aggregateCounters.launchBytes > 0)
    assertTrue(summary.aggregateCounters.resultBytes > 0)
  }

  @Test fun `bounded accounting validates against the governed review-context schema`() {
    ReviewContextSchemaValidator.validate(recordedReview().second.toBoundedPayload(), "review-accounting")
  }

  @Test fun `a recorded review retains its measured sizes and none of the measured bodies`() {
    val (recorder, summary) = recordedReview()
    val payload = summary.toBoundedPayload()

    assertEquals(
      recorder.parentPrompts.sumOf { it.toByteArray().size.toLong() },
      aggregate(payload)["launch_bytes"],
      "Launch bytes measure exactly the prompts the lanes were given.",
    )
    assertEquals(
      recorder.parentPrompts.size * toolOutputBody.toByteArray().size.toLong(),
      aggregate(payload)["result_bytes"],
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

  @Test fun `a legacy evidence-unreviewable segment quarantines and regenerates in band`() {
    withConnection { connection ->
      val summary = recordedReview().second
      val current = summary.toBoundedPayload()
      val legacy = legacyEvidenceUnreviewablePayload(current)
      connection.prepareStatement(
        """
        INSERT INTO review_accounting (review_id, packet_digest, bounded_payload_json, updated_at)
        VALUES (?, ?, ?, CURRENT_TIMESTAMP)
        """.trimIndent(),
      ).use { statement ->
        statement.setString(1, REVIEW_RUN_ID)
        statement.setString(2, summary.packetDigest)
        statement.setString(3, JsonSupport.mapToJsonString(legacy))
        statement.executeUpdate()
      }

      assertNull(loadReviewAccounting(connection, REVIEW_RUN_ID))
      val quarantined = skillbill.db.telemetry.TelemetryOutboxStore(connection).listPending(null)
      assertTrue(
        quarantined.any { record ->
          record.eventName == skillbill.review.model.REVIEW_STAGE_DEGRADATION_EVENT_NAME &&
            record.payloadJson.contains("accounting_contract_quarantined")
        },
      )

      upsertReviewAccounting(connection, ReviewAccountingRecord(REVIEW_RUN_ID, summary.packetDigest, current))
      val regenerated = assertNotNull(loadReviewAccounting(connection, REVIEW_RUN_ID))
      assertEquals(REVIEW_CONTEXT_CONTRACT_VERSION, regenerated.boundedPayload["contract_version"])
    }
  }

  @Test fun `a pre-bump accounting record quarantines and regenerates in band`() {
    withConnection { connection ->
      val summary = recordedReview().second
      val current = summary.toBoundedPayload()
      val legacy = LinkedHashMap(current).apply { this["contract_version"] = "2.0" }
      connection.prepareStatement(
        """
        INSERT INTO review_accounting (review_id, packet_digest, bounded_payload_json, updated_at)
        VALUES (?, ?, ?, CURRENT_TIMESTAMP)
        """.trimIndent(),
      ).use { statement ->
        statement.setString(1, REVIEW_RUN_ID)
        statement.setString(2, summary.packetDigest)
        statement.setString(3, JsonSupport.mapToJsonString(legacy))
        statement.executeUpdate()
      }

      assertNull(loadReviewAccounting(connection, REVIEW_RUN_ID))
      val quarantined = skillbill.db.telemetry.TelemetryOutboxStore(connection).listPending(null)
      assertTrue(
        quarantined.any { record ->
          record.eventName == skillbill.review.model.REVIEW_STAGE_DEGRADATION_EVENT_NAME &&
            record.payloadJson.contains("accounting_contract_quarantined")
        },
      )

      upsertReviewAccounting(connection, ReviewAccountingRecord(REVIEW_RUN_ID, summary.packetDigest, current))
      val regenerated = assertNotNull(loadReviewAccounting(connection, REVIEW_RUN_ID))
      assertEquals(REVIEW_CONTEXT_CONTRACT_VERSION, regenerated.boundedPayload["contract_version"])
      assertEquals("2.2", regenerated.boundedPayload["contract_version"])
    }
  }

  @Test fun `a legacy accounting row with retired usage fields remains readable`() {
    withConnection { connection ->
      val summary = recordedReview().second
      val legacy = legacyAccountingPayload(summary.toBoundedPayload())
      connection.prepareStatement(
        """
        INSERT INTO review_accounting (review_id, packet_digest, bounded_payload_json, updated_at)
        VALUES (?, ?, ?, CURRENT_TIMESTAMP)
        """.trimIndent(),
      ).use { statement ->
        statement.setString(1, REVIEW_RUN_ID)
        statement.setString(2, summary.packetDigest)
        statement.setString(3, JsonSupport.mapToJsonString(legacy))
        statement.executeUpdate()
      }

      val loaded = assertNotNull(loadReviewAccounting(connection, REVIEW_RUN_ID))
      assertEquals("2.1", loaded.boundedPayload["contract_version"])
      assertEquals(JsonSupport.mapToJsonString(legacy), storedAccountingJson(connection))
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
   * changed guidance file, the resolved rubric, and the lane result.
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
          )
        },
        rubricBody = { rubricBody },
      ),
      recorder,
    )

    val result = runner.run(
      harnessRequest(
        reviewRunId = REVIEW_RUN_ID,
        prelaunchExpansions = listOf(
          ReviewPrelaunchExpansion(
            "parallel-code-review",
            "src/Repo.kt",
            "The durable redaction proof measures an explicitly authorized complete-file expansion.",
          ),
        ),
      ),
    )

    return recorder to assertNotNull(result.accountingSummary, "The recorded review produced no accounting.")
  }

  @Suppress("UNCHECKED_CAST")
  private fun legacyEvidenceUnreviewablePayload(current: Map<String, Any?>): Map<String, Any?> {
    val digest = "a".repeat(64)
    val lanes = (current["lanes"] as List<Map<String, Any?>>).map { lane ->
      if (lane["lane"] == "parent") {
        lane
      } else {
        LinkedHashMap(lane).apply {
          put("bundle_composition_digest", digest)
          put(
            "segment_accounting",
            listOf(
              mapOf(
                "segment_id" to "seg-000",
                "measured_bytes" to 128L,
                "entry_count" to 2,
                "composition_digest" to digest,
              ),
            ),
          )
          put("unreviewed_segment_ids", listOf("evidence-unreviewable"))
        }
      }
    }
    return LinkedHashMap(current).apply { put("lanes", lanes) }
  }

  @Suppress("UNCHECKED_CAST")
  private fun legacyAccountingPayload(current: Map<String, Any?>): Map<String, Any?> {
    val usage = mapOf("input_tokens" to 1L, "ownership" to "direct")
    val legacyNodes = (current["lanes"] as List<Map<String, Any?>>).map { lane ->
      LinkedHashMap(lane).apply {
        put("provider_usage", usage)
        put("direct_usage", usage)
        put("inclusive_usage", usage)
      }
    }
    val parent = LinkedHashMap(current["parent"] as Map<String, Any?>).apply {
      put("provider_usage", usage)
      put("direct_usage", usage)
      put("inclusive_usage", usage)
    }
    val integration = (current["integration"] as Map<String, Any?>?)?.let {
      LinkedHashMap(it).apply { put("usage", emptyMap<String, Any?>()) }
    }
    return LinkedHashMap(current).apply {
      put("contract_version", "2.1")
      put("parent", parent)
      put("lanes", legacyNodes)
      put("aggregate_direct_usage", emptyMap<String, Any?>())
      put("aggregate_inclusive_usage", emptyMap<String, Any?>())
      put("budget_regression", false)
      put("integration", integration)
    }
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
