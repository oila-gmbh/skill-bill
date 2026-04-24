package skillbill.review

import java.sql.Connection
import kotlin.io.path.readText

object ReviewRuntime {
  fun parseReview(text: String): ImportedReview {
    val reviewRunId =
      requireMatch(
        reviewRunIdPattern,
        text,
        "Review output is missing 'Review run ID: <review-run-id>'.",
      )
    val reviewSessionId =
      requireMatch(
        reviewSessionIdPattern,
        text,
        "Review output is missing 'Review session ID: <review-session-id>'.",
      )
    return ImportedReview(
      reviewRunId = reviewRunId,
      reviewSessionId = reviewSessionId,
      rawText = text,
      routedSkill = extractSummaryValue(text, "routed_skill"),
      detectedScope = extractSummaryValue(text, "detected_scope"),
      detectedStack = extractSummaryValue(text, "detected_stack"),
      executionMode = extractSummaryValue(text, "execution_mode"),
      specialistReviews = extractSpecialistReviews(text),
      findings = parseReviewFindings(text),
    )
  }

  fun readInput(inputPath: String, stdinText: String? = null): Pair<String, String?> {
    if (inputPath == "-") {
      require(stdinText != null) { "stdinText is required when inputPath is '-'." }
      return stdinText to null
    }
    val path = expandAndNormalizePath(inputPath)
    return path.readText() to path.toString()
  }

  fun saveImportedReview(connection: Connection, review: ImportedReview, sourcePath: String?) {
    val existingReviewSummary = existingReviewSummary(connection, review.reviewRunId)
    val existingFindings = fetchImportedFindings(connection, review.reviewRunId)
    val summarySnapshotChanged = reviewSummaryChanged(existingReviewSummary, review, existingFindings)
    connection.autoCommit = false
    try {
      upsertReviewRun(connection, review, sourcePath)
      if (summarySnapshotChanged) {
        ReviewStatsRuntime.clearReviewFinishedTelemetryState(connection, review.reviewRunId)
      }
      if (existingFindings != review.findings) {
        replaceFindings(connection, review)
      }
      connection.commit()
    } catch (error: java.sql.SQLException) {
      connection.rollback()
      throw error
    } finally {
      connection.autoCommit = true
    }
  }

  fun fetchImportedFindings(connection: Connection, reviewRunId: String): List<ImportedFinding> =
    connection.prepareStatement(importedFindingsSql).use { statement ->
      statement.setString(PARAM_ONE, reviewRunId)
      statement.executeQuery().use { resultSet ->
        buildList {
          while (resultSet.next()) {
            add(resultSet.toImportedFinding())
          }
        }
      }
    }

  fun fetchReviewSummary(connection: Connection, reviewRunId: String): ReviewSummary =
    connection.prepareStatement(reviewSummarySql).use { statement ->
      statement.setString(PARAM_ONE, reviewRunId)
      statement.executeQuery().use { resultSet ->
        require(resultSet.next()) { "Unknown review run id '$reviewRunId'." }
        resultSet.toReviewSummary()
      }
    }

  fun fetchFindingMetadata(connection: Connection, reviewRunId: String, findingId: String): FindingMetadata =
    connection.prepareStatement(findingMetadataSql).use { statement ->
      statement.setString(PARAM_ONE, reviewRunId)
      statement.setString(PARAM_TWO, findingId)
      statement.executeQuery().use { resultSet ->
        require(resultSet.next()) { "Unknown finding id '$findingId' for review run '$reviewRunId'." }
        FindingMetadata(
          findingId = resultSet.getString("finding_id"),
          severity = resultSet.getString("severity"),
          confidence = resultSet.getString("confidence"),
        )
      }
    }

  fun fetchNumberedFindings(connection: Connection, reviewRunId: String): List<NumberedFinding> {
    require(reviewExists(connection, reviewRunId)) { "Unknown review run id '$reviewRunId'." }
    return connection.prepareStatement(numberedFindingsSql).use { statement ->
      statement.setString(PARAM_ONE, reviewRunId)
      statement.executeQuery().use { resultSet ->
        buildList {
          var index = 1
          while (resultSet.next()) {
            add(resultSet.toNumberedFinding(index++))
          }
        }
      }
    }
  }

  fun reviewExists(connection: Connection, reviewRunId: String): Boolean =
    connection.prepareStatement("SELECT 1 FROM review_runs WHERE review_run_id = ?").use { statement ->
      statement.setString(PARAM_ONE, reviewRunId)
      statement.executeQuery().use { resultSet -> resultSet.next() }
    }

  fun findingExists(connection: Connection, reviewRunId: String, findingId: String): Boolean =
    connection.prepareStatement("SELECT 1 FROM findings WHERE review_run_id = ? AND finding_id = ?").use { statement ->
      statement.setString(PARAM_ONE, reviewRunId)
      statement.setString(PARAM_TWO, findingId)
      statement.executeQuery().use { resultSet -> resultSet.next() }
    }
}
