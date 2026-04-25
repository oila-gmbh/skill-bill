package skillbill.infrastructure.sqlite.review

import skillbill.review.FindingOutcomeRow
import skillbill.review.ReviewSummary
import java.sql.Connection

private val acceptedFindingOutcomeTypes = setOf("finding_accepted", "fix_applied", "finding_edited")
private val rejectedFindingOutcomeTypes = setOf("fix_rejected", "false_positive")
private val findingOutcomeTypes =
  listOf(
    "finding_accepted",
    "fix_applied",
    "finding_edited",
    "fix_rejected",
    "false_positive",
  )

private data class FindingQueryFilter(
  val latestFeedbackFilter: String,
  val findingsFilter: String,
  val parameters: List<String>,
)

private class FindingSummaryAccumulator {
  private val outcomeCounts = findingOutcomeTypes.associateWith { 0 }.toMutableMap()
  private val acceptedSeverityCounts = emptySeverityCounts().toMutableMap()
  private val rejectedSeverityCounts = emptySeverityCounts().toMutableMap()
  private val unresolvedSeverityCounts = emptySeverityCounts().toMutableMap()
  private val acceptedFindingDetails = mutableListOf<Map<String, Any?>>()
  private val rejectedFindingDetails = mutableListOf<Map<String, Any?>>()
  private var acceptedFindings = 0
  private var rejectedFindings = 0
  private var unresolvedFindings = 0
  private var rejectedFindingsWithNotes = 0

  fun apply(row: FindingOutcomeRow) {
    incrementOutcomeCount(row.outcomeType)
    when {
      row.outcomeType in acceptedFindingOutcomeTypes -> applyAcceptedFinding(row)
      row.outcomeType in rejectedFindingOutcomeTypes -> applyRejectedFinding(row)
      else -> applyUnresolvedFinding(row)
    }
  }

  fun toPayload(): Map<String, Any?> {
    val totalFindings = acceptedFindings + rejectedFindings + unresolvedFindings
    return mapOf(
      "total_findings" to totalFindings,
      "accepted_findings" to acceptedFindings,
      "rejected_findings" to rejectedFindings,
      "unresolved_findings" to unresolvedFindings,
      "accepted_rate" to rate(acceptedFindings, totalFindings),
      "rejected_rate" to rate(rejectedFindings, totalFindings),
      "latest_outcome_counts" to outcomeCounts,
      "accepted_severity_counts" to acceptedSeverityCounts,
      "rejected_severity_counts" to rejectedSeverityCounts,
      "unresolved_severity_counts" to unresolvedSeverityCounts,
      "accepted_finding_details" to acceptedFindingDetails,
      "rejected_findings_with_notes" to rejectedFindingsWithNotes,
      "rejected_finding_details" to rejectedFindingDetails,
    )
  }

  private fun incrementOutcomeCount(outcomeType: String) {
    if (outcomeType in outcomeCounts) {
      outcomeCounts[outcomeType] = outcomeCounts.getValue(outcomeType) + 1
    }
  }

  private fun applyAcceptedFinding(row: FindingOutcomeRow) {
    acceptedFindings += 1
    acceptedSeverityCounts[row.severity] = acceptedSeverityCounts.getValue(row.severity) + 1
    acceptedFindingDetails +=
      mapOf(
        "finding_id" to row.findingId,
        "severity" to row.severity,
        "confidence" to row.confidence,
        "location" to row.location,
        "description" to row.description,
        "outcome_type" to row.outcomeType,
      )
  }

  private fun applyRejectedFinding(row: FindingOutcomeRow) {
    rejectedFindings += 1
    rejectedSeverityCounts[row.severity] = rejectedSeverityCounts.getValue(row.severity) + 1
    val rejectedPayload =
      mutableMapOf<String, Any?>(
        "finding_id" to row.findingId,
        "severity" to row.severity,
        "confidence" to row.confidence,
        "location" to row.location,
        "description" to row.description,
        "outcome_type" to row.outcomeType,
      )
    if (row.note.isNotEmpty()) {
      rejectedPayload["note"] = row.note
      rejectedFindingsWithNotes += 1
    }
    rejectedFindingDetails += rejectedPayload
  }

  private fun applyUnresolvedFinding(row: FindingOutcomeRow) {
    unresolvedFindings += 1
    unresolvedSeverityCounts[row.severity] = unresolvedSeverityCounts.getValue(row.severity) + 1
  }
}

fun queryLatestFindingOutcomes(connection: Connection, reviewRunId: String?): List<FindingOutcomeRow> {
  val filter = buildFindingOutcomeFilters(reviewRunId)
  return connection.prepareStatement(latestFindingOutcomesSql(filter)).use { statement ->
    filter.parameters.forEachIndexed { index, value ->
      statement.setString(index + 1, value)
    }
    statement.executeQuery().use { resultSet ->
      buildList {
        while (resultSet.next()) {
          add(
            FindingOutcomeRow(
              reviewRunId = resultSet.getString("review_run_id"),
              findingId = resultSet.getString("finding_id"),
              severity = resultSet.getString("severity"),
              confidence = resultSet.getString("confidence"),
              location = resultSet.getString("location"),
              description = resultSet.getString("description"),
              outcomeType = resultSet.getString("outcome_type").orEmpty(),
              note = resultSet.getString("note").orEmpty(),
            ),
          )
        }
      }
    }
  }
}

fun summarizeFindingRows(findingRows: List<FindingOutcomeRow>): Map<String, Any?> {
  val summary = FindingSummaryAccumulator()
  findingRows.forEach(summary::apply)
  return summary.toPayload()
}

fun shouldSkipReviewFinishedTelemetry(findingRows: List<FindingOutcomeRow>, reviewSummary: ReviewSummary): Boolean {
  val summary = summarizeFindingRows(findingRows)
  val resolvedFindings = summary.intValue("accepted_findings") + summary.intValue("rejected_findings")
  return summary.intValue("total_findings") > 0 &&
    resolvedFindings == 0 &&
    (!reviewSummary.reviewFinishedAt.isNullOrEmpty() || !reviewSummary.reviewFinishedEventEmittedAt.isNullOrEmpty())
}

fun emptySeverityCounts(): Map<String, Int> = mapOf("Blocker" to 0, "Major" to 0, "Minor" to 0)

private fun buildFindingOutcomeFilters(reviewRunId: String?): FindingQueryFilter = if (reviewRunId == null) {
  FindingQueryFilter(latestFeedbackFilter = "", findingsFilter = "", parameters = emptyList())
} else {
  FindingQueryFilter(
    latestFeedbackFilter = "WHERE review_run_id = ?",
    findingsFilter = "WHERE f.review_run_id = ?",
    parameters = listOf(reviewRunId, reviewRunId),
  )
}

private fun latestFindingOutcomesSql(filter: FindingQueryFilter): String = """
  WITH latest_feedback AS (
    SELECT review_run_id, finding_id, MAX(id) AS latest_id
    FROM feedback_events
    ${filter.latestFeedbackFilter}
    GROUP BY review_run_id, finding_id
  )
  SELECT
    f.review_run_id,
    f.finding_id,
    f.severity,
    f.confidence,
    f.location,
    f.description,
    COALESCE(fe.event_type, '') AS outcome_type,
    COALESCE(fe.note, '') AS note
  FROM findings f
  LEFT JOIN latest_feedback lf
    ON lf.review_run_id = f.review_run_id AND lf.finding_id = f.finding_id
  LEFT JOIN feedback_events fe
    ON fe.id = lf.latest_id
  ${filter.findingsFilter}
  ORDER BY f.review_run_id, f.finding_id
""".trimIndent()
