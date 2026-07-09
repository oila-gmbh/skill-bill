package skillbill.review

import skillbill.SAMPLE_REVIEW
import skillbill.infrastructure.sqlite.review.ReviewRuntime
import skillbill.infrastructure.sqlite.review.ReviewStatsRuntime
import skillbill.ports.telemetry.model.toReviewFinishedTelemetryPayload
import skillbill.review.model.ImportedReview
import skillbill.tempDbConnection
import java.sql.Connection
import kotlin.test.Test
import kotlin.test.assertEquals

class ReviewFinishedTelemetryNormalizationTest {
  @Test
  fun `mixed Kotlin labels normalize to kmp and retain only their descriptive detail`() {
    val (_, connection) = tempDbConnection("review-finished-mixed-kmp-label")
    connection.use {
      val review = saveReview(
        connection,
        SAMPLE_REVIEW
          .replace("rvw-20260402-001", "rvw-mixed-kmp-label")
          .replace("rvs-20260402-001", "rvs-mixed-kmp-label")
          .replace("Detected stack: kotlin", "Detected stack: KMP/Kotlin"),
      )

      val payload = reviewFinishedPayload(connection, review.reviewRunId)

      assertEquals("kmp", payload["review_platform"])
      assertEquals("kmp", payload["platform_slug"])
      assertEquals("kmp", payload["detected_stack"])
      assertEquals("KMP/Kotlin", payload["detected_stack_detail"])
    }
  }

  @Test
  fun `review-finished payload normalizes labels and preserves removed stack detail`() {
    val (_, connection) = tempDbConnection("review-finished-normalized-labels")
    connection.use {
      val review =
        saveReview(
          connection,
          SAMPLE_REVIEW
            .replace("rvw-20260402-001", "rvw-normalized-labels")
            .replace("rvs-20260402-001", "rvs-normalized-labels")
            .replace("Routed to: bill-kotlin-code-review", "Routed to: skill-bill:bill-kotlin-code-review")
            .replace("Detected stack: kotlin", "Detected stack: Kotlin Gradle JVM"),
        )

      val payload = reviewFinishedPayload(connection, review.reviewRunId)

      assertEquals("bill-kotlin-code-review", payload["routed_skill"])
      assertEquals("kotlin", payload["review_platform"])
      assertEquals(payload["platform_slug"], payload["review_platform"])
      assertEquals(payload["platform_slug"], payload["detected_stack"])
      assertEquals("Kotlin Gradle JVM", payload["detected_stack_detail"])
      assertEquals(false, payload["fallback"])
    }
  }

  @Test
  fun `review-finished payload prefers routed platform over descriptive stack fingerprint`() {
    val (_, connection) = tempDbConnection("review-finished-routed-kmp-label")
    connection.use {
      val review =
        saveReview(
          connection,
          SAMPLE_REVIEW
            .replace("rvw-20260402-001", "rvw-routed-kmp-label")
            .replace("rvs-20260402-001", "rvs-routed-kmp-label")
            .replace("Routed to: bill-kotlin-code-review", "Routed to: bill-kmp-code-review")
            .replace("Detected stack: kotlin", "Detected stack: Kotlin Multiplatform"),
        )

      val payload =
        reviewFinishedPayload(
          connection = connection,
          reviewRunId = review.reviewRunId,
          routedSkillPlatformSlugs = mapOf("bill-kmp-code-review" to "kmp"),
        )

      assertEquals("bill-kmp-code-review", payload["routed_skill"])
      assertEquals("kmp", payload["review_platform"])
      assertEquals("kmp", payload["platform_slug"])
      assertEquals("kmp", payload["detected_stack"])
      assertEquals("Kotlin Multiplatform", payload["detected_stack_detail"])
    }
  }

  @Test
  fun `manifest routing wins for ambiguous prose while exact clean labels remain authoritative`() {
    val (_, connection) = tempDbConnection("review-finished-manifest-ambiguity")
    connection.use {
      val ambiguous = saveReview(
        connection,
        SAMPLE_REVIEW
          .replace("rvw-20260402-001", "rvw-manifest-ambiguity")
          .replace("rvs-20260402-001", "rvs-manifest-ambiguity")
          .replace("Routed to: bill-kotlin-code-review", "Routed to: bill-kmp-code-review")
          .replace("Detected stack: kotlin", "Detected stack: Kotlin and iOS mixed workspace"),
      )
      val ambiguousPayload = reviewFinishedPayload(
        connection,
        ambiguous.reviewRunId,
        mapOf("bill-kmp-code-review" to "kmp"),
      )
      assertEquals("kmp", ambiguousPayload["review_platform"])
      assertEquals("Kotlin and iOS mixed workspace", ambiguousPayload["detected_stack_detail"])

      val clean = saveReview(
        connection,
        SAMPLE_REVIEW
          .replace("rvw-20260402-001", "rvw-clean-conflict")
          .replace("rvs-20260402-001", "rvs-clean-conflict")
          .replace("Routed to: bill-kotlin-code-review", "Routed to: bill-kmp-code-review"),
      )
      val cleanPayload = reviewFinishedPayload(
        connection,
        clean.reviewRunId,
        mapOf("bill-kmp-code-review" to "kmp"),
      )
      assertEquals("kotlin", cleanPayload["review_platform"])
      assertEquals(null, cleanPayload["detected_stack_detail"])
    }
  }

  @Test
  fun `review-finished payload keeps kebab descriptive stack out of normalized platform fields`() {
    val (_, connection) = tempDbConnection("review-finished-kebab-descriptive-label")
    connection.use {
      val review =
        saveReview(
          connection,
          SAMPLE_REVIEW
            .replace("rvw-20260402-001", "rvw-kebab-descriptive-label")
            .replace("rvs-20260402-001", "rvs-kebab-descriptive-label")
            .replace("Detected stack: kotlin", "Detected stack: backend-kotlin"),
        )

      val payload = reviewFinishedPayload(connection, review.reviewRunId)

      assertEquals("kotlin", payload["review_platform"])
      assertEquals("kotlin", payload["platform_slug"])
      assertEquals("kotlin", payload["detected_stack"])
      assertEquals("backend-kotlin", payload["detected_stack_detail"])
    }
  }

  @Test
  fun `review-finished payload emits structured fallback and nonblank unresolved routing defaults`() {
    val (_, connection) = tempDbConnection("review-finished-fallback-labels")
    connection.use {
      val review =
        saveReview(
          connection,
          """
          Review session ID: rvs-fallback-labels
          Review run ID: rvw-fallback-labels
          Detected review scope: branch diff
          Detected stack: kmp→kotlin fallback
          Execution mode: inline

          ### 2. Risk Register
          No findings.
          """.trimIndent(),
        )

      val payload = reviewFinishedPayload(connection, review.reviewRunId)

      assertEquals("unrouted", payload["routed_skill"])
      assertEquals("kmp", payload["review_platform"])
      assertEquals("kmp", payload["platform_slug"])
      assertEquals("kmp", payload["detected_stack"])
      assertEquals("kmp→kotlin fallback", payload["detected_stack_detail"])
      assertEquals(true, payload["fallback"])
      assertEquals("kotlin_quality_check_fallback", payload["fallback_reason"])
    }
  }
}

private fun saveReview(connection: Connection, rawReview: String): ImportedReview {
  val review = ReviewParser.parseReview(rawReview.trimIndent())
  ReviewRuntime.saveImportedReview(connection, review, sourcePath = null)
  return review
}

private fun reviewFinishedPayload(
  connection: Connection,
  reviewRunId: String,
  routedSkillPlatformSlugs: Map<String, String> = emptyMap(),
): Map<String, Any?> = ReviewStatsRuntime.buildReviewFinishedPayload(
  connection = connection,
  reviewRunId = reviewRunId,
  level = "anonymous",
  routedSkillPlatformSlugs = routedSkillPlatformSlugs,
).toReviewFinishedTelemetryPayload().toPayload()
