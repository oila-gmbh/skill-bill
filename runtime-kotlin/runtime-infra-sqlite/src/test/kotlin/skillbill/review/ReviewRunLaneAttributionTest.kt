package skillbill.review

import skillbill.infrastructure.sqlite.review.ReviewRuntime
import skillbill.infrastructure.sqlite.review.ensureTerminalReviewState
import skillbill.infrastructure.sqlite.review.fetchReviewRunLanes
import skillbill.infrastructure.sqlite.review.queryReviewLaneEffectiveness
import skillbill.review.model.ImportedFinding
import skillbill.review.model.ImportedReview
import skillbill.review.model.ReviewRunLane
import skillbill.tempDbConnection
import java.sql.Connection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ReviewRunLaneAttributionTest {
  // AC-002: lanes are durable rows, never one comma-joined value; AC-003: a finding carries the lane
  // that produced it, resolved to that lane's plan-sourced pack and area.
  @Test
  fun `saving a review records one row per lane and attributes each finding to its lane`() {
    val (_, connection) = tempDbConnection("review-lanes")
    connection.use {
      ReviewRuntime.saveImportedReview(connection, reviewWithLanes(), sourcePath = null)

      val lanes = fetchReviewRunLanes(connection, RUN_ID)
      assertEquals(
        listOf("bill-kmp-code-review-architecture", "bill-kotlin-code-review-testing"),
        lanes.map { it.laneSkillName },
      )
      assertEquals(listOf("kmp" to "architecture", "kotlin" to "testing"), lanes.map { it.packSlug to it.area })
      assertEquals(listOf(0, 1), lanes.map { it.depth })
      assertEquals(listOf(false, true), lanes.map { it.required })
      assertEquals(listOf(listOf("kmp"), listOf("kmp", "kotlin")), lanes.map { it.originLayerChain })

      assertEquals(
        listOf("bill-kmp-code-review-architecture" to "architecture", null to null),
        findingLaneRows(connection).map { (lane, area, _) -> lane to area },
      )
      assertEquals("kmp", findingLaneRows(connection).first().third)
    }
  }

  @Test
  fun `re-importing the same run replaces its lane rows rather than duplicating them`() {
    val (_, connection) = tempDbConnection("review-lanes-reimport")
    connection.use {
      ReviewRuntime.saveImportedReview(connection, reviewWithLanes(), sourcePath = null)
      val narrowed = reviewWithLanes().let { review -> review.copy(planLanes = review.planLanes.take(1)) }

      ReviewRuntime.saveImportedReview(connection, narrowed, sourcePath = null)

      assertEquals(
        listOf("bill-kmp-code-review-architecture"),
        fetchReviewRunLanes(connection, RUN_ID).map { it.laneSkillName },
      )
      assertEquals(1, rowCount(connection, "review_run_lanes"))
    }
  }

  // AC-004: effectiveness joins findings and their dispositions to the canonical routed skill, and a
  // finding with no lane attribution lands in an explicit bucket instead of falling out of the join.
  @Test
  fun `lane effectiveness groups by canonical routed skill and lane area and keeps unattributed findings`() {
    val (_, connection) = tempDbConnection("review-lane-effectiveness")
    connection.use {
      ReviewRuntime.saveImportedReview(connection, reviewWithLanes(), sourcePath = null)
      recordFeedback(connection, RUN_ID, "F-001", "fix_applied")
      recordFeedback(connection, RUN_ID, "F-002", "false_positive")

      val rows = queryReviewLaneEffectiveness(connection, RUN_ID).associateBy { it.packSlug to it.area }

      val attributed = assertNotNull(rows["kmp" to "architecture"])
      assertEquals("bill-kmp-code-review", attributed.routedSkillCanonical)
      assertEquals(1, attributed.totalFindings)
      assertEquals(1, attributed.acceptedFindings)
      assertEquals(0, attributed.rejectedFindings)

      val unattributed = assertNotNull(
        rows["unattributed" to "unattributed"],
        "A finding with no lane must be reported under an explicit unattributed bucket.",
      )
      assertEquals(1, unattributed.totalFindings)
      assertEquals(1, unattributed.rejectedFindings)
    }
  }

  // AC-005/AC-006: the terminal facts of a run are durable even when it produced no findings, and an
  // already-recorded finish timestamp is never rewritten.
  @Test
  fun `terminal state records a finish timestamp and execution mode for a zero-findings run`() {
    val (_, connection) = tempDbConnection("review-terminal-state")
    connection.use {
      val review = reviewWithLanes().copy(findings = emptyList())
      ReviewRuntime.saveImportedReview(connection, review, sourcePath = null)
      assertNull(ReviewRuntime.fetchReviewSummary(connection, RUN_ID).reviewFinishedAt)

      ensureTerminalReviewState(connection, RUN_ID, review.executionMode)

      val summary = ReviewRuntime.fetchReviewSummary(connection, RUN_ID)
      val finishedAt = assertNotNull(summary.reviewFinishedAt)
      assertEquals("inline", summary.executionMode)
      assertEquals(2, fetchReviewRunLanes(connection, RUN_ID).size, "A zero-findings run still records its lanes.")

      ensureTerminalReviewState(connection, RUN_ID, "delegated")
      assertEquals(finishedAt, ReviewRuntime.fetchReviewSummary(connection, RUN_ID).reviewFinishedAt)
    }
  }

  @Test
  fun `terminal state records an execution mode for a run that never reported one`() {
    val (_, connection) = tempDbConnection("review-terminal-mode")
    connection.use {
      ReviewRuntime.saveImportedReview(connection, reviewWithLanes().copy(executionMode = null), sourcePath = null)

      ensureTerminalReviewState(connection, RUN_ID, executionMode = null)

      val summary = ReviewRuntime.fetchReviewSummary(connection, RUN_ID)
      assertEquals("unresolved", summary.executionMode)
      assertNotNull(summary.reviewFinishedAt)
    }
  }

  private fun reviewWithLanes() = ImportedReview(
    reviewRunId = RUN_ID,
    reviewSessionId = "rvs-lane-001",
    rawText = "raw",
    routedSkill = "bill-kmp-code-review",
    detectedScope = "unstaged changes",
    detectedStack = "kmp",
    executionMode = "inline",
    specialistReviews = listOf("bill-kmp-code-review-architecture", "bill-kotlin-code-review-testing"),
    findings = listOf(
      ImportedFinding(
        findingId = "F-001",
        severity = "Major",
        confidence = "High",
        location = "Repo.kt:12",
        description = "Transaction is not rolled back.",
        findingText = "raw finding",
        laneSkillName = "bill-kmp-code-review-architecture",
      ),
      ImportedFinding(
        findingId = "F-002",
        severity = "Minor",
        confidence = "Low",
        location = "README.md:1",
        description = "Wording is stale.",
        findingText = "raw finding",
      ),
    ),
    routedSkillCanonical = "bill-kmp-code-review",
    detectedStackCanonical = "kmp",
    planLanes = listOf(
      ReviewRunLane(
        laneSkillName = "bill-kmp-code-review-architecture",
        packSlug = "kmp",
        area = "architecture",
        depth = 0,
        required = false,
        orderIndex = 0,
        originLayerChain = listOf("kmp"),
        resolutionState = ReviewRunLaneResolver.RESOLVED,
      ),
      ReviewRunLane(
        laneSkillName = "bill-kotlin-code-review-testing",
        packSlug = "kotlin",
        area = "testing",
        depth = 1,
        required = true,
        orderIndex = 1,
        originLayerChain = listOf("kmp", "kotlin"),
        resolutionState = ReviewRunLaneResolver.RESOLVED,
      ),
    ),
  )

  private fun findingLaneRows(connection: Connection): List<Triple<String?, String?, String?>> =
    connection.createStatement().use { statement ->
      statement.executeQuery(
        "SELECT lane_skill_name, lane_area, lane_pack_slug FROM findings ORDER BY finding_id",
      ).use { resultSet ->
        buildList {
          while (resultSet.next()) {
            add(Triple(resultSet.getString(1), resultSet.getString(2), resultSet.getString(3)))
          }
        }
      }
    }

  private fun recordFeedback(connection: Connection, runId: String, findingId: String, eventType: String) {
    connection.prepareStatement(
      "INSERT INTO feedback_events (review_run_id, finding_id, event_type, note) VALUES (?, ?, ?, '')",
    ).use { statement ->
      statement.setString(1, runId)
      statement.setString(2, findingId)
      statement.setString(3, eventType)
      statement.executeUpdate()
    }
  }

  private fun rowCount(connection: Connection, tableName: String): Int = connection.createStatement().use { statement ->
    statement.executeQuery("SELECT COUNT(*) FROM $tableName").use { resultSet ->
      check(resultSet.next())
      resultSet.getInt(1)
    }
  }

  private companion object {
    const val RUN_ID = "rvw-lane-001"
  }
}
